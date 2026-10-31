package com.timeagent.core

import java.time.*
import java.time.format.DateTimeFormatter

object TimeInput {
    private val minuteFormat = DateTimeFormatter.ofPattern("HH:mm")
    /** Display precision is not storage precision. Metadata edits must not round recorded time. */
    fun parse(date: String, time: String, zone: ZoneId, original: Instant? = null): Instant {
        val dayText = date.trim()
        val timeText = time.trim()
        if (original != null) {
            val local = original.atZone(zone)
            if (local.toLocalDate().toString() == dayText && local.format(minuteFormat) == timeText) return original
        }
        return LocalDate.parse(dayText).atTime(LocalTime.parse(timeText)).atZone(zone).toInstant()
    }
}
