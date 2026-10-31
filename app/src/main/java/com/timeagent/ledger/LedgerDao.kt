package com.timeagent.ledger

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface LedgerDao {
    @Query("SELECT * FROM ledger_accounts ORDER BY sortOrder, name")
    suspend fun accounts(): List<LedgerAccountEntity>

    @Query("SELECT * FROM ledger_accounts WHERE id=:id")
    suspend fun account(id: String): LedgerAccountEntity?

    @Upsert
    suspend fun putAccount(item: LedgerAccountEntity)

    @Query("DELETE FROM ledger_accounts WHERE id=:id")
    suspend fun removeAccount(id: String)

    @Query("SELECT * FROM ledger_categories ORDER BY sortOrder, name")
    suspend fun categories(): List<LedgerCategoryEntity>

    @Query("SELECT * FROM ledger_categories WHERE id=:id")
    suspend fun category(id: String): LedgerCategoryEntity?

    @Upsert
    suspend fun putCategory(item: LedgerCategoryEntity)

    @Query("DELETE FROM ledger_categories WHERE id=:id")
    suspend fun removeCategory(id: String)

    @Query("SELECT * FROM payment_captures ORDER BY occurredAt DESC, createdAt DESC")
    suspend fun captures(): List<PaymentCaptureEntity>

    @Query("SELECT * FROM payment_captures WHERE sourcePackage=:sourcePackage AND (occurredAt BETWEEN :fromAt AND :toAt OR (:referenceId IS NOT NULL AND externalTransactionId=:referenceId))")
    suspend fun captureCandidates(sourcePackage: String, fromAt: Long, toAt: Long, referenceId: String?): List<PaymentCaptureEntity>

    @Query("SELECT * FROM payment_captures WHERE id=:id")
    suspend fun capture(id: String): PaymentCaptureEntity?

    @Upsert
    suspend fun putCapture(item: PaymentCaptureEntity)

    @Query("DELETE FROM payment_captures WHERE id=:id")
    suspend fun removeCapture(id: String)

    @Query("SELECT * FROM ledger_transactions WHERE deletedAt IS NULL ORDER BY occurredAt DESC, createdAt DESC")
    suspend fun transactions(): List<LedgerTransactionEntity>

    @Query("SELECT * FROM ledger_transactions WHERE id=:id")
    suspend fun transaction(id: String): LedgerTransactionEntity?

    @Query("SELECT COUNT(*) FROM ledger_transactions WHERE accountId=:id")
    suspend fun transactionCountForAccount(id: String): Int

    @Query("SELECT COUNT(*) FROM ledger_transactions WHERE categoryId=:id")
    suspend fun transactionCountForCategory(id: String): Int

    @Upsert
    suspend fun putTransaction(item: LedgerTransactionEntity)

    @Query("DELETE FROM ledger_transactions WHERE id=:id")
    suspend fun removeTransaction(id: String)

    /** Shared monotonic revisions table; same keys the core repository uses. */
    @Query("SELECT revision FROM entity_revisions WHERE `key`=:key")
    suspend fun revision(key: String): Long?

    @Query("INSERT OR REPLACE INTO entity_revisions(`key`, revision) VALUES(:key, :revision)")
    suspend fun putRevision(key: String, revision: Long)
}
