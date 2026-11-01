package com.timeagent.assistant

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AssistantRepositoryIntegrationTest {
    private lateinit var db: TimeAgentDatabase
    private lateinit var repo: TimeRepository
    private val now = Instant.parse("2026-10-20T08:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TimeAgentDatabase::class.java).allowMainThreadQueries().build(); repo = TimeRepository(db, Clock.fixed(now, zone)) }
    @After fun cleanup() { db.close() }
    private suspend fun context() = AssistantContext(now, zone, LocalDate.of(2026, 10, 19), LocalSnapshot(tasks = db.dao().tasks(), plans = db.dao().activePlans(), records = db.dao().records(), loading = false))
    @Test fun `creating a titled schedule does not implicitly create a task`() = runBlocking {
        val preview = (AssistantPlanner().prepare(AssistantCommand.CreateEvent("独立阅读", now.toEpochMilli(), now.plusSeconds(3600).toEpochMilli()), context()) as AssistantReply.Preview).value
        RepositoryAssistantWriter(repo).commit(preview, false)
        assertTrue(db.dao().tasks().isEmpty())
        val plan = db.dao().activePlans().single()
        assertNull(plan.taskId)
        assertEquals("独立阅读", plan.title)
        repo.undoLastAction()
        assertTrue(db.dao().activePlans().isEmpty())
    }
    @Test fun `cancelled preview leaves Room untouched and repeat confirmation journals once`() = runBlocking {
        val gate = AssistantConfirmation(RepositoryAssistantWriter(repo))
        val preview = (AssistantPlanner().prepare(AssistantCommand.CreateTask("阅读", 30), context()) as AssistantReply.Preview).value
        gate.stage(preview); assertTrue(db.dao().tasks().isEmpty()); gate.cancel()
        assertTrue(gate.confirm(preview.id) is CommitResult.Rejected); assertTrue(db.dao().tasks().isEmpty()); assertTrue(db.dao().history().isEmpty())
        gate.stage(preview)
        assertTrue(gate.confirm(preview.id) is CommitResult.Saved)
        assertTrue(gate.confirm(preview.id) is CommitResult.AlreadySaved)
        assertEquals(1, db.dao().tasks().size); assertEquals(1, db.dao().history().size)
    }
    @Test fun `stale task cancellation cannot overwrite later Room edit`() = runBlocking {
        val saved = repo.saveTask(TaskEntity("t", "阅读"))
        val preview = (AssistantPlanner().prepare(AssistantCommand.CancelTask(taskId = "t"), context()) as AssistantReply.Preview).value
        repo.saveTask(saved.copy(title = "新名称"), expectedVersion = saved.version)
        val gate = AssistantConfirmation(RepositoryAssistantWriter(repo)); gate.stage(preview)
        assertTrue(gate.confirm(preview.id) is CommitResult.Rejected)
        assertEquals("新名称", db.dao().task("t")!!.title); assertEquals("TODO", db.dao().task("t")!!.status)
        assertEquals(2, db.dao().history().size)
    }
    @Test fun `plan conflicts appear in preview and cancellation uses versioned write`() = runBlocking {
        repo.saveTask(TaskEntity("t", "阅读"))
        repo.savePlan(ScheduleBlockEntity("p", "t", now.toEpochMilli(), now.plusSeconds(3600).toEpochMilli()))
        val preview = (AssistantPlanner().prepare(AssistantCommand.CreatePlan(taskId = "t", startAt = now.plusSeconds(1800).toEpochMilli(), endAt = now.plusSeconds(5400).toEpochMilli()), context()) as AssistantReply.Preview).value
        assertEquals(1, preview.warnings.size); assertEquals(1, db.dao().plans().size)
        val gate = AssistantConfirmation(RepositoryAssistantWriter(repo)); gate.stage(preview); assertTrue(gate.confirm(preview.id) is CommitResult.Saved)
        assertEquals(2, db.dao().plans().size)
        val cancel = (AssistantPlanner().prepare(AssistantCommand.CancelPlan(planId = "p"), context()) as AssistantReply.Preview).value
        gate.stage(cancel); assertTrue(gate.confirm(cancel.id) is CommitResult.Saved)
        assertEquals("CANCELLED", db.dao().plan("p")!!.status); assertEquals(1, db.dao().activePlans().size)
    }
}
