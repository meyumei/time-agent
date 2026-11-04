package com.timeagent.quickentry

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.timeagent.backup.PersonalBackup

/**
 * v18 (N09) adds quick entries and plan templates. Quick entries reference the existing
 * `activity_templates` rows; there is deliberately no second category/tag system.
 *
 * Existing data is untouched. A migration snapshot is created before the schema change so a
 * failed upgrade can be restored with the existing backup recovery path.
 */
object QuickEntryMigration : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `quick_entries` (" +
                "`id` TEXT NOT NULL, " +
                "`activityId` TEXT NOT NULL, " +
                "`label` TEXT, " +
                "`icon` TEXT NOT NULL, " +
                "`color` INTEGER NOT NULL, " +
                "`projectId` TEXT, " +
                "`taskId` TEXT, " +
                "`tagIds` TEXT NOT NULL, " +
                "`action` TEXT NOT NULL, " +
                "`defaultDurationMinutes` INTEGER, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "`pinned` INTEGER NOT NULL, " +
                "`usageCount` INTEGER NOT NULL, " +
                "`lastUsedAt` INTEGER, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "`version` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`activityId`) REFERENCES `activity_templates`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE RESTRICT)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_quick_entries_activityId` ON `quick_entries` (`activityId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_quick_entries_projectId` ON `quick_entries` (`projectId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_quick_entries_taskId` ON `quick_entries` (`taskId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_quick_entries_pinned_sortOrder` ON `quick_entries` (`pinned`, `sortOrder`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `plan_templates` (" +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`description` TEXT NOT NULL, " +
                "`version` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `plan_template_items` (" +
                "`id` TEXT NOT NULL, " +
                "`templateId` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`dayOffset` INTEGER NOT NULL, " +
                "`startTime` TEXT, " +
                "`durationMinutes` INTEGER, " +
                "`scheduleKind` TEXT NOT NULL, " +
                "`projectId` TEXT, " +
                "`tagIds` TEXT NOT NULL, " +
                "`reminderOffsets` TEXT NOT NULL, " +
                "`notes` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`templateId`) REFERENCES `plan_templates`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_plan_template_items_templateId` ON `plan_template_items` (`templateId`)")
    }

    fun withBackup(context: Context): Migration = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            PersonalBackup.createBeforeMigration(context, db, 17, 18)
            QuickEntryMigration.migrate(db)
        }
    }
}
