package com.timeagent.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.roundToInt

/** A local-clock selection; coordinates are measured inside the graph, excluding its labels. */
data class TimelineSelection(val date: LocalDate, val startMinute: Int, val endMinute: Int) {
    init { require(startMinute in 0..1439 && endMinute in (startMinute + 1)..1440) }
    fun span(zone: ZoneId): TimeSpan {
        fun at(minute: Int) = if (minute == 1440) date.plusDays(1).atStartOfDay(zone).toInstant()
            else date.atTime(minute / 60, minute % 60).atZone(zone).toInstant()
        return TimeSpan(at(startMinute), at(endMinute))
    }
    companion object {
        fun at(viewport: TimelineViewport, x: Double, y: Double, width: Double, height: Double): TimelineSelection? {
            if (listOf(x, y, width, height).any { !it.isFinite() } || width <= 0 || height <= 0 || x < 0 || x >= width || y < 0 || y >= height) return null
            val date = LocalDate.ofEpochDay(floor(viewport.firstDay + x / viewport.dayWidth(width)).toLong())
            val start = (((viewport.firstMinute + y / viewport.minuteHeight(height)) / 15).roundToInt() * 15).coerceIn(0, 1425)
            return TimelineSelection(date, start, (start + 60).coerceAtMost(1440))
        }
        fun range(date: LocalDate, firstMinute: Int, lastMinute: Int): TimelineSelection {
            val a = (firstMinute / 15.0).roundToInt().times(15).coerceIn(0, 1425)
            val b = (lastMinute / 15.0).roundToInt().times(15).coerceIn(0, 1440)
            val start = minOf(a, b).coerceAtMost(1425)
            return TimelineSelection(date, start, maxOf(a, b, start + 15).coerceAtMost(1440))
        }
    }
}
