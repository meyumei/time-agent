package com.timeagent.data

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.timeagent.backup.PersonalBackup
import java.time.ZoneId

object ScheduleMigration : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val rows = db.query("SELECT count(*) FROM schedule_blocks").use { it.moveToFirst(); it.getLong(0) }
        db.execSQL("""
            CREATE TABLE `schedule_blocks_new` (
                `id` TEXT NOT NULL, `taskId` TEXT, `startAt` INTEGER NOT NULL, `endAt` INTEGER NOT NULL,
                `status` TEXT NOT NULL, `isLocked` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL, `version` INTEGER NOT NULL,
                `title` TEXT NOT NULL DEFAULT '', `kind` TEXT NOT NULL DEFAULT 'TIMED',
                `allDayStartDate` TEXT, `allDayEndDate` TEXT, `timezoneId` TEXT NOT NULL,
                `notes` TEXT NOT NULL DEFAULT '', `colorOverride` TEXT,
                `source` TEXT NOT NULL DEFAULT 'MANUAL', `sourceRef` TEXT, `deletedAt` INTEGER,
                PRIMARY KEY(`id`), FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)
        """.trimIndent())
        db.execSQL("""
            INSERT INTO schedule_blocks_new
                (id, taskId, startAt, endAt, status, isLocked, createdAt, updatedAt, version, timezoneId)
            SELECT id, taskId, startAt, endAt, status, isLocked, createdAt, updatedAt, version, ?
            FROM schedule_blocks
        """.trimIndent(), arrayOf(ZoneId.systemDefault().id))
        check(db.query("SELECT count(*) FROM schedule_blocks_new").use { it.moveToFirst(); it.getLong(0) } == rows)
        db.execSQL("DROP TABLE schedule_blocks")
        db.execSQL("ALTER TABLE schedule_blocks_new RENAME TO schedule_blocks")
        db.execSQL("CREATE INDEX index_schedule_blocks_taskId ON schedule_blocks(taskId)")
        db.execSQL("CREATE INDEX index_schedule_blocks_startAt ON schedule_blocks(startAt)")
        db.execSQL("CREATE TABLE command_log (requestId TEXT NOT NULL PRIMARY KEY, commandType TEXT NOT NULL, resultJson TEXT NOT NULL, createdAt INTEGER NOT NULL)")
    }

    fun withBackup(context: Context): Migration = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            PersonalBackup.createBeforeMigration(context, db)
            ScheduleMigration.migrate(db)
        }
    }
}
