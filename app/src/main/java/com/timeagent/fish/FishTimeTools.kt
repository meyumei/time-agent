package com.timeagent.fish

import com.google.gson.Gson
import com.timeagent.assistant.*
import com.timeagent.core.WeekState
import com.timeagent.data.*
import com.timeagent.domain.command.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
import java.security.MessageDigest
import java.time.*
import java.util.UUID

/** Runtime calls replay before interpreting live data, so a lost response cannot repeat a write. */
class FishTimeTools(private val repository: TimeRepository) {
    private val gson = Gson()
    private val conversations = mutableMapOf<String, AssistantConversation>()
    private val lock = Mutex()
    private class ToolRejected(val result: JSONObject) : RuntimeException()

    suspend fun execute(name: String, args: JSONObject): JSONObject {
        val legacy = JSONObject(args.toString())
        if (legacy.optString("taskId").isNotBlank() && !legacy.has("expectedVersion")) {
            repository.snapshot().tasks.firstOrNull { it.id == legacy.optString("taskId") }?.let { legacy.put("expectedVersion", it.version) }
        }
        val result = execute(name, legacy, Origin.FISH("legacy", "legacy", UUID.randomUUID().toString()))
        if (result.optString("status") in setOf("INVALID", "NOT_FOUND")) throw IllegalArgumentException(result.optString("message"))
        if (result.optString("status") == "CONFLICT") throw StaleChange()
        if (result.optString("status") == "FAILED") throw IllegalStateException(result.optString("message"))
        return result
    }

    suspend fun execute(name: String, args: JSONObject, origin: Origin.FISH): JSONObject = lock.withLock {
        val requestId = requestId(origin)
        try {
            repository.transaction {
                repository.commands.replay(requestId)?.let { return@transaction json(it) }
                val result = when (name) {
                    "time_today", "time_schedule", "time_tasks", "time_projects" -> executed(repository.commands.execute(
                        ReadTimeData(requestId, name, args.optString("date").takeIf { it.isNotBlank() }, origin)))
                    "time_undo" -> {
                        val latest = repository.snapshot().history.firstOrNull { !it.undone }
                        if (latest == null) json(CommandResult(requestId, CommandStatus.NOT_FOUND, message = "没有可撤销的业务操作"))
                        else executed(repository.commands.execute(UndoCommand(requestId, "operation:${latest.id}", origin)))
                    }
                    "time_quick_action" -> quickAction(args.getString("text"), requestId, origin)
                    else -> write(name, args, requestId, origin)
                }
                result
            }
        } catch (e: CancellationException) { throw e }
        catch (e: ToolRejected) { e.result }
        catch (_: DateTimeException) { json(CommandResult(requestId, CommandStatus.INVALID, message = "日期时间格式无效，请使用带时区的 ISO-8601 时间，尚未保存")) }
        catch (_: JSONException) { json(CommandResult(requestId, CommandStatus.INVALID, message = "工具参数缺失或格式无效，尚未保存")) }
        catch (e: IllegalArgumentException) { json(CommandResult(requestId, CommandStatus.INVALID, message = e.message ?: "参数无效，尚未保存")) }
        catch (e: Exception) { json(CommandResult(requestId, CommandStatus.FAILED, message = "操作未保存，请稍后重试；原数据保持不变")) }
    }

    private suspend fun quickAction(text: String, requestId: String, origin: Origin.FISH): JSONObject {
        require(text.trim().length in 1..2000) { "请一次说一个操作，最多 2000 字" }
        if (Regex("^(?:请帮我|帮我|请)?记住").containsMatchIn(text.trim())) {
            return executed(repository.commands.execute(RememberRule(requestId, text.trim(), origin)))
        }
        val quick = conversations[origin.sessionId]?.stagedCopy() ?: AssistantConversation(repository)
        // Publish draft/undo state only after the outermost business transaction commits.
        repository.afterCommit { conversations[origin.sessionId] = quick }
        val now = repository.now()
        quick.submit(text, AssistantContext(now, repository.zone, WeekState.startOfWeek(now.atZone(repository.zone).toLocalDate()),
            repository.snapshot()), requestId = requestId, origin = origin)
        val state = quick.state.value
        state.commandResult?.let { return executed(it) }
        return json(CommandResult(requestId, if (state.pending != null) CommandStatus.NEEDS_CONFIRMATION else CommandStatus.INVALID,
            message = state.turns.lastOrNull { it.role == "assistant" }?.text ?: "尚未执行"))
            .put("needsConfirmation", state.pending != null).put("undoAvailable", false)
    }

