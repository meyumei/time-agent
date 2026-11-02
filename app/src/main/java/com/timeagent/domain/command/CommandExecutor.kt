package com.timeagent.domain.command

import com.google.gson.Gson
import com.timeagent.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Duration

class CommandExecutor internal constructor(private val repository: TimeRepository) {
    private val dao = repository.database.dao()
    private val gson = Gson()
    private val changes = MutableSharedFlow<DataChanged>(extraBufferCapacity = 64)
    val events = changes.asSharedFlow()

    private class Rejected(val status: CommandStatus, message: String, val conflict: ConflictInfo? = null) : RuntimeException(message)
    private data class Applied(val targets: List<Pair<String, String>>, val undoToken: String?, val warnings: List<ScheduleWarning> = emptyList())

    internal suspend fun replay(requestId: String): CommandResult? = dao.commandResult(requestId)?.let {
        gson.fromJson(it.resultJson, CommandResult::class.java).copy(status = CommandStatus.DUPLICATE)
    }

    suspend fun execute(command: Command): CommandResult = execute(command, preserveFailure = false)

    internal suspend fun executeForRepository(command: Command): CommandResult = execute(command, preserveFailure = true)

    private suspend fun execute(command: Command, preserveFailure: Boolean): CommandResult = try {
        require(command.requestId.isNotBlank()) { "请求编号不能为空，尚未保存" }
        repository.transaction {
            replay(command.requestId)?.let { return@transaction it }
            val applied = when (command) {
                is UndoCommand -> changeHistory(command.undoToken, forward = false)
                is RedoCommand -> changeHistory(command.undoToken, forward = true)
                else -> {
                    val captured = repository.commandWrite(command.requestId, label(command)) {
                        when (command) {
                            is MutationCommand -> apply(command)
                            is BatchCommand -> {
                                require(command.commands.isNotEmpty()) { "没有可执行的内容" }
                                command.commands.flatMap { apply(it) }
                            }
                            else -> error("不支持的命令")
                        }
                    }
                    Applied(captured.entries.flatMap(repository::historyTargets), captured.undoToken, captured.value)
                }
            }
            val affected = applied.targets.distinct().map { (type, id) ->
                EntityRef(type, id, dao.revision("$type:$id")?.revision)
            }
            val timerRecord = when (command) {
                is StartTimer -> dao.record(command.recordId)
                is StopTimer -> dao.record(command.id)
                else -> null
            }
            val result = CommandResult(command.requestId, CommandStatus.APPLIED, affected, applied.undoToken,
                "${label(command)}成功", warnings = applied.warnings, timerRecord = timerRecord)
            dao.putCommandResult(CommandLogEntity(command.requestId, command.javaClass.simpleName, gson.toJson(result), repository.now().toEpochMilli()))
            dao.deleteCommandResultsBefore(repository.now().minus(Duration.ofDays(30)).toEpochMilli())
            if (affected.isNotEmpty()) {
                val event = DataChanged(affected.map { it.type }.toSet())
                repository.afterCommit { changes.emit(event) }
            }
            result
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Rejected) {
        CommandResult(command.requestId, e.status, message = e.message!!, conflict = e.conflict)
    } catch (_: StaleChange) {
        CommandResult(command.requestId, CommandStatus.CONFLICT, message = "数据已经变化，尚未保存；请重新打开后核对")
    } catch (_: TimerAlreadyRunning) {
        CommandResult(command.requestId, CommandStatus.CONFLICT, message = "已有计时正在进行，请先确认切换",
            conflict = ConflictInfo("RECORD", "", null, null, null, "RUNNING_TIMER"))
    } catch (e: IllegalArgumentException) {
        CommandResult(command.requestId, CommandStatus.INVALID, message = e.message ?: "内容无效，尚未保存")
    } catch (e: Exception) {
        // Existing repository callers use database exception types to recover and retry.
        if (preserveFailure) throw e
        CommandResult(command.requestId, CommandStatus.FAILED, message = "操作未保存，请稍后重试；原数据保持不变")
    }

    private suspend fun version(type: String, id: String, expected: Long?, create: Boolean = false) {
        val entity: Any? = when (type) {
            "PLAN" -> dao.plan(id)
            "RECORD" -> dao.record(id)
            "TASK" -> dao.task(id)
            else -> error("未知实体类型")
        }
        val current = when (entity) {
            is ScheduleBlockEntity -> entity.version
            is TimeRecordEntity -> entity.version
            is TaskEntity -> entity.version
            else -> null
        }
        val deleted = when (entity) {
            is TaskEntity -> entity.deletedAt != null || entity.status == "CANCELLED"
            else -> false
        }
        if (!create && (entity == null || deleted)) throw Rejected(CommandStatus.NOT_FOUND, "内容不存在或已删除，尚未保存")
        if (create && entity != null || !create && expected != null && current != expected) {
            throw Rejected(CommandStatus.CONFLICT, "数据版本已经变化，尚未保存；请重新打开后核对",
                ConflictInfo(type, id, expected, current, entity?.let(gson::toJson)))
        }
    }

    private suspend fun task(id: String?, expectedVersion: Long?) {
        if (id != null) version("TASK", id, expectedVersion)
        else if (expectedVersion != null) throw Rejected(CommandStatus.CONFLICT, "任务关联已经变化，尚未保存",
            ConflictInfo("TASK", "", expectedVersion, null, null))
    }

    private suspend fun apply(command: MutationCommand): List<ScheduleWarning> {
        val warnings = when (command) {
            is CreateSchedule -> {
                version("PLAN", command.schedule.id, null, create = true)
                task(command.schedule.taskId, command.expectedTaskVersion)
                repository.savePlanRaw(command.schedule, expectedTaskVersion = command.expectedTaskVersion, expectedConflicts = command.expectedConflicts)
            }
            is UpdateSchedule -> {
                version("PLAN", command.schedule.id, command.expectedVersion)
                task(command.schedule.taskId, command.expectedTaskVersion)
                repository.savePlanRaw(command.schedule, command.expectedVersion, command.expectedTaskVersion, command.expectedConflicts)
            }
            is DeleteSchedule -> {
                version("PLAN", command.id, command.expectedVersion)
                repository.deletePlanRaw(command.id, command.expectedVersion)
                emptyList()
            }
            is CreateActual -> {
                version("RECORD", command.record.id, null, create = true)
                task(command.record.taskId, command.expectedTaskVersion)
                repository.saveRecordRaw(command.record, command.allowFuture, expectedTaskVersion = command.expectedTaskVersion)
                emptyList()
            }
            is UpdateActual -> {
                version("RECORD", command.record.id, command.expectedVersion)
                if (command.expectedTaskVersion != null) task(command.record.taskId, command.expectedTaskVersion)
                repository.saveRecordRaw(command.record, command.allowFuture, command.expectedVersion, command.expectedTaskVersion)
                emptyList()
            }
            is DeleteActual -> {
                version("RECORD", command.id, command.expectedVersion)
                repository.deleteRecordRaw(command.id, command.expectedVersion)
                emptyList()
            }
            is StartTimer -> {
                version("RECORD", command.recordId, null, create = true)
                task(command.taskId, command.expectedTaskVersion)
                repository.startTimerRaw(command.taskId, command.recordId, command.switchExisting, command.expectedTaskVersion)
                emptyList()
            }
            is StopTimer -> {
                version("RECORD", command.id, command.expectedVersion)
                repository.stopTimerRaw(command.id, command.expectedVersion)
                emptyList()
            }
        }
        return warnings.map { ScheduleWarning(it.id, it.title, it.span.start.toEpochMilli(), it.span.end.toEpochMilli()) }
    }

    private suspend fun actionForToken(token: String): OperationEntity? = when {
        token.startsWith("group:") -> dao.actionFor("ASSISTANT_BATCH", token.removePrefix("group:"))
        token.startsWith("operation:") -> token.removePrefix("operation:").toLongOrNull()?.let { dao.action(it) }
        else -> null
    }

    private suspend fun changeHistory(token: String?, forward: Boolean): Applied {
        val next = if (forward) dao.redoAction() else dao.lastAction()
        val action = if (token == null) next else actionForToken(token)
        if (action == null) throw Rejected(CommandStatus.NOT_FOUND, if (forward) "还没有可重做的操作" else "还没有可撤销的操作")
        if (action.id != next?.id) throw Rejected(CommandStatus.CONFLICT, "操作历史已经变化，请先处理最近的操作")
        val targets = repository.historyTargets(action)
        if (forward) repository.redoLastActionRaw() else repository.undoLastActionRaw()
        return Applied(targets, null)
    }

    private fun label(command: Command): String = when (command) {
        is CreateSchedule, is UpdateSchedule -> "保存日程"
        is DeleteSchedule -> "删除日程"
        is CreateActual, is UpdateActual -> "保存实际记录"
        is DeleteActual -> "删除实际记录"
        is StartTimer -> if (command.switchExisting) "切换计时" else "开始计时"
        is StopTimer -> "结束计时"
        is BatchCommand -> "批量保存"
        is UndoCommand -> "撤销"
        is RedoCommand -> "重做"
    }
}

internal fun CommandResult.throwIfRejected() {
    when (status) {
        CommandStatus.APPLIED, CommandStatus.DUPLICATE -> Unit
        CommandStatus.CONFLICT -> if (conflict?.reason == "RUNNING_TIMER") throw TimerAlreadyRunning() else throw StaleChange()
        CommandStatus.INVALID, CommandStatus.NOT_FOUND -> throw IllegalArgumentException(message)
        CommandStatus.FAILED -> throw IllegalStateException(message)
    }
}
