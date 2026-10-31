package com.timeagent.extraction

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.data.TimeRepository
import com.timeagent.data.TaskEntity
import com.timeagent.data.ScheduleBlockEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class ExtractionSaveTest {
    private val now = Instant.parse("2026-10-26T02:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    private lateinit var db: TimeAgentDatabase
    private lateinit var repo: TimeRepository
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TimeAgentDatabase::class.java).allowMainThreadQueries().build()
        repo = TimeRepository(db, Clock.fixed(now, zone))
    }
    @After fun tearDown() = db.close()

    @Test fun duplicateAndUndoNeverReplay() = runBlocking<Unit> {
        val preview = ChatTextExtractor.extract("明天 19:00 开会", now, zone)
        val selected = preview.items.map { it.copy(acceptedAsMine = true) }
        val first = saveExtraction(repo, preview, selected)
        assertEquals(1, first.created)
        assertEquals(0, saveExtraction(repo, preview, selected).created)
        repo.undoLastAction(first.requestId)
        assertEquals(0, repo.snapshot().reminders.size)
        assertEquals(0, saveExtraction(repo, preview, selected).created)
    }

    @Test fun invalidSecondItemRollsBackFirstAndMarker() = runBlocking<Unit> {
        val preview = ChatTextExtractor.extract("明天 19:00 开会；明天 20:00 吃饭", now, zone)
        val selected = preview.items.mapIndexed { index, it -> it.copy(acceptedAsMine = true, title = if (index == 1) "" else it.title) }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { saveExtraction(repo, preview, selected) } }
        assertTrue(repo.snapshot().reminders.isEmpty())
        assertEquals(2, saveExtraction(repo, preview, preview.items.map { it.copy(acceptedAsMine = true) }).created)
    }

    @Test fun staleDateAndTamperedSourceRejected() = runBlocking<Unit> {
        val preview = ChatTextExtractor.extract("明天开会", now, zone)
        val selected = preview.items.map { it.copy(acceptedAsMine = true) }
        val tomorrow = TimeRepository(db, Clock.fixed(now.plusSeconds(86400), zone))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { saveExtraction(tomorrow, preview, selected) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { saveExtraction(repo, preview.copy(sourceText = "别的内容"), selected) } }
    }

    @Test fun editedTaskWithoutDueDateCannotBeSaved() = runBlocking<Unit> {
        val preview = ChatTextExtractor.extract("明天交报告", now, zone)
        val selected = listOf(preview.items.single().copy(dueAt = null, acceptedAsMine = true))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { saveExtraction(repo, preview, selected) } }
        assertTrue(repo.snapshot().tasks.isEmpty())
    }

    @Test fun laterPlanConflictRollsBackEarlierReminderAndMarkers() = runBlocking<Unit> {
        val preview = ChatTextExtractor.extract("明天 18:00 出门；明天 20:00到21:00 开会", now, zone)
        val selected = preview.items.map { it.copy(acceptedAsMine = true) }
        repo.saveTask(TaskEntity("existing", "已有计划"))
        repo.savePlan(ScheduleBlockEntity("existing-plan", "existing", selected[1].startAt!!, selected[1].endAt!!))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { saveExtraction(repo, preview, selected) } }
        assertTrue(repo.snapshot().reminders.isEmpty())
        assertEquals(1, repo.snapshot().plans.size)
        db.dao().removePlan("existing-plan")
        assertEquals(2, saveExtraction(repo, preview, selected).created)
    }

    @Test fun deterministicIdCollisionDoesNotOverwriteEditedRow() = runBlocking<Unit> {
        val preview = ChatTextExtractor.extract("明天交报告", now, zone)
        val item = preview.items.single().copy(acceptedAsMine = true)
        val fp = hashText(listOf(item.original, item.title, item.kind.name, "", "", item.dueAt.toString()).joinToString("\u0000"))
        val id = UUID.nameUUIDFromBytes("CHAT_EXTRACT:TASK:$fp".toByteArray(Charsets.UTF_8)).toString()
        repo.saveTask(TaskEntity(id, "用户手改的标题"))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { saveExtraction(repo, preview, listOf(item)) } }
        assertEquals("用户手改的标题", db.dao().task(id)?.title)
    }

    @Test fun undoThenEditingCandidateCannotReplaySameSourceItem() = runBlocking<Unit> {
        val preview = ChatTextExtractor.extract("明天 19:00 开会", now, zone)
        val original = preview.items.single().copy(acceptedAsMine = true)
        val saved = saveExtraction(repo, preview, listOf(original))
        repo.undoLastAction(saved.requestId)
        val edited = original.copy(title = "改成另一件事", kind = ExtractionKind.TASK,
            startAt = null, dueAt = now.plusSeconds(2 * 86400).toEpochMilli())
        val replay = saveExtraction(repo, preview, listOf(edited))
        assertEquals(0, replay.created)
        assertEquals(1, replay.skipped)
        assertTrue(repo.snapshot().tasks.isEmpty())
        assertTrue(repo.snapshot().reminders.isEmpty())
    }

    @Test fun consumedItemStaysSkippedInNewBatchWithAnotherItem() = runBlocking<Unit> {
        val preview = ChatTextExtractor.extract("明天 19:00 开会；后天 20:00 看电影", now, zone)
        val first = preview.items[0].copy(acceptedAsMine = true)
        val second = preview.items[1].copy(acceptedAsMine = true)
        val saved = saveExtraction(repo, preview, listOf(first))
        repo.undoLastAction(saved.requestId)
        val changedFirst = first.copy(title = "另一个任务", kind = ExtractionKind.TASK,
            startAt = null, dueAt = now.plusSeconds(3 * 86400).toEpochMilli())
        val next = saveExtraction(repo, preview, listOf(changedFirst, second))
        assertEquals(1, next.created)
        assertEquals(1, next.skipped)
        assertTrue(repo.snapshot().tasks.isEmpty())
        assertEquals(listOf("看电影"), repo.snapshot().reminders.map { it.title })
    }
}
