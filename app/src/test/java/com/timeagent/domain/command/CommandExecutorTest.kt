package com.timeagent.domain.command

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CommandExecutorTest {
    private class TestClock(var time: Instant = Instant.parse("2026-11-02T04:00:00Z")) : Clock() {
        override fun instant() = time
        override fun getZone(): ZoneId = ZoneId.of("Asia/Shanghai")
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(time, zone)
    }

    private fun plan(id: String = "plan") = ScheduleBlockEntity(
        id, null, 1_000, 2_000, title = "独立日程", timezoneId = "Asia/Shanghai",
    )
    private fun record(id: String = "actual") = TimeRecordEntity(id, null, 1_000, 2_000, title = "实际投入")

    private fun scenario(block: suspend CoroutineScope.(TimeRepository, TimeAgentDatabase, TestClock) -> Unit) = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = TimeAgentDatabase.openInMemory(context)
        val clock = TestClock()
        try { block(TimeRepository(db, clock), db, clock) } finally { db.close() }
    }

    @Test fun duplicateSurvivesANewExecutorAndReturnsTheFirstResult() = scenario { repo, db, clock ->
        val first = repo.commands.execute(CreateSchedule("create", plan()))
        val retry = TimeRepository(db, clock).commands.execute(CreateSchedule("create", plan().copy(title = "不能覆盖")))
        assertEquals(CommandStatus.APPLIED, first.status)
        assertEquals(first.copy(status = CommandStatus.DUPLICATE), retry)
        assertNotNull(first.undoToken)
        assertEquals(listOf(EntityRef("PLAN", "plan", 0)), first.affected)
        assertEquals("独立日程", db.dao().plan("plan")!!.title)
        assertEquals(1, db.dao().history().size)
        assertTrue(db.dao().tasks().isEmpty())
        assertTrue(db.dao().records().isEmpty())
    }

    @Test fun staleUpdateReportsCurrentValueWithoutWriting() = scenario { repo, db, _ ->
        repo.commands.execute(CreateSchedule("create", plan()))
        val current = db.dao().plan("plan")!!
        val result = repo.commands.execute(UpdateSchedule("edit", current.copy(title = "旧草稿"), 42))
        assertEquals(CommandStatus.CONFLICT, result.status)
        assertEquals(42L, result.conflict!!.expectedVersion)
        assertEquals(current.version, result.conflict!!.currentVersion)
        assertTrue(result.conflict!!.currentValue!!.contains("独立日程"))
        assertEquals(current, db.dao().plan("plan"))
        assertEquals(1, db.dao().history().size)
        assertNull(db.dao().commandResult("edit"))
    }

    @Test fun createDoesNotOverwriteAnExistingIdAndMissingUpdatesAreNotFound() = scenario { repo, db, _ ->
        repo.commands.execute(CreateSchedule("create", plan()))
        assertEquals(CommandStatus.CONFLICT, repo.commands.execute(CreateSchedule("again", plan().copy(title = "覆盖"))).status)
        assertEquals(CommandStatus.NOT_FOUND, repo.commands.execute(UpdateSchedule("missing", plan("absent"), 0)).status)
        assertEquals(CommandStatus.NOT_FOUND, repo.commands.execute(DeleteSchedule("missing-delete", "absent", 0)).status)
        assertEquals("独立日程", db.dao().plan("plan")!!.title)
    }

    @Test fun invalidInputDoesNotLeaveALogHistoryOrRevision() = scenario { repo, db, _ ->
        val result = repo.commands.execute(CreateSchedule("invalid", plan().copy(endAt = 500)))
        assertEquals(CommandStatus.INVALID, result.status)
        assertTrue(result.affected.isEmpty())
        assertNull(result.undoToken)
        assertTrue(db.dao().plans().isEmpty())
        assertTrue(db.dao().history().isEmpty())
        assertNull(db.dao().revision("PLAN:plan"))
        assertNull(db.dao().commandResult("invalid"))
    }

    @Test fun deleteChecksVersionAndItsUndoAndRedoPreserveTheBeforeSnapshot() = scenario { repo, db, _ ->
        repo.commands.execute(CreateSchedule("create", plan()))
        val before = db.dao().plan("plan")!!
        assertEquals(CommandStatus.CONFLICT, repo.commands.execute(DeleteSchedule("stale", before.id, 99)).status)
        val deleted = repo.commands.execute(DeleteSchedule("delete", before.id, before.version))
        assertNotNull(db.dao().plan("plan")!!.deletedAt)
        val undo = UndoCommand("undo", deleted.undoToken!!)
        assertEquals(CommandStatus.APPLIED, repo.commands.execute(undo).status)
        assertNull(db.dao().plan("plan")!!.deletedAt)
        val restored = db.dao().plan("plan")!!
        assertEquals(before.title, restored.title)
        assertTrue(restored.version > before.version)
        assertEquals(CommandStatus.DUPLICATE, repo.commands.execute(undo).status)
        assertEquals(restored, db.dao().plan("plan"))
        assertEquals(CommandStatus.APPLIED, repo.commands.execute(RedoCommand("redo", deleted.undoToken)).status)
        assertNotNull(db.dao().plan("plan")!!.deletedAt)
    }

    @Test fun oneBatchHasOneUndoUnitForAllItsEntities() = scenario { repo, db, _ ->
        val result = repo.commands.execute(BatchCommand("batch", listOf(
            CreateSchedule("child-plan", plan()), CreateActual("child-actual", record()),
        )))
        assertEquals(CommandStatus.APPLIED, result.status)
        assertEquals(setOf("PLAN", "RECORD"), result.affected.map { it.type }.toSet())
        assertEquals(1, db.dao().history().size)
        assertNull(db.dao().commandResult("child-plan"))
        repo.commands.execute(UndoCommand("undo", result.undoToken!!))
        assertTrue(db.dao().plans().isEmpty())
        assertTrue(db.dao().records().isEmpty())
        repo.commands.execute(RedoCommand("redo", result.undoToken))
        assertEquals(1, db.dao().plans().size)
        assertEquals(1, db.dao().records().size)
    }

    @Test fun failedBatchRollsBackEarlierChildrenAndDoesNotPublish() = scenario { repo, db, _ ->
        val events = mutableListOf<DataChanged>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) { repo.commands.events.collect { events += it } }
        try {
            val result = repo.commands.execute(BatchCommand("batch", listOf(
                CreateSchedule("child-plan", plan()), UpdateActual("child-actual", record("absent"), 0),
            )))
            yield()
            assertEquals(CommandStatus.NOT_FOUND, result.status)
            assertTrue(db.dao().plans().isEmpty())
            assertTrue(db.dao().history().isEmpty())
            assertNull(db.dao().commandResult("batch"))
            assertNull(db.dao().revision("PLAN:plan"))
            assertTrue(events.isEmpty())
        } finally { observer.cancelAndJoin() }
    }

    @Test fun simultaneousRetriesWriteExactlyOnce() = scenario { repo, db, _ ->
        val command = CreateSchedule("shared-request", plan())
        val results = coroutineScope { List(2) { async(Dispatchers.Default) { repo.commands.execute(command) } }.awaitAll() }
        assertEquals(setOf(CommandStatus.APPLIED, CommandStatus.DUPLICATE), results.map { it.status }.toSet())
        assertEquals(1, db.dao().plans().size)
        assertEquals(1, db.dao().history().size)
    }

    @Test fun simultaneousEditsWithTheSameVersionHaveOnlyOneWinner() = scenario { repo, db, _ ->
        repo.commands.execute(CreateSchedule("create", plan()))
        val before = db.dao().plan("plan")!!
        val results = coroutineScope { (1..2).map { index ->
            async(Dispatchers.Default) { repo.commands.execute(UpdateSchedule("edit-$index", before.copy(title = "稿$index"), before.version)) }
        }.awaitAll() }
        assertEquals(setOf(CommandStatus.APPLIED, CommandStatus.CONFLICT), results.map { it.status }.toSet())
        assertEquals(before.version + 1, db.dao().plan("plan")!!.version)
        assertEquals(2, db.dao().history().size)
    }

    @Test fun timerRetryAndSwitchKeepOneRunningRecordAndOneUndoForTheSwitch() = scenario { repo, db, clock ->
        val start = StartTimer("start", null)
        assertEquals(CommandStatus.APPLIED, repo.commands.execute(start).status)
        assertEquals(CommandStatus.DUPLICATE, repo.commands.execute(start).status)
        clock.time = clock.time.plusSeconds(60)
        assertEquals(CommandStatus.CONFLICT, repo.commands.execute(StartTimer("blocked", null)).status)
        val switched = repo.commands.execute(StartTimer("switch", null, switchExisting = true))
        assertEquals(CommandStatus.APPLIED, switched.status)
        assertEquals("switch", db.dao().runningTimer()!!.id)
        assertNotNull(db.dao().record("start")!!.endAt)
        assertEquals(2, db.dao().history().size)
        repo.commands.execute(UndoCommand("undo-switch", switched.undoToken!!))
        assertEquals("start", db.dao().runningTimer()!!.id)
        assertNull(db.dao().record("switch"))
    }

    @Test fun stopTimerRetryRetainsItsFirstEndAndVersion() = scenario { repo, db, clock ->
        repo.commands.execute(StartTimer("start", null))
        clock.time = clock.time.plusSeconds(60)
        val stop = StopTimer("stop", "start", db.dao().record("start")!!.version)
        val first = repo.commands.execute(stop)
        val ended = db.dao().record("start")
        clock.time = clock.time.plusSeconds(60)
        assertEquals(first.copy(status = CommandStatus.DUPLICATE), repo.commands.execute(stop))
        assertEquals(ended, db.dao().record("start"))
    }

    @Test fun actualCrudAllowsOverlapButRejectsStaleVersions() = scenario { repo, db, _ ->
        repo.commands.execute(CreateActual("create-a", record("a")))
        repo.commands.execute(CreateActual("create-b", record("b")))
        val a = db.dao().record("a")!!
        val edit = repo.commands.execute(UpdateActual("edit-a", a.copy(notes = "备注"), a.version))
        assertEquals(CommandStatus.APPLIED, edit.status)
        assertEquals(CommandStatus.CONFLICT, repo.commands.execute(DeleteActual("stale-delete", a.id, a.version)).status)
        val current = db.dao().record(a.id)!!
        val deletion = repo.commands.execute(DeleteActual("delete-a", a.id, current.version))
        assertEquals(CommandStatus.APPLIED, deletion.status)
        assertNull(db.dao().record(a.id))
        repo.commands.execute(UndoCommand("undo-delete", deletion.undoToken!!))
        assertEquals("备注", db.dao().record(a.id)!!.notes)
        assertEquals(2, db.dao().records().size)
    }

    @Test fun logWriteFailureRollsBackTheEntityAndHistory() = scenario { repo, db, _ ->
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_command_log BEFORE INSERT ON command_log BEGIN SELECT RAISE(ABORT, 'test log failure'); END")
        val result = repo.commands.execute(CreateSchedule("fail-log", plan()))
        assertEquals(CommandStatus.FAILED, result.status)
        assertTrue(db.dao().plans().isEmpty())
        assertTrue(db.dao().history().isEmpty())
        assertNull(db.dao().revision("PLAN:plan"))
    }

    @Test fun successfulWritesCleanResultsOlderThanThirtyDays() = scenario { repo, db, clock ->
        repo.commands.execute(CreateSchedule("old", plan("old")))
        clock.time = clock.time.plus(Duration.ofDays(30))
        repo.commands.execute(CreateSchedule("boundary", plan("boundary")))
        assertNotNull(db.dao().commandResult("old"))
        clock.time = clock.time.plusMillis(1)
        repo.commands.execute(CreateSchedule("new", plan("new")))
        assertNull(db.dao().commandResult("old"))
        assertNotNull(db.dao().commandResult("boundary"))
    }

    @Test fun eventsWaitForTheOuterCommitAndAreNotReplayedForDuplicate() = scenario { repo, db, _ ->
        val events = mutableListOf<DataChanged>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) { repo.commands.events.collect { events += it } }
        try {
            repo.transaction {
                assertEquals(CommandStatus.APPLIED, repo.commands.execute(CreateSchedule("create", plan())).status)
                assertTrue(events.isEmpty())
            }
            yield()
            assertEquals(listOf(setOf("PLAN")), events.map { it.affectedTypes })
            assertNotNull(db.dao().commandResult("create"))
            repo.commands.execute(CreateSchedule("create", plan()))
            yield()
            assertEquals(1, events.size)
        } finally { observer.cancelAndJoin() }
    }

    @Test fun anOuterFailureDiscardsNestedCommandLogsAndEvents() = scenario { repo, db, _ ->
        val events = mutableListOf<DataChanged>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) { repo.commands.events.collect { events += it } }
        try {
            try {
                repo.transaction {
                    repo.commands.execute(CreateSchedule("create", plan()))
                    error("outer failure")
                }
                fail("must fail")
            } catch (_: IllegalStateException) { }
            yield()
            assertTrue(db.dao().plans().isEmpty())
            assertNull(db.dao().commandResult("create"))
            assertTrue(events.isEmpty())
        } finally { observer.cancelAndJoin() }
    }

    @Test fun aHandledNestedRejectionCannotPublishRolledBackWrites() = scenario { repo, db, _ ->
        val events = mutableListOf<DataChanged>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) { repo.commands.events.collect { events += it } }
        try {
            try {
                repo.transaction {
                    repo.commands.execute(CreateSchedule("create", plan()))
                    assertEquals(CommandStatus.NOT_FOUND, repo.commands.execute(DeleteActual("missing", "absent", 0)).status)
                }
            } catch (_: IllegalStateException) { }
            yield()
            assertTrue(db.dao().plans().isEmpty())
            assertNull(db.dao().commandResult("create"))
            assertTrue("回滚后不能发布数据变化", events.isEmpty())
        } finally { observer.cancelAndJoin() }
    }

    @Test fun retryAfterUndoDoesNotResurrectTheOriginalWrite() = scenario { repo, db, _ ->
        val command = CreateSchedule("create", plan())
        val result = repo.commands.execute(command)
        repo.commands.execute(UndoCommand("undo", result.undoToken!!))
        assertEquals(result.copy(status = CommandStatus.DUPLICATE), repo.commands.execute(command))
        assertTrue(db.dao().plans().isEmpty())
    }

    @Test fun anOldUndoTokenCannotUndoAnUnrelatedNewerAction() = scenario { repo, db, _ ->
        val first = repo.commands.execute(CreateSchedule("first", plan("first")))
        repo.commands.execute(CreateSchedule("second", plan("second")))
        assertEquals(CommandStatus.CONFLICT, repo.commands.execute(UndoCommand("wrong-undo", first.undoToken!!)).status)
        assertEquals(2, db.dao().plans().size)
        assertTrue(db.dao().history().none { it.undone })
        assertNull(db.dao().commandResult("wrong-undo"))
    }
}
