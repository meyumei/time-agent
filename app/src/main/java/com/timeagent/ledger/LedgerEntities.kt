package com.timeagent.ledger

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object LedgerDirection {
    const val EXPENSE = "EXPENSE"
    const val INCOME = "INCOME"
    const val REFUND = "REFUND"
    val ALL = setOf(EXPENSE, INCOME, REFUND)
}

object LedgerAccountType {
    const val CASH = "CASH"
    const val BANK = "BANK"
    const val WECHAT = "WECHAT"
    const val ALIPAY = "ALIPAY"
    const val OTHER = "OTHER"
    val ALL = setOf(CASH, BANK, WECHAT, ALIPAY, OTHER)
}

fun directionLabel(value: String): String = when (value) {
    LedgerDirection.EXPENSE -> "支出"
    LedgerDirection.INCOME -> "收入"
    LedgerDirection.REFUND -> "退款"
    else -> value
}

fun accountTypeLabel(value: String): String = when (value) {
    LedgerAccountType.CASH -> "现金"
    LedgerAccountType.BANK -> "银行卡"
    LedgerAccountType.WECHAT -> "微信"
    LedgerAccountType.ALIPAY -> "支付宝"
    else -> "其他"
}

/** A wallet or card. Archived accounts stay referenced by old transactions but cannot be chosen anew. */
@Entity(tableName = "ledger_accounts", indices = [Index("archivedAt"), Index("sortOrder")])
data class LedgerAccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String = LedgerAccountType.CASH,
    val currency: String = "CNY",
    val openingBalanceMinor: Long = 0,
    val sortOrder: Int = 0,
    val archivedAt: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Long = 0,
)

/** A spending/income bucket. Refund categories are separate so refunds never hide inside expenses. */
@Entity(tableName = "ledger_categories", indices = [Index("direction"), Index("archivedAt"), Index("sortOrder")])
data class LedgerCategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val direction: String = LedgerDirection.EXPENSE,
    val icon: String = "",
    val sortOrder: Int = 0,
    val archivedAt: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Long = 0,
)

/**
 * A parsed payment notification or imported bill row. The source fields are immutable; only the
 * confirmation state and the reconciliation reference may be filled in later.
 * No raw notification text is stored, only its normalized hash.
 */
@Entity(tableName = "payment_captures", indices = [Index("status"), Index("occurredAt"), Index("externalTransactionId"), Index("rawTextHash")])
data class PaymentCaptureEntity(
    @PrimaryKey val id: String,
    val sourcePackage: String,
    val sourceLabel: String,
    val notificationKey: String = "",
    val externalTransactionId: String? = null,
    val occurredAt: Long,
    val amountMinor: Long,
    val direction: String,
    val merchant: String = "",
    val rawTextHash: String,
    val parsedJson: String = "",
    val parserVersion: Int = 1,
    val status: String = "PENDING",
    val transactionId: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Long = 0,
) {
    object Status {
        const val PENDING = "PENDING"
        const val CONFIRMED = "CONFIRMED"
        const val IGNORED = "IGNORED"
    }
}

/** Confirmed ledger row. Deletion is a soft flag so undo/redo and history keep working. */
@Entity(
    tableName = "ledger_transactions",
    foreignKeys = [
        ForeignKey(entity = LedgerAccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = LedgerCategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = PaymentCaptureEntity::class, parentColumns = ["id"], childColumns = ["captureId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("accountId"), Index("categoryId"), Index("captureId"), Index("occurredAt"), Index("deletedAt")],
)
data class LedgerTransactionEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val categoryId: String,
    val captureId: String? = null,
    val amountMinor: Long,
    val direction: String,
    val merchant: String = "",
    val note: String = "",
    val occurredAt: Long,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val confirmedAt: Long? = null,
    val version: Long = 0,
    val deletedAt: Long? = null,
)
