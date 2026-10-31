package com.timeagent.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class TimeRulesTest {
    private fun instant(time: String) = Instant.parse("2026-10-19T${time}:00Z")
    private fun span(start: String, end: String) = TimeSpan(instant(start), instant(end))

    @Test fun `adjacent intervals do not conflict but positive intersections do`() {
        assertFalse(span("09:00", "10:00").overlaps(span("10:00", "11:00")))
        assertTrue(span("09:00", "10:00").overlaps(span("09:30", "10:30")))
    }
    @Test fun `invalid or zero duration intervals are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { span("10:00", "09:00") }
        assertThrows(IllegalArgumentException::class.java) { span("10:00", "10:00") }
    }
    @Test fun `overlapping records report cumulative effort and union separately`() {
        val result = TimeStatistics.calculate(listOf(span("14:00", "15:00"), span("14:30", "15:30")))
        assertEquals(120L, result.recordedMinutes)
        assertEquals(90L, result.elapsedMinutes)
    }
    @Test fun `nested and disjoint spans form correct union`() {
        val result = TimeStatistics.calculate(listOf(span("09:00", "12:00"), span("10:00", "11:00"), span("13:00", "14:00")))
        assertEquals(300L, result.recordedMinutes)
        assertEquals(240L, result.elapsedMinutes)
        assertEquals(TimeStatistics(0, 0), TimeStatistics.calculate(emptyList()))
    }
    @Test fun `statistics clip records to selected week`() {
        val result = TimeStatistics.calculate(listOf(span("08:00", "12:00")), span("09:00", "10:00"))
        assertEquals(TimeStatistics(60, 60), result)
    }
    @Test fun `browsing across year does not change today`() {
        val today = LocalDate.of(2027, 1, 1)
        val state = WeekState(today, WeekState.startOfWeek(today).minusWeeks(2))
        assertEquals(LocalDate.of(2026, 12, 14), state.selectedWeekStart)
        assertEquals(today, state.next().today)
        assertEquals(LocalDate.of(2026, 12, 28), state.current().selectedWeekStart)
    }
    @Test fun `academic week uses explicit first teaching monday`() {
        assertEquals(8, WeekState.academicWeek(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 8, 31), 20))
        assertNull(WeekState.academicWeek(LocalDate.of(2026, 8, 30), LocalDate.of(2026, 8, 31), 20))
        assertNull(WeekState.academicWeek(LocalDate.of(2027, 8, 30), LocalDate.of(2026, 8, 31), 20))
    }
    @Test fun `free slots merge busy spans and keep buffer`() {
        val slots = Scheduling.findFreeSlots(span("09:00", "14:00"), listOf(span("10:00", "11:00"), span("10:30", "12:00")), 30, 10)
        assertEquals(listOf(span("09:00", "09:50"), span("12:10", "14:00")), slots)
    }
    @Test fun `cross midnight blocks split into visible daily segments`() {
        val range = TimeSpan(Instant.parse("2026-10-19T15:30:00Z"), Instant.parse("2026-10-19T17:00:00Z"))
        val segments = TimelineLayout.split(range, ZoneId.of("Asia/Shanghai"))
        assertEquals(2, segments.size)
        assertEquals(LocalDate.of(2026, 10, 19), segments[0].date)
        assertEquals(1410, segments[0].startMinute)
        assertEquals(1440, segments[0].endMinute)
        assertEquals(0, segments[1].startMinute)
        assertEquals(60, segments[1].endMinute)
    }
    @Test fun `overlap layout keeps all events in separate reachable lanes`() {
        val laidOut = TimelineLayout.lanes(listOf("a" to span("09:00", "11:00"), "b" to span("10:00", "12:00"), "c" to span("12:00", "13:00")))
        assertEquals(3, laidOut.size)
        assertNotEquals(laidOut.first { it.id == "a" }.lane, laidOut.first { it.id == "b" }.lane)
        assertEquals(2, laidOut.first { it.id == "a" }.laneCount)
        assertEquals(1, laidOut.first { it.id == "c" }.laneCount)
    }
}
