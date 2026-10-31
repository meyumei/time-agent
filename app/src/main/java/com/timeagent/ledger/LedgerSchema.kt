package com.timeagent.ledger

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 11 -> 12: payment capture drafts and the confirmed ledger.
 * The SQL below must stay byte-for-byte compatible with the Room entities; main registers it.
 */
object LedgerSchema {
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            createTables(db)
        }
    }

    fun createTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ledger_accounts` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `currency` TEXT NOT NULL, `openingBalanceMinor` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `archivedAt` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_accounts_archivedAt` ON `ledger_accounts` (`archivedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_accounts_sortOrder` ON `ledger_accounts` (`sortOrder`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ledger_categories` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `direction` TEXT NOT NULL, `icon` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, `archivedAt` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_categories_direction` ON `ledger_categories` (`direction`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_categories_archivedAt` ON `ledger_categories` (`archivedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_categories_sortOrder` ON `ledger_categories` (`sortOrder`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `payment_captures` (`id` TEXT NOT NULL, `sourcePackage` TEXT NOT NULL, `sourceLabel` TEXT NOT NULL, `notificationKey` TEXT NOT NULL, `externalTransactionId` TEXT, `occurredAt` INTEGER NOT NULL, `amountMinor` INTEGER NOT NULL, `direction` TEXT NOT NULL, `merchant` TEXT NOT NULL, `rawTextHash` TEXT NOT NULL, `parsedJson` TEXT NOT NULL, `parserVersion` INTEGER NOT NULL, `status` TEXT NOT NULL, `transactionId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_captures_status` ON `payment_captures` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_captures_occurredAt` ON `payment_captures` (`occurredAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_captures_externalTransactionId` ON `payment_captures` (`externalTransactionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_captures_rawTextHash` ON `payment_captures` (`rawTextHash`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ledger_transactions` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `captureId` TEXT, `amountMinor` INTEGER NOT NULL, `direction` TEXT NOT NULL, `merchant` TEXT NOT NULL, `note` TEXT NOT NULL, `occurredAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `confirmedAt` INTEGER, `version` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`accountId`) REFERENCES `ledger_accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`categoryId`) REFERENCES `ledger_categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`captureId`) REFERENCES `payment_captures`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_transactions_accountId` ON `ledger_transactions` (`accountId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_transactions_categoryId` ON `ledger_transactions` (`categoryId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_transactions_captureId` ON `ledger_transactions` (`captureId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_transactions_occurredAt` ON `ledger_transactions` (`occurredAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_transactions_deletedAt` ON `ledger_transactions` (`deletedAt`)")
    }
}
