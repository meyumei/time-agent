package com.timeagent.domain.command

import com.timeagent.data.PersonalMemoryEntity
import com.timeagent.data.ScheduleBlockEntity
import com.timeagent.data.TaskEntity
import com.timeagent.ledger.LedgerTransactionEntity

sealed interface InboxTarget {
    data class Task(val item: TaskEntity) : InboxTarget
    data class Schedule(val item: ScheduleBlockEntity) : InboxTarget
    data class Ledger(val item: LedgerTransactionEntity) : InboxTarget
    data class Memory(val item: PersonalMemoryEntity) : InboxTarget
}

data class ConvertInbox(
    override val requestId: String,
    val ideaId: String,
    val expectedVersion: Long,
    val target: InboxTarget,
    override val origin: Origin = Origin.UI,
) : Command
