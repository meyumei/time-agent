package com.timeagent.ui

import com.timeagent.core.TimelineViewport
import com.timeagent.data.LocalSnapshot
import com.timeagent.data.ScheduleBlockEntity
import com.timeagent.data.TaskEntity
import com.timeagent.data.TimeRecordEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TimelineRenderCacheTest {
    private val monday = LocalDate.of(2026, 10, 19)
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-20T10:15:00Z")
    private val fit = TimelineViewport.fit(monday)

    @Test fun `fractional pan zoom and vertical pan reuse the prepared date range`() {
        val cache = TimelineRenderCache()
        val snapshot = snapshot()
        val first = cache.window(snapshot, now, fit.copy(firstDay = fit.firstDay + .25), zone)
        val moved = cache.window(snapshot, now, fit.copy(firstDay = fit.firstDay + .7, zoom = 1.04, firstMinute = 20.0), zone)

        assertSame("Only the covered dates determine the expensive layout", first, moved)
        assertSame(first.days.getValue(monday), moved.days.getValue(monday))
        assertEquals(8, first.days.size)
    }

    @Test fun `moving the date range retains the layouts for the seven shared days`() {
        val cache = TimelineRenderCache()
        val snapshot = snapshot()
        val first = cache.window(snapshot, now, fit.copy(firstDay = fit.firstDay + .2), zone)
        val moved = cache.window(snapshot, now, fit.copy(firstDay = fit.firstDay + 1.2), zone)

        (1L..7L).forEach { offset ->
            assertSame(first.days.getValue(monday.plusDays(offset)), moved.days.getValue(monday.plusDays(offset)))
        }
        val returned = cache.window(snapshot, now, fit, zone)
        assertSame("Returning to a nearby day reuses its lane assignment", first.days.getValue(monday), returned.days.getValue(monday))
    }

    @Test fun `clock ticks without running records never rebuild or rescan the source`() {
        val tasks = CountingList(listOf(TaskEntity("task", "课程")))
        val snapshot = snapshot().copy(tasks = tasks)
        val cache = TimelineRenderCache()
        val first = cache.window(snapshot, now, fit, zone)
        val reads = tasks.reads

        repeat(40) { offset ->
            cache.window(snapshot, now.plusSeconds(offset.toLong()), fit.shiftDays(offset.toLong()), zone)
        }
        val returned = cache.window(snapshot, now.plusSeconds(60), fit, zone)
        assertEquals("Date browsing must query the prepared source, not the semester entities", reads, tasks.reads)
        assertNotSame("Old daily layouts are evicted instead of growing without limit", first.days.getValue(monday), returned.days.getValue(monday))
        assertSame(returned, cache.window(snapshot, now.plusSeconds(61), fit, zone))
    }

    @Test fun `a running record crossing midnight invalidates both days and leaves other days cached`() {
        val started = Instant.parse("2026-10-19T23:30:00Z")
        val beforeMidnight = Instant.parse("2026-10-19T23:50:00Z")
        val afterMidnight = Instant.parse("2026-10-20T00:10:00Z")
        val snapshot = snapshot().copy(records = listOf(TimeRecordEntity("running", null, started.toEpochMilli(), null)))
        val cache = TimelineRenderCache()
        val before = cache.window(snapshot, beforeMidnight, fit, zone)
        val after = cache.window(snapshot, afterMidnight, fit, zone)

        assertNotSame(before.days.getValue(monday), after.days.getValue(monday))
        assertNotSame(before.days.getValue(monday.plusDays(1)), after.days.getValue(monday.plusDays(1)))
        assertSame(before.days.getValue(monday.plusDays(2)), after.days.getValue(monday.plusDays(2)))
        assertEquals(afterMidnight, after.items.single { it.id == "running" }.span.end)
        assertEquals(1440, after.days.getValue(monday).events.single { it.item.id == "running" }.segment.endMinute)
        assertEquals(10, after.days.getValue(monday.plusDays(1)).events.single().segment.endMinute)

        val clockRewound = cache.window(snapshot, beforeMidnight, fit, zone)
        assertTrue("The old end day must also be cleared when the clock moves backwards", clockRewound.days.getValue(monday.plusDays(1)).events.isEmpty())
    }

    @Test fun `running record ticks retain complete historical day layouts`() {
        val timerStart = Instant.parse("2026-10-18T23:30:00Z")
        val firstNow = Instant.parse("2026-10-22T00:10:00Z")
        val snapshot = snapshot().copy(records = listOf(TimeRecordEntity("long-running", null, timerStart.toEpochMilli(), null)))
        val cache = TimelineRenderCache()
        val before = cache.window(snapshot, firstNow, fit, zone)
        val after = cache.window(snapshot, firstNow.plusSeconds(60), fit, zone)

        (0L..2L).forEach { offset ->
            val finishedDate = monday.plusDays(offset)
            assertSame("A completed local-day segment does not change when only the timer's end advances: $finishedDate", before.days.getValue(finishedDate), after.days.getValue(finishedDate))
        }
        assertNotSame("Only the unfinished end day needs a new segment", before.days.getValue(monday.plusDays(3)), after.days.getValue(monday.plusDays(3)))
        assertSame(before.days.getValue(monday.plusDays(4)), after.days.getValue(monday.plusDays(4)))
        assertEquals(11, after.days.getValue(monday.plusDays(3)).events.single().segment.endMinute)
        assertEquals("The agenda item must still expose the live timer end", firstNow.plusSeconds(60), after.items.single { it.id == "long-running" }.span.end)
        assertEquals("A hit on a cached historical day must resolve the current timer item", firstNow.plusSeconds(60), after.itemAt(fit, 700.0, 1440.0, 90.0, 120.0)?.span?.end)
    }

    @Test fun `rewinding a running timer preserves unchanged completed dates and removes its former end day`() {
        val timerStart = Instant.parse("2026-10-18T23:30:00Z")
        val snapshot = snapshot().copy(records = listOf(TimeRecordEntity("long-running", null, timerStart.toEpochMilli(), null)))
        val cache = TimelineRenderCache()
        val before = cache.window(snapshot, Instant.parse("2026-10-22T00:10:00Z"), fit, zone)
        val after = cache.window(snapshot, Instant.parse("2026-10-21T23:50:00Z"), fit, zone)

        assertSame(before.days.getValue(monday), after.days.getValue(monday))
        assertSame(before.days.getValue(monday.plusDays(1)), after.days.getValue(monday.plusDays(1)))
        assertNotSame(before.days.getValue(monday.plusDays(2)), after.days.getValue(monday.plusDays(2)))
        assertTrue(after.days.getValue(monday.plusDays(3)).events.isEmpty())
        assertSame(before.days.getValue(monday.plusDays(4)), after.days.getValue(monday.plusDays(4)))
    }

    @Test fun `an entirely historical window resolves fresh timer items without rebuilding its days`() {
        val firstNow = Instant.parse("2026-11-01T00:10:00Z")
        val snapshot = snapshot().copy(records = listOf(TimeRecordEntity("long-running", null, Instant.parse("2026-10-18T23:30:00Z").toEpochMilli(), null)))
        val cache = TimelineRenderCache()
        val before = cache.window(snapshot, firstNow, fit, zone)
        val oldItem = before.items.single { it.id == "long-running" }
        val after = cache.window(snapshot, firstNow.plusSeconds(60), fit, zone)

        before.dates.forEach { date -> assertSame("A historical day has no changed local segment: $date", before.days.getValue(date), after.days.getValue(date)) }
        assertEquals(firstNow.plusSeconds(60), after.items.single { it.id == "long-running" }.span.end)
        assertEquals("Even an earlier window reference uses the source's current item", firstNow.plusSeconds(60), before.currentItem(oldItem).span.end)
        assertEquals(firstNow.plusSeconds(60), after.itemAt(fit, 700.0, 1440.0, 90.0, 120.0)?.span?.end)
    }

    @Test fun `a changed task invalidates content while unrelated snapshot fields keep the cache`() {
        val snapshot = snapshot()
        val cache = TimelineRenderCache()
        val first = cache.window(snapshot, now, fit, zone)
        assertSame(first, cache.window(snapshot.copy(loading = true), now, fit, zone))

        val renamed = cache.window(snapshot.copy(tasks = listOf(TaskEntity("task", "更新后的课程"))), now, fit, zone)
        assertNotSame(first.days.getValue(monday), renamed.days.getValue(monday))
        assertEquals("更新后的课程", renamed.items.single().title)
    }

    @Test fun `changing the time zone rebuilds local day segments`() {
        val start = Instant.parse("2026-10-19T23:00:00Z").toEpochMilli()
        val snapshot = snapshot().copy(plans = listOf(ScheduleBlockEntity("plan", "task", start, start + 7_200_000)))
        val cache = TimelineRenderCache()
        val utc = cache.window(snapshot, now, fit, zone)
        assertEquals(2, utc.days.values.sumOf { it.events.size })

        val shanghai = cache.window(snapshot, now, fit, ZoneId.of("Asia/Shanghai"))
        assertTrue(shanghai.days.getValue(monday).events.isEmpty())
        val segment = shanghai.days.getValue(monday.plusDays(1)).events.single().segment
        assertEquals(420, segment.startMinute)
        assertEquals(540, segment.endMinute)
    }

    @Test fun `decades of running time create segments only for the eight covered dates`() {
        val snapshot = LocalSnapshot(records = listOf(TimeRecordEntity("long", null, Instant.parse("2000-01-01T00:00:00Z").toEpochMilli(), null)), loading = false)
        val frame = TimelineRenderCache().window(snapshot, Instant.parse("2026-11-01T00:00:00Z"), fit.copy(firstDay = fit.firstDay + .2), zone)
        assertEquals(8, frame.days.size)
        assertEquals(8, frame.days.values.sumOf { it.events.size })
        assertTrue(frame.days.values.all { it.events.single().segment.startMinute == 0 && it.events.single().segment.endMinute == 1440 })
    }

    @Test fun `scheduled conflict lanes stay separate from records and conflict pairs are unique across midnight`() {
        val start = Instant.parse("2026-10-19T23:00:00Z").toEpochMilli()
        val snapshot = snapshot().copy(
            plans = listOf(ScheduleBlockEntity("a", "task", start, start + 7_200_000), ScheduleBlockEntity("b", "task", start, start + 7_200_000)),
            records = listOf(TimeRecordEntity("record", null, start, start + 7_200_000)),
        )
        val frame = TimelineRenderCache().window(snapshot, now, fit, zone)
        assertEquals(1, frame.conflicts.size)
        frame.days.values.filter { it.events.isNotEmpty() }.forEach { day ->
            assertTrue(day.events.filter { it.item.kind == ItemKind.PLAN }.all { it.lane.laneCount == 2 && it.conflicting })
            assertEquals(1, day.events.single { it.item.kind == ItemKind.RECORD }.lane.laneCount)
            assertFalse(day.events.single { it.item.kind == ItemKind.RECORD }.conflicting)
        }
    }

    @Test fun `hit testing follows fractional date movement and excludes lane gaps`() {
        val start = monday.plusDays(1).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val snapshot = snapshot().copy(
            plans = listOf(ScheduleBlockEntity("a", "task", start, start + 7_200_000), ScheduleBlockEntity("b", "task", start, start + 7_200_000)),
            records = listOf(TimeRecordEntity("record", null, start, start + 7_200_000)),
        )
        val cache = TimelineRenderCache()
        val firstViewport = fit.copy(firstDay = fit.firstDay + .25)
        val frame = cache.window(snapshot, now, firstViewport, zone)
        assertEquals("a", frame.itemAt(firstViewport, 700.0, 1440.0, 85.0, 660.0)?.id)
        assertEquals("b", frame.itemAt(firstViewport, 700.0, 1440.0, 130.0, 660.0)?.id)
        assertEquals("record", frame.itemAt(firstViewport, 700.0, 1440.0, 165.0, 660.0)?.id)
        assertNull("The one dp lane margin is blank grid", frame.itemAt(firstViewport, 700.0, 1440.0, 116.0, 660.0))

        val moved = firstViewport.copy(firstDay = fit.firstDay + .75)
        assertSame(frame, cache.window(snapshot, now, moved, zone))
        assertEquals("a", frame.itemAt(moved, 700.0, 1440.0, 35.0, 660.0)?.id)
        assertNull("The former card position now belongs to another lane gap or day", frame.itemAt(moved, 700.0, 1440.0, 85.0, 500.0))
        val zoomed = moved.copy(zoom = 2.0, firstMinute = 300.0)
        val zoomedFrame = cache.window(snapshot, now, zoomed, zone)
        assertEquals("a", zoomedFrame.itemAt(zoomed, 700.0, 1440.0, 60.0, 720.0)?.id)
        assertNull(zoomedFrame.itemAt(zoomed, 700.0, 1440.0, -1.0, 720.0))
    }

    private fun snapshot(): LocalSnapshot {
        val start = monday.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        return LocalSnapshot(
            tasks = listOf(TaskEntity("task", "课程")),
            plans = listOf(ScheduleBlockEntity("plan", "task", start, start + 7_200_000)),
            loading = false,
        )
    }

    private class CountingList<T>(private val values: List<T>) : AbstractList<T>() {
        var reads = 0
            private set
        override val size: Int get() = values.size
        override fun get(index: Int): T = values[index].also { reads++ }
    }
}
