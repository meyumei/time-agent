package com.timeagent.core

import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate
import java.time.ZoneId

class TimelineSelectionTest {
    private val monday = LocalDate.of(2026, 10, 19)
    @Test fun `tap resolves date and nearest quarter hour from actual viewport`() {
        val picked = requireNotNull(TimelineSelection.at(TimelineViewport.fit(monday), 150.0, 780.0, 700.0, 1440.0))
        assertEquals(monday.plusDays(1), picked.date)
        assertEquals(780, picked.startMinute)
        assertEquals(840, picked.endMinute)
    }
    @Test fun `selection follows fractional date and zoomed hour offset`() {
        val viewport = TimelineViewport(monday.toEpochDay() + 1.25, 2.0, 300.0)
        val picked = requireNotNull(TimelineSelection.at(viewport, 175.0, 120.0, 700.0, 720.0))
        assertEquals(monday.plusDays(2), picked.date)
        assertEquals(420, picked.startMinute)
        assertEquals(480, picked.endMinute)
    }
    @Test fun `upward drag remains one date and orders both endpoints`() {
        val picked = TimelineSelection.range(monday, 990, 810)
        assertEquals(810, picked.startMinute)
        assertEquals(990, picked.endMinute)
    }
    @Test fun `late tap ends at midnight without crossing the displayed day`() {
        val picked = requireNotNull(TimelineSelection.at(TimelineViewport.fit(monday), 10.0, 1438.0, 700.0, 1440.0))
        assertEquals(1425, picked.startMinute)
        assertEquals(1440, picked.endMinute)
        assertEquals("2026-10-20T00:00+08:00[Asia/Shanghai]", picked.span(ZoneId.of("Asia/Shanghai")).end.atZone(ZoneId.of("Asia/Shanghai")).toString())
    }
    @Test fun `outside or invalid screen coordinates do not create a date`() {
        val viewport = TimelineViewport.fit(monday)
        assertNull(TimelineSelection.at(viewport, -1.0, 20.0, 700.0, 1440.0))
        assertNull(TimelineSelection.at(viewport, 700.0, 20.0, 700.0, 1440.0))
        assertNull(TimelineSelection.at(viewport, 20.0, Double.NaN, 700.0, 1440.0))
        assertNull(TimelineSelection.at(viewport, 20.0, 20.0, 0.0, 1440.0))
    }
}
