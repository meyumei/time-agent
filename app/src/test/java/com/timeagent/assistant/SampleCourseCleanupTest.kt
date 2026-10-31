package com.timeagent.assistant

import android.content.Context
import androidx.room.Room
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
@Config(sdk = [28])
class SampleCourseCleanupTest {
    private lateinit var db: TimeAgentDatabase
    private lateinit var repo: TimeRepository
    private val now = Instant.parse("2026-10-20T08:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    @Before fun setup() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(app, TimeAgentDatabase::class.java).allowMainThreadQueries().build()
        repo = TimeRepository(db, Clock.fixed(now, zone))
        val bundle = JsonCourseAdapter().parse(app.assets.open("course-import-example.json").bufferedReader().use { it.readText() })
        repo.commitImport(repo.previewImport(bundle))
        val real = db.dao().courses().first().copy(id = "real", sourceId = "scut-pdf", externalKey = "real", name = "示例·真实课程")
        db.dao().putCourse(real)
        db.dao().putOccurrence(db.dao().occurrences().first().copy(id = "real-o", courseId = real.id, sourceOccurrenceKey = "real-o"))
        repo.saveTask(TaskEntity("task", "阅读"))
        Unit
    }
    @After fun close() { db.close() }
    private suspend fun context() = AssistantContext(now, zone, LocalDate.of(2026, 10, 19), LocalSnapshot(tasks = db.dao().tasks(), courses = db.dao().courses(), occurrences = db.dao().occurrences(), loading = false))
    private suspend fun preview(): AssistantPreview {
        val ctx = context()
        val command = (LocalAssistantParser().parse("取消示例课程", ctx) as Interpretation.Parsed).command
        return (AssistantPlanner().prepare(command, ctx) as AssistantReply.Preview).value
    }
    @Test fun `all bundled examples cancel atomically after confirmation and one undo restores the entire batch`() = runBlocking {
        val before = db.dao().occurrences()
        val item = preview()
        assertTrue(item.title.contains("3 门 / 32 次"))
        assertEquals(before, db.dao().occurrences())
        val gate = AssistantConfirmation(RepositoryAssistantWriter(repo)); gate.stage(item)
        assertTrue(gate.confirm(item.id) is CommitResult.Saved)
        assertTrue(gate.confirm(item.id) is CommitResult.AlreadySaved)
        assertEquals(32, db.dao().occurrences().count { it.status == "CANCELLED" })
        assertEquals("SCHEDULED", db.dao().occurrence("real-o")!!.status)
        assertEquals("TODO", db.dao().task("task")!!.status)
        assertEquals(1, db.dao().history().count { it.entityType == "COURSE_BATCH" })
        repo.undoLastAction()
        assertTrue(db.dao().occurrences().all { it.status == "SCHEDULED" })
        assertTrue(db.dao().occurrences().filter { it.id != "real-o" }.all { it.version > before.first { old -> old.id == it.id }.version })
    }
    @Test fun `changed occurrence rejects entire old preview without partial cancellation`() = runBlocking {
        val item = preview()
        val target = db.dao().occurrences().first { it.id != "real-o" }
        repo.saveOccurrence(target.copy(locationOverride = "手工调整"), target.version)
        val gate = AssistantConfirmation(RepositoryAssistantWriter(repo)); gate.stage(item)
        assertTrue(gate.confirm(item.id) is CommitResult.Rejected)
        assertEquals(0, db.dao().occurrences().count { it.status == "CANCELLED" })
        assertEquals("手工调整", db.dao().occurrence(target.id)!!.locationOverride)
        assertTrue(db.dao().history().none { it.entityType == "COURSE_BATCH" })
    }
    @Test fun `cancelled preview never changes data and a fresh preview after cleanup is a read-only answer`() = runBlocking {
        val item = preview(); val gate = AssistantConfirmation(RepositoryAssistantWriter(repo))
        gate.stage(item); gate.cancel()
        assertTrue(gate.confirm(item.id) is CommitResult.Rejected)
        assertTrue(db.dao().occurrences().all { it.status == "SCHEDULED" })
        gate.stage(item); assertTrue(gate.confirm(item.id) is CommitResult.Saved)
        assertTrue(AssistantPlanner().prepare(AssistantCommand.CancelSampleCourses, context()) is AssistantReply.Answer)
    }
    @Test fun `cloud batch action accepts no broadened selector and never executes on decode`() = runBlocking {
        val result = CloudCommandCodec.decode("""{"action":"cancel_sample_courses"}""") as Interpretation.Parsed
        assertEquals(AssistantCommand.CancelSampleCourses, result.command)
        assertTrue(CloudCommandCodec.decode("""{"action":"cancel_sample_courses","sourceId":"scut-pdf"}""") is Interpretation.Clarify)
        assertTrue(db.dao().occurrences().all { it.status == "SCHEDULED" })
        val ctx = context()
        for (input in listOf("不要取消示例课程", "取消今天的示例课程", "取消所有课程", "示例课程有哪些"))
            assertNotEquals(AssistantCommand.CancelSampleCourses, (LocalAssistantParser().parse(input, ctx) as? Interpretation.Parsed)?.command)
    }
    @Test fun `stale batch undo restores none of the courses when even one saved item differs`() = runBlocking {
        val item = preview(); val gate = AssistantConfirmation(RepositoryAssistantWriter(repo)); gate.stage(item)
        assertTrue(gate.confirm(item.id) is CommitResult.Saved)
        val changed = db.dao().occurrences().first { it.status == "CANCELLED" }
        // Simulates an independent edit without adding a newer journal action above the batch.
        db.dao().putOccurrence(changed.copy(locationOverride = "后续编辑", version = changed.version + 1))
        try { repo.undoLastAction(); fail("Stale batch must reject atomically") } catch (_: StaleChange) { }
        assertEquals(32, db.dao().occurrences().count { it.status == "CANCELLED" })
        assertEquals("后续编辑", db.dao().occurrence(changed.id)!!.locationOverride)
    }
}
