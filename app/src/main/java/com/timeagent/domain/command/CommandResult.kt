package com.timeagent.domain.command

import com.timeagent.data.TimeRecordEntity

enum class CommandStatus { APPLIED, DUPLICATE, CONFLICT, INVALID, NOT_FOUND, FAILED }

data class EntityRef(
    val type: String,
    val id: String,
    val newVersion: Long?,
)

data class ConflictInfo(
    val type: String,
    val id: String,
    val expectedVersion: Long?,
    val currentVersion: Long?,
    val currentValue: String?,
    val reason: String = "VERSION",
)

data class ScheduleWarning(
    val id: String,
    val title: String,
    val startAt: Long,
    val endAt: Long,
)

data class CommandResult(
    val requestId: String,
    val status: CommandStatus,
    val affected: List<EntityRef> = emptyList(),
    val undoToken: String? = null,
    val message: String,
    val conflict: ConflictInfo? = null,
    val warnings: List<ScheduleWarning> = emptyList(),
    val timerRecord: TimeRecordEntity? = null,
)

data class DataChanged(val affectedTypes: Set<String>)
