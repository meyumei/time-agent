package com.timeagent.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class TimeInputTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    @Test fun `unchanged minute fields preserve original seconds and milliseconds`() {
        val original = Instant.parse("2026-10-19T01:00:45.125Z")
        assertEquals(original, TimeInput.parse("2026-10-19", "09:00", zone, original))
    }
    @Test fun `changed minute fields produce explicitly edited instant`() {
        val original = Instant.parse("2026-10-19T01:00:45.125Z")
        assertEquals(Instant.parse("2026-10-19T01:20:00Z"), TimeInput.parse("2026-10-19", "09:20", zone, original))
    }
    @Test fun `short intervals remain positive when only notes are changed`() {
        val start = Instant.parse("2026-10-19T01:00:25.500Z")
        val end = Instant.parse("2026-10-19T01:00:45.900Z")
        assertTrue(TimeInput.parse("2026-10-19", "09:00", zone, end) > TimeInput.parse("2026-10-19", "09:00", zone, start))
    }
}
