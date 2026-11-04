package com.timeagent.ledger

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Links one confirmed ledger transaction to one source payment capture.
 * The legacy [LedgerTransactionEntity.captureId] remains as the read fallback for old rows.
 */
@Entity(
    tableName = "ledger_transaction_sources",
    foreignKeys = [
        ForeignKey(
            entity = LedgerTransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PaymentCaptureEntity::class,
            parentColumns = ["id"],
            childColumns = ["captureId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("transactionId"),
        Index("captureId"),
        Index(value = ["transactionId", "captureId"], unique = true),
    ],
)
data class LedgerTransactionSourceEntity(
    @PrimaryKey val id: String,
    val transactionId: String,
    val captureId: String,
)
