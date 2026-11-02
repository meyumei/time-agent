package com.timeagent.data

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TimelineRangeDeviceTest {
    @Test fun visibleDatesPrefetchOneCalendarDayAtEachEndAcrossMonth() {
        val zone = ZoneId.of("Asia/Shanghai")
        val range = TimelineRange.visible(LocalDate.parse("2026-10-31"), LocalDate.parse("2026-11-02"), zone)
        assertEquals(LocalDate.parse("2026-10-30").atStartOfDay(zone).toInstant().toEpochMilli(), range.start)
        assertEquals(LocalDate.parse("2026-11-04").atStartOfDay(zone).toInstant().toEpochMilli(), range.end)
    }

    @Test fun dstPrefetchUsesCalendarDaysRatherThanFixedMilliseconds() {
        val zone = ZoneId.of("America/New_York")
        val range = TimelineRange.visible(LocalDate.parse("2026-03-08"), LocalDate.parse("2026-03-08"), zone)
        assertEquals(71 * 3_600_000L, range.end - range.start)
    }

    @Test fun reversedDatesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TimelineRange.visible(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-01"), ZoneId.of("UTC"))
        }
    }
}
