package com.timeagent.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TimelineViewportTest {
    private val monday = LocalDate.of(2026, 10, 19)

    @Test fun `fit viewport contains seven dates and all 1440 minutes`() {
        val viewport = TimelineViewport.fit(monday)
        assertEquals(1.0, viewport.zoom, 0.0)
        assertEquals(0.0, viewport.firstMinute, 0.0)
        assertEquals(50.0, viewport.dayWidth(350.0), 0.00001)
        assertEquals(0.5, viewport.minuteHeight(720.0), 0.00001)
        assertEquals(monday, viewport.visibleDates().first())
        assertEquals(monday.plusDays(6), viewport.visibleDates().last())
    }

    @Test fun `dragging by a fraction of a day has no snapping`() {
        val moved = TimelineViewport.fit(monday).transform(-12.5, 0.0, 1.0, 100.0, 100.0, 350.0, 720.0)
        assertEquals(monday.toEpochDay() + 0.25, moved.firstDay, 0.000001)
        assertEquals(8, moved.visibleDates().size)
        assertEquals(monday.plusDays(7), moved.visibleDates().last())
    }

    @Test fun `one day drag changes Monday through Sunday into Tuesday through Monday`() {
        val moved = TimelineViewport.fit(monday).transform(-50.0, 0.0, 1.0, 100.0, 100.0, 350.0, 720.0)
        assertEquals(monday.plusDays(1), moved.visibleDates().first())
        assertEquals(monday.plusDays(7), moved.visibleDates().last())
        assertEquals(7, moved.visibleDates().size)
    }

    @Test fun `calendar navigation retains the fractional date zoom and vertical position`() {
        val zoomed = TimelineViewport.fit(monday).transform(-12.5, -30.0, 2.0, 125.0, 300.0, 350.0, 720.0)
        val next = zoomed.shiftDays(7)
        assertEquals(zoomed.firstDay + 7, next.firstDay, 0.000001)
        assertEquals(zoomed.zoom, next.zoom, 0.0)
        assertEquals(zoomed.firstMinute, next.firstMinute, 0.0)
        assertEquals(zoomed, next.shiftDays(-7))
        assertTrue(zoomed.shiftDays(Long.MAX_VALUE).firstDay.isFinite())
        assertTrue(zoomed.shiftDays(Long.MIN_VALUE).firstDay.isFinite())
    }

    @Test fun `zoom keeps the focused day and minute fixed under the fingers`() {
        val fitted = TimelineViewport.fit(monday)
        val zoomed = fitted.transform(0.0, 0.0, 2.0, 125.0, 300.0, 350.0, 720.0)
        assertEquals(2.0, zoomed.zoom, 0.000001)
        assertEquals(fitted.firstDay + 125.0 / fitted.dayWidth(350.0), zoomed.firstDay + 125.0 / zoomed.dayWidth(350.0), 0.000001)
        assertEquals(600.0, zoomed.firstMinute + 300.0 / zoomed.minuteHeight(720.0), 0.000001)
        assertEquals(100.0, zoomed.dayWidth(350.0), 0.000001)
        assertEquals(1.0, zoomed.minuteHeight(720.0), 0.000001)
    }

    @Test fun `vertical pan clamps to the full day at both edges`() {
        val zoomed = TimelineViewport.fit(monday).transform(0.0, 0.0, 2.0, 100.0, 300.0, 350.0, 720.0)
        val bottom = zoomed.transform(0.0, -10000.0, 1.0, 0.0, 0.0, 350.0, 720.0)
        assertEquals(720.0, bottom.firstMinute, 0.000001)
        val top = bottom.transform(0.0, 10000.0, 1.0, 0.0, 0.0, 350.0, 720.0)
        assertEquals(0.0, top.firstMinute, 0.000001)
    }

    @Test fun `invalid gesture numbers never produce nonfinite viewport state`() {
        val fitted = TimelineViewport.fit(monday)
        val unchanged = fitted.transform(Double.NaN, Double.POSITIVE_INFINITY, Double.NaN, 100.0, 300.0, 350.0, 720.0)
        assertEquals(fitted, unchanged)
        val zero = fitted.transform(12.0, 0.0, 2.0, 100.0, 300.0, 0.0, 720.0)
        assertEquals(fitted, zero)
        val maximum = fitted.transform(0.0, 0.0, 100000.0, 100.0, 300.0, 350.0, 720.0)
        assertEquals(TimelineViewport.MAX_ZOOM, maximum.zoom, 0.0)
        assertTrue(maximum.firstDay.isFinite())
        val minimum = maximum.transform(0.0, 0.0, 0.000001, 100.0, 300.0, 350.0, 720.0)
        assertEquals(1.0, minimum.zoom, 0.0)
        assertEquals(0.0, minimum.firstMinute, 0.0)
    }

    @Test fun `many tiny drags preserve sub day precision across a year boundary`() {
        val date = LocalDate.of(2030, 12, 31)
        var viewport = TimelineViewport.fit(date)
        repeat(1000) { viewport = viewport.transform(-0.05, 0.0, 1.0, 0.0, 0.0, 350.0, 720.0) }
        assertEquals(date.plusDays(1).toEpochDay().toDouble(), viewport.firstDay, 0.000001)
    }

    @Test fun `unrenderable tiny dimensions are ignored instead of creating NaN`() {
        val fitted = TimelineViewport.fit(monday)
        assertEquals(fitted, fitted.transform(0.0, 0.0, 2.0, 0.0, 0.0, Double.MIN_VALUE, Double.MIN_VALUE))
    }

    @Test fun `large finite dimensions preserve focal coordinates without overflowing`() {
        val fitted = TimelineViewport.fit(monday)
        val size = Double.MAX_VALUE
        val zoomed = fitted.transform(0.0, 0.0, 2.0, size / 2, size / 2, size, size)
        assertEquals(monday.toEpochDay() + 1.75, zoomed.firstDay, 0.000001)
        assertEquals(360.0, zoomed.firstMinute, 0.000001)
    }

    @Test fun `decades long blocks are clipped before creating visible daily segments`() {
        val span = TimeSpan(Instant.parse("1970-01-01T00:00:00Z"), Instant.parse("2100-01-01T00:00:00Z"))
        val viewport = TimelineViewport.fit(monday).transform(-12.5, 0.0, 1.0, 0.0, 0.0, 350.0, 720.0)
        val segments = viewport.segments(span, ZoneId.of("Asia/Shanghai"))
        assertEquals(8, segments.size)
        assertEquals(monday, segments.first().date)
        assertEquals(monday.plusDays(7), segments.last().date)
        assertTrue(segments.all { it.startMinute == 0 && it.endMinute == 1440 })
    }
}
