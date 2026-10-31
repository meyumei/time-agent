package com.timeagent.assistant

import com.timeagent.data.*
import java.time.*

data class AssistantContext(val now: Instant, val zone: ZoneId, val selectedWeekStart: LocalDate, val snapshot: LocalSnapshot)

enum class QueryScope { TODAY, TOMORROW, SELECTED_WEEK, TASKS, FREE_TIME }

sealed interface AssistantCommand {
    data class Batch(val commands: List<AssistantCommand>) : AssistantCommand
    data class Query(val scope: QueryScope, val date: LocalDate? = null, val minMinutes: Int = 30) : AssistantCommand
    data class CreateTask(val title: String, val minutes: Int = 30, val dueAt: Long? = null) : AssistantCommand
    data class CreateIdea(val title: String, val content: String = "") : AssistantCommand
    data class DeleteIdea(val ideaId: String) : AssistantCommand
    data class CreateReminder(val title: String, val at: Long) : AssistantCommand
    data class EditReminder(val reminderId: String? = null, val reminderTitle: String? = null, val at: Long) : AssistantCommand
    data class CancelReminder(val reminderId: String? = null, val reminderTitle: String? = null) : AssistantCommand
    data class CreatePlan(val taskId: String? = null, val taskTitle: String? = null, val startAt: Long, val endAt: Long) : AssistantCommand
    data class CreateEvent(val title: String, val startAt: Long, val endAt: Long) : AssistantCommand
    data class ManualRecord(val taskId: String? = null, val taskTitle: String? = null, val label: String, val startAt: Long, val endAt: Long, val notes: String? = null) : AssistantCommand
    data class EditTask(val taskId: String? = null, val taskTitle: String? = null, val title: String? = null, val minutes: Int? = null, val dueAt: Long? = null) : AssistantCommand
    data class CancelTask(val taskId: String? = null, val taskTitle: String? = null) : AssistantCommand
    data class MovePlan(val planId: String? = null, val taskTitle: String? = null, val startAt: Long, val endAt: Long) : AssistantCommand
    data class CancelPlan(val planId: String? = null, val taskTitle: String? = null) : AssistantCommand
    data class EditRecord(val recordId: String, val startAt: Long? = null, val endAt: Long? = null, val label: String? = null) : AssistantCommand
    data class DeleteRecord(val recordId: String) : AssistantCommand
    data class CompleteTask(val taskId: String) : AssistantCommand
    data class EditCourse(val occurrenceId: String, val startAt: Long, val endAt: Long) : AssistantCommand
    data class CancelCourse(val occurrenceId: String) : AssistantCommand
    data object CancelSampleCourses : AssistantCommand
    data class StartTimer(val taskId: String? = null, val taskTitle: String? = null) : AssistantCommand
    data object StopTimer : AssistantCommand
}

sealed interface Interpretation {
    data class Parsed(val command: AssistantCommand) : Interpretation
    data class Clarify(val message: String) : Interpretation
    data class Unsupported(val message: String) : Interpretation
}

sealed interface AssistantReply {
    data class Answer(val text: String) : AssistantReply
    data class Clarification(val text: String) : AssistantReply
    data class Preview(val value: AssistantPreview) : AssistantReply
}

data class AssistantPreview(
    val id: String,
    val title: String,
    val before: String,
    val after: String,
    val warnings: List<String> = emptyList(),
    val needsFutureApproval: Boolean = false,
    val mutation: AssistantMutation,
)

sealed interface AssistantMutation {
    data class Batch(val mutations: List<AssistantMutation>) : AssistantMutation
    data class SaveIdea(val idea: IdeaEntity, val expectedVersion: Long? = null) : AssistantMutation
    data class SaveReminder(val reminder: ReminderEntity, val expectedVersion: Long? = null) : AssistantMutation
    data class SaveTask(val task: TaskEntity, val expectedVersion: Long? = null) : AssistantMutation
    data class SavePlan(val plan: ScheduleBlockEntity, val expectedVersion: Long? = null, val expectedTaskVersion: Long? = null, val expectedConflicts: List<Conflict>? = null) : AssistantMutation
    data class SaveRecord(val record: TimeRecordEntity, val expectedTaskVersion: Long? = null, val expectedVersion: Long? = null) : AssistantMutation
    data class SaveEvent(val task: TaskEntity, val plan: ScheduleBlockEntity, val expectedConflicts: List<Conflict>) : AssistantMutation
    data class DeleteRecord(val id: String, val expectedVersion: Long) : AssistantMutation
    data class SaveCourse(val occurrence: CourseOccurrenceEntity, val expectedVersion: Long, val expectedConflicts: List<Conflict>? = null) : AssistantMutation
    data class StartTimer(val requestId: String, val taskId: String?, val expectedTaskVersion: Long? = null) : AssistantMutation
    data class StopTimer(val recordId: String, val expectedVersion: Long) : AssistantMutation
    data class CancelSampleCourses(val requestId: String, val courses: List<CourseEntity>, val occurrences: List<CourseOccurrenceEntity>) : AssistantMutation
}

sealed interface CommitResult {
    data class Saved(val message: String = "已保存，可在操作历史中撤销") : CommitResult
    data object AlreadySaved : CommitResult
    data class Rejected(val message: String, val requiresNewPreview: Boolean = false) : CommitResult
}
