package com.timeagent.data

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.timeagent.backup.PersonalBackup

/**
 * v15 adds the many-to-many link between confirmed ledger rows and the payment captures they were
 * built from. Existing rows are not copied: the legacy `ledger_transactions.captureId` column stays
 * as the read fallback for entries recorded before merge/split existed.
 */
object LedgerSourceMigration : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `ledger_transaction_sources` (`id` TEXT NOT NULL, `transactionId` TEXT NOT NULL, `captureId` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`transactionId`) REFERENCES `ledger_transactions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`captureId`) REFERENCES `payment_captures`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_transaction_sources_transactionId` ON `ledger_transaction_sources` (`transactionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_transaction_sources_captureId` ON `ledger_transaction_sources` (`captureId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_ledger_transaction_sources_transactionId_captureId` ON `ledger_transaction_sources` (`transactionId`, `captureId`)")
    }

    fun withBackup(context: Context): Migration = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            PersonalBackup.createBeforeMigration(context, db, 14, 15)
            LedgerSourceMigration.migrate(db)
        }
    }
}
