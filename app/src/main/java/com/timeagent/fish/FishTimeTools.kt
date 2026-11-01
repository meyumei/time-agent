package com.timeagent.fish

import com.google.gson.Gson
import com.timeagent.assistant.*
import com.timeagent.core.WeekState
import com.timeagent.data.*
import org.json.JSONObject
import java.time.*
import java.util.UUID

/** Tools read live business facts and commit through the existing planner/writer. */
class FishTimeTools(private val repository: TimeRepository) {
    private val gson = Gson()
    private val quick = AssistantConversation(repository)
    suspend fun execute(name: String, args: JSONObject): JSONObject {
        val data = repository.snapshot()
        val now = repository.now()
        val zone = repository.zone
        fun json(value: Any) = JSONObject(gson.toJson(value))
        if (name == "time_quick_action") {
            val text=args.getString("text").trim()
            require(text.length in 1..2000) { "请一次说一个操作，最多 2000 字" }
            if (Regex("^(?:请帮我|帮我|请)?记住").containsMatchIn(text)) {
                require(com.timeagent.memory.PersonalMemoryRules.fromExplicit(text,now)!=null) { "请明确说明需要保存的记忆或排程约定" }
                val saved=repository.rememberExplicit(text,"fish-memory:${UUID.randomUUID()}")
                return JSONObject().put("message","已记住：${saved.text}").put("destination","memory")
            }
            quick.submit(text,AssistantContext(now,zone,WeekState.startOfWeek(now.atZone(zone).toLocalDate()),data),null)
            val state=quick.state.value
            return JSONObject().put("message",state.turns.lastOrNull { it.role=="assistant" }?.text ?: "尚未执行")
                .put("needsConfirmation",state.pending!=null).put("undoAvailable",state.undoId!=null)
        }
        if (name == "time_undo") {
            require(data.history.any { !it.undone }) { "没有可撤销的业务操作" }
            repository.undoLastAction()
            return JSONObject().put("message","已撤销最近一次业务操作")
        }
        if (name == "time_complete_task") {
            val instance=data.recurringInstances.firstOrNull { it.taskId==args.getString("taskId") }
            if (instance != null) {
                require(instance.status=="PENDING") { "本次周期事项已经处理，请在周期或期限页面核对" }
                com.timeagent.recurring.RecurringService(repository).act(instance.id,instance.version,"COMPLETE","fish-complete:${UUID.randomUUID()}")
                return JSONObject().put("id",instance.taskId).put("message","本次已完成，实际投入未自动生成")
            }
        }
        if (name == "time_today" || name == "time_schedule") {
            val date = if (name == "time_today") now.atZone(zone).toLocalDate() else LocalDate.parse(args.getString("date"))
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val tasks = data.tasks.filter { it.deletedAt == null }.associateBy { it.id }
            fun local(time: Long) = Instant.ofEpochMilli(time).atZone(zone).toOffsetDateTime().toString()
            return json(mapOf("date" to date.toString(), "zone" to zone.id, "now" to now.toString(),
                "plans" to data.plans.filter { it.status == "SCHEDULED" && it.deletedAt == null && it.startAt < end && (if (it.kind == "POINT") it.startAt >= start else it.endAt > start) && (it.taskId == null || tasks[it.taskId] != null) }.map {
                    mapOf("id" to it.id,"taskId" to it.taskId,"title" to it.displayTitle(tasks[it.taskId]),"start" to local(it.startAt),"end" to local(it.endAt)) },
                "courses" to data.occurrences.filter { it.status != "CANCELLED" && it.startAt < end && it.endAt > start }.map {
                    mapOf("id" to it.id,"title" to data.courses.find { c -> c.id == it.courseId }?.name,"start" to local(it.startAt),"end" to local(it.endAt)) },
                "tasks" to tasks.values.filter { it.status in setOf("TODO","IN_PROGRESS") && (it.dueAt == null || it.dueAt < end) }.map {
                    mapOf("id" to it.id,"title" to it.title,"dueAt" to it.dueAt?.let(::local),"status" to it.status) },
                "actual" to data.records.filter { it.startAt < end && (it.endAt ?: now.toEpochMilli()) > start }.map {
                    mapOf("id" to it.id,"title" to (it.title.ifBlank { tasks[it.taskId]?.title ?: "实际记录" }),"start" to local(it.startAt),"end" to it.endAt?.let(::local)) }))
        }
        if (name == "time_tasks") return json(mapOf("tasks" to data.tasks.filter { it.deletedAt == null && it.status in setOf("TODO","IN_PROGRESS") }.map {
            mapOf("id" to it.id,"title" to it.title,"status" to it.status,"estimatedMinutes" to it.estimatedMinutes,"dueAt" to it.dueAt) }))
        if (name == "time_projects") return json(mapOf("projects" to data.projects.filter { it.deletedAt == null }.map { project ->
            val ids = data.placements.filter { it.projectId == project.id }.map { it.taskId }.toSet()
            mapOf("id" to project.id,"name" to project.name,"status" to project.status,"tasks" to data.tasks.filter { it.id in ids && it.deletedAt == null }.map {
                mapOf("id" to it.id,"title" to it.title,"status" to it.status) })
        }))
        val taskId = args.optString("taskId").takeIf { it.isNotBlank() }
        fun timestamp(key: String) = OffsetDateTime.parse(args.getString(key)).toInstant().toEpochMilli()
        val command = when (name) {
            "time_create_task" -> AssistantCommand.CreateTask(args.getString("title"))
            "time_complete_task" -> AssistantCommand.CompleteTask(requireNotNull(taskId) { "缺少任务标识" })
            "time_create_plan" -> if (taskId == null) AssistantCommand.CreateEvent(args.getString("title"),timestamp("start"),timestamp("end"))
                else AssistantCommand.CreatePlan(taskId=taskId,startAt=timestamp("start"),endAt=timestamp("end"))
            "time_record" -> AssistantCommand.ManualRecord(taskId=taskId,label=args.getString("title"),startAt=timestamp("start"),endAt=timestamp("end"))
            else -> throw IllegalArgumentException("不支持的时间工具：$name")
        }
        val context = AssistantContext(now, zone, WeekState.startOfWeek(now.atZone(zone).toLocalDate()), data)
        val preview = when (val reply = AssistantPlanner().prepare(command,context)) {
            is AssistantReply.Preview -> reply.value
            is AssistantReply.Answer -> return JSONObject().put("message",reply.text)
            is AssistantReply.Clarification -> throw IllegalArgumentException(reply.text)
        }
        require(!preview.needsFutureApproval) { "实际记录包含未来时间，请改为计划，或在业务页面核对后记录" }
        require(preview.warnings.isEmpty()) { preview.warnings.joinToString("；") + "；请在业务页面处理冲突" }
        val projectId = args.optString("projectId").takeIf { it.isNotBlank() }
        val createdId = when (val mutation = preview.mutation) {
            is AssistantMutation.SaveTask -> mutation.task.id
            is AssistantMutation.SaveEvent -> mutation.task.id
            is AssistantMutation.SavePlan -> mutation.plan.id
            is AssistantMutation.SaveRecord -> mutation.record.id
            else -> preview.id
        }
        repository.assistantTransaction(preview.id) {
            RepositoryAssistantWriter(repository).commit(preview,false)
            if (name == "time_create_task" && projectId != null) repository.savePlacement(TaskPlacementEntity(createdId,projectId))
        }
        return JSONObject().put("id",createdId).put("message",preview.after).put("undoAvailable",true)
    }
}