    private suspend fun write(name: String, args: JSONObject, requestId: String, origin: Origin.FISH): JSONObject {
        val data = repository.snapshot()
        val now = repository.now()
        val taskId = args.optString("taskId").takeIf { it.isNotBlank() }
        val expected = if (args.has("expectedVersion")) args.getLong("expectedVersion") else null
        if (name == "time_complete_task" || taskId != null && name in setOf("time_create_plan", "time_record")) {
            require(expected != null) { "请先查询任务并传入 expectedVersion，尚未保存" }
        }
        if (expected != null && taskId != null) {
            val task = data.tasks.firstOrNull { it.id == taskId && it.deletedAt == null }
            if (task?.version != expected) return json(CommandResult(requestId, CommandStatus.CONFLICT,
                message = "任务版本已经变化，尚未保存；请重新查询后核对", conflict = ConflictInfo("TASK", taskId, expected, task?.version, task?.let(gson::toJson))))
        }
        if (name == "time_complete_task") {
            val instance = data.recurringInstances.firstOrNull { it.taskId == taskId }
            if (instance != null) {
                require(instance.status == "PENDING") { "本次周期事项已经处理，请在周期或期限页面核对" }
                val task = requireNotNull(data.tasks.firstOrNull { it.id == taskId })
                return executed(repository.commands.execute(CompleteRecurring(requestId, instance.id, instance.version, expected ?: task.version, origin)))
            }
        }
        fun timestamp(key: String) = OffsetDateTime.parse(args.getString(key)).toInstant().toEpochMilli()
        if (name == "time_create_plan" && args.optString("kind", "TIMED") != "TIMED") {
            val kind = args.getString("kind")
            require(kind in setOf("POINT", "ALL_DAY")) { "日程类型必须是 TIMED、POINT 或 ALL_DAY" }
            val timezone = ZoneId.of(args.optString("timezoneId", repository.zone.id))
            val start = timestamp("start")
            val end = timestamp("end")
            val plan = ScheduleBlockEntity(requestId, taskId, start, end, title = args.getString("title"), kind = kind,
                timezoneId = timezone.id, allDayStartDate = if (kind == "ALL_DAY") Instant.ofEpochMilli(start).atZone(timezone).toLocalDate().toString() else null,
                allDayEndDate = if (kind == "ALL_DAY") Instant.ofEpochMilli(end).atZone(timezone).toLocalDate().toString() else null,
                source = "FISH", sourceRef = "${origin.sessionId}/${origin.messageId}")
            val conflicts = if (kind == "POINT") emptyList() else repository.conflicts(com.timeagent.core.TimeSpan(
                LocalDate.parse(plan.allDayStartDate).atStartOfDay(timezone).toInstant(), LocalDate.parse(plan.allDayEndDate).plusDays(1).atStartOfDay(timezone).toInstant()))
            require(conflicts.isEmpty()) { "时间冲突，请在业务页面核对" }
            val preview = AssistantPreview(requestId, "安排日程", "", plan.title, mutation = AssistantMutation.SavePlan(plan,
                expectedTaskVersion = expected, expectedConflicts = emptyList()))
            return executed(repository.commands.execute(CommitAssistant(requestId, preview, origin = origin)))
        }
        val command = when (name) {
            "time_create_task" -> AssistantCommand.CreateTask(args.getString("title"))
            "time_complete_task" -> AssistantCommand.CompleteTask(requireNotNull(taskId) { "缺少任务标识" })
            "time_create_plan" -> if (taskId == null) AssistantCommand.CreateEvent(args.getString("title"), timestamp("start"), timestamp("end"))
                else AssistantCommand.CreatePlan(taskId = taskId, startAt = timestamp("start"), endAt = timestamp("end"))
            "time_record" -> AssistantCommand.ManualRecord(taskId = taskId, label = args.getString("title"), startAt = timestamp("start"), endAt = timestamp("end"))
            else -> throw IllegalArgumentException("不支持的时间工具：$name")
        }
        val context = AssistantContext(now, repository.zone, WeekState.startOfWeek(now.atZone(repository.zone).toLocalDate()), data)
        val preview = when (val reply = AssistantPlanner().prepare(command, context)) {
            is AssistantReply.Preview -> reply.value
            is AssistantReply.Answer -> return JSONObject().put("requestId", requestId).put("status", "INVALID").put("affected", JSONArray()).put("conflict", JSONObject.NULL).put("message", reply.text)
            is AssistantReply.Clarification -> throw IllegalArgumentException(reply.text)
        }
        require(!preview.needsFutureApproval) { "实际记录包含未来时间，请改为计划，或在业务页面核对后记录" }
        require(preview.warnings.isEmpty()) { preview.warnings.joinToString("；") + "；请在业务页面处理冲突" }
        return executed(repository.commands.execute(CommitAssistant(requestId, preview, projectId = args.optString("projectId").takeIf { it.isNotBlank() }, origin = origin)))
    }

    private fun executed(result: CommandResult): JSONObject {
        val value = json(result)
        // Command rejection must cross the outer rollback boundary; clarification keeps its draft.
        if (result.status in setOf(CommandStatus.CONFLICT, CommandStatus.INVALID, CommandStatus.NOT_FOUND, CommandStatus.FAILED)) throw ToolRejected(value)
        return value
    }

    private fun json(result: CommandResult): JSONObject {
        val value = JSONObject(gson.toJson(result))
        if (result.payload != null && result.status in setOf(CommandStatus.APPLIED, CommandStatus.DUPLICATE)) {
            val payload = JSONObject(result.payload)
            payload.keys().forEach { key -> if (key !in setOf("status", "requestId", "affected", "conflict", "undoToken")) value.put(key, payload.get(key)) }
        }
        value.remove("payload")
        return value.put("conflict", result.conflict?.let { JSONObject(gson.toJson(it)) } ?: JSONObject.NULL)
            .put("undoAvailable", result.undoToken != null)
    }

    private fun requestId(origin: Origin.FISH): String {
        require(origin.sessionId.isNotBlank() && origin.messageId.isNotBlank() && origin.toolCallId.isNotBlank()) { "缺少运行时工具调用标识，尚未执行" }
        val identity = JSONArray(listOf(origin.sessionId, origin.messageId, origin.toolCallId)).toString()
        return "fish:" + MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
