package com.timeagent.focus

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.timeagent.backup.PersonalBackup

/**
 * v17 adds the focus session table. No existing row is rewritten. The table is new, so NOT NULL
 * columns need no defaults and the Room schema stays byte-for-byte consistent with the entity.
 *
 * The main database must register FocusSessionEntity, expose focusDao(), bump to version 17 and
 * add `FocusMigration.withBackup(context)` to its migration list.
 */
object FocusMigration : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `focus_sessions` (" +
                "`id` TEXT NOT NULL, " +
                "`requestId` TEXT NOT NULL, " +
                "`taskId` TEXT, " +
                "`scheduleBlockId` TEXT, " +
                "`label` TEXT NOT NULL, " +
                "`mode` TEXT NOT NULL, " +
                "`plannedFocusMinutes` INTEGER NOT NULL, " +
                "`plannedBreakMinutes` INTEGER NOT NULL, " +
                "`longBreakMinutes` INTEGER NOT NULL, " +
                "`roundsPlanned` INTEGER NOT NULL, " +
                "`roundIndex` INTEGER NOT NULL, " +
                "`state` TEXT NOT NULL, " +
                "`startedAt` INTEGER NOT NULL, " +
                "`pausedAccumulatedMs` INTEGER NOT NULL, " +
                "`lastResumedAt` INTEGER, " +
                "`endedAt` INTEGER, " +
                "`actualRecordId` TEXT, " +
                "`timezone` TEXT NOT NULL, " +
                "`version` INTEGER NOT NULL, " +
                "`focusedElapsedMs` INTEGER NOT NULL, " +
                "`roundElapsedMs` INTEGER NOT NULL, " +
                "`phaseStartedAt` INTEGER NOT NULL, " +
                "`phaseAnchorElapsedMs` INTEGER NOT NULL, " +
                "`pausedAtElapsedMs` INTEGER, " +
                "`pausedFromState` TEXT, " +
                "`longBreakEvery` INTEGER NOT NULL, " +
                "`bootCount` INTEGER NOT NULL, " +
                "`timeChanged` INTEGER NOT NULL, " +
                "`needsReview` INTEGER NOT NULL, " +
                "`finishReason` TEXT, " +
                "`segmentsJson` TEXT NOT NULL, " +
                "`lastWallAt` INTEGER NOT NULL, " +
                "`lastElapsedAt` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_focus_sessions_requestId` ON `focus_sessions` (`requestId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_focus_sessions_state` ON `focus_sessions` (`state`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_focus_sessions_startedAt` ON `focus_sessions` (`startedAt`)")
    }

    fun withBackup(context: Context): Migration = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            PersonalBackup.createBeforeMigration(context, db, 16, 17)
            FocusMigration.migrate(db)
        }
    }
}
