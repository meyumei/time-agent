package com.timeagent.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Runs the same exported-schema contract under Robolectric and on Android. */
abstract class ScheduleMigrationContract {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), TimeAgentDatabase::class.java,
    )

    @Test fun realV12SampleRetainsEveryOldRowAndValidatesV13Schema() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "t13-migration-sample.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        InstrumentationRegistry.getInstrumentation().context.assets.open("db/v12-sample.db").use { input -> path.outputStream().use(input::copyTo) }
        val before = mutableMapOf<String, Pair<List<String>, List<List<String?>>>>()
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(12, db.version)
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata') ORDER BY name", null).use { tables ->
                while (tables.moveToNext()) {
                    val table = tables.getString(0)
                    db.rawQuery("SELECT * FROM `$table` ORDER BY rowid", null).use { c ->
                        before[table] = c.columnNames.toList() to buildList {
                            while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) })
                        }
                    }
                }
            }
        }
        val db = helper.runMigrationsAndValidate(name, 13, true, ScheduleMigration)
        try {
            before.forEach { (table, expected) ->
                db.query("SELECT ${expected.first.joinToString { "`$it`" }} FROM `$table` ORDER BY rowid").use { c ->
                    val rows = buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
                    assertEquals("Preserved $table", expected.second, rows)
                }
            }
            db.query("SELECT * FROM schedule_blocks").use { c ->
                assertEquals(13, c.count)
                while (c.moveToNext()) {
                    assertEquals("", c.getString(c.getColumnIndexOrThrow("title")))
                    assertEquals("TIMED", c.getString(c.getColumnIndexOrThrow("kind")))
                    assertEquals(ZoneId.systemDefault().id, c.getString(c.getColumnIndexOrThrow("timezoneId")))
                    assertEquals("", c.getString(c.getColumnIndexOrThrow("notes")))
                    assertEquals("MANUAL", c.getString(c.getColumnIndexOrThrow("source")))
                    for (field in listOf("allDayStartDate", "allDayEndDate", "colorOverride", "sourceRef", "deletedAt")) {
                        assertTrue(c.isNull(c.getColumnIndexOrThrow(field)))
                    }
                }
            }
            db.query("PRAGMA table_info(schedule_blocks)").use { c ->
                while (c.moveToNext()) if (c.getString(1) == "taskId") assertEquals(0, c.getInt(3))
            }
            db.query("PRAGMA foreign_key_list(schedule_blocks)").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("tasks", c.getString(c.getColumnIndexOrThrow("table")))
                assertEquals("RESTRICT", c.getString(c.getColumnIndexOrThrow("on_delete")))
                assertFalse(c.moveToNext())
            }
            db.query("PRAGMA index_list(schedule_blocks)").use { c ->
                val indices = buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
                assertTrue(indices.containsAll(listOf("index_schedule_blocks_taskId", "index_schedule_blocks_startAt")))
            }
            db.query("SELECT count(*) FROM command_log").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
            db.query("PRAGMA integrity_check").use { c -> assertTrue(c.moveToFirst()); assertEquals("ok", c.getString(0)) }
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            db.execSQL("INSERT INTO schedule_blocks(id,taskId,startAt,endAt,status,isLocked,createdAt,updatedAt,version,title,timezoneId) VALUES('standalone',NULL,1000,2000,'SCHEDULED',0,0,0,0,'Independent','UTC')")
            db.execSQL("INSERT INTO command_log VALUES('request-1','CreateSchedule','{}',1000)")
            try {
                db.execSQL("INSERT INTO command_log VALUES('request-1','CreateSchedule','{}',2000)")
                fail("requestId must be unique")
            } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
