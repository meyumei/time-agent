package com.timeagent.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.timeagent.suggestions.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SuggestionActionsTest {
    private lateinit var db: TimeAgentDatabase
    private lateinit var repo: TimeRepository
    private val now = Instant.parse("2026-10-26T02:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    @Before fun setup() {
        db = TimeAgentDatabase.openInMemory(ApplicationProvider.getApplicationContext<Context>())
        repo = TimeRepository(db, Clock.fixed(now, zone))
    }
    @After fun close() = db.close()
    private suspend fun suggestion(type: String = SuggestionEngine.TYPE_FREE_SLOT): AgentSuggestionEntity {
        val task = repo.saveTask(TaskEntity("task", "读书", estimatedMinutes = 45, dueAt = now.minusSeconds(7200).toEpochMilli()))
        val candidate = SuggestionEngine.generate(repo.snapshot(), zone, now).first { it.type == type }
        repo.recordSuggestions(listOf(candidate.toEntity("suggestion", now.toEpochMilli())))
        return db.dao().suggestion("suggestion")!!
    }
    @Test fun `schedule is exactly once and undo restores both card and plan`() = runBlocking<Unit> {
        val card = suggestion()
        val action = SuggestionAction.Schedule(card.proposedStartAt!!, card.proposedEndAt!!)
        assertTrue(repo.executeSuggestion(card.id, card.version, action))
        assertFalse(repo.executeSuggestion(card.id, card.version, action))
        assertEquals(1, db.dao().plans().size)
        assertEquals(45 * 60_000L, db.dao().plans().single().let { it.endAt - it.startAt })
        repo.undoLastAction()
        assertTrue(db.dao().plans().isEmpty())
        assertEquals("PENDING", db.dao().suggestion(card.id)!!.status)
        assertTrue(db.dao().suggestion(card.id)!!.version > card.version)
        // An old click cannot reapply after undo.
        assertThrows(StaleChange::class.java) { runBlocking { repo.executeSuggestion(card.id, card.version, action) } }
    }
    @Test fun `updated task invalidates saved action without changing any plan`() = runBlocking<Unit> {
        val card = suggestion()
        val task = db.dao().task("task")!!
        repo.saveTask(task.copy(title = "新的内容"), task.version)
        assertThrows(StaleChange::class.java) { runBlocking { repo.executeSuggestion(card.id, card.version, SuggestionAction.StartTimer) } }
        assertNull(db.dao().runningTimer())
    }
    @Test fun `regeneration expires obsolete object revisions instead of stacking broken cards`() = runBlocking<Unit> {
        val card = suggestion()
        val task = db.dao().task("task")!!
        repo.saveTask(task.copy(title = "修改后的读书"), task.version)
        val next = SuggestionEngine.generate(repo.snapshot(), zone, now).filter { it.type == SuggestionEngine.TYPE_FREE_SLOT }
        repo.recordSuggestions(next.map { it.toEntity("new-card", now.toEpochMilli()) })
        assertEquals("EXPIRED", db.dao().suggestion(card.id)!!.status)
        assertEquals(listOf("new-card"), db.dao().suggestions().map { it.id })
    }
    @Test fun `occupied or undersized replacement slot fails atomically`() = runBlocking<Unit> {
        val card = suggestion()
        repo.saveTask(TaskEntity("other", "课程准备"))
        repo.savePlan(ScheduleBlockEntity("busy", "other", card.proposedStartAt!!, card.proposedEndAt!!))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.executeSuggestion(card.id, card.version, SuggestionAction.Schedule(card.proposedStartAt, card.proposedEndAt)) } }
        assertEquals("PENDING", db.dao().suggestion(card.id)!!.status)
        assertEquals(1, db.dao().plans().size)
        val start = card.proposedEndAt + 60_000
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.executeSuggestion(card.id, card.version, SuggestionAction.Schedule(start, start + 10 * 60_000)) } }
    }
    @Test fun `postpone changes deadline and undo keeps restored card bound to latest version`() = runBlocking<Unit> {
        val card = suggestion(SuggestionEngine.TYPE_OVERDUE)
        val oldDue = db.dao().task("task")!!.dueAt
        repo.executeSuggestion(card.id, card.version, SuggestionAction.Postpone(now.plusSeconds(86400).toEpochMilli()))
        assertEquals(now.plusSeconds(86400).toEpochMilli(), db.dao().task("task")!!.dueAt)
        repo.undoLastAction()
        assertEquals(oldDue, db.dao().task("task")!!.dueAt)
        val restored = db.dao().suggestion(card.id)!!
        assertEquals(db.dao().task("task")!!.version, restored.taskVersion)
        assertTrue(repo.executeSuggestion(restored.id, restored.version, SuggestionAction.StartTimer))
        assertEquals("task", db.dao().runningTimer()!!.taskId)
    }
    @Test fun `snooze can be undone without losing suggestion`() = runBlocking<Unit> {
        val card = suggestion()
        repo.executeSuggestion(card.id, card.version, SuggestionAction.Snooze(now.plusSeconds(3600).toEpochMilli()))
        assertTrue(db.dao().suggestions().isEmpty())
        repo.undoLastAction()
        assertEquals("PENDING", db.dao().suggestion(card.id)!!.status)
    }
    @Test fun `legacy text only and expired suggestions cannot mutate tasks`() = runBlocking<Unit> {
        val card = suggestion()
        db.dao().putSuggestion(card.copy(expiresAt = now.minusSeconds(1).toEpochMilli()))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.executeSuggestion(card.id, card.version, SuggestionAction.StartTimer) } }
        db.dao().putSuggestion(card.copy(taskId = null, taskVersion = null, expiresAt = null))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.executeSuggestion(card.id, card.version, SuggestionAction.StartTimer) } }
        assertNull(db.dao().runningTimer())
    }
}
