package com.timeagent.domain.command

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LegacyCommandRoutingTest {
    private fun scenario(block: suspend (TimeRepository, TimeAgentDatabase) -> Unit) = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = TimeAgentDatabase.openInMemory(context)
        try { block(TimeRepository(db, Clock.fixed(Instant.parse("2026-11-02T04:00:00Z"), ZoneOffset.UTC)), db) }
        finally { db.close() }
    }

    private fun plan() = ScheduleBlockEntity("p", null, 1_000, 2_000, title = "日程")

    @Test fun existingScheduleEntryPointsUseTheCommandLogAndKeepWarnings() = scenario { repo, db ->
        repo.savePlan(plan(), requestId = "create")
        repo.savePlan(plan().copy(id = "overlap", startAt = 1_500, endAt = 2_500), requestId = "other")
        val before = db.dao().plan("p")!!
        val warnings = repo.savePlan(before.copy(notes = "备注"), before.version, requestId = "edit")
        assertEquals(listOf("overlap"), warnings.map { it.id })
        repo.deletePlan("p", db.dao().plan("p")!!.version, requestId = "delete")
        for (id in listOf("create", "other", "edit", "delete")) assertNotNull(db.dao().commandResult(id))
        repo.undoLastAction()
        assertNull(db.dao().plan("p")!!.deletedAt)
        repo.redoLastAction()
        assertNotNull(db.dao().plan("p")!!.deletedAt)
    }

    @Test fun recurringStyleDeletionAndReschedulingOfTheSameIdRemainSupported() = scenario { repo, db ->
        repo.savePlan(plan())
        repo.deletePlan("p")
        repo.deletePlan("p")
        val deleted = db.dao().plan("p")!!
        repo.savePlan(plan().copy(startAt = 3_000, endAt = 4_000), deleted.version)
        assertNull(db.dao().plan("p")!!.deletedAt)
        assertEquals(3_000, db.dao().plan("p")!!.startAt)
    }

    @Test fun legacyAssistantBatchKeepsItsSingleUndoAndNestedCommandToken() = scenario { repo, db ->
        repo.assistantTransaction("assistant") {
            val task = repo.saveTask(TaskEntity("task", "任务"))
            repo.savePlan(plan().copy(taskId = task.id), requestId = "child")
        }
        assertEquals(1, db.dao().history().size)
        assertEquals("group:assistant", com.google.gson.Gson().fromJson(db.dao().commandResult("child")!!.resultJson, CommandResult::class.java).undoToken)
        repo.undoLastAction("assistant")
        assertTrue(db.dao().plans().isEmpty())
        assertTrue(db.dao().tasks().isEmpty())
        repo.redoLastAction()
        assertEquals(1, db.dao().plans().size)
        assertEquals(1, db.dao().tasks().size)
    }

    @Test fun actualRecordEntryPointsPersistCommandsAndPreserveOverlap() = scenario { repo, db ->
        val record = TimeRecordEntity("r", null, 1_000, 2_000)
        repo.saveRecord(record, requestId = "create")
        val before = db.dao().record("r")!!
        repo.saveRecord(before.copy(title = "补录"), expectedVersion = before.version, requestId = "edit")
        repo.deleteRecord("r", db.dao().record("r")!!.version, requestId = "delete")
        assertNull(db.dao().record("r"))
        assertEquals("CreateActual", db.dao().commandResult("create")!!.commandType)
        assertEquals("UpdateActual", db.dao().commandResult("edit")!!.commandType)
        assertEquals("DeleteActual", db.dao().commandResult("delete")!!.commandType)
        repo.undoLastAction()
        assertEquals("补录", db.dao().record("r")!!.title)
    }

    @Test fun deletionRetryUsesItsLogEvenAfterTheActualRecordIsGone() = scenario { repo, db ->
        repo.saveRecord(TimeRecordEntity("r", null, 1_000, 2_000))
        repo.deleteRecord("r", requestId = "delete")
        val history = db.dao().history()
        repo.deleteRecord("r", requestId = "delete")
        assertNull(db.dao().record("r"))
        assertEquals(history, db.dao().history())
    }

    @Test fun stoppedTimerRetryReturnsItsFirstValueEvenAfterALaterDeletion() = scenario { repo, db ->
        repo.saveRecord(TimeRecordEntity("r", null, 1_000, 2_000))
        val ended = repo.stopTimer("r", requestId = "stop")
        repo.deleteRecord("r")
        assertEquals(ended, repo.stopTimer("r", requestId = "stop"))
        assertNull(db.dao().record("r"))
    }
}
