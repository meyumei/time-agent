package com.timeagent.habits

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.timeagent.backup.PersonalBackup

/**
 * Version 18 -> 19: habits and manual check-ins.
 *
 * Only two tables are added. Automatic habit results are never stored: they are derived from
 * `time_records` / `record_tags` / `health_sync_items` / `focus_sessions` / `workouts.db` on read,
 * so there is no cache to rebuild or invalidate.
 *
 * The SQL below must stay byte-for-byte compatible with [HabitEntity] and [HabitCheckinEntity];
 * the main database registers [withBackup] so an in-place upgrade is snapshotted first.
 */
object HabitMigration : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        createTables(db)
    }

    fun withBackup(context: Context): Migration = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            PersonalBackup.createBeforeMigration(context, db, 18, 19)
            createTables(db)
        }
    }

    fun createTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `habits` (" +
                "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `icon` TEXT NOT NULL, `color` INTEGER NOT NULL, " +
                "`metric` TEXT NOT NULL, `source` TEXT NOT NULL, `sourceRef` TEXT, `target` INTEGER NOT NULL, " +
                "`period` TEXT NOT NULL, `activeWeekdays` TEXT NOT NULL, `startDate` TEXT NOT NULL, " +
                "`archivedAt` INTEGER, `reminderEnabled` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `version` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_habits_archivedAt` ON `habits` (`archivedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_habits_sortOrder` ON `habits` (`sortOrder`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `habit_checkins` (" +
                "`habitId` TEXT NOT NULL, `date` TEXT NOT NULL, `value` INTEGER NOT NULL, `note` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `version` INTEGER NOT NULL, " +
                "PRIMARY KEY(`habitId`, `date`), " +
                "FOREIGN KEY(`habitId`) REFERENCES `habits`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_habit_checkins_habitId` ON `habit_checkins` (`habitId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_habit_checkins_date` ON `habit_checkins` (`date`)")
    }
}
