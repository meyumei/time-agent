package com.timeagent.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor

data class HorizontalRange(val firstDay: Double, val dayCount: Int)
data class VerticalScale(val hourHeightDp: Double, val firstMinute: Double, val overview: Boolean)

/** Calendar coordinates are doubles so tiny drags do not lose precision at today's epoch day. */
data class TimelineViewport(
    val firstDay: Double,
    val zoom: Double = 1.0,
    val firstMinute: Double = 0.0,
    val dayCount: Int = 7,
    val overview: Boolean = false,
    val selectedDay: Double = firstDay + dayCount / 2,
) {
    init {
        require(firstDay.isFinite() && firstDay in MIN_DAY..MAX_DAY)
        require(zoom.isFinite() && zoom in MIN_ZOOM..MAX_ZOOM)
        require(firstMinute.isFinite() && firstMinute in 0.0..MINUTES_PER_DAY)
        require(dayCount in SUPPORTED_DAY_COUNTS)
    }

    val horizontal: HorizontalRange get() = HorizontalRange(firstDay, dayCount)
    val vertical: VerticalScale get() = VerticalScale(64.0 * zoom, firstMinute, overview)
    fun dayWidth(width: Double): Double = width / dayCount
    fun minuteHeight(height: Double, density: Double = 1.0): Double =
        if (overview) height / MINUTES_PER_DAY else vertical.hourHeightDp * density / 60.0
    fun visibleMinutes(height: Double, density: Double = 1.0): Double =
        (height / minuteHeight(height, density)).coerceAtMost(MINUTES_PER_DAY)
    fun withFirstDay(day: Double): TimelineViewport {
        val first = day.coerceIn(MIN_DAY, MAX_DAY)
        return copy(firstDay = first, selectedDay = (selectedDay + first - firstDay).coerceIn(MIN_DAY, MAX_DAY))
    }
    fun shiftDays(days: Long): TimelineViewport = withFirstDay(firstDay + days)

    /** Pan is in screen pixels; focus is relative to the timeline's date/hour grid. */
    fun transform(
        panX: Double,
        panY: Double,
        scaleChange: Double,
        focusX: Double,
        focusY: Double,
        width: Double,
        height: Double,
        density: Double = 1.0,
    ): TimelineViewport {
        if (!width.isFinite() || !height.isFinite() || width <= 0.0 || height <= 0.0) return this
        val factor = scaleChange.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val baseZoom = if (overview) height / density / 24.0 / 64.0 else zoom
        val nextZoom = (baseZoom * factor).coerceIn(MIN_ZOOM, MAX_DENSITY_ZOOM)
        val x = (focusX.takeIf(Double::isFinite) ?: (width / 2)).coerceIn(0.0, width)
        val y = (focusY.takeIf(Double::isFinite) ?: (height / 2)).coerceIn(0.0, height)
        val dx = panX.takeIf(Double::isFinite) ?: 0.0
        val dy = panY.takeIf(Double::isFinite) ?: 0.0
        val oldDayWidth = dayWidth(width)
        val oldMinuteHeight = minuteHeight(height, density)
        val nextDayWidth = oldDayWidth
        val staysOverview = overview && factor == 1.0
        val nextMinuteHeight = if (staysOverview) oldMinuteHeight else 64.0 * nextZoom * density / 60.0
        if (oldDayWidth <= 0.0 || oldMinuteHeight <= 0.0 || nextDayWidth <= 0.0 || nextMinuteHeight <= 0.0) return this
        val day = (firstDay + x / oldDayWidth - (x + dx) / nextDayWidth).coerceIn(MIN_DAY, MAX_DAY)
        val minute = (firstMinute + y / oldMinuteHeight - (y + dy) / nextMinuteHeight)
            .coerceIn(0.0, (MINUTES_PER_DAY - height / nextMinuteHeight).coerceAtLeast(0.0))
        return withFirstDay(day).copy(zoom = nextZoom, firstMinute = minute, overview = staysOverview)
    }

    fun visibleDates(): List<LocalDate> {
        val start = floor(firstDay).toLong()
        val end = ceil(firstDay + dayCount).toLong()
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
        const val MIN_ZOOM = 0.5
        const val MAX_DENSITY_ZOOM = 2.5
        private const val MINUTES_PER_DAY = 1440.0
        private val SUPPORTED_DAY_COUNTS = setOf(1, 3, 7)
        private val MIN_DAY = LocalDate.MIN.plusDays(14).toEpochDay().toDouble()
        private val MAX_DAY = LocalDate.MAX.minusDays(14).toEpochDay().toDouble()

        fun fit(firstDate: LocalDate, dayCount: Int = 7): TimelineViewport =
            TimelineViewport(firstDate.toEpochDay().toDouble().coerceIn(MIN_DAY, MAX_DAY), dayCount = dayCount)

        fun centered(date: LocalDate, dayCount: Int = 3): TimelineViewport =
            fit(if (dayCount == 7) WeekState.startOfWeek(date) else date.minusDays((dayCount / 2).toLong()), dayCount)
                .copy(selectedDay = date.toEpochDay().toDouble())

        fun withDayCount(viewport: TimelineViewport, dayCount: Int): TimelineViewport {
            require(dayCount in SUPPORTED_DAY_COUNTS)
            val center = floor(viewport.selectedDay)
            val first = if (dayCount == 7) WeekState.startOfWeek(LocalDate.ofEpochDay(center.toLong())).toEpochDay().toDouble()
                else center - dayCount / 2
            return viewport.copy(firstDay = first.coerceIn(MIN_DAY, MAX_DAY), dayCount = dayCount)
        }
    }
}
