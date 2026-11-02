package com.timeagent.data

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.timeagent.backup.PersonalBackup

object TimelineIndexMigration : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX index_schedule_blocks_deletedAt_startAt ON schedule_blocks(deletedAt, startAt)")
    }

    fun withBackup(context: Context): Migration = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            PersonalBackup.createBeforeMigration(context, db, 13, 14)
            TimelineIndexMigration.migrate(db)
        }
    }
}
