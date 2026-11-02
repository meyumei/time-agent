package com.timeagent.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class TimelineRepositoryQueryDeviceTest {
    @Test fun snapshotContainsOnlyReferencedRowsAndRangeReminders() = runBlocking {
        val db = TimeAgentDatabase.openInMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val dao = db.dao()
            dao.putTask(TaskEntity("visible", "Visible"))
            dao.putTask(TaskEntity("unrelated", "Unrelated"))
            dao.putTask(TaskEntity("deleted", "Historical title", deletedAt = 1))
            dao.putPlan(ScheduleBlockEntity("point", "visible", 1000, 1000, kind = "POINT"))
            dao.putPlan(ScheduleBlockEntity("far", "unrelated", 9000, 10000))
            dao.putRecord(TimeRecordEntity("ongoing", "deleted", 0, null))
            dao.putRecord(TimeRecordEntity("ended", "unrelated", 0, 900))
            dao.putReminder(ReminderEntity("in", "In", 1500))
            dao.putReminder(ReminderEntity("out", "Out", 2000))
            dao.putIdea(IdeaEntity("idea", "Unrelated"))
            val result = withTimeout(5000) { TimeRepository(db).observeTimeline(1000, 2000).first() }
            assertFalse(result.loading)
            assertEquals(listOf("point"), result.plans.map { it.id })
            assertEquals(listOf("ongoing"), result.records.map { it.id })
            assertEquals(setOf("visible", "deleted"), result.tasks.map { it.id }.toSet())
            assertEquals("Historical title", result.tasks.first { it.id == "deleted" }.title)
            assertEquals(listOf("in"), result.reminders.map { it.id })
            assertTrue(result.ideas.isEmpty())
            assertTrue(result.history.isEmpty())
            assertTrue(result.attachments.isEmpty())
        } finally { db.close() }
    }

    @Test fun boundedStreamIgnoresUnrelatedWritesAndRefreshesTaskLabels() = runBlocking {
        val db = TimeAgentDatabase.openInMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        val results = Channel<LocalSnapshot>(Channel.UNLIMITED)
        val task = TaskEntity("task", "Before")
        db.dao().putTask(task)
        db.dao().putPlan(ScheduleBlockEntity("plan", "task", 1000, 2000))
        val job = launch(Dispatchers.Default) {
            TimeRepository(db).observeTimeline(1000, 2000).collect { results.send(it) }
        }
        try {
            assertEquals("Before", withTimeout(5000) { results.receive() }.tasks.single().title)
            db.dao().putIdea(IdeaEntity("idea", "Unrelated"))
            assertNull(withTimeoutOrNull(300) { results.receive() })
            db.dao().putRecord(TimeRecordEntity("far", null, 8000, 9000))
            assertNull(withTimeoutOrNull(300) { results.receive() })
            db.dao().putTask(task.copy(title = "After"))
            assertEquals("After", withTimeout(5000) { results.receive() }.tasks.single().title)
            db.dao().putRecord(TimeRecordEntity("overlap", null, 500, 1500))
            assertEquals(listOf("overlap"), withTimeout(5000) { results.receive() }.records.map { it.id })
        } finally {
            job.cancelAndJoin()
            db.close()
        }
    }

    @Test fun courseLabelsAndCrossBoundaryOccurrencesAreLoadedTogether() = runBlocking {
        val db = TimeAgentDatabase.openInMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val dao = db.dao()
            dao.putTerm(AcademicTermEntity("term", "Term", "2026-11-02", 20, "UTC"))
            dao.putCourse(CourseEntity("course", "Visible course", "Teacher", "Room", "term", "fixture", "course", 0, 0))
            dao.putCourse(CourseEntity("far", "Far course", "", "", "term", "fixture", "far", 0, 0))
            dao.putOccurrence(CourseOccurrenceEntity("cross", "course", "2026-11-02", 500, 1500, "cross"))
            dao.putOccurrence(CourseOccurrenceEntity("far", "far", "2026-11-02", 2000, 3000, "far"))
            dao.putOccurrence(CourseOccurrenceEntity("cancelled", "course", "2026-11-02", 1000, 2000, "cancelled", status = "CANCELLED"))
            val result = withTimeout(5000) { TimeRepository(db).observeTimeline(1000, 2000).first() }
            assertEquals(listOf("cross"), result.occurrences.map { it.id })
            assertEquals("Visible course", result.courses.single().name)
        } finally { db.close() }
    }
}
