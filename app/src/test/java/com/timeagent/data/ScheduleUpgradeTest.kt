package com.timeagent.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import com.timeagent.backup.PersonalBackup
import com.timeagent.workout.data.WorkoutDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScheduleUpgradeTest {
    @Test fun `snapshot failure aborts before changing the v12 schema`() = runBlocking(Dispatchers.IO) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.noBackupFilesDir, "migration-snapshots")
        directory.listFiles()?.forEach { it.delete() }
        directory.delete()
        directory.parentFile!!.mkdirs()
        directory.writeText("Directory is unavailable")
        context.deleteDatabase("time-agent.db")
        val path = context.getDatabasePath("time-agent.db").apply {
            parentFile!!.mkdirs()
            File("src/androidTest/assets/db/v12-sample.db").copyTo(this, overwrite = true)
        }
        val db = TimeAgentDatabase.open(context)
        try {
            val failure = runCatching { db.openHelper.writableDatabase }.exceptionOrNull()
            assertNotNull(failure)
            android.database.sqlite.SQLiteDatabase.openDatabase(path.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { old ->
                assertEquals(12, old.version)
                old.rawQuery("SELECT count(*) FROM schedule_blocks", null).use { it.moveToFirst(); assertEquals(13, it.getInt(0)) }
                old.rawQuery("SELECT name FROM sqlite_master WHERE name='command_log'", null).use { assertFalse(it.moveToFirst()) }
                old.rawQuery("SELECT name FROM sqlite_master WHERE name='schedule_blocks_new'", null).use { assertFalse(it.moveToFirst()) }
                old.rawQuery("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='schedule_blocks' AND name NOT LIKE 'sqlite_%' ORDER BY name", null).use { c ->
                    val indices = buildList { while (c.moveToNext()) add(c.getString(0)) }
                    assertEquals(listOf("index_schedule_blocks_startAt", "index_schedule_blocks_taskId"), indices)
                }
            }
        } finally {
            db.close()
            context.deleteDatabase("time-agent.db")
            directory.delete()
        }
    }

    @Test fun `migration snapshots retain only the newest two complete backups`() = runBlocking(Dispatchers.IO) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val snapshots = File(context.noBackupFilesDir, "migration-snapshots")
        snapshots.listFiles()?.forEach { it.delete() }
        try {
            repeat(3) {
                context.deleteDatabase("time-agent.db")
                context.getDatabasePath("time-agent.db").apply {
                    parentFile!!.mkdirs()
                    File("src/androidTest/assets/db/v12-sample.db").copyTo(this, overwrite = true)
                }
                val db = TimeAgentDatabase.open(context)
                try { assertEquals(13, db.openHelper.writableDatabase.version) } finally { db.close() }
            }
            val files = snapshots.listFiles()!!
            assertEquals(2, files.size)
            files.forEach {
                val envelope = JsonParser.parseString(it.readText()).asJsonObject
                val payload = JsonParser.parseString(envelope["payload"].asString).asJsonObject
                assertEquals(12, payload["main"].asJsonObject["version"].asInt)
            }
        } finally {
            context.deleteDatabase("time-agent.db")
            snapshots.listFiles()?.forEach { it.delete() }
        }
    }

    @Test fun `real v12 upgrade saves a pre migration backup and can restore it`() = runBlocking(Dispatchers.IO) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val snapshots = File(context.noBackupFilesDir, "migration-snapshots")
        snapshots.listFiles()?.forEach { it.delete() }
        for ((asset, name) in listOf("v12-sample.db" to "time-agent.db", "workouts-sample.db" to "workouts.db")) {
            context.deleteDatabase(name)
            context.getDatabasePath(name).apply {
                parentFile!!.mkdirs()
                File("src/androidTest/assets/db", asset).copyTo(this, overwrite = true)
            }
        }
        val attachmentBytes = "synthetic migration attachment".toByteArray()
        var attachmentKey = ""
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("time-agent.db").path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { old ->
            old.rawQuery("SELECT storageKey FROM attachments LIMIT 1", null).use { c -> assertTrue(c.moveToFirst()); attachmentKey = c.getString(0) }
            old.execSQL("UPDATE attachments SET sizeBytes=?, sha256=? WHERE storageKey=?", arrayOf(
                attachmentBytes.size, com.timeagent.attachments.AttachmentStore.hash(attachmentBytes), attachmentKey))
        }
        val attachmentFile = File(context.filesDir, "attachments/$attachmentKey").apply { parentFile!!.mkdirs(); writeBytes(attachmentBytes) }
        context.getSharedPreferences("appearance", 0).edit().putString("theme", "DARK").commit()
        context.getSharedPreferences("assistant-settings", 0).edit().putString("apiKey", "LOCAL_TEST_ONLY_KEY").commit()
        val db = TimeAgentDatabase.open(context)
        val workout = WorkoutDatabase.open(context)
        try {
            assertEquals(13, db.openHelper.writableDatabase.version)
            val backup = snapshots.listFiles()!!.single().readBytes()
            val envelope = JsonParser.parseString(backup.toString(Charsets.UTF_8)).asJsonObject
            val payload = JsonParser.parseString(envelope["payload"].asString).asJsonObject
            assertEquals(12, payload["main"].asJsonObject["version"].asInt)
            assertFalse(payload["main"].asJsonObject["tables"].asJsonObject.has("command_log"))
            val oldRows = payload["main"].asJsonObject["tables"].asJsonObject["schedule_blocks"].asJsonObject
            assertEquals(13, oldRows["rows"].asJsonArray.size())
            assertEquals(9, oldRows["columns"].asJsonArray.size())
            assertEquals(3, payload["workout"].asJsonObject["tables"].asJsonObject["workout_sessions"].asJsonObject["rows"].asJsonArray.size())
            assertEquals("DARK", payload["preferences"].asJsonObject["appearance"].asJsonObject["theme"].asJsonObject["value"].asString)
            assertFalse(payload["preferences"].asJsonObject["assistant-settings"].asJsonObject.has("apiKey"))
            assertFalse(payload.toString().contains("LOCAL_TEST_ONLY_KEY"))
            assertArrayEquals(attachmentBytes, android.util.Base64.decode(payload["attachmentFiles"].asJsonObject[attachmentKey].asString, android.util.Base64.NO_WRAP))
            val before = db.dao().plans()
            val task = db.dao().tasks().first()
            db.dao().putTask(task.copy(title = "modified after migration"))
            db.dao().removePlan(before.first().id)
            db.dao().putCommandResult(CommandLogEntity("after-migration", "test", "{}", 1000))
            attachmentFile.writeText("changed after migration")
            val service = PersonalBackup(context, db, workout)
            assertEquals(12, service.preview(backup).mainVersion)
            service.restore(backup)
            assertEquals(task, db.dao().task(task.id))
            assertEquals(before, db.dao().plans())
            assertEquals(48, db.dao().records().size)
            assertEquals(3, workout.dao().sessions().size)
            assertArrayEquals(attachmentBytes, attachmentFile.readBytes())
            assertNull(db.dao().commandResult("after-migration"))
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            workout.close()
            db.close()
            context.deleteDatabase("workouts.db")
            context.deleteDatabase("time-agent.db")
            snapshots.listFiles()?.forEach { it.delete() }
            attachmentFile.delete()
            context.getSharedPreferences("appearance", 0).edit().clear().commit()
            context.getSharedPreferences("assistant-settings", 0).edit().clear().commit()
        }
    }
}
