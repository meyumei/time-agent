package com.timeagent.ui

import com.timeagent.core.TimelineViewport
import com.timeagent.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class TimelineRecordWidthTest {
    private val date = LocalDate.of(2026, 10, 21)
    private val zone = ZoneId.of("Asia/Shanghai")
    private val viewport = TimelineViewport.fit(date)
    private val now = date.atTime(14, 0).atZone(zone).toInstant()
    private fun stamp(hour: Int, minute: Int = 0) = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun record(id: String, start: Long, end: Long) = TimeRecordEntity(id, null, start, end, notes = "睡眠")
    private fun day(records: List<TimeRecordEntity>, plans: List<ScheduleBlockEntity> = emptyList()) = TimelineRenderCache().window(
        LocalSnapshot(tasks = listOf(TaskEntity("t", "课程学习")), plans = plans, records = records, loading = false), now, viewport, zone).days.getValue(date)
    private fun TimelineDayLayout.bounds(id: String) = requireNotNull(bounds(events.single { it.item.id == id }, 100.0, 1.0, 0.0, 1440.0))

    @Test fun `a lone sleep uses the full day width`() {
        val layout = day(listOf(record("sleep", stamp(1, 30), stamp(8, 30))))
        assertEquals(98.0, layout.bounds("sleep").width, .001)
        assertEquals(1.0, layout.bounds("sleep").x, .001)
    }
    @Test fun `later plans do not shrink a nonoverlapping sleep or each other`() {
        val layout = day(listOf(record("sleep", stamp(1, 30), stamp(8, 30))), listOf(ScheduleBlockEntity("p", "t", stamp(10), stamp(12))))
        assertEquals(98.0, layout.bounds("sleep").width, .001)
        assertEquals(98.0, layout.bounds("p").width, .001)
    }
    @Test fun `touching end and start boundaries do not count as overlap`() {
        val layout = day(listOf(record("sleep", stamp(1, 30), stamp(8, 30))), listOf(ScheduleBlockEntity("p", "t", stamp(8, 30), stamp(10))))
        assertEquals(98.0, layout.bounds("sleep").width, .001)
        assertEquals(98.0, layout.bounds("p").width, .001)
    }
    @Test fun `overlapping actual records without scheduled events share the full day width`() {
        val layout = day(listOf(record("a", stamp(1), stamp(8)), record("b", stamp(2), stamp(9))))
        val a = layout.bounds("a"); val b = layout.bounds("b")
        assertEquals(48.0, a.width, .001); assertEquals(48.0, b.width, .001)
        assertTrue(a.x + a.width < b.x)
    }
    @Test fun `mixed overlap keeps scheduled cards wide and actual comparison separate`() {
        val layout = day(listOf(record("actual", stamp(0), stamp(23))), listOf(ScheduleBlockEntity("p", "t", stamp(10), stamp(12))))
        val actual = layout.bounds("actual"); val planned = layout.bounds("p")
        assertTrue(planned.width > 72.0)
        assertTrue(planned.x + planned.width < actual.x)
        assertFalse(layout.events.single { it.item.id == "actual" }.conflicting)
    }
    @Test fun `a later mixed overlap does not reserve a strip during independent sleep`() {
        val layout = day(listOf(record("sleep", stamp(1, 30), stamp(8, 30)), record("actual", stamp(11), stamp(12))), listOf(ScheduleBlockEntity("p", "t", stamp(10), stamp(13))))
        assertEquals(98.0, layout.bounds("sleep").width, .001)
        assertEquals(80.0, layout.bounds("p").width, .001)
    }
    @Test fun `expanded sleep card is clickable across its full width after panning and zooming`() {
        val data = LocalSnapshot(records = listOf(record("sleep", stamp(1, 30), stamp(8, 30))), loading = false)
        val cache = TimelineRenderCache()
        val panned = viewport.copy(firstDay = viewport.firstDay - .25)
        val frame = cache.window(data, now, panned, zone)
        assertEquals("sleep", frame.itemAt(panned, 700.0, 1440.0, 35.0, 300.0)?.id)
        val zoomed = panned.copy(zoom = 2.0)
        assertEquals("sleep", cache.window(data, now, zoomed, zone).itemAt(zoomed, 700.0, 1440.0, 80.0, 600.0)?.id)
    }
}
