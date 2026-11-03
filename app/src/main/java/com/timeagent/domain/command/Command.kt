package com.timeagent.domain.command

import com.timeagent.data.Conflict
import com.timeagent.data.ScheduleBlockEntity
import com.timeagent.data.TimeRecordEntity
import com.timeagent.assistant.AssistantPreview
import com.timeagent.assistant.AssistantCommand
import java.time.LocalDate

sealed interface Origin {
    data object UI : Origin
    data object VOICE : Origin
    data object NOTIFICATION : Origin
    data object WIDGET : Origin
    data object CALENDAR_SYNC : Origin
    data object TEMPLATE : Origin
    data object SYSTEM : Origin

    data class FISH(
        val sessionId: String,
        val messageId: String,
        val toolCallId: String,
    ) : Origin
}

sealed interface Command {
    val requestId: String
    val origin: Origin
}

sealed interface MutationCommand : Command

data class CreateSchedule(
    override val requestId: String,
    val schedule: ScheduleBlockEntity,
    val expectedTaskVersion: Long? = null,
    val expectedConflicts: List<Conflict>? = null,
    override val origin: Origin = Origin.UI,
) : MutationCommand

data class UpdateSchedule(
    override val requestId: String,
    val schedule: ScheduleBlockEntity,
    val expectedVersion: Long,
    val expectedTaskVersion: Long? = null,
    val expectedConflicts: List<Conflict>? = null,
    override val origin: Origin = Origin.UI,
) : MutationCommand

data class DeleteSchedule(
    override val requestId: String,
    val id: String,
    val expectedVersion: Long,
    override val origin: Origin = Origin.UI,
) : MutationCommand

data class CreateActual(
    override val requestId: String,
    val record: TimeRecordEntity,
    val allowFuture: Boolean = false,
    val expectedTaskVersion: Long? = null,
    override val origin: Origin = Origin.UI,
) : MutationCommand

data class UpdateActual(
    override val requestId: String,
    val record: TimeRecordEntity,
    val expectedVersion: Long,
    val allowFuture: Boolean = false,
    val expectedTaskVersion: Long? = null,
    override val origin: Origin = Origin.UI,
) : MutationCommand

data class DeleteActual(
    override val requestId: String,
    val id: String,
    val expectedVersion: Long,
    override val origin: Origin = Origin.UI,
) : MutationCommand

data class StartTimer(
    override val requestId: String,
    val taskId: String?,
    val switchExisting: Boolean = false,
    val expectedTaskVersion: Long? = null,
    val recordId: String = requestId,
    override val origin: Origin = Origin.UI,
) : MutationCommand

data class StopTimer(
    override val requestId: String,
    val id: String,
    val expectedVersion: Long,
    override val origin: Origin = Origin.UI,
) : MutationCommand

data class BatchCommand(
    override val requestId: String,
    val commands: List<MutationCommand>,
    override val origin: Origin = Origin.UI,
) : Command

data class UndoCommand(
    override val requestId: String,
    val undoToken: String,
    override val origin: Origin = Origin.UI,
) : Command

data class RedoCommand(
    override val requestId: String,
    val undoToken: String? = null,
    override val origin: Origin = Origin.UI,
) : Command

data class CommitAssistant(
    override val requestId: String,
    val preview: AssistantPreview,
    val allowFuture: Boolean = false,
    val projectId: String? = null,
    override val origin: Origin = Origin.VOICE,
) : Command

data class RememberRule(
    override val requestId: String,
    val text: String,
    override val origin: Origin = Origin.VOICE,
) : Command

data class CompleteRecurring(
    override val requestId: String,
    val instanceId: String,
    val expectedVersion: Long,
    val expectedTaskVersion: Long,
    override val origin: Origin,
) : Command

data class SaveRecurringRule(
    override val requestId: String,
    val rule: com.timeagent.data.RecurringRuleEntity,
    val expectedVersion: Long?,
    val from: LocalDate,
    val scope: com.timeagent.recurring.RuleChangeScope,
    val preview: com.timeagent.recurring.RuleChangePreview? = null,
    override val origin: Origin = Origin.UI,
) : Command

data class ActRecurringInstance(
    override val requestId: String,
    val instanceId: String,
    val expectedVersion: Long,
    val action: String,
    val expectedTaskVersion: Long,
    val expectedPlans: Map<String, Long>,
    override val origin: Origin = Origin.UI,
) : Command

data class MoveRecurringInstance(
    override val requestId: String,
    val check: com.timeagent.recurring.RecurringMovePreview,
    override val origin: Origin = Origin.UI,
) : Command

data class DeliverReminder(
    override val requestId: String,
    val id: String,
    val remindAt: Long,
    override val origin: Origin = Origin.NOTIFICATION,
) : Command

data class ReadTimeData(
    override val requestId: String,
    val tool: String,
    val date: String? = null,
    override val origin: Origin,
) : Command

data class ReadAssistantQuery(
    override val requestId: String,
    val query: AssistantCommand.Query,
    val selectedWeekStart: LocalDate,
    override val origin: Origin = Origin.VOICE,
) : Command

data class CountActualRecords(
    override val requestId: String,
    val sleepOnly: Boolean,
    override val origin: Origin = Origin.VOICE,
) : Command
