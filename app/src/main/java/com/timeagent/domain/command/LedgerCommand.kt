package com.timeagent.domain.command

import com.timeagent.ledger.CaptureConfirmation
import com.timeagent.ledger.LedgerAccountEntity
import com.timeagent.ledger.LedgerCategoryEntity
import com.timeagent.ledger.LedgerTransactionEntity
import com.timeagent.ledger.LedgerMergeInput
import com.timeagent.ledger.LedgerSplitPart
import com.timeagent.ledger.ParsedPayment

sealed interface LedgerMutation {
    data class SaveAccount(val item: LedgerAccountEntity, val expectedVersion: Long?) : LedgerMutation
    data class ArchiveAccount(val id: String, val expectedVersion: Long?) : LedgerMutation
    data class SaveCategory(val item: LedgerCategoryEntity, val expectedVersion: Long?) : LedgerMutation
    data class ArchiveCategory(val id: String, val expectedVersion: Long?) : LedgerMutation
    data class SaveTransaction(val item: LedgerTransactionEntity, val expectedVersion: Long?) : LedgerMutation
    data class DeleteTransaction(val id: String, val expectedVersion: Long?) : LedgerMutation
    data class Merge(val versions: Map<String, Long>, val details: LedgerMergeInput, val resultId: String) : LedgerMutation
    data class Split(val id: String, val expectedVersion: Long, val parts: List<LedgerSplitPart>) : LedgerMutation
    data class Capture(val payment: ParsedPayment, val notificationKey: String) : LedgerMutation
    data class Ignore(val id: String, val expectedVersion: Long?) : LedgerMutation
    data class Confirm(val id: String, val confirmation: CaptureConfirmation, val expectedVersion: Long?) : LedgerMutation
    data class Import(val bytes: ByteArray) : LedgerMutation
}

data class WriteLedger(
    override val requestId: String,
    val mutation: LedgerMutation,
    override val origin: Origin = Origin.UI,
) : Command
