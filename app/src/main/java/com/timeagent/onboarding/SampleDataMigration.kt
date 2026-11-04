package com.timeagent.onboarding

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.timeagent.backup.PersonalBackup

/**
 * v21 adds the sample-data association table. Nothing existing is rewritten, so a failed migration
 * only leaves an empty table behind and the normal Room rollback path still applies.
 *
 * The table is intentionally independent from tasks/schedules/records/projects: deleting a marker
 * never touches user data, and deleting a sample row never needs a schema change on the main tables.
 */
object SampleDataMigration : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sample_records` (" +
                "`entityType` TEXT NOT NULL, " +
                "`entityId` TEXT NOT NULL, " +
                "`batchId` TEXT NOT NULL, " +
                "`initialVersion` INTEGER NOT NULL, " +
                "PRIMARY KEY(`entityType`, `entityId`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sample_records_batchId` ON `sample_records` (`batchId`)")
    }

    fun withBackup(context: Context): Migration = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            PersonalBackup.createBeforeMigration(context, db, 20, 21)
            SampleDataMigration.migrate(db)
        }
    }
}
