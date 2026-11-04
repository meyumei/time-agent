package com.timeagent.ui.timeline

import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

enum class TimelineDragKind { MOVE, RESIZE_START, RESIZE_END }

data class TimelineDragResult(
    val startAt: Long,
    val endAt: Long,
    val dayShift: Long,
)

private const val MILLIS_PER_MINUTE = 60_000L
private const val SECONDS_PER_DAY = 86_400L

fun preview(
    startAt: Long,
    endAt: Long,
    deltaMinutes: Int,
    kind: TimelineDragKind,
    snapMinutes: Int = 15,
    minimumMinutes: Int = snapMinutes,
): TimelineDragResult {
    require(snapMinutes > 0) { "snapMinutes must be positive" }
    require(minimumMinutes > 0) { "minimumMinutes must be positive" }
    val deltaMillis = snapDeltaMillis(deltaMinutes, snapMinutes)
    val minimumMillis = minimumMinutes.toLong() * MILLIS_PER_MINUTE
    val (newStart, newEnd) = when (kind) {
        TimelineDragKind.MOVE -> startAt + deltaMillis to endAt + deltaMillis
        TimelineDragKind.RESIZE_START -> minOf(startAt + deltaMillis, endAt - minimumMillis) to endAt
        TimelineDragKind.RESIZE_END -> startAt to maxOf(endAt + deltaMillis, startAt + minimumMillis)
    }
    return TimelineDragResult(
        newStart,
        newEnd,
        utcDayIndex(newStart) - utcDayIndex(startAt),
    )
}

fun minuteDelta(deltaPixels: Float, minutesPerPixel: Float): Int {
    if (!deltaPixels.isFinite() || !minutesPerPixel.isFinite() || minutesPerPixel <= 0f) return 0
    return (deltaPixels * minutesPerPixel).roundToInt()
}

/** Horizontal travel changes local calendar dates, preserving wall time across DST. */
fun previewCalendarDrag(
    startAt: Long,
    endAt: Long,
    deltaMinutes: Int,
    deltaDays: Int,
    kind: TimelineDragKind,
    zone: ZoneId,
    snapMinutes: Int,
    point: Boolean = false,
    allDay: Boolean = false,
): TimelineDragResult {
    val start = Instant.ofEpochMilli(startAt).atZone(zone)
    val end = Instant.ofEpochMilli(endAt).atZone(zone)
    if (allDay) {
        val first = start.toLocalDate()
        val exclusiveEnd = end.toLocalDate()
        val nextStart = when (kind) {
            TimelineDragKind.MOVE -> first.plusDays(deltaDays.toLong())
            TimelineDragKind.RESIZE_START -> minOf(first.plusDays(deltaDays.toLong()), exclusiveEnd.minusDays(1))
            TimelineDragKind.RESIZE_END -> first
        }
        val nextEnd = when (kind) {
            TimelineDragKind.MOVE -> exclusiveEnd.plusDays(deltaDays.toLong())
            TimelineDragKind.RESIZE_START -> exclusiveEnd
            TimelineDragKind.RESIZE_END -> maxOf(exclusiveEnd.plusDays(deltaDays.toLong()), first.plusDays(1))
        }
        return TimelineDragResult(nextStart.atStartOfDay(zone).toInstant().toEpochMilli(),
            nextEnd.atStartOfDay(zone).toInstant().toEpochMilli(), nextStart.toEpochDay() - first.toEpochDay())
    }
    fun move(value: java.time.ZonedDateTime): Long {
        if (deltaMinutes == 0 && deltaDays == 0) return value.toInstant().toEpochMilli()
        val candidate = value.plusDays(deltaDays.toLong()).plusMinutes(deltaMinutes.toLong())
        val minute = candidate.toLocalTime().toSecondOfDay() / 60.0
        val snapped = (minute / snapMinutes).roundToInt().toLong() * snapMinutes
        return candidate.toLocalDate().atStartOfDay().plusMinutes(snapped).atZone(zone).toInstant().toEpochMilli()
    }
    val minimum = snapMinutes * MILLIS_PER_MINUTE
    val nextStart = when (kind) {
        TimelineDragKind.MOVE -> move(start)
        TimelineDragKind.RESIZE_START -> minOf(move(start), endAt - minimum)
        TimelineDragKind.RESIZE_END -> startAt
    }
    val nextEnd = if (point) nextStart else when (kind) {
        TimelineDragKind.MOVE -> nextStart + (endAt - startAt)
        TimelineDragKind.RESIZE_START -> endAt
        TimelineDragKind.RESIZE_END -> maxOf(move(end), startAt + minimum)
    }
    return TimelineDragResult(nextStart, nextEnd,
        Instant.ofEpochMilli(nextStart).atZone(zone).toLocalDate().toEpochDay() - start.toLocalDate().toEpochDay())
}

private fun snapDeltaMillis(deltaMinutes: Int, snapMinutes: Int): Long {
    val steps = (deltaMinutes.toDouble() / snapMinutes.toDouble()).roundToInt().toLong()
    return (steps * snapMinutes.toLong()).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()) * MILLIS_PER_MINUTE
}

private fun utcDayIndex(epochMillis: Long): Long {
    val seconds = Instant.ofEpochMilli(epochMillis).epochSecond
    val day = seconds / SECONDS_PER_DAY
    return if (seconds % SECONDS_PER_DAY < 0L) day - 1L else day
}
