package com.timeagent.assistant

import com.timeagent.data.*
import com.timeagent.domain.command.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConversationTurn(val role: String, val text: String, val localOnly: Boolean = false, val at: Long = System.currentTimeMillis())
data class AssistantChatState(
    val turns: List<ConversationTurn> = emptyList(), val busy: Boolean = false,
    val pending: AssistantPreview? = null, val undoId: String? = null,
    val completionRevision: Long = 0,
    val localDestination: String? = null,
    val commandResult: CommandResult? = null,
)
fun interface ConversationCloud {
    suspend fun interpret(input: String, context: AssistantContext, turns: List<ConversationTurn>): CloudResult
}

/** Same-page agent: interpretation -> local validation -> atomic execution -> concise result + undo. */
class AssistantConversation(private val repository: TimeRepository) {
    private val _state = MutableStateFlow(AssistantChatState())
    val state = _state.asStateFlow()
    private val interpreter = ConversationInterpreter()
    private val planner = AssistantPlanner()
    private val writer = RepositoryAssistantWriter(repository)
    private var unfinished: String? = null
    private var awaitingCommand: AssistantCommand? = null
    private var activeRequestId: String? = null
    private var activeOrigin: Origin = Origin.VOICE

    suspend fun matchingQuickEntries(input: String): List<com.timeagent.quickentry.QuickEntryEntity> {
        val name = Regex("^开始(?:计时)?\\s*(.+)$").matchEntire(input.trim())?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() } ?: return emptyList()
        return repository.transaction {
            val activities = repository.database.dao().activities().associateBy { it.id }
            repository.database.quickEntryDao().entries().filter { entry ->
                name == entry.label?.takeIf { it.isNotBlank() } || name == activities[entry.activityId]?.name
            }
        }
    }

    internal fun stagedCopy(): AssistantConversation = AssistantConversation(repository).also {
        it._state.value = _state.value
        it.unfinished = unfinished
        it.awaitingCommand = awaitingCommand
        it.interpreter.lastMutation = interpreter.lastMutation
    }
    private fun answer(text: String, completed: Boolean = false) {
        val current = _state.value
        _state.value = current.copy(turns = (current.turns + ConversationTurn("assistant", text)).takeLast(20),
            completionRevision = current.completionRevision + if (completed) 1 else 0)
    }
    fun cancelPending() { unfinished = null; awaitingCommand = null; _state.value = _state.value.copy(pending = null) }
    fun showLocalResult(input: String, text: String, destination: String, undoId: String? = null) {
        cancelPending(); interpreter.lastMutation = null
        _state.value = _state.value.copy(turns = (_state.value.turns + listOf(ConversationTurn("user", input, true), ConversationTurn("assistant", text, true))).takeLast(20), busy = false,
            pending = null, undoId = undoId, completionRevision = _state.value.completionRevision + 1, localDestination = destination)
    }

    suspend fun submit(input: String, context: AssistantContext, cloud: ConversationCloud? = null,
                       requestId: String? = null, origin: Origin = Origin.VOICE) {
        val text = input.trim()
        if (_state.value.busy || text.isBlank()) return
        activeRequestId = requestId
        activeOrigin = origin
        _state.value = _state.value.copy(busy = true, turns = (_state.value.turns + ConversationTurn("user", text)).takeLast(20), localDestination = null, commandResult = null)
        try {
            if (text.length > 2000) { answer("请一次说一个操作，文字最多 2000 字。"); return }
            if (ConversationIntentGuard.refusesWrite(text)) {
                cancelPending()
                val query = (interpreter.parse(text, context) as? Interpretation.Parsed)?.command as? AssistantCommand.Query
                if (query != null) {
                    when (val reply = planner.prepare(query, context)) {
                        is AssistantReply.Answer -> { queryResult(query, context); answer(reply.text, completed = true) }
                        is AssistantReply.Clarification -> answer(reply.text)
                        is AssistantReply.Preview -> Unit
                    }
                    return
                }
                if (ConversationIntentGuard.isRecordQuestion(text)) {
                    val result = repository.commands.execute(CountActualRecords(activeRequestId ?: java.util.UUID.randomUUID().toString(),
                        text.contains("睡眠"), activeOrigin))
                    _state.value = _state.value.copy(commandResult = result)
                    answer(result.message, completed = true)
                } else answer("已按你的要求暂不保存，数据未修改。", completed = true)
                return
            }
            if (text in UNDO_COMMANDS) { undo(thisActionOnly = text !in WHOLE_HISTORY_UNDO); return }
            if (text in REDO_COMMANDS) { redo(); return }
            if (text in setOf("取消", "算了", "不用了", "取消操作")) { cancelPending(); answer("已取消待处理的操作。", completed = true); return }
            val quickEntries = matchingQuickEntries(text)
            if (quickEntries.isNotEmpty()) {
                cancelPending()
                if (quickEntries.size > 1) {
                    _state.value = _state.value.copy(localDestination = "quick-entries")
                    answer("找到多个同名快捷记录，请打开快捷记录选择，尚未开始。", completed = true)
                    return
                }
                val entry = quickEntries.single()
                val id = activeRequestId ?: java.util.UUID.randomUUID().toString()
                val result = com.timeagent.quickentry.QuickEntryRepository(repository, repository.applicationContext)
                    .activate(entry.id, id, origin)
                val destination: String?
                val reply: String
                when (result) {
                    is com.timeagent.quickentry.QuickEntryLaunch.StartedTimer -> {
                        destination = null; reply = "已开始${entry.label ?: "计时"}"
                    }
                    is com.timeagent.quickentry.QuickEntryLaunch.StartedFocus -> {
                        destination = "focus"; reply = "已开始专注"
                    }
                    is com.timeagent.quickentry.QuickEntryLaunch.NeedsConfirmation -> {
                        destination = "quick-entry-confirm:${entry.id}"
                        reply = "当前已有${result.slots.joinToString("、") { it.label }}。请打开快捷记录确认是否结束当前计时并开始。"
                    }
                    is com.timeagent.quickentry.QuickEntryLaunch.OpenEditor -> {
                        destination = "quick-entry-confirm:${entry.id}"; reply = "已找到快捷预设，请打开预览后保存。"
                    }
                    is com.timeagent.quickentry.QuickEntryLaunch.Failed -> {
                        destination = "quick-entries"; reply = result.message
                    }
                }
                val commandResult = repository.commands.replay(id)
                _state.value = _state.value.copy(localDestination = destination, commandResult = commandResult,
                    undoId = commandResult?.undoToken)
                answer(reply, completed = true)
                return
            }
            if (text in setOf("确认", "确认执行", "就这样", "确认保存", "继续执行")) {
                val pending = _state.value.pending
                if (pending != null) execute(pending, confirmed = true) else answer("当前没有等待确认的操作。", completed = true)
                return
            }
            _state.value = _state.value.copy(pending = null)
            val followUp = unfinished != null && !Regex("^(?:请|帮我|记录|补记|安排|新建|创建|删除|取消|修改|把|查看|查询|开始|停止|结束)").containsMatchIn(text)
            val request = if (followUp) "${unfinished.orEmpty()} $text" else text
            val explicitId = text.takeIf { followUp && it.matches(Regex("#[A-Za-z0-9:_-]{1,128}")) }?.drop(1)
            val interpretationRequest = ConversationIntentGuard.withoutPreviewRequest(request)
            var result = explicitId?.let { id -> selectIdentity(awaitingCommand, id)?.let(Interpretation::Parsed) } ?: interpreter.parse(interpretationRequest, context)
            awaitingCommand = null
            if (result !is Interpretation.Parsed && cloud != null) {
                result = when (val remote = cloud.interpret(request, context, _state.value.turns.filterNot { it.localOnly }.takeLast(8))) {
                    is CloudResult.Success -> remote.interpretation
                    is CloudResult.Failure -> { answer(remote.message); unfinished = request; return }
                }
            }
            currentCoroutineContext().ensureActive()
            when (result) {
                is Interpretation.Parsed -> {
                    unfinished = null
                    when (val reply = planner.prepare(result.command, context)) {
                        is AssistantReply.Answer -> {
                            if (result.command is AssistantCommand.Query) queryResult(result.command, context)
                            answer(reply.text, completed = true)
                        }
                        is AssistantReply.Clarification -> { unfinished = request; awaitingCommand = result.command; answer(reply.text) }
                        is AssistantReply.Preview -> {
                            val item = reply.value
                            val previewOnly = ConversationIntentGuard.requestsPreview(request)
                            if (item.needsFutureApproval || item.warnings.any { it.startsWith("时间冲突") } || previewOnly ||
                                origin is Origin.FISH && item.mutation.deletedCount() > 10) {
                                _state.value = _state.value.copy(pending = item)
                                val details = if (item.mutation is AssistantMutation.Batch) item.after else item.after.lines().take(3).joinToString("\n")
                                answer(if (previewOnly) "已按“先预览”生成操作，确认后才保存；数据尚未修改。\n${item.title}\n$details\n仍然执行吗？"
                                else "${item.title}\n$details\n${item.warnings.joinToString("\n")}\n仍然执行吗？")
                            } else {
                                execute(item)
                            }
                        }
                    }
                }
                is Interpretation.Clarify -> { unfinished = request; answer(result.message) }
                is Interpretation.Unsupported -> { unfinished = request; answer("请补充具体内容和时间，例如“记录睡眠一点半到八点半”。自由表达可在助手设置中开启智能理解。") }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: StaleChange) { _state.value = _state.value.copy(pending = null); answer("相关内容已变化，本次未执行。请重新说一次，我会按最新数据处理。") }
        catch (e: IllegalArgumentException) { answer(e.message ?: "内容无效，请调整后重试。") }
        catch (_: Exception) { answer("这次未完成，请稍后重试；已保存的数据可以在时间轴和操作历史查看。") }
        finally { _state.value = _state.value.copy(busy = false) }
    }

    private suspend fun execute(item: AssistantPreview, confirmed: Boolean = false): Boolean {
        if (item.needsFutureApproval && !confirmed) return false
        if (activeOrigin is Origin.FISH && item.mutation.deletedCount() > 10) {
            answer("批量删除超过 10 条，请在 App 对应业务页面核对并确认；数据尚未修改。")
            return false
        }
        val result = writer.commitResult(item, confirmed && item.needsFutureApproval, activeRequestId ?: item.id, activeOrigin)
        _state.value = _state.value.copy(commandResult = result)
        result.throwIfRejected()
        val wrote = result.status == CommandStatus.APPLIED
        if (wrote) {
            interpreter.lastMutation = item.mutation
            _state.value = _state.value.copy(pending = null, undoId = result.undoToken?.removePrefix("group:"))
            val details = when (item.mutation) {
                is AssistantMutation.Batch -> item.after.lines().filterNot { it.startsWith("任务 #") || it.startsWith("计划 #") || it == "不关联任务" }.joinToString("\n")
                is AssistantMutation.CancelSampleCourses -> "${item.mutation.courses.size} 门 / ${item.mutation.occurrences.size} 次内置示例课程已取消。"
                else -> item.after.lines().filterNot { it.startsWith("任务 #") || it.startsWith("计划 #") || it == "不关联任务" }.take(3).joinToString("\n")
            }
            answer("已执行 · ${item.title}\n$details", completed = true)
        } else answer("这次操作已经执行，未重复保存。", completed = true)
        return wrote
    }

    private suspend fun queryResult(query: AssistantCommand.Query, context: AssistantContext) {
        val result = repository.commands.execute(ReadAssistantQuery(activeRequestId ?: java.util.UUID.randomUUID().toString(),
            query, context.selectedWeekStart, activeOrigin))
        _state.value = _state.value.copy(commandResult = result)
    }

    private suspend fun undo(thisActionOnly: Boolean) {
        if (thisActionOnly && _state.value.undoId == null) { answer("当前没有这次助手操作可撤销，未改动其他历史操作。"); return }
        val token = _state.value.undoId?.let { "group:$it" }
            ?: repository.snapshot().history.firstOrNull { !it.undone }?.let { "operation:${it.id}" }
        if (token == null) { answer("还没有可撤销的操作。"); return }
        val result = repository.commands.execute(UndoCommand(activeRequestId ?: java.util.UUID.randomUUID().toString(), token, activeOrigin))
        _state.value = _state.value.copy(commandResult = result)
        result.throwIfRejected()
        unfinished = null; awaitingCommand = null; interpreter.lastMutation = null
        _state.value = _state.value.copy(undoId = null, pending = null)
        answer("已撤销，时间轴已恢复。", completed = true)
    }

    /** Redo restores the operation the user just undid; it never creates a second copy of the record. */
    private suspend fun redo() {
        val result = repository.commands.execute(RedoCommand(activeRequestId ?: java.util.UUID.randomUUID().toString(), origin = activeOrigin))
        _state.value = _state.value.copy(commandResult = result)
        result.throwIfRejected()
        unfinished = null; awaitingCommand = null; interpreter.lastMutation = null
        _state.value = _state.value.copy(undoId = null, pending = null)
        answer("已恢复刚才撤销的操作。", completed = true)
    }

    private companion object {
        private val UNDO_COMMANDS = setOf("撤销", "回撤", "撤回", "撤销这次", "撤销刚才", "撤销最近操作", "撤销刚才的操作")
        private val WHOLE_HISTORY_UNDO = setOf("撤销", "回撤", "撤回")
        private val REDO_COMMANDS = setOf("重做", "前撤", "恢复刚才撤销", "恢复刚才撤销的操作", "恢复撤销", "恢复刚才的操作")
    }

    private fun selectIdentity(command: AssistantCommand?, id: String): AssistantCommand? = when (command) {
        is AssistantCommand.CreatePlan -> command.copy(taskId = id, taskTitle = null)
        is AssistantCommand.StartTimer -> command.copy(taskId = id, taskTitle = null)
        is AssistantCommand.CancelTask -> command.copy(taskId = id, taskTitle = null)
        is AssistantCommand.EditTask -> command.copy(taskId = id, taskTitle = null)
        is AssistantCommand.MovePlan -> command.copy(planId = id, taskTitle = null)
        is AssistantCommand.CancelPlan -> command.copy(planId = id, taskTitle = null)
        else -> null
    }
}
