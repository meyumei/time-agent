package com.timeagent.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor

/** Calendar coordinates are doubles so tiny drags do not lose precision at today's epoch day. */
data class TimelineViewport(
    val firstDay: Double,
    val zoom: Double = 1.0,
    val firstMinute: Double = 0.0,
) {
    init {
        require(firstDay.isFinite() && firstDay in MIN_DAY..MAX_DAY)
        require(zoom.isFinite() && zoom in 1.0..MAX_ZOOM)
        require(firstMinute.isFinite() && firstMinute in 0.0..(MINUTES_PER_DAY - MINUTES_PER_DAY / zoom))
    }

    fun dayWidth(width: Double): Double = (width / FIT_DAYS) * zoom
    fun minuteHeight(height: Double): Double = (height / MINUTES_PER_DAY) * zoom
    fun shiftDays(days: Long): TimelineViewport = copy(firstDay = (firstDay + days).coerceIn(MIN_DAY, MAX_DAY))

    /** Pan is in screen pixels; focus is relative to the timeline's date/hour grid. */
    fun transform(
        panX: Double,
        panY: Double,
        scaleChange: Double,
        focusX: Double,
        focusY: Double,
        width: Double,
        height: Double,
    ): TimelineViewport {
        if (!width.isFinite() || !height.isFinite() || width <= 0.0 || height <= 0.0) return this
        val factor = scaleChange.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val nextZoom = (zoom * factor).coerceIn(1.0, MAX_ZOOM)
        val x = (focusX.takeIf(Double::isFinite) ?: (width / 2)).coerceIn(0.0, width)
        val y = (focusY.takeIf(Double::isFinite) ?: (height / 2)).coerceIn(0.0, height)
        val dx = panX.takeIf(Double::isFinite) ?: 0.0
        val dy = panY.takeIf(Double::isFinite) ?: 0.0
        val oldDayWidth = dayWidth(width)
        val oldMinuteHeight = minuteHeight(height)
        val nextDayWidth = (width / FIT_DAYS) * nextZoom
        val nextMinuteHeight = (height / MINUTES_PER_DAY) * nextZoom
        if (oldDayWidth <= 0.0 || oldMinuteHeight <= 0.0 || nextDayWidth <= 0.0 || nextMinuteHeight <= 0.0) return this
        val day = (firstDay + x / oldDayWidth - (x + dx) / nextDayWidth).coerceIn(MIN_DAY, MAX_DAY)
        val minute = (firstMinute + y / oldMinuteHeight - (y + dy) / nextMinuteHeight)
            .coerceIn(0.0, MINUTES_PER_DAY - MINUTES_PER_DAY / nextZoom)
        return TimelineViewport(day, nextZoom, minute)
    }

    fun visibleDates(): List<LocalDate> {
        val start = floor(firstDay).toLong()
        val end = ceil(firstDay + FIT_DAYS / zoom).toLong()
        return (start until end).map(LocalDate::ofEpochDay)
    }

    fun window(zone: ZoneId): TimeSpan {
        val dates = visibleDates()
        return TimeSpan(dates.first().atStartOfDay(zone).toInstant(), dates.last().plusDays(1).atStartOfDay(zone).toInstant())
    }

    /** Clip before splitting so a long running event only creates the visible days. */
    fun segments(span: TimeSpan, zone: ZoneId): List<DaySegment> =
        span.clip(window(zone))?.let { TimelineLayout.split(it, zone) }.orEmpty()

    companion object {
        const val MAX_ZOOM = 6.0
        private const val FIT_DAYS = 7.0
        private const val MINUTES_PER_DAY = 1440.0
        private val MIN_DAY = LocalDate.MIN.plusDays(14).toEpochDay().toDouble()
        private val MAX_DAY = LocalDate.MAX.minusDays(14).toEpochDay().toDouble()

        fun fit(firstDate: LocalDate): TimelineViewport =
            TimelineViewport(firstDate.toEpochDay().toDouble().coerceIn(MIN_DAY, MAX_DAY))
    }
}
