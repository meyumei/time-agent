package com.timeagent.calendar

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.timeagent.backup.PersonalBackup

/**
 * v20 adds N04 system-calendar mapping. No existing table is rewritten and no row is backfilled:
 * a user who never opens "日历同步" keeps an empty pair of tables, and the first full scan creates
 * links from what is actually in CalendarContract.
 */
object CalendarSyncMigration : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `calendar_links` (" +
                "`id` TEXT NOT NULL, " +
                "`localEntityType` TEXT NOT NULL, " +
                "`localEntityId` TEXT NOT NULL, " +
                "`calendarId` INTEGER NOT NULL, " +
                "`eventId` INTEGER NOT NULL, " +
                "`instanceOriginalStart` INTEGER, " +
                "`syncAccount` TEXT NOT NULL, " +
                "`lastLocalVersion` INTEGER NOT NULL, " +
                "`lastRemoteHash` TEXT NOT NULL, " +
                "`lastSyncedAt` INTEGER NOT NULL, " +
                "`origin` TEXT NOT NULL, " +
                "`state` TEXT NOT NULL, " +
                "`remoteDeleted` INTEGER NOT NULL, " +
                "`conflictKind` TEXT, " +
                "`conflictKept` TEXT, " +
                "`conflictLocalSnapshot` TEXT, " +
                "`conflictRemoteSnapshot` TEXT, " +
                "`conflictAt` INTEGER, " +
                "`readOnlyReason` TEXT, " +
                "`version` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_calendar_links_localEntityId` ON `calendar_links` (`localEntityId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_calendar_links_calendarId` ON `calendar_links` (`calendarId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_calendar_links_calendarId_eventId` ON `calendar_links` (`calendarId`, `eventId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_calendar_links_state` ON `calendar_links` (`state`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_calendar_links_origin` ON `calendar_links` (`origin`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `calendar_sync_state` (" +
                "`calendarId` INTEGER NOT NULL, " +
                "`calendarName` TEXT NOT NULL, " +
                "`accountName` TEXT NOT NULL, " +
                "`direction` TEXT NOT NULL, " +
                "`lastFullScanAt` INTEGER, " +
                "`lastSyncAt` INTEGER, " +
                "`lastError` TEXT, " +
                "`version` INTEGER NOT NULL, " +
                "PRIMARY KEY(`calendarId`))",
        )
    }

    fun withBackup(context: Context): Migration = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            PersonalBackup.createBeforeMigration(context, db, 19, 20)
            CalendarSyncMigration.migrate(db)
        }
    }
}
