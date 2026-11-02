package com.timeagent.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

abstract class TimelineIndexMigrationContract {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), TimeAgentDatabase::class.java,
    )

    @Test fun v13RowsAndCommandResultsSurviveIndexMigration() {
        val name = "t16-index-migration.db"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(name)
        helper.createDatabase(name, 13).apply {
            execSQL("INSERT INTO schedule_blocks(id,taskId,startAt,endAt,status,isLocked,createdAt,updatedAt,version,title,timezoneId) VALUES('point',NULL,1000,1000,'SCHEDULED',0,0,0,7,'Fixture','UTC')")
            execSQL("INSERT INTO time_records(id,taskId,startAt,endAt,categoryId,notes,source,createdAt,updatedAt,isManuallyEdited,version,title) VALUES('running',NULL,500,NULL,NULL,'Fixture','MANUAL',0,0,0,4,'Running')")
            execSQL("INSERT INTO command_log VALUES('retry','CreateSchedule','fixture-result',1000)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 14, true, TimelineIndexMigration)
        try {
            db.query("SELECT title,version FROM schedule_blocks WHERE id='point'").use {
                assertTrue(it.moveToFirst()); assertEquals("Fixture", it.getString(0)); assertEquals(7, it.getInt(1))
            }
            db.query("SELECT endAt,version FROM time_records WHERE id='running'").use {
                assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)); assertEquals(4, it.getInt(1))
            }
            db.query("SELECT resultJson FROM command_log WHERE requestId='retry'").use {
                assertTrue(it.moveToFirst()); assertEquals("fixture-result", it.getString(0))
            }
            db.query("PRAGMA index_info(index_schedule_blocks_deletedAt_startAt)").use {
                val columns = buildList { while (it.moveToNext()) add(it.getString(2)) }
                assertEquals(listOf("deletedAt", "startAt"), columns)
            }
            db.query("EXPLAIN QUERY PLAN SELECT * FROM schedule_blocks WHERE deletedAt IS NULL AND startAt<2000").use {
                val details = buildList { while (it.moveToNext()) add(it.getString(3)) }.joinToString()
                assertTrue(details, details.contains("index_schedule_blocks_deletedAt_startAt"))
            }
            db.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun v12SampleMigratesThroughBothVersionsWithoutLosingRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "t16-v12-chain.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        InstrumentationRegistry.getInstrumentation().context.assets.open("db/v12-sample.db").use { input ->
            path.outputStream().use { input.copyTo(it) }
        }
        val db = helper.runMigrationsAndValidate(name, 14, true, ScheduleMigration, TimelineIndexMigration)
        try {
            db.query("SELECT count(*) FROM schedule_blocks").use { it.moveToFirst(); assertEquals(13, it.getInt(0)) }
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
