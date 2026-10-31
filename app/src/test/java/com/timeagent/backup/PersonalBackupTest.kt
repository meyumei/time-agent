package com.timeagent.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.*
import com.timeagent.workout.data.*
import com.timeagent.core.workout.*
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PersonalBackupTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var main: TimeAgentDatabase
    private lateinit var workout: WorkoutDatabase
    private lateinit var service: PersonalBackup
    private val gson = Gson()
    private val stamp = Instant.parse("2026-10-26T02:00:00Z").toEpochMilli()
    private val marker get() = File(context.noBackupFilesDir, "test-personal-rollback.json")
    @Before fun setup() {
        context.deleteDatabase("backup-main-test.db"); context.deleteDatabase("backup-workouts-test.db")
        marker.delete()
        PersonalDataGate.recoveryRequired = false
        main = Room.databaseBuilder(context, TimeAgentDatabase::class.java, "backup-main-test.db").allowMainThreadQueries().build()
        workout = Room.databaseBuilder(context, WorkoutDatabase::class.java, "backup-workouts-test.db").allowMainThreadQueries().build()
        service = PersonalBackup(context, main, workout, marker)
        listOf("appearance", "workout", "health_sync", "assistant-settings", "timeline_colors_v1").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        File(context.filesDir, "workout-custom-exercises.json").writeText("[]")
    }
    @After fun cleanup() {
        main.close(); workout.close()
        context.deleteDatabase("backup-main-test.db"); context.deleteDatabase("backup-workouts-test.db")
        marker.delete()
        PersonalDataGate.recoveryRequired = false
    }
    private suspend fun seed() {
        val dao = main.dao()
        dao.putCategory(CategoryEntity("cat", "自定义"))
        dao.putActivity(ActivityTemplateEntity("parent", "运动", categoryId = "cat"))
        dao.putActivity(ActivityTemplateEntity("child", "练腿", parentId = "parent", categoryId = "cat"))
        dao.putTask(TaskEntity("t", "保留任务", categoryId = "cat", activityId = "child", version = 81, deletedAt = stamp))
        dao.putPlan(ScheduleBlockEntity("p", "t", stamp, stamp + 60000, status = "CANCELLED", isLocked = true))
        dao.putRecord(TimeRecordEntity("r", "t", stamp - 60000, stamp, categoryId = "cat", isManuallyEdited = true, version = 44))
        dao.putTerm(AcademicTermEntity("term", "真实学期", "2026-09-01", 18, "Asia/Shanghai"))
        dao.putCourse(CourseEntity("course", "课程", "教师", "地点", "term", "src", "c", stamp, stamp))
        dao.putOccurrence(CourseOccurrenceEntity("occ", "course", "2026-10-26", stamp, stamp + 3600000, "key", isManuallyModified = true))
        dao.putIdea(IdeaEntity("idea", "想法", deletedAt = stamp, version = 17))
        dao.putNote(NoteEntity("note", "TASK", "t", body = "关联笔记"))
        dao.putReminder(ReminderEntity("reminder", "已提醒", stamp, "t", enabled = false, deliveredAt = stamp))
        dao.putBatch(ImportBatchEntity("batch", "src", startedAt = stamp, completedAt = stamp, importedCount = 1, skippedCount = 0))
        dao.putHealthItem(HealthSyncItemEntity("gone", "SLEEP", "已删除同步", stamp - 60000, stamp, "synthetic", userDeleted = true))
        dao.putHealthItem(HealthSyncItemEntity("linked", "SLEEP", "睡眠", stamp - 60000, stamp, "synthetic", recordId = "r"))
        repeat(205) { dao.putHealthItem(HealthSyncItemEntity("archived-$it", "SLEEP", "合成保护记录", stamp - 60000, stamp, "synthetic", userDeleted = true)) }
        dao.putRevision(EntityRevision("TASK:t", 101))
        dao.putRevision(EntityRevision("REMINDER_DELIVERY:reminder:$stamp", stamp))
        listOf("PENDING", "DISMISSED", "EXPIRED", "APPLIED", "SNOOZED").forEach { status -> dao.putSuggestion(AgentSuggestionEntity(status, "FREE_SLOT", "建议", "说明", status, status = status, version = 3)) }
        repeat(55) { dao.putSuggestion(AgentSuggestionEntity("archived-card-$it", "SLEEP_SHORT", "合成历史建议", "说明", "archived-card-$it", status = "DISMISSED")) }
        repeat(95) { dao.putAction(OperationEntity(actionType = "synthetic", entityType = "TASK", entityId = "t", beforeSnapshot = "{}", afterSnapshot = "{}", createdAt = stamp, undone = it % 2 == 0, redoable = it == 94)) }
        val raw = main.openHelper.writableDatabase
        raw.execSQL("INSERT INTO tags(id,name,color) VALUES('tag','标签',123)")
        raw.execSQL("INSERT INTO task_tags(taskId,tagId) VALUES('t','tag')")
        raw.execSQL("INSERT INTO record_tags(recordId,tagId) VALUES('r','tag')")
        val paused = WorkoutSession(id = "session", title = "暂停训练", startedAt = stamp - 60000, pausedAt = stamp, status = WorkoutSessionStatus.PAUSED)
        workout.dao().putSession(WorkoutSessionRow.from(paused, gson))
        val template = WorkoutTemplate(id = "template", title = "自定义模板", movements = emptyList(), updatedAt = stamp)
        workout.dao().putTemplate(WorkoutTemplateRow(template.id, stamp, gson.toJson(template)))
        context.getSharedPreferences("appearance", 0).edit().putString("theme", "DARK").commit()
        context.getSharedPreferences("workout", 0).edit().putStringSet("pinned", setOf("custom:test")).putBoolean("sound", false).commit()
        context.getSharedPreferences("health_sync", 0).edit().putString("gbExportUri", "content://synthetic/device-only").putLong("lastSyncAt", stamp).putBoolean("sleep", true).commit()
        context.getSharedPreferences("assistant-settings", 0).edit().putString("apiKey", "synthetic-secret-never-export").putBoolean("cloudEnabled", true).putString("model", "deepseek-chat").commit()
        File(context.filesDir, "workout-custom-exercises.json").writeText(gson.toJson(listOf(ExerciseDefinition("custom:move", "自己的动作", listOf("legs")))))
    }
    private fun count(table: String): Long = main.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getLong(0) }
    @Test fun `full round trip keeps hidden rows relations protection and paused training`() = runBlocking {
        seed()
        val bytes = service.create()
        val preview = service.preview(bytes)
        assertEquals(95, preview.mainCounts["operation_history"])
        assertEquals(1, preview.workoutCounts["workout_sessions"])
        assertEquals(207, preview.mainCounts["health_sync_items"])
        assertEquals(60, preview.mainCounts["agent_suggestions"])
        assertEquals(1, preview.customExerciseCount)
        assertFalse(String(bytes, Charsets.UTF_8).contains("synthetic-secret-never-export"))
        assertFalse(String(bytes, Charsets.UTF_8).contains("content://synthetic/device-only"))
        main.dao().putTask(main.dao().task("t")!!.copy(title = "临时改动"))
        workout.dao().deleteTemplate("template")
        val result = service.restore(bytes)
        assertTrue(result.message.isNotBlank())
        assertEquals("保留任务", main.dao().task("t")!!.title)
        assertEquals(101L, main.dao().revision("TASK:t")!!.revision)
        assertTrue(main.dao().record("r")!!.isManuallyEdited)
        assertEquals("CANCELLED", main.dao().plans().single().status)
        assertTrue(main.dao().healthItem("gone")!!.userDeleted)
        assertEquals(95L, count("operation_history"))
        listOf("tags", "task_tags", "record_tags", "notes", "reminders").forEach { assertEquals(1L, count(it)) }
        assertNotNull(workout.dao().template("template"))
        assertEquals(WorkoutSessionStatus.PAUSED, gson.fromJson(workout.dao().session("session")!!.payload, WorkoutSession::class.java).status)
        assertEquals("", context.getSharedPreferences("health_sync", 0).getString("gbExportUri", ""))
        assertFalse(marker.exists())
    }
    @Test fun `corrupt or truncated bundle is rejected before any live write`() = runBlocking {
        seed()
        val bytes = service.create()
        val corrupt = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { runBlocking { service.restore(corrupt) } }
        assertThrows(Exception::class.java) { service.preview(bytes.copyOf(17)) }
        assertEquals("保留任务", main.dao().task("t")!!.title)
        assertEquals(95L, count("operation_history"))
        assertNotNull(workout.dao().template("template"))
    }
    @Test fun `failure after first database commit rolls back both databases and device preferences`() = runBlocking {
        seed()
        val bytes = service.create()
        main.dao().putTask(main.dao().task("t")!!.copy(title = "恢复前的新内容"))
        workout.dao().deleteTemplate("template")
        context.getSharedPreferences("appearance", 0).edit().putString("theme", "LIGHT").commit()
        service.afterFirstDatabaseRestored = { error("synthetic injected failure") }
        assertThrows(IllegalStateException::class.java) { runBlocking { service.restore(bytes) } }
        assertEquals("恢复前的新内容", main.dao().task("t")!!.title)
        assertNull(workout.dao().template("template"))
        assertEquals("LIGHT", context.getSharedPreferences("appearance", 0).getString("theme", ""))
        assertEquals("content://synthetic/device-only", context.getSharedPreferences("health_sync", 0).getString("gbExportUri", ""))
        assertFalse(marker.exists())
    }
    @Test fun `interrupted rollback is recovered from durable original pair before reopen`() = runBlocking {
        seed()
        val bytes = service.create()
        main.dao().putTask(main.dao().task("t")!!.copy(title = "中断前的新内容"))
        workout.dao().deleteTemplate("template")
        service.afterFirstDatabaseRestored = { error("synthetic process interruption") }
        service.beforeRollback = { error("synthetic runtime unavailable during rollback") }
        assertThrows(IllegalStateException::class.java) { runBlocking { service.restore(bytes) } }
        assertTrue(marker.exists())
        assertEquals("保留任务", main.dao().task("t")!!.title)
        main.close(); workout.close()
        PersonalBackup.recoverIfNeeded(context, marker)
        main = Room.databaseBuilder(context, TimeAgentDatabase::class.java, "backup-main-test.db").allowMainThreadQueries().build()
        workout = Room.databaseBuilder(context, WorkoutDatabase::class.java, "backup-workouts-test.db").allowMainThreadQueries().build()
        assertEquals("中断前的新内容", main.dao().task("t")!!.title)
        assertNull(workout.dao().template("template"))
        assertEquals("content://synthetic/device-only", context.getSharedPreferences("health_sync", 0).getString("gbExportUri", ""))
        assertFalse(marker.exists())
    }
    @Test fun `valid checksum with broken foreign key or unsupported schema is rejected`() = runBlocking {
        seed()
        val bytes = service.create()
        fun altered(mutate: (com.google.gson.JsonObject) -> Unit): ByteArray {
            val envelope = com.google.gson.JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
            val payload = com.google.gson.JsonParser.parseString(envelope["payload"].asString).asJsonObject
            mutate(payload)
            val raw = payload.toString()
            envelope.addProperty("payload", raw)
            envelope.addProperty("sha256", java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })
            return envelope.toString().toByteArray(Charsets.UTF_8)
        }
        val badFk = altered { payload ->
            val table = payload.getAsJsonObject("main").getAsJsonObject("tables").getAsJsonObject("schedule_blocks")
            val index = table.getAsJsonArray("columns").map { it.asString }.indexOf("taskId")
            table.getAsJsonArray("rows")[0].asJsonArray[index].asJsonObject.addProperty("value", "missing-task")
        }
        assertThrows(Exception::class.java) { runBlocking { service.restore(badFk) } }
        val badVersion = altered { it.getAsJsonObject("main").addProperty("version", 999) }
        assertThrows(IllegalArgumentException::class.java) { service.preview(badVersion) }
        assertEquals("保留任务", main.dao().task("t")!!.title)
        assertNotNull(workout.dao().template("template"))
        assertFalse(marker.exists())
    }
    @Test fun `notes awaiting undo survive export restore and reconnect when record is undone`() = runBlocking {
        val repo = TimeRepository(main, Clock.fixed(Instant.ofEpochMilli(stamp), ZoneId.of("Asia/Shanghai")))
        repo.saveRecord(TimeRecordEntity("deleted-record", null, stamp - 60000, stamp, title = "已删除的实际"))
        repo.saveNote(NoteEntity("orphan-note", "RECORD", "deleted-record", body = "等待撤销时恢复关联"))
        repo.deleteRecord("deleted-record")
        val bytes = service.create()
        assertEquals(1, service.preview(bytes).unlinkedNoteCount)
        service.restore(bytes)
        assertNull(main.dao().record("deleted-record"))
        assertEquals("等待撤销时恢复关联", main.dao().note("orphan-note")!!.body)
        repo.undoLastAction()
        assertNotNull(main.dao().record("deleted-record"))
        assertEquals("deleted-record", main.dao().note("orphan-note")!!.targetId)
    }
    @Test fun `memory cards and scheduling fields round trip with full backup`() = runBlocking {
        seed()
        val repo = TimeRepository(main, Clock.fixed(Instant.ofEpochMilli(stamp), ZoneId.of("Asia/Shanghai")))
        val card = repo.rememberExplicit("记住周三不练腿", "backup-memory")
        val bytes = service.create()
        repo.saveMemory(card.copy(enabled = false), card.version)
        service.restore(bytes)
        val restored = main.dao().memory(card.id)!!
        assertTrue(restored.enabled)
        assertEquals(3, restored.weekday)
        assertEquals("练腿", restored.keyword)
        assertEquals("AVOID_WEEKDAY", restored.ruleType)
    }
    @Test fun `v7 backup upgrades into current schema without inventing memory and keeps all old user data`() = runBlocking {
        seed()
        val bytes = service.create()
        val envelope = com.google.gson.JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
        val payload = com.google.gson.JsonParser.parseString(envelope["payload"].asString).asJsonObject
        payload.getAsJsonObject("main").apply { addProperty("version", 7); getAsJsonObject("tables").keySet().toList().filter{it !in BackupTables.mainForVersion(7)}.forEach { getAsJsonObject("tables").remove(it) } }
        val raw = payload.toString()
        envelope.addProperty("payload", raw)
        envelope.addProperty("sha256", java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })
        val legacy = envelope.toString().toByteArray(Charsets.UTF_8)
        val repo = TimeRepository(main)
        repo.saveMemory(PersonalMemoryEntity("new-memory", "FACT", "备份之后新建的记忆"))
        assertEquals(7, service.preview(legacy).mainVersion)
        service.restore(legacy)
        assertTrue(main.dao().memories().isEmpty())
        assertEquals("保留任务", main.dao().task("t")!!.title)
        assertEquals(207L, count("health_sync_items"))
        assertNotNull(workout.dao().template("template"))
    }
}
