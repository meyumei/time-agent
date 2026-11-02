package com.timeagent.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TimelineQueryDeviceTest {

    @Test
    fun halfOpenRangeExcludesAdjacentAndRejectsInvalidRange() = runBlocking {
        val db = openDb()
        try {
            val core = db.dao()
            val timeline = db.timelineDao()
            core.putPlan(plan("p1", 1_000L, 2_000L))
            core.putRecord(record("r1", 1_000L, 2_000L))

            assertTrue(timeline.blocks(0L, 1_000L).isEmpty())
            assertTrue(timeline.records(0L, 1_000L).isEmpty())
            assertTrue(timeline.blocks(2_000L, 3_000L).isEmpty())
            assertTrue(timeline.records(2_000L, 3_000L).isEmpty())

            assertEquals(listOf("p1"), timeline.blocks(1_000L, 2_000L).map { it.id })
            assertEquals(listOf("r1"), timeline.records(1_000L, 2_000L).map { it.id })

            assertTrue(timeline.blocks(1_500L, 1_500L).isEmpty())
            assertTrue(timeline.records(1_500L, 1_500L).isEmpty())
            assertTrue(timeline.blocks(2_000L, 1_000L).isEmpty())
            assertTrue(timeline.records(2_000L, 1_000L).isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun spansCrossMidnightMonthAndLeapDayWithoutBleeding() = runBlocking {
        val db = openDb()
        try {
            val core = db.dao()
            val timeline = db.timelineDao()
            val jan31Late = Instant.parse("2024-01-31T23:30:00Z").toEpochMilli()
            val feb1 = Instant.parse("2024-02-01T00:00:00Z").toEpochMilli()
            val feb1Late = Instant.parse("2024-02-01T23:30:00Z").toEpochMilli()
            val feb2 = Instant.parse("2024-02-02T00:00:00Z").toEpochMilli()
            val feb29Late = Instant.parse("2024-02-29T23:30:00Z").toEpochMilli()
            val mar1 = Instant.parse("2024-03-01T00:00:00Z").toEpochMilli()

            core.putPlan(plan("month", jan31Late, feb1))
            core.putPlan(plan("midnight", feb1Late, feb2))
            core.putPlan(plan("leap", feb29Late, mar1))

            assertEquals(listOf("month"), timeline.blocks(jan31Late + 1L, feb1).map { it.id })
            assertEquals(listOf("midnight"), timeline.blocks(feb1Late + 1L, feb2).map { it.id })
            assertEquals(listOf("leap"), timeline.blocks(feb29Late + 1L, mar1).map { it.id })

            assertTrue(timeline.blocks(feb1, feb1Late).isEmpty())
            assertTrue(timeline.blocks(feb2, feb29Late).isEmpty())
            assertTrue(timeline.blocks(mar1, mar1 + 1L).isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun pointAndAllDaySpansUseHalfOpenBounds() = runBlocking {
        val db = openDb()
        try {
            val core = db.dao()
            val timeline = db.timelineDao()
            val pointAt = 10_000L
            val dayStart = 20_000L
            val dayEnd = dayStart + 24L * 60L * 60L * 1_000L

            core.putPlan(plan("point", pointAt, pointAt, kind = "POINT"))
            core.putPlan(plan("day", dayStart, dayEnd, kind = "ALL_DAY"))

            assertEquals(listOf("point"), timeline.blocks(pointAt - 10L, pointAt + 1L).map { it.id })
            assertTrue(timeline.blocks(pointAt - 10L, pointAt).isEmpty())
            assertTrue(timeline.blocks(pointAt + 1L, pointAt + 2L).isEmpty())
            assertTrue(timeline.blocks(pointAt, pointAt).isEmpty())

            assertEquals(listOf("day"), timeline.blocks(dayStart, dayStart + 1L).map { it.id })
            assertEquals(listOf("day"), timeline.blocks(dayEnd - 1L, dayEnd).map { it.id })
            assertTrue(timeline.blocks(dayStart - 1L, dayStart).isEmpty())
            assertTrue(timeline.blocks(dayEnd, dayEnd + 1L).isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun runningRecordIntersectsFarFutureRange() = runBlocking {
        val db = openDb()
        try {
            val core = db.dao()
            val timeline = db.timelineDao()
            val start = Instant.parse("2015-01-01T00:00:00Z").toEpochMilli()
            val farStart = Instant.parse("2030-01-01T00:00:00Z").toEpochMilli()

            core.putRecord(record("running", start, null))

            assertEquals(listOf("running"), timeline.records(start, start + 1L).map { it.id })
            assertEquals(listOf("running"), timeline.records(farStart, farStart + 1L).map { it.id })
            assertTrue(timeline.records(start - 1L, start).isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun plansRespectTaskVisibilityWhileRecordsSurviveDeletedOrCancelledTasks() = runBlocking {
        val db = openDb()
        try {
            val core = db.dao()
            val timeline = db.timelineDao()
            core.putTask(task("done", status = "DONE"))
            core.putTask(task("cancelled", status = "CANCELLED"))
            core.putTask(task("deleted", deletedAt = 1L))

            core.putPlan(plan("b_done", 1_000L, 2_000L, taskId = "done"))
            core.putPlan(plan("a_orphan", 1_000L, 2_000L))
            core.putPlan(plan("c_cancelled_task", 1_000L, 2_000L, taskId = "cancelled"))
            core.putPlan(plan("d_deleted_task", 1_000L, 2_000L, taskId = "deleted"))
            core.putPlan(plan("e_deleted_block", 1_000L, 2_000L, taskId = "done", deletedAt = 2L))
            core.putPlan(plan("f_not_scheduled", 1_000L, 2_000L, status = "CANCELLED"))
            core.putRecord(record("r1", 1_000L, 2_000L, taskId = "deleted"))
            core.putRecord(record("r2", 1_000L, 2_000L, taskId = "cancelled"))

            assertEquals(
                listOf("a_orphan", "b_done"),
                timeline.blocks(0L, 10_000L).map { it.id }
            )
            assertEquals(
                listOf("r1", "r2"),
                timeline.records(0L, 10_000L).map { it.id }
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun observeBlocksEmitsForPlanWritesButNotRecordWrites() = runBlocking {
        val db = openDb()
        val timeline = db.timelineDao()
        val sizes = Channel<Int>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Default) {
            timeline.observeBlocks(0L, 10_000L).collect { sizes.send(it.size) }
        }
        try {
            assertEquals(0, withTimeout(5_000L) { sizes.receive() })
            db.dao().putPlan(plan("p1", 1_000L, 2_000L))
            assertEquals(1, withTimeout(5_000L) { sizes.receive() })

            db.dao().putRecord(record("r1", 1_000L, 2_000L))
            assertNull(withTimeoutOrNull(500L) { sizes.receive() })

            db.dao().putPlan(plan("p2", 3_000L, 4_000L))
            assertEquals(2, withTimeout(5_000L) { sizes.receive() })
        } finally {
            job.cancelAndJoin()
            db.close()
        }
    }

    private fun openDb(): TimeAgentDatabase =
        TimeAgentDatabase.openInMemory(InstrumentationRegistry.getInstrumentation().targetContext)

    private fun task(
        id: String,
        status: String = "TODO",
        deletedAt: Long? = null
    ): TaskEntity = TaskEntity(
        id = id,
        title = "Task $id",
        status = status,
        deletedAt = deletedAt
    )

    private fun plan(
        id: String,
        startAt: Long,
        endAt: Long,
        taskId: String? = null,
        status: String = "SCHEDULED",
        kind: String = "TIMED",
        deletedAt: Long? = null
    ): ScheduleBlockEntity = ScheduleBlockEntity(
        id = id,
        taskId = taskId,
        startAt = startAt,
        endAt = endAt,
        status = status,
        kind = kind,
        title = "Plan $id",
        deletedAt = deletedAt
    )

    private fun record(
        id: String,
        startAt: Long,
        endAt: Long?,
        taskId: String? = null
    ): TimeRecordEntity = TimeRecordEntity(
        id = id,
        taskId = taskId,
        startAt = startAt,
        endAt = endAt,
        title = "Record $id"
    )
}
