package com.timeagent.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.timeagent.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RepositoryTest {
    private lateinit var db: TimeAgentDatabase
    private lateinit var repo: TimeRepository
    private val now = Instant.parse("2026-10-19T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("Asia/Shanghai"))
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setup() {
        context.deleteDatabase("repository-test.db")
        db = Room.databaseBuilder(context, TimeAgentDatabase::class.java, "repository-test.db").allowMainThreadQueries().build()
        repo = TimeRepository(db, clock)
    }
    @After fun teardown() { db.close(); context.deleteDatabase("repository-test.db") }
    private suspend fun task(id: String = "task-1") = repo.saveTask(TaskEntity(id, "论文阅读", estimatedMinutes = 60))

    @Test fun `task plans and overlapping actuals persist independently after reopen`() = runBlocking {
        task()
        repo.savePlan(ScheduleBlockEntity("p1", "task-1", now.toEpochMilli(), now.plusSeconds(7200).toEpochMilli()))
        repo.saveRecord(TimeRecordEntity("r1", "task-1", now.minusSeconds(3600).toEpochMilli(), now.toEpochMilli()))
        repo.saveRecord(TimeRecordEntity("r2", "task-1", now.minusSeconds(1800).toEpochMilli(), now.plusSeconds(1800).toEpochMilli()), allowFuture = true)
        db.close()
        db = Room.databaseBuilder(context, TimeAgentDatabase::class.java, "repository-test.db").allowMainThreadQueries().build()
        assertEquals(1, db.dao().plans().size)
        assertEquals(2, db.dao().records().size)
        assertEquals(7200000L, db.dao().plans().first().endAt - db.dao().plans().first().startAt)
    }
    @Test fun `plan conflicts warn and allow explicit save`() = runBlocking {
        task()
        repo.savePlan(ScheduleBlockEntity("p1", "task-1", now.toEpochMilli(), now.plusSeconds(3600).toEpochMilli()))
        val result = repo.savePlan(ScheduleBlockEntity("p2", "task-1", now.plusSeconds(1800).toEpochMilli(), now.plusSeconds(5400).toEpochMilli()))
        assertEquals(1, result.size)
        assertEquals(2, db.dao().plans().size)
    }
    @Test fun `starting another timer requires explicit switch and survives recreation`() = runBlocking {
        task(); task("task-2")
        repo.startTimer("task-1", "timer-1")
        val recreated = TimeRepository(db, clock)
        assertEquals("timer-1", recreated.runningTimer()?.id)
        try { recreated.startTimer("task-2", "timer-2"); fail("Expected timer confirmation") } catch (_: TimerAlreadyRunning) { }
        assertEquals(1, db.dao().records().size)
        val later = TimeRepository(db, Clock.fixed(now.plusSeconds(60), clock.zone))
        later.startTimer("task-2", "timer-2", switchExisting = true)
        assertEquals("task-2", later.runningTimer()?.taskId)
        assertEquals(now.plusSeconds(60).toEpochMilli(), db.dao().record("timer-1")!!.endAt)
    }
    @Test fun `double save and double start are idempotent`() = runBlocking {
        task(); task()
        repo.startTimer("task-1", "same-request")
        repo.startTimer("task-1", "same-request")
        assertEquals(1, db.dao().tasks().size)
        assertEquals(1, db.dao().records().size)
    }
    @Test fun `soft deleted tasks hide future plans but retain actual history and undo restores them`() = runBlocking {
        task()
        repo.savePlan(ScheduleBlockEntity("p1", "task-1", now.toEpochMilli(), now.plusSeconds(3600).toEpochMilli()))
        repo.saveRecord(TimeRecordEntity("r1", "task-1", now.minusSeconds(3600).toEpochMilli(), now.toEpochMilli()))
        repo.deleteTask("task-1", LinkedScheduleAction.DELETE)
        assertNotNull(db.dao().task("task-1")!!.deletedAt)
        assertEquals(0, db.dao().activePlans().size)
        assertEquals(1, db.dao().records().size)
        repo.undoLastAction()
        assertNull(db.dao().task("task-1")!!.deletedAt)
        assertEquals(1, db.dao().activePlans().size)
    }
    @Test fun `invalid and unconfirmed future records leave database unchanged`() = runBlocking {
        try { repo.saveRecord(TimeRecordEntity("bad", null, now.toEpochMilli(), now.minusSeconds(60).toEpochMilli())); fail() } catch (_: IllegalArgumentException) { }
        try { repo.saveRecord(TimeRecordEntity("future", null, now.plusSeconds(60).toEpochMilli(), now.plusSeconds(120).toEpochMilli())); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(db.dao().records().isEmpty())
    }
    @Test fun `stale plan edits cannot overwrite later changes`() = runBlocking {
        task()
        val plan = ScheduleBlockEntity("p1", "task-1", now.toEpochMilli(), now.plusSeconds(3600).toEpochMilli())
        repo.savePlan(plan)
        repo.savePlan(plan.copy(endAt = now.plusSeconds(7200).toEpochMilli()), expectedVersion = 0)
        try { repo.savePlan(plan, expectedVersion = 0); fail("Expected stale edit rejection") } catch (_: StaleChange) { }
        assertEquals(now.plusSeconds(7200).toEpochMilli(), db.dao().plans().single().endAt)
    }
    @Test fun `import rechecks duplicates in transaction and preserves manual occurrence changes`() = runBlocking {
        val term = ImportTerm("term", LocalDate.of(2026, 10, 19), 2, clock.zone)
        val bundle = CourseNormalizer.normalize("fixture", term, mapOf(1 to PeriodTime(LocalTime.of(9, 0), LocalTime.of(10, 0))), listOf(ImportedCourse("数学", "李老师", "A101", 1, 1, 1, setOf(1, 2), "math")))
        val preview = repo.previewImport(bundle)
        assertEquals(2, preview.newCount)
        repo.commitImport(preview, "batch-1")
        val occurrence = db.dao().occurrences().first()
        repo.saveOccurrence(occurrence.copy(locationOverride = "B202"))
        repo.commitImport(preview, "batch-2")
        assertEquals(2, db.dao().occurrences().size)
        assertEquals("B202", db.dao().occurrence(occurrence.id)!!.locationOverride)
        assertEquals(2, db.dao().importBatches().size)
    }
    @Test fun `undo refuses to clobber an external later edit`() = runBlocking {
        task()
        db.dao().putTask(db.dao().task("task-1")!!.copy(title = "新的标题", version = 9))
        try { repo.undoLastAction(); fail("Expected safe undo rejection") } catch (_: StaleChange) { }
        assertEquals("新的标题", db.dao().task("task-1")!!.title)
    }
    @Test fun `undo does not recycle a version that a stale editor can reuse`() = runBlocking {
        task()
        val initial = ScheduleBlockEntity("p1", "task-1", now.toEpochMilli(), now.plusSeconds(3600).toEpochMilli())
        repo.savePlan(initial)
        repo.savePlan(initial.copy(endAt = now.plusSeconds(7200).toEpochMilli()), expectedVersion = 0)
        val stale = db.dao().plan("p1")!!
        repo.undoLastAction()
        val restored = db.dao().plan("p1")!!
        repo.savePlan(restored.copy(endAt = now.plusSeconds(10800).toEpochMilli()), expectedVersion = restored.version)
        try { repo.savePlan(stale.copy(endAt = now.plusSeconds(14400).toEpochMilli()), expectedVersion = stale.version); fail("Recycled version accepted stale editor") } catch (_: StaleChange) { }
        assertEquals(now.plusSeconds(10800).toEpochMilli(), db.dao().plan("p1")!!.endAt)
    }
    @Test fun `undo can walk multiple edits while keeping revisions monotonic`() = runBlocking {
        task()
        val initial = db.dao().task("task-1")!!
        repo.saveTask(initial.copy(title = "第二版"), initial.version)
        val second = db.dao().task("task-1")!!
        repo.saveTask(second.copy(title = "第三版"), second.version)
        repo.undoLastAction()
        val revision = db.dao().task("task-1")!!.version
        assertTrue(revision > second.version + 1)
        repo.undoLastAction()
        assertEquals(initial.title, db.dao().task("task-1")!!.title)
        assertTrue(db.dao().task("task-1")!!.version > revision)
    }
    @Test fun `cross batch meetings preserve effective room and teacher against stored definition`() = runBlocking {
        val term = ImportTerm("term", LocalDate.of(2026, 10, 19), 2, clock.zone)
        val periods = mapOf(1 to PeriodTime(LocalTime.of(9, 0), LocalTime.of(10, 0)))
        val first = ImportedCourse("数学", "李老师", "A101", 1, 1, 1, setOf(1), "math")
        repo.commitImport(repo.previewImport(CourseNormalizer.normalize("fixture", term, periods, listOf(first))), "first")
        repo.commitImport(repo.previewImport(CourseNormalizer.normalize("fixture", term, periods, listOf(first.copy(dayOfWeek = 3, location = "B202", teacher = "王老师")))), "second")
        val wednesday = db.dao().occurrences().first { LocalDate.parse(it.date).dayOfWeek == DayOfWeek.WEDNESDAY }
        assertEquals("B202", wednesday.locationOverride)
        assertEquals("王老师", wednesday.teacherOverride)
    }
    @Test fun `failed import rolls back term course occurrence and batch together`() = runBlocking {
        val term = ImportTerm("term", LocalDate.of(2026, 10, 19), 2, clock.zone)
        val bundle = CourseNormalizer.normalize("fixture", term, mapOf(1 to PeriodTime(LocalTime.of(9, 0), LocalTime.of(10, 0))), listOf(ImportedCourse("数学", "李老师", "A101", 1, 1, 1, setOf(1, 2), "math")))
        val corrupt = bundle.copy(occurrences = listOf(bundle.occurrences[0], bundle.occurrences[1].copy(courseKey = "missing-parent")))
        try { repo.commitImport(repo.previewImport(corrupt), "bad-batch"); fail("Expected FK failure") } catch (_: android.database.SQLException) { }
        assertTrue(db.dao().terms().isEmpty())
        assertTrue(db.dao().courses().isEmpty())
        assertTrue(db.dao().occurrences().isEmpty())
        assertTrue(db.dao().importBatches().isEmpty())
    }
    @Test fun `editing retained history preserves its deleted task association`() = runBlocking {
        task()
        repo.saveRecord(TimeRecordEntity("history", "task-1", now.minusSeconds(3600).toEpochMilli(), now.toEpochMilli()))
        repo.deleteTask("task-1", LinkedScheduleAction.DELETE)
        val existing = db.dao().record("history")!!
        repo.saveRecord(existing.copy(notes = "补充说明"), expectedVersion = existing.version)
        assertEquals("task-1", db.dao().record("history")!!.taskId)
        assertEquals("补充说明", db.dao().record("history")!!.notes)
    }
    @Test fun `timer state survives closing and reopening the file database`() = runBlocking {
        task(); repo.startTimer("task-1", "persistent-timer")
        db.close()
        db = Room.databaseBuilder(context, TimeAgentDatabase::class.java, "repository-test.db").allowMainThreadQueries().build()
        val restored = TimeRepository(db, Clock.fixed(now.plusSeconds(300), clock.zone))
        assertEquals(now.toEpochMilli(), restored.runningTimer()!!.startAt)
        assertEquals(now.plusSeconds(300).toEpochMilli(), restored.stopTimer("persistent-timer").endAt)
    }
}
