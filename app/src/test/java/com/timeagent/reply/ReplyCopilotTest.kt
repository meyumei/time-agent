package com.timeagent.reply

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ReplyCopilotTest {
    private lateinit var db: TimeAgentDatabase
    private lateinit var repo: TimeRepository
    private val now = Instant.parse("2026-10-27T02:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val source = "明天下午3点到4点讨论课设"
    @Before fun setup() { db = TimeAgentDatabase.openInMemory(ApplicationProvider.getApplicationContext<Context>()); repo = TimeRepository(db, Clock.fixed(now, zone)) }
    @After fun cleanup() { db.close() }
    private suspend fun check(): ReplyAvailability {
        val first = com.timeagent.extraction.ChatTextExtractor.extract(source, now, zone).items.single()
        return ReplyCopilot.check(source, first.id, LocalDate.parse("2026-10-28"), 30, LocalTime.of(8,0), LocalTime.of(22,0), repo.snapshot(), now, zone)
    }
    @Test fun `drafting has no writes and save is explicit idempotent and undoable`() = runBlocking<Unit> {
        val draft = ReplyCopilot.draft(check(), ReplyKind.ACCEPT, zone = zone)
        assertTrue(repo.snapshot().tasks.isEmpty()); assertTrue(repo.snapshot().plans.isEmpty()); assertTrue(repo.snapshot().history.isEmpty())
        assertTrue(draft.text.contains("15:00"))
        assertTrue(runCatching { ReplyCopilot.save(repo, draft, false) }.isFailure)
        assertTrue(ReplyCopilot.save(repo, draft, true)); assertFalse(ReplyCopilot.save(repo, draft, true))
        assertEquals(1, repo.snapshot().tasks.size); assertEquals(1, repo.snapshot().plans.size); assertTrue(repo.snapshot().records.isEmpty())
        repo.undoLastAction(draft.requestId)
        assertTrue(repo.snapshot().plans.isEmpty()); assertFalse(ReplyCopilot.save(repo, draft, true))
    }
    @Test fun `changed schedule rejects saved promise while preserving draft text`() = runBlocking<Unit> {
        val draft = ReplyCopilot.draft(check(), ReplyKind.ACCEPT, zone = zone)
        repo.saveTask(TaskEntity("other", "已有安排"))
        repo.savePlan(ScheduleBlockEntity("other", "other", draft.slot!!.start.toEpochMilli(), draft.slot.end.toEpochMilli()))
        assertTrue(runCatching { ReplyCopilot.save(repo, draft, true) }.isFailure)
        assertEquals(1, repo.snapshot().tasks.size); assertEquals(1, repo.snapshot().plans.size)
        assertTrue(draft.text.contains("课设"))
    }
    @Test fun `uncertain text only offers clarification and running timer blocks alternatives`() = runBlocking<Unit> {
        val uncertain = ReplyCopilot.check("什么时候方便？", null, LocalDate.parse("2026-10-28"), 30, LocalTime.of(8,0), LocalTime.of(22,0), repo.snapshot(), now, zone)
        assertNull(uncertain.accepted); assertTrue(uncertain.alternatives.isEmpty())
        assertNull(ReplyCopilot.draft(uncertain, ReplyKind.CLARIFY, zone = zone).slot)
        db.dao().putRecord(TimeRecordEntity("running", null, now.toEpochMilli(), null))
        val blocked = check(); assertTrue(blocked.runningTimer); assertTrue(blocked.alternatives.isEmpty()); assertNull(blocked.accepted)
    }
}
