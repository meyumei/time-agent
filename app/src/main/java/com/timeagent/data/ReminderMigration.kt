package com.timeagent.data

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.timeagent.backup.PersonalBackup
import com.timeagent.reminders.reminderInstanceRequestCode

/**
 * v16 introduces durable reminder instances and lets a reminder point at a schedule, deadline,
 * recurring instance or habit in addition to the legacy task link.
 *
 * `taskId` is intentionally kept for one more version so older editors keep working. Existing rows
 * are migrated to `ownerType=TASK/ownerId=taskId`; standalone reminders become
 * `ownerType=REMINDER/ownerId=id`. A PRIMARY instance is backfilled for every legacy reminder so
 * the new coordinator owns all future alarms immediately and the legacy receiver cannot deliver the
 * same reminder twice. Past, disabled, delivered and already-finished parents are backfilled as
 * terminal states so an upgrade never replays old notifications.
 */
object ReminderMigration : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `reminders` ADD COLUMN `ownerType` TEXT NOT NULL DEFAULT 'REMINDER'")
        db.execSQL("ALTER TABLE `reminders` ADD COLUMN `ownerId` TEXT")
        db.execSQL("ALTER TABLE `reminders` ADD COLUMN `repeatIntervalMinutes` INTEGER")
        db.execSQL("ALTER TABLE `reminders` ADD COLUMN `repeatMaxCount` INTEGER")
        db.execSQL("UPDATE `reminders` SET `ownerType`='TASK', `ownerId`=`taskId` WHERE `taskId` IS NOT NULL")
        db.execSQL("UPDATE `reminders` SET `ownerType`='REMINDER', `ownerId`=`id` WHERE `taskId` IS NULL")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `reminder_instances` (" +
                "`id` TEXT NOT NULL, " +
                "`reminderId` TEXT NOT NULL, " +
                "`ownerType` TEXT NOT NULL, " +
                "`ownerId` TEXT, " +
                "`ownerVersion` INTEGER NOT NULL, " +
                "`reminderVersion` INTEGER NOT NULL, " +
                "`fireAt` INTEGER NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`repeatIndex` INTEGER NOT NULL, " +
                "`state` TEXT NOT NULL, " +
                "`requestCode` INTEGER NOT NULL, " +
                "`version` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminder_instances_reminderId` ON `reminder_instances` (`reminderId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminder_instances_fireAt` ON `reminder_instances` (`fireAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminder_instances_reminderId_state` ON `reminder_instances` (`reminderId`, `state`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminder_instances_ownerType_ownerId` ON `reminder_instances` (`ownerType`, `ownerId`)")

        val now = System.currentTimeMillis()
        db.execSQL(
            "INSERT OR IGNORE INTO `reminder_instances` (" +
                "`id`, `reminderId`, `ownerType`, `ownerId`, `ownerVersion`, `reminderVersion`, " +
                "`fireAt`, `kind`, `repeatIndex`, `state`, `requestCode`, `version`) " +
                "SELECT 'primary:'||r.`id`||':'||r.`version`, r.`id`, r.`ownerType`, r.`ownerId`, " +
                "COALESCE(t.`version`, r.`version`), r.`version`, r.`remindAt`, 'PRIMARY', 0, " +
                "CASE " +
                "WHEN r.`enabled` = 0 THEN 'CANCELLED' " +
                "WHEN r.`deliveredAt` IS NOT NULL THEN 'FIRED' " +
                "WHEN r.`taskId` IS NOT NULL AND (t.`deletedAt` IS NOT NULL OR t.`status` IN ('COMPLETED','CANCELLED')) THEN 'CANCELLED' " +
                "WHEN r.`remindAt` <= $now THEN 'CANCELLED' " +
                "ELSE 'SCHEDULED' END, " +
                "0, 1 " +
                "FROM `reminders` r LEFT JOIN `tasks` t ON t.`id` = CASE WHEN r.`ownerType` = 'TASK' THEN r.`ownerId` ELSE NULL END",
        )

        // requestCode is a stable instance-id hash. SQLite cannot reproduce the Kotlin hash, so
        // stamp the backfilled rows in Kotlin; the scheduler also derives the same value on demand.
        val codes = mutableListOf<Pair<Int, String>>()
        val cursor = db.query("SELECT `id` FROM `reminder_instances`")
        try {
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                codes += reminderInstanceRequestCode(id) to id
            }
        } finally {
            cursor.close()
        }
        codes.forEach { (code, id) ->
            db.execSQL("UPDATE `reminder_instances` SET `requestCode`=? WHERE `id`=?", arrayOf(code, id))
        }
    }

    fun withBackup(context: Context): Migration = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            PersonalBackup.createBeforeMigration(context, db, 15, 16)
            ReminderMigration.migrate(db)
        }
    }
}
