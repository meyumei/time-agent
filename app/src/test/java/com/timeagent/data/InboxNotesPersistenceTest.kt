package com.timeagent.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Phase A2 persistence contracts: the real exported v2 schema must migrate to v3, undone inbox
 * writes must survive a process restart, and the reminder delivery ledger must not be reset by
 * close/open or undo/redo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InboxNotesPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-21T02:00:00Z")
    private val clock = Clock.fixed(now, zone)

    @Before fun clean() {
        listOf("time-agent.db", "a2-redo.db", "a2-delivery.db", "a2-task.db", "a2-batch.db", "a2-api.db").forEach { context.deleteDatabase(it) }
    }

    @After fun cleanup() {
        listOf("time-agent.db", "a2-redo.db", "a2-delivery.db", "a2-task.db", "a2-batch.db", "a2-api.db").forEach { context.deleteDatabase(it) }
    }

    private fun open(name: String): TimeAgentDatabase =
        Room.databaseBuilder(context, TimeAgentDatabase::class.java, name).allowMainThreadQueries().build()

    @Test fun `exported v2 schema upgrades without losing tasks activities blocks or history`() = runBlocking {
        val schema = JsonParser.parseString(File("schemas/com.timeagent.data.TimeAgentDatabase/2.json").readText())
            .asJsonObject["database"].asJsonObject
        context.openOrCreateDatabase("time-agent.db", Context.MODE_PRIVATE, null).use { old ->
            schema["entities"].asJsonArray.forEach { entry ->
                val table = entry.asJsonObject
                val name = table["tableName"].asString
                old.execSQL(table["createSql"].asString.replace("\${TABLE_NAME}", name))
                table.getAsJsonArray("indices")?.forEach { index ->
                    old.execSQL(index.asJsonObject["createSql"].asString.replace("\${TABLE_NAME}", name))
                }
            }
            schema["setupQueries"].asJsonArray.forEach { old.execSQL(it.asString) }
            old.execSQL("INSERT INTO activity_templates (id,name,parentId,defaultMinutes,categoryId,sortOrder,archivedAt,createdAt,updatedAt,version) VALUES ('act','健身',NULL,60,NULL,0,NULL,1,1,0)")
            old.execSQL("INSERT INTO tasks (id,title,description,estimatedMinutes,dueAt,priority,status,categoryId,createdAt,updatedAt,completedAt,source,deletedAt,version,activityId) VALUES ('t1','旧任务','',30,NULL,1,'TODO',NULL,1,1,NULL,'MANUAL',NULL,0,'act')")
            old.execSQL("INSERT INTO schedule_blocks (id,taskId,startAt,endAt,status,isLocked,createdAt,updatedAt,version) VALUES ('p1','t1',1000,4600000,'SCHEDULED',0,1,1,0)")
            old.execSQL("INSERT INTO operation_history (id,actionType,entityType,entityId,beforeSnapshot,afterSnapshot,createdAt,undone) VALUES (1,'保存任务','TASK','t1',NULL,NULL,1,0)")
            old.version = 2
        }

        val upgraded = TimeAgentDatabase.open(context)
        try {
            assertEquals(13, upgraded.openHelper.readableDatabase.version)
            assertEquals("act", upgraded.dao().task("t1")!!.activityId)
            assertEquals("健身", upgraded.dao().activity("act")!!.name)
            assertEquals("p1", upgraded.dao().plans().single().id)
            assertEquals(1, upgraded.dao().history().size)
            assertFalse(upgraded.dao().history().single().redoable)
            assertTrue(upgraded.dao().reminders().isEmpty())
            assertTrue(upgraded.dao().ideas().isEmpty())
            assertTrue(upgraded.dao().notes().isEmpty())

            upgraded.dao().putIdea(IdeaEntity("idea", "旧库新增的想法"))
            upgraded.dao().putReminder(ReminderEntity("rem", "旧库新增的提醒", now.plusSeconds(600).toEpochMilli()))
            upgraded.dao().putNote(NoteEntity("note", "TASK", "t1", "旧库新增的笔记", "正文"))
            assertEquals("旧库新增的想法", upgraded.dao().idea("idea")!!.title)
            assertEquals("旧库新增的提醒", upgraded.dao().reminder("rem")!!.title)
            assertEquals("t1", upgraded.dao().note("note")!!.targetId)
        } finally {
            upgraded.close()
        }
    }

    @Test fun `undo survives closing the database and redo restores every inbox record`() = runBlocking {
        var database = open("a2-redo.db")
        var repo = TimeRepository(database, clock)
        val idea = repo.saveIdea(IdeaEntity("idea", "去骑车"))
        val reminder = repo.saveReminder(ReminderEntity("rem", "提醒骑车", now.plusSeconds(600).toEpochMilli()))
        val note = repo.saveNote(NoteEntity("note", "IDEA", "idea", "灵感", "沿江骑行"))
        repo.undoLastAction()
        repo.undoLastAction()
        repo.undoLastAction()
        assertTrue(database.dao().ideas().isEmpty())
        assertTrue(database.dao().reminders().isEmpty())
        assertTrue(database.dao().notes().isEmpty())
        assertNotNull(database.dao().redoAction())
        database.close()

        database = open("a2-redo.db")
        repo = TimeRepository(database, clock)
        repo.redoLastAction()
        assertEquals("去骑车", database.dao().idea("idea")!!.title)
        repo.redoLastAction()
        assertEquals("提醒骑车", database.dao().reminder("rem")!!.title)
        repo.redoLastAction()
        assertEquals("IDEA", database.dao().note("note")!!.targetType)
        assertTrue(database.dao().idea("idea")!!.version > idea.version)
        assertTrue(database.dao().reminder("rem")!!.version > reminder.version)
        assertTrue(database.dao().note("note")!!.version > note.version)
        database.close()
    }

    @Test fun `delivery ledger survives reopen and undo redo`() = runBlocking {
        val t0 = now
        var database = open("a2-delivery.db")
        var repo = TimeRepository(database, Clock.fixed(t0, zone))
        val saved = repo.saveReminder(ReminderEntity("rem", "复诊", t0.plusSeconds(60).toEpochMilli()))
        database.close()

        database = open("a2-delivery.db")
        repo = TimeRepository(database, Clock.fixed(t0.plusSeconds(120), zone))
        assertTrue(repo.markReminderDelivered("rem", saved.remindAt))
        assertFalse(repo.markReminderDelivered("rem", saved.remindAt))
        assertNotNull(database.dao().revision("REMINDER_DELIVERY:rem:${saved.remindAt}"))

        repo.undoLastAction()
        assertNull(database.dao().reminder("rem"))
        repo.redoLastAction()
        assertNotNull("Redo must not resurrect a delivered reminder as undelivered", database.dao().reminder("rem")!!.deliveredAt)
        database.close()

        database = open("a2-delivery.db")
        repo = TimeRepository(database, Clock.fixed(t0.plusSeconds(180), zone))
        val restored = database.dao().reminder("rem")!!
        val edited = repo.saveReminder(restored.copy(title = "复诊改期"), restored.version)
        assertEquals("复诊改期", edited.title)
        assertNotNull("Editing the title must not reset the delivery ledger", edited.deliveredAt)
        database.close()
    }

    @Test fun `completed or cancelled parent task blocks reminder delivery`() = runBlocking {
        val database = open("a2-task.db")
        val repo = TimeRepository(database, clock)
        val past = now.minusSeconds(1).toEpochMilli()
        database.dao().putTask(TaskEntity("done", "交报告", status = "COMPLETED"))
        database.dao().putReminder(ReminderEntity("r1", "交报告", past, taskId = "done"))
        database.dao().putTask(TaskEntity("cancelled", "旧任务", status = "CANCELLED"))
        database.dao().putReminder(ReminderEntity("r2", "旧任务", past, taskId = "cancelled"))

        assertFalse(repo.markReminderDelivered("r1", past))
        assertFalse(repo.markReminderDelivered("r2", past))
        assertNull(database.dao().reminder("r1")!!.deliveredAt)
        assertNull(database.dao().reminder("r2")!!.deliveredAt)
        assertNull(database.dao().revision("REMINDER_DELIVERY:r1:$past"))
        database.close()
    }

    @Test fun `scheduling an idea as a reminder is one batched undo and redo`() = runBlocking {
        val database = open("a2-batch.db")
        val repo = TimeRepository(database, clock)
        val idea = repo.saveIdea(IdeaEntity("idea", "周末想去骑车"))
        repo.assistantTransaction("schedule-idea-test") {
            repo.saveReminder(ReminderEntity("rem", "周末想去骑车", now.plusSeconds(600).toEpochMilli()))
            repo.saveIdea(idea.copy(status = "SCHEDULED"), idea.version)
        }

        repo.undoLastAction()

        assertEquals("UNSCHEDULED", database.dao().idea("idea")!!.status)
        assertTrue("一次撤销必须同时撤销由该想法生成的提醒", database.dao().reminders().isEmpty())

        repo.redoLastAction()

        assertEquals("SCHEDULED", database.dao().idea("idea")!!.status)
        assertEquals("rem", database.dao().reminders().single().id)
        database.close()
    }

    @Test fun `the schedule idea convenience API stays one undo and one redo`() = runBlocking {
        val database = open("a2-api.db")
        val repo = TimeRepository(database, clock)
        val idea = repo.saveIdea(IdeaEntity("idea", "周末想去骑车"))
        val (scheduled, reminder) = repo.scheduleIdeaAsReminder(
            idea.id,
            idea.version,
            ReminderEntity("rem", idea.title, now.plusSeconds(600).toEpochMilli()),
        )
        assertEquals("SCHEDULED", scheduled.status)
        assertEquals(idea.title, reminder.title)
        assertNull(reminder.taskId)
        assertTrue(database.dao().plans().isEmpty())

        repo.undoLastAction()
        assertEquals("UNSCHEDULED", database.dao().idea("idea")!!.status)
        assertTrue(database.dao().reminders().isEmpty())

        repo.redoLastAction()
        assertEquals("SCHEDULED", database.dao().idea("idea")!!.status)
        assertEquals("rem", database.dao().reminders().single().id)
        database.close()
    }
}
