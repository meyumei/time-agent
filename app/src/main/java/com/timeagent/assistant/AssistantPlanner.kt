package com.timeagent.assistant

import com.timeagent.core.*
import com.timeagent.data.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Converts validated intent into a concrete, immutable preview without touching the database. */
class AssistantPlanner {
    fun prepare(command: AssistantCommand, context: AssistantContext): AssistantReply {
        if (context.snapshot.loading) return AssistantReply.Clarification("数据正在加载，请稍后再解析。")
        return try { build(command, context) }
        catch (e: NeedClarification) { AssistantReply.Clarification(e.message ?: "请补充具体内容。") }
        catch (_: IllegalArgumentException) { AssistantReply.Clarification("名称、日期或时长无效，请检查后重试；尚未修改数据。") }
    }

    private fun build(command: AssistantCommand, context: AssistantContext): AssistantReply {
        val data = context.snapshot
        val id = UUID.randomUUID().toString()
        val now = context.now.toEpochMilli()
        fun preview(title: String, before: String, after: String, mutation: AssistantMutation, warnings: List<String> = emptyList(), future: Boolean = false) =
            AssistantReply.Preview(AssistantPreview(id, title, before, after, warnings, future, mutation))
        return when (command) {
            is AssistantCommand.Batch -> {
                val replies = command.commands.map { build(it, context) }
                val previews = replies.filterIsInstance<AssistantReply.Preview>().map { it.value }
                if (previews.isEmpty()) return AssistantReply.Answer(replies.filterIsInstance<AssistantReply.Answer>().joinToString("\n") { it.text })
                preview("记录与修正 · ${previews.size} 项", previews.joinToString("\n\n") { it.before },
                    replies.joinToString("\n\n") { when (it) {
                        is AssistantReply.Preview -> it.value.after
                        is AssistantReply.Answer -> it.text
                        is AssistantReply.Clarification -> throw NeedClarification(it.text)
                    } }, AssistantMutation.Batch(previews.map { it.mutation }),
                    previews.flatMap { it.warnings }.distinct(), previews.any { it.needsFutureApproval })
            }
            is AssistantCommand.CreateIdea -> {
                val title = command.title.trim()
                if (title.isEmpty()) throw NeedClarification("请说明想记录的想法内容，例如：记个想法周末去骑车。")
                require(title.length <= 200 && !title.contains('\u0000') && command.content.length <= 20000)
                // An idea is a thought, not a schedule: no task, no plan, no reminder, status stays UNSCHEDULED.
                val idea = IdeaEntity(id, title, command.content, "UNSCHEDULED")
                preview("记录想法 · ${idea.title}", "尚无这条想法", "${idea.title}\n状态：待安排\n不建任务、计划或提醒", AssistantMutation.SaveIdea(idea))
            }
            is AssistantCommand.DeleteIdea -> {
                val idea = data.ideas.singleOrNull { it.id == command.ideaId && it.deletedAt == null }
                    ?: throw NeedClarification("这条想法不存在或已删除。")
                preview("删除想法 · ${idea.title}", "${idea.title}\n状态：待安排", "已删除，可撤销恢复", AssistantMutation.SaveIdea(idea.copy(deletedAt = now), idea.version))
            }
            is AssistantCommand.CreateReminder -> {
                val title = command.title.trim()
                if (title.isEmpty()) throw NeedClarification("请说明提醒的内容，例如：提醒明天下午三点健身。")
                require(title.length <= 120 && !title.contains('\u0000'))
                validateTimestamp(command.at)
                if (command.at <= now) throw NeedClarification("这个时间已经过去，请说明将来的日期和时间。")
                // A point reminder is one instant only: no invented duration and no task or block behind it.
                val reminder = ReminderEntity(id, title, command.at)
                preview("提醒 · ${reminder.title}", "尚无这条提醒", "${reminder.title}\n${time(reminder.remindAt, context)}\n只提醒一次，不建任务或时段", AssistantMutation.SaveReminder(reminder))
            }
            is AssistantCommand.EditReminder -> {
                val reminder = findReminder(command.reminderId, command.reminderTitle, data)
                validateTimestamp(command.at)
                if (command.at <= now) throw NeedClarification("这个时间已经过去，请说明将来的日期和时间。")
                val changed = reminder.copy(remindAt = command.at)
                preview("修改提醒 · ${reminder.title}", "${reminder.title}\n${time(reminder.remindAt, context)}",
                    "${changed.title}\n${time(changed.remindAt, context)}\n只提醒一次，不建任务或时段", AssistantMutation.SaveReminder(changed, reminder.version))
            }
            is AssistantCommand.CancelReminder -> {
                val reminder = findReminder(command.reminderId, command.reminderTitle, data)
                if (!reminder.enabled) return AssistantReply.Answer("这条提醒已经取消，无需重复操作。")
                preview("取消提醒 · ${reminder.title}", "${reminder.title}\n${time(reminder.remindAt, context)}", "已取消，不再提醒；可撤销恢复",
                    AssistantMutation.SaveReminder(reminder.copy(enabled = false), reminder.version))
            }
            is AssistantCommand.Query -> query(command, context)
            is AssistantCommand.CreateEvent -> {
                validateTitle(command.title); validateSpan(command.startAt, command.endAt)
                val plan = ScheduleBlockEntity(id, null, command.startAt, command.endAt, title = command.title.trim(), timezoneId = context.zone.id)
                preview("安排 · ${plan.title}", "尚无此日程", "${plan.title}\n${range(plan.startAt, plan.endAt, context)}",
                    AssistantMutation.SavePlan(plan, expectedConflicts = conflictValues(plan.startAt, plan.endAt, null, context)), conflicts(plan.startAt, plan.endAt, null, context))
            }
            is AssistantCommand.EditRecord -> {
                val record = data.records.singleOrNull { it.id == command.recordId } ?: throw NeedClarification("这条记录不存在，请重新选择。")
                if (record.endAt == null) throw NeedClarification("该记录正在计时，请先停止计时。")
                require(command.startAt != null || command.endAt != null || command.label != null)
                command.label?.let(::validateTitle)
                val changed = record.copy(startAt = command.startAt ?: record.startAt, endAt = command.endAt ?: record.endAt,
                    title = if (record.title.isNotBlank()) command.label ?: record.title else record.title,
                    notes = if (record.title.isBlank()) command.label ?: record.notes else record.notes)
                validateSpan(changed.startAt, requireNotNull(changed.endAt))
                if (changed == record) return AssistantReply.Answer("这段记录已是所述时间，无需重复修改。\n${recordDescription(record, context)}")
                val future = changed.endAt > now
                preview("修改实际记录", recordDescription(record, context), recordDescription(changed, context),
                    AssistantMutation.SaveRecord(changed, expectedVersion = record.version), if (future) listOf("记录包含未来时间，若尚未发生请改建计划。") else emptyList(), future)
            }
            is AssistantCommand.DeleteRecord -> {
                val record = data.records.singleOrNull { it.id == command.recordId } ?: throw NeedClarification("这条记录不存在，请重新选择。")
                if (record.endAt == null) throw NeedClarification("该记录正在计时，请先停止计时。")
                preview("删除实际记录", recordDescription(record, context), "已删除，可撤销恢复", AssistantMutation.DeleteRecord(record.id, record.version))
            }
            is AssistantCommand.CompleteTask -> {
                val task = findTask(command.taskId, null, data)
                preview("完成 · ${task.title}", taskDescription(task, context), "${task.title} · 已完成", AssistantMutation.SaveTask(task.copy(status = "COMPLETED"), task.version))
            }
            is AssistantCommand.EditCourse -> {
                validateSpan(command.startAt, command.endAt)
                val item = data.occurrences.singleOrNull { it.id == command.occurrenceId } ?: throw NeedClarification("课程不存在，请重新选择。")
                val name = data.courses.find { it.id == item.courseId }?.name ?: "课程"
                val changed = item.copy(startAt = command.startAt, endAt = command.endAt, date = Instant.ofEpochMilli(command.startAt).atZone(context.zone).toLocalDate().toString(), status = "RESCHEDULED")
                preview("调整课程 · $name", range(item.startAt, item.endAt, context), "$name\n${range(changed.startAt, changed.endAt, context)}",
                    AssistantMutation.SaveCourse(changed, item.version, conflictValues(changed.startAt, changed.endAt, item.id, context)), conflicts(changed.startAt, changed.endAt, item.id, context))
            }
            is AssistantCommand.CancelCourse -> {
                val item = data.occurrences.singleOrNull { it.id == command.occurrenceId && it.status != "CANCELLED" } ?: throw NeedClarification("没有找到可取消的这次课程。")
                val name = data.courses.find { it.id == item.courseId }?.name ?: "课程"
                preview("取消本次课程 · $name", "$name\n${range(item.startAt, item.endAt, context)}", "仅取消这一次，可撤销恢复", AssistantMutation.SaveCourse(item.copy(status = "CANCELLED"), item.version))
            }
            AssistantCommand.CancelSampleCourses -> {
                val courses = SampleCourseScope.courses(data.courses)
                val occurrences = SampleCourseScope.occurrences(data.courses, data.occurrences)
                if (occurrences.isEmpty()) return AssistantReply.Answer("没有可取消的内置示例课程。")
                val included = courses.filter { course -> occurrences.any { it.courseId == course.id } }
                preview("取消示例课程 · ${included.size} 门 / ${occurrences.size} 次",
                    included.joinToString("\n\n") { course ->
                        val items = occurrences.filter { it.courseId == course.id }.sortedBy { it.startAt }
                        "${course.name} · ${items.size} 次\n" + items.joinToString("\n") { range(it.startAt, it.endAt, context) }
                    },
                    "取消整个内置示例学期的 ${occurrences.size} 次课程。确认后从时间轴隐藏；可在操作历史中一次撤销恢复。\n真实导入的课程、任务和实际记录保留。",
                    AssistantMutation.CancelSampleCourses(id, courses, occurrences),
                    listOf("范围是整个内置示例学期，包含当前屏幕以外的日期。请核对上方课程与次数。"))
            }
            is AssistantCommand.CreateTask -> {
                validateTitle(command.title); require(command.minutes in 1..10080)
                command.dueAt?.let { validateTimestamp(it) }
                val task = TaskEntity(id, command.title.trim(), estimatedMinutes = command.minutes, dueAt = command.dueAt, source = "ASSISTANT")
                val warnings = if (activeTasks(data).any { it.title == task.title }) listOf("已有同名任务；确认后会另建一项。") else emptyList()
                preview("新建任务", "尚无此任务", taskDescription(task, context), AssistantMutation.SaveTask(task), warnings)
            }
            is AssistantCommand.CreatePlan -> {
                validateSpan(command.startAt, command.endAt)
                val task = findTask(command.taskId, command.taskTitle, data, true)
                if (task.status == "COMPLETED") throw NeedClarification("这个任务已完成，请先在任务页恢复为待办再安排计划。")
                val plan = ScheduleBlockEntity(id, task.id, command.startAt, command.endAt)
                preview("新建计划 · ${task.title}", "尚无此计划", "${task.title}\n${range(plan.startAt, plan.endAt, context)}\n任务 #${task.id}",
                    AssistantMutation.SavePlan(plan, expectedTaskVersion = task.version, expectedConflicts = conflictValues(command.startAt, command.endAt, null, context)), conflicts(command.startAt, command.endAt, null, context))
            }
            is AssistantCommand.ManualRecord -> {
                validateTitle(command.label); validateSpan(command.startAt, command.endAt)
                require(command.notes == null || (command.notes.length <= 20000 && !command.notes.contains('\u0000')))
                val task = if (command.taskId != null || command.taskTitle != null) findTask(command.taskId, command.taskTitle, data) else null
                val category = data.categories.singleOrNull { !it.isArchived && it.name == command.label.trim() }
                val record = TimeRecordEntity(id, task?.id, command.startAt, command.endAt, categoryId = task?.categoryId ?: category?.id,
                    title = if (command.notes != null) command.label.trim() else "", notes = command.notes ?: command.label.trim(), source = "ASSISTANT")
                if (data.records.any { it.startAt == record.startAt && it.endAt == record.endAt && it.title == record.title && it.notes == record.notes && it.taskId == record.taskId })
                    return AssistantReply.Answer("已经有这段记录，未重复添加。\n${recordDescription(record, context)}")
                val future = command.endAt > now
                val overlap = data.records.count { it.endAt != null && command.startAt < it.endAt && it.startAt < command.endAt }
                val details = buildString {
                    append(record.title.ifBlank { task?.title ?: record.notes })
                    append('\n')
                    if ((task != null || record.title.isNotBlank()) && record.notes.isNotBlank()) append("备注：${record.notes}\n")
                    append(range(command.startAt, command.endAt, context))
                    append('\n')
                    append(if (task == null) "不关联任务" else "任务 #${task.id}")
                }
                preview("添加实际记录", "尚无此记录", details,
                    AssistantMutation.SaveRecord(record, task?.version), buildList {
                        if (future) add("这段实际记录包含未来时间；如尚未发生，请取消并创建计划。")
                        if (overlap > 0) add("与 $overlap 条实际记录重叠；累计投入会累加，去重时间跨度不会重复计算。")
                    }, future)
            }
            is AssistantCommand.EditTask -> {
                val task = findTask(command.taskId, command.taskTitle, data)
                if (command.title == null && command.minutes == null && command.dueAt == null) throw NeedClarification("请说明要修改的名称、时长或截止时间。")
                command.title?.let(::validateTitle); command.minutes?.let { require(it in 1..10080) }; command.dueAt?.let(::validateTimestamp)
                val changed = task.copy(title = command.title?.trim() ?: task.title, estimatedMinutes = command.minutes ?: task.estimatedMinutes, dueAt = command.dueAt ?: task.dueAt)
                preview("修改任务 · ${task.title}", taskDescription(task, context), taskDescription(changed, context), AssistantMutation.SaveTask(changed, task.version))
            }
            is AssistantCommand.CancelTask -> {
                val task = findTask(command.taskId, command.taskTitle, data)
                if (data.records.any { it.taskId == task.id && it.endAt == null }) throw NeedClarification("此任务正在计时，请先停止计时，再取消任务。")
                val count = data.plans.count { it.taskId == task.id && it.status == "SCHEDULED" }
                preview("取消任务 · ${task.title}", taskDescription(task, context), "${task.title}\n状态：已取消\n任务 #${task.id}", AssistantMutation.SaveTask(task.copy(status = "CANCELLED"), task.version),
                    listOf("取消后该任务的 $count 项计划会从日历隐藏；实际记录保留，可在操作历史中撤销。"))
            }
            is AssistantCommand.MovePlan -> {
                val plan = findPlan(command.planId, command.taskTitle, data, context)
                if (plan.kind == "POINT") validateTimestamp(command.startAt) else validateSpan(command.startAt, command.endAt)
                if (plan.isLocked) throw NeedClarification("这项计划已锁定，请先在计划编辑器中解锁。")
                val task = plan.taskId?.let { findTask(it, null, data) }
                val title = plan.displayTitle(task)
                val planZone = ZoneId.of(plan.timezoneId)
                val changed = plan.copy(startAt = command.startAt, endAt = if (plan.kind == "POINT") command.startAt else command.endAt,
                    allDayStartDate = if (plan.kind == "ALL_DAY") Instant.ofEpochMilli(command.startAt).atZone(planZone).toLocalDate().toString() else plan.allDayStartDate,
                    allDayEndDate = if (plan.kind == "ALL_DAY") Instant.ofEpochMilli(command.endAt - 1).atZone(planZone).toLocalDate().toString() else plan.allDayEndDate)
                val clashes = if (plan.kind == "POINT") emptyList() else conflictValues(changed.startAt, changed.endAt, plan.id, context)
                preview("移动计划 · $title", "$title\n${range(plan.startAt, plan.endAt, context)}\n计划 #${plan.id}",
                    "$title\n${range(changed.startAt, changed.endAt, context)}\n计划 #${plan.id}", AssistantMutation.SavePlan(changed, plan.version, task?.version, clashes),
                    if (plan.kind == "POINT") emptyList() else conflicts(changed.startAt, changed.endAt, plan.id, context))
            }
            is AssistantCommand.CancelPlan -> {
                val plan = findPlan(command.planId, command.taskTitle, data, context)
                if (plan.isLocked) throw NeedClarification("这项计划已锁定，请先在计划编辑器中解锁。")
                val task = plan.taskId?.let { findTask(it, null, data) }
                val title = plan.displayTitle(task)
                preview("取消计划 · $title", "$title\n${range(plan.startAt, plan.endAt, context)}\n计划 #${plan.id}",
                    "状态：已取消\n计划 #${plan.id}", AssistantMutation.SavePlan(plan.copy(status = "CANCELLED"), plan.version, task?.version,
                        if (plan.kind == "POINT") emptyList() else conflictValues(plan.startAt, plan.endAt, plan.id, context)))
            }
            is AssistantCommand.StartTimer -> {
                val running = data.records.firstOrNull { it.endAt == null }
                if (running != null) throw NeedClarification("已有计时进行中：${data.tasks.find { it.id == running.taskId }?.title ?: "自由计时"}。请先停止计时，再开始新的计时。")
                val task = if (command.taskId != null || command.taskTitle != null) findTask(command.taskId, command.taskTitle, data) else null
                preview("开始计时", "当前没有运行的计时", "${task?.title ?: "自由计时"}\n确认时开始记录实际投入${task?.let { "\n任务 #${it.id}" } ?: ""}", AssistantMutation.StartTimer(id, task?.id, task?.version))
            }
            AssistantCommand.StopTimer -> {
                val timer = data.records.firstOrNull { it.endAt == null } ?: throw NeedClarification("当前没有正在进行的计时。")
                val title = data.tasks.find { it.id == timer.taskId }?.title ?: "自由计时"
                preview("停止计时 · $title", "$title\n开始：${time(timer.startAt, context)}\n记录 #${timer.id}", "$title\n确认时结束当前计时\n记录 #${timer.id}", AssistantMutation.StopTimer(timer.id, timer.version))
            }
        }
    }

    private fun query(command: AssistantCommand.Query, context: AssistantContext): AssistantReply.Answer {
        val data = context.snapshot
        if (command.scope == QueryScope.TASKS) {
            val tasks = activeTasks(data)
            return AssistantReply.Answer(if (tasks.isEmpty()) "当前没有任务。" else "任务清单（${tasks.size} 项）\n" + tasks.joinToString("\n\n") { taskDescription(it, context) })
        }
        val today = context.now.atZone(context.zone).toLocalDate()
        val start = command.date ?: when (command.scope) {
            QueryScope.TOMORROW -> today.plusDays(1)
            QueryScope.SELECTED_WEEK -> context.selectedWeekStart
            else -> today
        }
        val end = if (command.scope == QueryScope.SELECTED_WEEK) start.plusDays(7) else start.plusDays(1)
        val window = TimeSpan(start.atStartOfDay(context.zone).toInstant(), end.atStartOfDay(context.zone).toInstant())
        val busy = scheduledEvents(data)
        if (command.scope == QueryScope.FREE_TIME) {
            require(command.minMinutes in 1..10080)
            val waking = TimeSpan(start.atTime(8, 0).atZone(context.zone).toInstant(), start.atTime(22, 0).atZone(context.zone).toInstant())
            val slots = Scheduling.findFreeSlots(waking, busy.filter { it.occupiesTime }.map { it.span }, command.minMinutes)
            return AssistantReply.Answer("$start 空闲（08:00–22:00，至少 ${command.minMinutes} 分钟；按课程和计划计算）\n" +
                if (slots.isEmpty()) "没有满足条件的空档。" else slots.joinToString("\n") { range(it.start.toEpochMilli(), it.end.toEpochMilli(), context) })
        }
        val events = busy.filter { it.span.overlaps(window) }.sortedBy { it.span.start }
        val label = if (command.scope == QueryScope.SELECTED_WEEK) "所选周 $start 至 ${end.minusDays(1)}" else "$start"
        return AssistantReply.Answer("$label 的安排（${events.size} 项）\n" + if (events.isEmpty()) "没有课程或计划。" else events.joinToString("\n\n") { "${it.kind} · ${it.title}\n${range(it.span.start.toEpochMilli(), it.span.end.toEpochMilli(), context)}\n#${it.id}" })
    }

    private data class Event(val id: String, val title: String, val kind: String, val span: TimeSpan, val occupiesTime: Boolean = true)
    private fun scheduledEvents(data: LocalSnapshot): List<Event> {
        val tasks = activeTasks(data).associateBy { it.id }
        return data.plans.filter { it.status == "SCHEDULED" && it.deletedAt == null && (it.taskId == null || it.taskId in tasks) }.map {
            Event(it.id, it.displayTitle(tasks[it.taskId]), "计划", TimeSpan(Instant.ofEpochMilli(it.startAt), Instant.ofEpochMilli(if (it.kind == "POINT") it.startAt + 1 else it.endAt)), it.kind != "POINT")
        } +
            data.occurrences.filter { it.status != "CANCELLED" }.map { Event(it.id, data.courses.find { c -> c.id == it.courseId }?.name ?: "课程", "课程", TimeSpan(Instant.ofEpochMilli(it.startAt), Instant.ofEpochMilli(it.endAt))) }
    }
    private fun conflicts(start: Long, end: Long, excluding: String?, context: AssistantContext): List<String> {
        return conflictValues(start, end, excluding, context).map {
            "时间冲突：${it.title}，${range(it.span.start.toEpochMilli(), it.span.end.toEpochMilli(), context)}"
        }
    }
    private fun conflictValues(start: Long, end: Long, excluding: String?, context: AssistantContext): List<Conflict> {
        val target = TimeSpan(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end))
        return scheduledEvents(context.snapshot).filter { it.occupiesTime && it.id != excluding && target.overlaps(it.span) }.map { Conflict(it.id, it.title, it.span) }
    }
    private fun activeTasks(data: LocalSnapshot) = data.tasks.filter { it.deletedAt == null && it.status != "CANCELLED" }
    private fun findTask(id: String?, title: String?, data: LocalSnapshot, forPlan: Boolean = false): TaskEntity {
        if (id.isNullOrBlank() && title.isNullOrBlank()) throw NeedClarification("请提供具体任务名称或 #任务ID。")
        val candidates = activeTasks(data).filter { if (id != null) it.id == id else it.title == title?.trim() }
        if (candidates.isEmpty()) throw NeedClarification(if (forPlan) "未找到已有任务“${title ?: id}”。请先新建任务并确认，然后再创建计划。" else "未找到任务“${title ?: id}”。请查看任务清单，使用完整名称或 #任务ID。")
        if (candidates.size != 1) throw NeedClarification("有 ${candidates.size} 个同名任务，请用 #任务ID 明确选择：\n" + candidates.joinToString("\n") { "${it.title} #${it.id}" })
        return candidates.single()
    }
    private fun findReminder(id: String?, title: String?, data: LocalSnapshot): ReminderEntity {
        if (id.isNullOrBlank() && title.isNullOrBlank()) throw NeedClarification("请说明是哪一条提醒，例如：取消刚才的提醒。")
        val candidates = data.reminders.filter { if (id != null) it.id == id else it.title == title?.trim() }
        if (candidates.isEmpty()) throw NeedClarification("未找到这条提醒；请先创建提醒，或说明完整内容。")
        if (candidates.size != 1) throw NeedClarification("找到 ${candidates.size} 条相关提醒，请说明要处理哪一条。")
        return candidates.single()
    }
    private fun findPlan(id: String?, title: String?, data: LocalSnapshot, context: AssistantContext): ScheduleBlockEntity {
        if (id.isNullOrBlank() && title.isNullOrBlank()) throw NeedClarification("请提供计划关联的任务名称或 #计划ID。")
        val taskIds = activeTasks(data).filter { it.title == title?.trim() }.map { it.id }.toSet()
        val candidates = data.plans.filter { it.deletedAt == null && it.status == "SCHEDULED" && if (id != null) it.id == id else it.title == title?.trim() || it.taskId in taskIds }
        if (candidates.isEmpty()) throw NeedClarification("没有找到对应计划，请查询日程并使用 #计划ID。")
        if (candidates.size != 1) throw NeedClarification("有多项计划，请使用 #计划ID 明确选择：\n" + candidates.joinToString("\n") { "${range(it.startAt, it.endAt, context)} #${it.id}" })
        return candidates.single()
    }
    private fun taskDescription(task: TaskEntity, context: AssistantContext) = "${task.title}\n预计 ${task.estimatedMinutes} 分钟 · ${when (task.status) { "COMPLETED" -> "已完成"; "IN_PROGRESS" -> "进行中"; "CANCELLED" -> "已取消"; else -> "待办" }}" +
        (task.dueAt?.let { "\n截止：${time(it, context)}" } ?: "\n未设截止时间") + "\n任务 #${task.id}"
    private fun recordDescription(record: TimeRecordEntity, context: AssistantContext) = buildString {
        append(record.title.ifBlank { record.notes })
        append('\n')
        append(range(record.startAt, requireNotNull(record.endAt), context))
        if (record.title.isNotBlank() && record.notes.isNotBlank()) append("\n备注：${record.notes}")
    }
    private fun validateTitle(title: String) { require(title.trim().length in 1..120 && !title.contains('\u0000')) }
    private fun validateTimestamp(value: Long) { require(value in 946684800000L..4133980800000L) }
    private fun validateSpan(start: Long, end: Long) { validateTimestamp(start); validateTimestamp(end); require(end > start && end - start <= 10080L * 60000) }
    private fun time(value: Long, context: AssistantContext) = Instant.ofEpochMilli(value).atZone(context.zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    private fun range(start: Long, end: Long, context: AssistantContext): String {
        val a = Instant.ofEpochMilli(start).atZone(context.zone)
        val b = Instant.ofEpochMilli(end).atZone(context.zone)
        return a.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "–" + b.format(DateTimeFormatter.ofPattern(if (a.toLocalDate() == b.toLocalDate()) "HH:mm" else "yyyy-MM-dd HH:mm"))
    }
    private class NeedClarification(message: String) : IllegalArgumentException(message)
}
