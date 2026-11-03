package com.timeagent.ui.timeline

import java.time.Instant
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

private fun snapDeltaMillis(deltaMinutes: Int, snapMinutes: Int): Long {
    val steps = (deltaMinutes.toDouble() / snapMinutes.toDouble()).roundToInt().toLong()
    return (steps * snapMinutes.toLong()).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()) * MILLIS_PER_MINUTE
}

private fun utcDayIndex(epochMillis: Long): Long {
    val seconds = Instant.ofEpochMilli(epochMillis).epochSecond
    val day = seconds / SECONDS_PER_DAY
    return if (seconds % SECONDS_PER_DAY < 0L) day - 1L else day
}
