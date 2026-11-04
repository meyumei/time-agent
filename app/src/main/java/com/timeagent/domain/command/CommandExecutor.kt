package com.timeagent.domain.command

import com.google.gson.Gson
import com.timeagent.data.*
import com.timeagent.assistant.*
import com.timeagent.fish.TimeToolQueries
import com.timeagent.recurring.RecurringService
import com.timeagent.workout.data.WorkoutRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Duration
import java.time.DateTimeException

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
            if (command is ReadTimeData) {
                return@transaction CommandResult(command.requestId, CommandStatus.APPLIED,
                    message = "已读取本地数据", payload = TimeToolQueries(repository).read(command.tool, command.date).toString())
            }
            if (command is ReadAssistantQuery) {
                val context = AssistantContext(repository.now(), repository.zone, command.selectedWeekStart, repository.snapshot())
                val answer = AssistantPlanner().prepare(command.query, context) as AssistantReply.Answer
                return@transaction CommandResult(command.requestId, CommandStatus.APPLIED, message = answer.text)
            }
            if (command is CountActualRecords) {
                val count = dao.records().count { it.endAt != null && (!command.sleepOnly || it.notes in setOf("睡眠", "睡觉")) }
                return@transaction CommandResult(command.requestId, CommandStatus.APPLIED,
                    message = "当前有 $count 条相关实际记录；本次询问未添加新记录。")
            }
            replay(command.requestId)?.let { return@transaction it }
            var payload: String? = null
            val applied = when (command) {
                is UndoCommand -> changeHistory(command.undoToken, forward = false)
                is RedoCommand -> changeHistory(command.undoToken, forward = true)
                is DeliverReminder -> {
                    if (!repository.markReminderDeliveredRaw(command.id, command.remindAt)) return@transaction CommandResult(
                        command.requestId, CommandStatus.NOT_FOUND, message = "提醒已处理或已变化，本次未投递")
                    Applied(listOf("REMINDER" to command.id), null)
                }
                else -> {
                    val captured = repository.commandWrite(command.requestId, label(command)) {
                        when (command) {
                            is MutationCommand -> apply(command)
                            is BatchCommand -> {
                                require(command.commands.isNotEmpty()) { "没有可执行的内容" }
                                command.commands.flatMap { apply(it) }
                            }
                            is CommitAssistant -> {
                                require(!command.preview.needsFutureApproval || command.allowFuture) { "实际记录包含未来时间，请先确认" }
                                val mutation = command.preview.mutation.fromOrigin(command.origin)
                                applyAssistant(mutation, command.allowFuture, command.origin)
                                val id = mutation.createdId()
                                if (command.projectId != null) {
                                    require(mutation is AssistantMutation.SaveTask) { "只有任务可设置项目归属" }
                                    repository.savePlacement(TaskPlacementEntity(id, command.projectId))
                                }
                                payload = gson.toJson(mapOf("id" to id, "message" to command.preview.after))
                                emptyList()
                            }
                            is RememberRule -> {
                                val saved = repository.rememberExplicitRaw(command.text)
                                payload = gson.toJson(gson.toJsonTree(saved).asJsonObject.apply {
                                    addProperty("destination", "memory")
                                    addProperty("message", "已记住：${saved.text}")
                                })
                                emptyList()
                            }
                            is ConvertInbox -> {
                                repository.convertInboxRaw(command)
                                emptyList()
                            }
                            is ApplySuggestion -> {
                                repository.executeSuggestionRaw(command.id, command.expectedVersion, command.action)
                                emptyList()
                            }
                            is SetSuggestionPolicy -> {
                                repository.setSuggestionPolicyRaw(command.type, command.suppressed)
                                emptyList()
                            }
                            is CompleteRecurring -> {
                                version("TASK", dao.recurringInstance(command.instanceId)?.taskId ?: "", command.expectedTaskVersion)
                                RecurringService(repository).actRaw(command.instanceId, command.expectedVersion, "COMPLETE")
                                payload = gson.toJson(mapOf("id" to dao.recurringInstance(command.instanceId)!!.taskId,
                                    "message" to "本次已完成，实际投入未自动生成"))
                                emptyList()
                            }
                            is SaveRecurringRule -> {
                                version("RECURRING_RULE", command.rule.id, command.expectedVersion, command.expectedVersion == null)
                                RecurringService(repository).saveRuleRaw(command.rule, command.expectedVersion, command.from, command.scope, command.preview)
                                emptyList()
                            }
                            is ActRecurringInstance -> {
                                version("RECURRING_INSTANCE", command.instanceId, command.expectedVersion)
                                val instance = requireNotNull(dao.recurringInstance(command.instanceId))
                                version("TASK", instance.taskId, command.expectedTaskVersion, allowCancelled = command.action.equals("REOPEN", true))
                                RecurringService(repository).actRaw(command.instanceId, command.expectedVersion, command.action, command.expectedPlans)
                                emptyList()
                            }
                            is MoveRecurringInstance -> {
                                RecurringService(repository).moveRaw(command.check)
                                emptyList()
                            }
                            is AdjustRecurringTimeline -> RecurringService(repository).adjustTimelineRaw(command).map {
                                ScheduleWarning(it.id, it.title, it.span.start.toEpochMilli(), it.span.end.toEpochMilli())
                            }
                            is WriteTaskDetails -> {
                                version("TASK", command.task.id, command.expectedVersion, command.expectedVersion == null)
                                val org = repository.database.organizationDao()
                                val placement = dao.placement(command.task.id)
                                val tags = org.taskTags().filter { it.taskId == command.task.id }.map { it.tagId }.distinct().sorted()
                                if ((command.updateOrganization || command.newSubtasks.isNotEmpty()) &&
                                    placement?.version != command.expectedPlacementVersion)
                                    throw StaleChange()
                                if (command.updateOrganization && tags != command.expectedTagIds.distinct().sorted())
                                    throw StaleChange()
                                if (command.updateOrganization) for (id in command.tagIds.distinct()) {
                                    require(org.tag(id) != null) {
                                        repository.applicationContext?.getString(com.timeagent.R.string.task_tag_missing)
                                            ?: "The selected tag was deleted; reopen the editor."
                                    }
                                }
                                if (command.updateOrganization) command.projectId?.let { id ->
                                    require(dao.project(id)?.let { it.deletedAt == null } == true) {
                                        repository.applicationContext?.getString(com.timeagent.R.string.task_project_missing)
                                            ?: "The selected project was deleted; reopen the editor."
                                    }
                                }
                                val saved = repository.saveTaskRaw(command.task, command.expectedVersion)
                                if (command.updateOrganization) {
                                    if (placement?.projectId != command.projectId) repository.savePlacementRaw(
                                        (placement ?: TaskPlacementEntity(saved.id)).copy(projectId = command.projectId), placement?.version)
                                    val nextTags = command.tagIds.distinct().sorted()
                                    if (tags != nextTags) {
                                        org.clearTaskTags(saved.id)
                                        nextTags.forEach { org.addTaskTag(TaskTagEntity(saved.id, it)) }
                                        repository.journal("ORG_TASK_TAGS", saved.id,
                                            com.timeagent.organization.OrgTagLinks(tags), com.timeagent.organization.OrgTagLinks(nextTags), "Task tags")
                                    }
                                }
                                val invalidLinks = repository.applicationContext?.getString(com.timeagent.R.string.task_links_invalid)
                                    ?: "Task links are invalid. Nothing was saved; reopen the editor."
                                require(command.newSubtasks.map { it.id }.distinct().size == command.newSubtasks.size) { invalidLinks }
                                if (command.newSubtasks.isNotEmpty()) {
                                    val parent = dao.placement(saved.id)
                                        ?: repository.savePlacementRaw(TaskPlacementEntity(saved.id), null)
                                    for (child in command.newSubtasks) {
                                        require(child.id != saved.id) { invalidLinks }
                                        version("TASK", child.id, null, create = true)
                                        val subtask = repository.saveTaskRaw(child, null)
                                        repository.savePlacementRaw(TaskPlacementEntity(subtask.id,
                                            projectId = parent.projectId, parentTaskId = saved.id), null)
                                    }
                                }
                                command.linkedDetails?.let { repository.applyTaskLinkedDetails(saved, it) }
                                val conflicts = command.newPlan?.let { plan ->
                                    require(plan.taskId == saved.id)
                                    version("PLAN", plan.id, null, true)
                                    repository.savePlanRaw(plan, expectedTaskVersion = saved.version)
                                }.orEmpty()
                                payload = gson.toJson(saved)
                                conflicts.map { ScheduleWarning(it.id, it.title, it.span.start.toEpochMilli(), it.span.end.toEpochMilli()) }
                            }
                            is WriteProject -> {
                                payload = gson.toJson(when (val mutation = command.mutation) {
                                    is ProjectMutation.Save -> {
                                        version("PROJECT", mutation.project.id, mutation.expectedVersion, mutation.expectedVersion == null)
                                        repository.saveProjectRaw(mutation.project, mutation.expectedVersion)
                                    }
                                    is ProjectMutation.Delete -> {
                                        version("PROJECT", mutation.id, mutation.expectedVersion)
                                        repository.deleteProjectRaw(mutation.id, mutation.expectedVersion)
                                        dao.project(mutation.id)
                                    }
                                    is ProjectMutation.PlaceTask -> {
                                        version("PLACEMENT", mutation.placement.taskId, mutation.expectedVersion, mutation.expectedVersion == null)
                                        repository.savePlacementRaw(mutation.placement, mutation.expectedVersion)
                                    }
                                    is ProjectMutation.SaveGoal -> {
                                        version("GOAL", mutation.goal.id, mutation.expectedVersion, mutation.expectedVersion == null)
                                        repository.saveWeeklyGoalRaw(mutation.goal, mutation.expectedVersion)
                                    }
                                })
                                emptyList()
                            }
                            is WriteLedger -> {
                                val ledger = com.timeagent.ledger.LedgerRepository(
                                    repository.database,
                                    java.time.Clock.fixed(repository.now(), repository.zone),
                                    repository,
                                )
                                payload = gson.toJson(ledger.applyMutation(command.mutation))
                                emptyList()
                            }
                            is WriteWorkout -> {
                                val workoutDb = requireNotNull(repository.workoutDatabase) { "训练数据库尚未就绪" }
                                payload = gson.toJson(WorkoutRepository(workoutDb, repository, repository.commandClock).applyMutation(command.mutation))
                                emptyList()
                            }
                            is WriteReminder -> {
                                payload = gson.toJson(com.timeagent.reminders.ReminderInstanceRepository(repository).applyMutation(command.mutation))
                                emptyList()
                            }
                            is WriteQuickEntry -> {
                                payload = gson.toJson(com.timeagent.quickentry.QuickEntryRepository(repository,
                                    requireNotNull(repository.applicationContext)).applyMutation(command.mutation, command.requestId))
                                emptyList()
                            }
                            is WriteHabit -> {
                                payload = gson.toJson(com.timeagent.habits.HabitRepository(repository,
                                    repository.applicationContext).applyMutation(command.mutation))
                                emptyList()
                            }
                            is WriteCalendarSync -> {
                                payload = gson.toJson(com.timeagent.calendar.CalendarSyncRepository(repository,
                                    requireNotNull(repository.applicationContext)).applyMutation(command.mutation))
                                emptyList()
                            }
                            is WriteSampleData -> {
                                payload = gson.toJson(com.timeagent.onboarding.SampleDataRepository(repository,
                                    requireNotNull(repository.applicationContext)).applyMutation(command.mutation))
                                emptyList()
                            }
                            is SwitchFocusToTimer -> {
                                val focus = com.timeagent.focus.FocusRepository(repository, repository.applicationContext)
                                focus.applyMutation(FocusMutation.Finish(command.focusId, command.expectedVersion))
                                repository.startTimerRaw(command.taskId, command.requestId, expectedTaskVersion = command.expectedTaskVersion)
                                emptyList()
                            }
                            is WriteFocus -> {
                                payload = gson.toJson(com.timeagent.focus.FocusRepository(
                                    repository, repository.applicationContext,
                                ).applyMutation(command.mutation, command.requestId))
                                emptyList()
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
                "${label(command)}成功", warnings = applied.warnings, timerRecord = timerRecord, payload = payload,
                sourceRef = (command.origin as? Origin.FISH)?.let { "${it.sessionId}/${it.messageId}" })
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
    } catch (_: com.timeagent.core.workout.WorkoutAlreadyActive) {
        CommandResult(command.requestId, CommandStatus.CONFLICT,
            message = "已有训练正在进行或暂停，请返回训练结束或确认切换后再开始",
            conflict = ConflictInfo("WORKOUT_SESSION", "", null, null, null, "RUNNING_WORKOUT"))
    } catch (_: DateTimeException) {
        CommandResult(command.requestId, CommandStatus.INVALID, message = "日期时间格式无效，请使用带时区的 ISO-8601 时间，尚未保存")
    } catch (e: IllegalArgumentException) {
        CommandResult(command.requestId, CommandStatus.INVALID, message = e.message ?: "内容无效，尚未保存")
    } catch (e: Exception) {
        // Existing repository callers use database exception types to recover and retry.
        if (preserveFailure) throw e
        CommandResult(command.requestId, CommandStatus.FAILED, message = "操作未保存，请稍后重试；原数据保持不变")
    }

    private suspend fun version(type: String, id: String, expected: Long?, create: Boolean = false, allowCancelled: Boolean = false) {
        val entity: Any? = when (type) {
            "PLAN" -> dao.plan(id)
            "RECORD" -> dao.record(id)
            "TASK" -> dao.task(id)
            "IDEA" -> dao.idea(id)
            "REMINDER" -> dao.reminder(id)
            "OCCURRENCE" -> dao.occurrence(id)
            "RECURRING_RULE" -> dao.recurringRule(id)
            "RECURRING_INSTANCE" -> dao.recurringInstance(id)
            "PROJECT" -> dao.project(id)
            "PLACEMENT" -> dao.placement(id)
            "GOAL" -> dao.weeklyGoal(id)
            else -> error("未知实体类型")
        }
        val current = when (entity) {
            is ScheduleBlockEntity -> entity.version
            is TimeRecordEntity -> entity.version
            is TaskEntity -> entity.version
            is IdeaEntity -> entity.version
            is ReminderEntity -> entity.version
            is CourseOccurrenceEntity -> entity.version
            is RecurringRuleEntity -> entity.version
            is RecurringInstanceEntity -> entity.version
            is ProjectEntity -> entity.version
            is TaskPlacementEntity -> entity.version
            is WeeklyGoalEntity -> entity.version
            else -> null
        }
        val deleted = when (entity) {
            is TaskEntity -> entity.deletedAt != null || (!allowCancelled && entity.status == "CANCELLED")
            is ProjectEntity -> entity.deletedAt != null
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

    private suspend fun applyAssistant(mutation: AssistantMutation, allowFuture: Boolean, origin: Origin) {
        when (mutation) {
            is AssistantMutation.Batch -> {
                require(mutation.mutations.isNotEmpty()) { "没有可执行的内容" }
                mutation.mutations.forEach { applyAssistant(it, allowFuture, origin) }
                return
            }
            is AssistantMutation.SaveTask -> version("TASK", mutation.task.id, mutation.expectedVersion, mutation.expectedVersion == null)
            is AssistantMutation.SavePlan -> {
                version("PLAN", mutation.plan.id, mutation.expectedVersion, mutation.expectedVersion == null)
                task(mutation.plan.taskId, mutation.expectedTaskVersion)
            }
            is AssistantMutation.SaveRecord -> {
                version("RECORD", mutation.record.id, mutation.expectedVersion, mutation.expectedVersion == null)
                if (mutation.expectedVersion == null) task(mutation.record.taskId, mutation.expectedTaskVersion)
            }
            is AssistantMutation.SaveIdea -> version("IDEA", mutation.idea.id, mutation.expectedVersion, mutation.expectedVersion == null)
            is AssistantMutation.SaveReminder -> version("REMINDER", mutation.reminder.id, mutation.expectedVersion, mutation.expectedVersion == null)
            is AssistantMutation.SaveCourse -> version("OCCURRENCE", mutation.occurrence.id, mutation.expectedVersion)
            is AssistantMutation.DeleteRecord -> version("RECORD", mutation.id, mutation.expectedVersion)
            is AssistantMutation.StartTimer -> {
                version("RECORD", mutation.requestId, null, create = true)
                task(mutation.taskId, mutation.expectedTaskVersion)
            }
            is AssistantMutation.StopTimer -> version("RECORD", mutation.recordId, mutation.expectedVersion)
            is AssistantMutation.SaveEvent -> {
                version("TASK", mutation.task.id, null, create = true)
                version("PLAN", mutation.plan.id, null, create = true)
            }
            is AssistantMutation.CancelSampleCourses -> Unit
        }
        RepositoryAssistantWriter(repository).writeRaw(mutation, allowFuture, origin)
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
        is CommitAssistant -> command.preview.title
        is RememberRule -> "保存个人规则"
        is ConvertInbox -> "分拣收件箱"
        is ApplySuggestion -> "执行建议"
        is SetSuggestionPolicy -> "修改建议偏好"
        is CompleteRecurring -> "完成周期事项"
        is SaveRecurringRule -> "保存周期规则"
        is ActRecurringInstance -> "处理周期实例"
        is MoveRecurringInstance -> "改期周期实例"
        is AdjustRecurringTimeline -> "调整周期日程"
        is WriteTaskDetails -> repository.applicationContext?.getString(com.timeagent.R.string.task_save_action) ?: "Save task"
        is WriteProject -> repository.applicationContext?.getString(com.timeagent.R.string.project_write_action) ?: "Update project"
        is WriteLedger -> when (command.mutation) {
            is LedgerMutation.SaveAccount -> "保存账户"
            is LedgerMutation.ArchiveAccount -> "归档账户"
            is LedgerMutation.SaveCategory -> "保存记账分类"
            is LedgerMutation.ArchiveCategory -> "归档记账分类"
            is LedgerMutation.SaveTransaction -> "保存账目"
            is LedgerMutation.DeleteTransaction -> "删除账目"
            is LedgerMutation.Merge -> "合并账目"
            is LedgerMutation.Split -> "拆分账目"
            is LedgerMutation.Capture -> "捕获支付草稿"
            is LedgerMutation.Ignore -> "忽略支付草稿"
            is LedgerMutation.Confirm -> "确认支付草稿"
            is LedgerMutation.Import -> "导入账单"
        }
        is WriteWorkout -> when (val mutation = command.mutation) {
            is WorkoutMutation.Start, is WorkoutMutation.StartFromTemplate, is WorkoutMutation.RepeatSession -> "开始训练"
            is WorkoutMutation.SaveSession -> mutation.label
            is WorkoutMutation.Finish -> "结束训练"
            is WorkoutMutation.SaveTemplate -> "保存训练模板"
            is WorkoutMutation.DeleteTemplate -> "删除训练模板"
            is WorkoutMutation.SwitchToTimer -> "结束训练并开始计时"
        }
        is DeliverReminder -> "投递提醒"
        is WriteQuickEntry -> "模板与快捷记录"
        is WriteHabit -> "修改习惯"
        is WriteCalendarSync -> "同步系统日历"
        is WriteSampleData -> "管理示例数据"
        is SwitchFocusToTimer -> "结束专注并开始计时"
        is WriteFocus -> when (command.mutation) {
            is FocusMutation.Start -> "开始专注"
            is FocusMutation.Pause -> "暂停专注"
            is FocusMutation.Resume -> "继续专注"
            is FocusMutation.Finish -> "结束专注"
            is FocusMutation.SkipBreak -> "跳过休息"
            is FocusMutation.Tick -> "切换专注阶段"
            is FocusMutation.AdjustSegment -> "调整专注实际片段"
        }
        is WriteReminder -> when (command.mutation) {
            is ReminderMutation.Save -> "保存提醒"
            is ReminderMutation.Delete -> "删除提醒"
            is ReminderMutation.Snooze -> "稍后提醒"
            is ReminderMutation.Act -> "处理提醒"
            is ReminderMutation.Fire -> "投递提醒"
            ReminderMutation.Reconcile -> "重新排程提醒"
        }
        is ReadTimeData -> "查询"
        is ReadAssistantQuery -> "查询"
        is CountActualRecords -> "查询实际记录"
    }
}

internal fun CommandResult.throwIfRejected() {
    when (status) {
        CommandStatus.APPLIED, CommandStatus.DUPLICATE -> Unit
        CommandStatus.CONFLICT -> if (conflict?.reason == "RUNNING_TIMER") throw TimerAlreadyRunning() else throw StaleChange()
        CommandStatus.INVALID, CommandStatus.NOT_FOUND, CommandStatus.NEEDS_CONFIRMATION -> throw IllegalArgumentException(message)
        CommandStatus.FAILED -> throw IllegalStateException(message)
    }
}
