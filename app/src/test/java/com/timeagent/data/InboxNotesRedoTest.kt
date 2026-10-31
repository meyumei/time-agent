package com.timeagent.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InboxNotesRedoTest {
    private lateinit var db: TimeAgentDatabase
    private lateinit var repo: TimeRepository
    private val now = Instant.parse("2026-10-21T02:00:00Z")
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TimeAgentDatabase::class.java).allowMainThreadQueries().build()
        repo = TimeRepository(db, Clock.fixed(now, ZoneId.of("Asia/Shanghai")))
    }
    @After fun cleanup() { db.close() }

    @Test fun `an idea enters the inbox without creating tasks reminders or time blocks`() = runBlocking {
        repo.saveIdea(IdeaEntity("idea", "周末想去骑车", "灵感来了，想沿江骑车，但还没定时间"))
        val snapshot = repo.snapshot()
        assertEquals("UNSCHEDULED", snapshot.ideas.single().status)
        assertEquals("周末想去骑车", snapshot.ideas.single().title)
        assertTrue(snapshot.tasks.isEmpty() && snapshot.plans.isEmpty() && snapshot.reminders.isEmpty())
    }
    @Test fun `a point reminder creates no hour long plan and supports stale edit protection`() = runBlocking {
        val first = repo.saveReminder(ReminderEntity("point", "健身", now.plusSeconds(3600).toEpochMilli()))
        val changed = repo.saveReminder(first.copy(remindAt = first.remindAt + 60000), first.version)
        assertTrue(repo.snapshot().plans.isEmpty())
        assertEquals(first.remindAt + 60000, db.dao().reminder("point")!!.remindAt)
        assertTrue(changed.version > first.version)
        try { repo.saveReminder(first.copy(title = "旧编辑"), first.version); fail("Stale reminder must not overwrite a later edit") } catch (_: StaleChange) { }
    }
    @Test fun `delivery validates the exact current point and is claimed once`() = runBlocking {
        val past = now.minusSeconds(1).toEpochMilli()
        db.dao().putReminder(ReminderEntity("point", "阅读", past))
        assertFalse(repo.markReminderDelivered("point", past - 60000))
        assertTrue(repo.markReminderDelivered("point", past))
        assertFalse(repo.markReminderDelivered("point", past))
        assertNotNull(db.dao().reminder("point")!!.deliveredAt)
        assertTrue(db.dao().history().isEmpty())
    }
    @Test fun `future and disabled reminders cannot be marked delivered`() = runBlocking {
        db.dao().putReminder(ReminderEntity("future", "阅读", now.plusSeconds(1).toEpochMilli()))
        db.dao().putReminder(ReminderEntity("off", "阅读", now.minusSeconds(1).toEpochMilli(), enabled = false))
        assertFalse(repo.markReminderDelivered("future", now.plusSeconds(1).toEpochMilli()))
        assertFalse(repo.markReminderDelivered("off", now.minusSeconds(1).toEpochMilli()))
    }
    @Test fun `module note is associated and versioned without changing its activity template`() = runBlocking {
        repo.initialize()
        val activity = db.dao().activity("fitness-chest")!!
        val note = repo.saveNote(NoteEntity("note", "ACTIVITY", activity.id, "练胸", "卧推 40kg 3组\n下次加重"))
        val updated = repo.saveNote(note.copy(body = note.body + "\n动作放慢"), note.version)
        assertEquals(activity, db.dao().activity(activity.id))
        assertTrue(repo.snapshot().notes.single().body.contains("动作放慢"))
        try { repo.saveNote(note.copy(body = "旧草稿"), note.version); fail() } catch (_: StaleChange) { }
        assertTrue(updated.version > note.version)
    }
    @Test fun `notes cannot reference a missing target`() = runBlocking {
        try { repo.saveNote(NoteEntity("note", "TASK", "missing", body = "内容")); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(db.dao().notes().isEmpty())
    }
    @Test fun `redo recreates an undone task with a fresh version`() = runBlocking {
        val first = repo.saveTask(TaskEntity("task", "阅读"))
        repo.undoLastAction()
        assertNull(db.dao().task("task"))
        repo.redoLastAction()
        val restored = db.dao().task("task")!!
        assertEquals(first.title, restored.title)
        assertTrue(restored.version > first.version)
        assertFalse(db.dao().history().single().undone)
    }
    @Test fun `multiple undo and redo steps use the correct order`() = runBlocking {
        val first = repo.saveTask(TaskEntity("task", "第一版"))
        val second = repo.saveTask(first.copy(title = "第二版"), first.version)
        repo.saveTask(second.copy(title = "第三版"), second.version)
        repo.undoLastAction(); repo.undoLastAction()
        assertEquals("第一版", db.dao().task("task")!!.title)
        repo.redoLastAction()
        assertEquals("第二版", db.dao().task("task")!!.title)
        repo.redoLastAction()
        assertEquals("第三版", db.dao().task("task")!!.title)
    }
    @Test fun `a new edit invalidates the previous redo branch`() = runBlocking {
        val first = repo.saveTask(TaskEntity("task", "第一版"))
        repo.saveTask(first.copy(title = "第二版"), first.version)
        repo.undoLastAction()
        val current = db.dao().task("task")!!
        repo.saveTask(current.copy(title = "新分支"), current.version)
        assertNull(db.dao().redoAction())
        try { repo.redoLastAction(); fail("An invalidated branch cannot be replayed") } catch (_: IllegalArgumentException) { }
        assertEquals("新分支", db.dao().task("task")!!.title)
    }
    @Test fun `redo refuses an external later edit instead of overwriting it`() = runBlocking {
        val first = repo.saveTask(TaskEntity("task", "第一版"))
        repo.saveTask(first.copy(title = "第二版"), first.version)
        repo.undoLastAction()
        val current = db.dao().task("task")!!
        db.dao().putTask(current.copy(title = "外部更改"))
        try { repo.redoLastAction(); fail() } catch (_: StaleChange) { }
        assertEquals("外部更改", db.dao().task("task")!!.title)
        assertNotNull(db.dao().redoAction())
    }
    @Test fun `assistant task and plan undo and redo as one atomic action`() = runBlocking {
        repo.assistantTransaction("request") {
            val task = repo.saveTask(TaskEntity("task", "阅读"))
            repo.savePlan(ScheduleBlockEntity("plan", task.id, now.toEpochMilli(), now.plusSeconds(1800).toEpochMilli()))
        }
        repo.undoLastAction()
        assertTrue(db.dao().tasks().isEmpty() && db.dao().plans().isEmpty())
        repo.redoLastAction()
        assertEquals("阅读", db.dao().task("task")!!.title)
        assertEquals("task", db.dao().plan("plan")!!.taskId)
        assertEquals(1, db.dao().history().size)
    }
    @Test fun `idea and point reminder support undo and redo without inventing spans`() = runBlocking {
        repo.saveIdea(IdeaEntity("idea", "去骑车"))
        repo.undoLastAction(); repo.redoLastAction()
        assertEquals("去骑车", repo.snapshot().ideas.single().title)
        repo.saveReminder(ReminderEntity("point", "去骑车", now.plusSeconds(600).toEpochMilli()))
        repo.undoLastAction(); repo.redoLastAction()
        assertEquals(1, repo.snapshot().reminders.size)
        assertTrue(repo.snapshot().plans.isEmpty())
    }
    @Test fun `note can be undone and redone while retaining its module association`() = runBlocking {
        val task = repo.saveTask(TaskEntity("task", "阅读"))
        repo.saveNote(NoteEntity("note", "TASK", task.id, "阅读笔记", "第一章"))
        repo.undoLastAction(); repo.redoLastAction()
        val restored = db.dao().note("note")!!
        assertEquals("第一章", restored.body)
        assertEquals(task.id, restored.targetId)
    }
}
