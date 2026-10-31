package com.timeagent.core

import java.time.*
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** All business intervals are half-open [start, end). */
data class TimeSpan(val start: Instant, val end: Instant) {
    init { require(end > start) { "结束时间必须晚于开始时间" } }
    fun overlaps(other: TimeSpan) = start < other.end && other.start < end
    fun clip(window: TimeSpan): TimeSpan? {
        val clippedStart = maxOf(start, window.start)
        val clippedEnd = minOf(end, window.end)
        return if (clippedEnd > clippedStart) TimeSpan(clippedStart, clippedEnd) else null
    }
    val seconds: Long get() = Duration.between(start, end).seconds
}

data class TimeStatistics(val recordedMinutes: Long, val elapsedMinutes: Long) {
    companion object {
        fun calculate(records: List<TimeSpan>, window: TimeSpan? = null): TimeStatistics {
            val spans = if (window == null) records else records.mapNotNull { it.clip(window) }
            return TimeStatistics(spans.sumOf { it.seconds } / 60, mergeSpans(spans).sumOf { it.seconds } / 60)
        }
    }
}

internal fun mergeSpans(spans: List<TimeSpan>): List<TimeSpan> {
    val merged = mutableListOf<TimeSpan>()
    for (span in spans.sortedBy { it.start }) {
        val last = merged.lastOrNull()
        if (last != null && span.start <= last.end) merged[merged.lastIndex] = TimeSpan(last.start, maxOf(last.end, span.end))
        else merged += span
    }
    return merged
}

data class WeekState(val today: LocalDate, val selectedWeekStart: LocalDate = startOfWeek(today)) {
    fun next() = copy(selectedWeekStart = selectedWeekStart.plusWeeks(1))
    fun previous() = copy(selectedWeekStart = selectedWeekStart.minusWeeks(1))
    fun current() = copy(selectedWeekStart = startOfWeek(today))
    fun window(zone: ZoneId) = TimeSpan(selectedWeekStart.atStartOfDay(zone).toInstant(), selectedWeekStart.plusWeeks(1).atStartOfDay(zone).toInstant())
    companion object {
        fun startOfWeek(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        fun academicWeek(date: LocalDate, firstMonday: LocalDate, count: Int): Int? {
            if (date < firstMonday) return null
            return (ChronoUnit.DAYS.between(firstMonday, date) / 7 + 1).toInt().takeIf { it in 1..count }
        }
    }
}

object Scheduling {
    fun findFreeSlots(window: TimeSpan, busy: List<TimeSpan>, requiredMinutes: Int, bufferMinutes: Int = 0): List<TimeSpan> {
        require(requiredMinutes > 0 && bufferMinutes >= 0)
        val occupied = mergeSpans(busy.mapNotNull { TimeSpan(it.start.minusSeconds(bufferMinutes * 60L), it.end.plusSeconds(bufferMinutes * 60L)).clip(window) })
        val free = mutableListOf<TimeSpan>()
        var cursor = window.start
        for (span in occupied) {
            if (span.start > cursor) free += TimeSpan(cursor, span.start)
            cursor = maxOf(cursor, span.end)
        }
        if (cursor < window.end) free += TimeSpan(cursor, window.end)
        return free.filter { it.seconds >= requiredMinutes * 60L }
    }
}

data class DaySegment(val date: LocalDate, val startMinute: Int, val endMinute: Int)
data class LanePlacement(val id: String, val span: TimeSpan, val lane: Int, val laneCount: Int)

object TimelineLayout {
    fun split(span: TimeSpan, zone: ZoneId): List<DaySegment> {
        val result = mutableListOf<DaySegment>()
        var date = span.start.atZone(zone).toLocalDate()
        while (date.atStartOfDay(zone).toInstant() < span.end) {
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
            val start = maxOf(span.start, date.atStartOfDay(zone).toInstant())
            val end = minOf(span.end, dayEnd)
            if (start < end) {
                val startMinute = start.atZone(zone).toLocalTime().toSecondOfDay() / 60
                val endMinute = if (end == dayEnd) 1440 else (end.atZone(zone).toLocalTime().toSecondOfDay() + 59) / 60
                result += DaySegment(date, startMinute, maxOf(startMinute + 1, endMinute))
            }
            date = date.plusDays(1)
        }
        return result
    }

    /** Greedy interval colouring, with equal-width lanes inside each connected overlap group. */
    fun lanes(events: List<Pair<String, TimeSpan>>): List<LanePlacement> {
        val result = mutableListOf<LanePlacement>()
        val group = mutableListOf<Pair<String, TimeSpan>>()
        var groupEnd: Instant? = null
        fun flush() {
            val ends = mutableListOf<Instant>()
            val placements = group.map { (id, span) ->
                val available = ends.indexOfFirst { it <= span.start }
                val lane = if (available < 0) ends.size.also { ends += span.end } else available.also { ends[it] = span.end }
                LanePlacement(id, span, lane, 0)
            }
            result += placements.map { it.copy(laneCount = ends.size) }
            group.clear()
        }
        for (event in events.sortedWith(compareBy<Pair<String, TimeSpan>> { it.second.start }.thenBy { it.first })) {
            if (groupEnd != null && event.second.start >= groupEnd) flush()
            if (group.isEmpty()) groupEnd = event.second.end else groupEnd = maxOf(groupEnd!!, event.second.end)
            group += event
        }
        flush()
        return result
    }
}
