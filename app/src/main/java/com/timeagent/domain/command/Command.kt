package com.timeagent.domain.command

import com.timeagent.data.Conflict
import com.timeagent.data.ScheduleBlockEntity
import com.timeagent.data.TimeRecordEntity

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
