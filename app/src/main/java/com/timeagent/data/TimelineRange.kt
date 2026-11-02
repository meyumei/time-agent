package com.timeagent.data

import java.time.LocalDate
import java.time.ZoneId

data class TimelineRange(val start: Long, val end: Long) {
    companion object {
        fun visible(first: LocalDate, last: LocalDate, zone: ZoneId): TimelineRange {
            require(!last.isBefore(first))
            return TimelineRange(
                first.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                last.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli(),
            )
        }
    }
}
