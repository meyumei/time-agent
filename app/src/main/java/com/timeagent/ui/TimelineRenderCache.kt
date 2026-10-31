package com.timeagent.ui

import com.timeagent.core.DaySegment
import com.timeagent.core.LanePlacement
import com.timeagent.core.TimeSpan
import com.timeagent.core.TimelineLayout
import com.timeagent.core.TimelineViewport
import com.timeagent.data.LocalSnapshot
import com.timeagent.data.TimeRecordEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor

internal val TimelineItem.layoutKey: String get() = "${kind}:$id"

internal data class TimelineEventLayout(
    val item: TimelineItem,
    val segment: DaySegment,
    val lane: LanePlacement,
    val conflicting: Boolean,
    val splitActualLane: Boolean = false,
)

internal data class TimelineConflictGroupLayout(
    val items: List<TimelineItem>,
    val startMinute: Double,
    val endMinute: Double,
    val splitActualLane: Boolean,
)

internal data class TimelineDayLayout(
    val date: LocalDate,
    val window: TimeSpan,
    val events: List<TimelineEventLayout>,
    val conflicts: List<TimelineConflict>,
    val conflictSegments: List<DaySegment>,
    val conflictGroups: List<TimelineConflictGroupLayout>,
) {
    val groupedKeys: Set<String> = conflictGroups.flatMap { it.items }.map { it.layoutKey }.toSet()

    /** The summary occupies its full painted rectangle, including gaps in former lanes. */
    fun groupBounds(group: TimelineConflictGroupLayout, dayWidth: Double, minuteHeight: Double, firstMinute: Double, visibleMinutes: Double): TimelineCardBounds? {
        val start = maxOf(group.startMinute, firstMinute)
        val end = minOf(group.endMinute, firstMinute + visibleMinutes)
        if (end <= start) return null
        val actualWidth = if (group.splitActualLane) (dayWidth * .18).coerceAtLeast(7.0).coerceAtMost(dayWidth * .3) else 0.0
        return TimelineCardBounds(
            x = 1.0,
            y = minuteHeight * (start - firstMinute),
            width = (dayWidth - actualWidth - 2.0).coerceAtLeast(.5),
            height = (minuteHeight * (end - start)).coerceAtLeast(3.0),
        )
    }

    /** Coordinates are dp, so the card gaps and minimum height match the UI. */
    fun bounds(event: TimelineEventLayout, dayWidth: Double, minuteHeight: Double, firstMinute: Double, visibleMinutes: Double): TimelineCardBounds? {
        val start = maxOf(event.segment.startMinute.toDouble(), firstMinute)
        val end = minOf(event.segment.endMinute.toDouble(), firstMinute + visibleMinutes)
        if (end <= start) return null
        val actualWidth = if (event.splitActualLane) (dayWidth * .18).coerceAtLeast(7.0).coerceAtMost(dayWidth * .3) else 0.0
        val scheduledWidth = dayWidth - actualWidth
        val actual = event.item.kind == ItemKind.RECORD
        val regionWidth = if (!event.splitActualLane) dayWidth else if (actual) actualWidth else scheduledWidth
        val laneWidth = regionWidth / event.lane.laneCount
        return TimelineCardBounds(
            x = (if (actual && event.splitActualLane) scheduledWidth else 0.0) + laneWidth * event.lane.lane + 1.0,
            y = minuteHeight * (start - firstMinute),
            width = (laneWidth - 2.0).coerceAtLeast(.5),
            height = (minuteHeight * (end - start)).coerceAtLeast(3.0),
        )
    }
}

internal data class TimelineCardBounds(val x: Double, val y: Double, val width: Double, val height: Double) {
    fun contains(pointX: Double, pointY: Double): Boolean = pointX >= x && pointX < x + width && pointY >= y && pointY < y + height
}

/** Live source lookup is independent of immutable local-day geometry. */
internal class TimelineItemSource {
    private val byKey = HashMap<String, TimelineItem>()
    var revision: Long = 0
        private set

    fun replace(items: List<TimelineItem>) {
        byKey.clear()
        items.forEach { byKey[it.layoutKey] = it }
        revision++
    }

    fun update(item: TimelineItem) {
        byKey[item.layoutKey] = item
        revision++
    }

    fun current(item: TimelineItem): TimelineItem = byKey[item.layoutKey] ?: item
}

internal data class TimelineRenderWindow(
    val dates: List<LocalDate>,
    val days: Map<LocalDate, TimelineDayLayout>,
    private val preparedItems: List<TimelineItem>,
    val conflicts: List<TimelineConflict>,
    private val itemSource: TimelineItemSource? = null,
) {
    val conflictingKeys: Set<String> = conflicts.flatMap { listOf(it.first.layoutKey, it.second.layoutKey) }.toSet()
    private var resolvedRevision = Long.MIN_VALUE
    private var resolvedItems = preparedItems

    val items: List<TimelineItem>
        get() {
            val revision = itemSource?.revision ?: 0L
            if (resolvedRevision != revision) {
                resolvedItems = preparedItems.map(::currentItem)
                resolvedRevision = revision
            }
            return resolvedItems
        }

    /** Resolve refreshed source data while retaining the cached local-day geometry. */
    fun currentItem(item: TimelineItem): TimelineItem = itemSource?.current(item) ?: item

    fun isOccupiedAt(viewport: TimelineViewport, width: Double, height: Double, x: Double, y: Double): Boolean {
        if (width <= 0.0 || height <= 0.0 || x < 0.0 || y < 0.0 || x >= width || y >= height) return false
        val dayWidth = viewport.dayWidth(width)
        val date = LocalDate.ofEpochDay(floor(viewport.firstDay + x / dayWidth).toLong())
        val day = days[date] ?: return false
        val dayX = dayWidth * (date.toEpochDay() - viewport.firstDay)
        return day.conflictGroups.any { group ->
            day.groupBounds(group, dayWidth, viewport.minuteHeight(height), viewport.firstMinute, 1440.0 / viewport.zoom)
                ?.contains(x - dayX, y) == true
        } || itemAt(viewport, width, height, x, y) != null
    }

    /** Resolve from the live viewport at touch time, rather than keeping stale screen rectangles. */
    fun itemAt(viewport: TimelineViewport, width: Double, height: Double, x: Double, y: Double): TimelineItem? {
        if (width <= 0.0 || height <= 0.0 || x < 0.0 || y < 0.0 || x >= width || y >= height) return null
        val dayWidth = viewport.dayWidth(width)
        val date = LocalDate.ofEpochDay(floor(viewport.firstDay + x / dayWidth).toLong())
        val day = days[date] ?: return null
        val dayX = dayWidth * (date.toEpochDay() - viewport.firstDay)
        return day.events.firstOrNull { event ->
            day.bounds(event, dayWidth, viewport.minuteHeight(height), viewport.firstMinute, 1440.0 / viewport.zoom)
                ?.contains(x - dayX, y) == true
        }?.item?.let(::currentItem)
    }
}

/**
 * Source entities are immutable snapshots. Prepare them once, then retain a bounded set of
 * complete local-day layouts. Fractional scrolling changes placement, never these layouts.
 */
internal class TimelineRenderCache {
    private var source: LocalSnapshot? = null
    private var sourceItems: List<TimelineItem> = emptyList()
    private val itemSource = TimelineItemSource()
    private var runningIndices: List<Int> = emptyList()
    private var sourceNow: Instant? = null
    private var localZone: ZoneId? = null
    private val dayCache = LinkedHashMap<LocalDate, TimelineDayLayout>(32, .75f, true)
    private var renderedWindow: TimelineRenderWindow? = null

    fun window(data: LocalSnapshot, now: Instant, viewport: TimelineViewport, zone: ZoneId): TimelineRenderWindow {
        updateSource(data, now)
        if (localZone != zone) {
            localZone = zone
            dayCache.clear()
            renderedWindow = null
        }
        val firstDay = floor(viewport.firstDay).toLong()
        val lastDay = ceil(viewport.firstDay + 7.0 / viewport.zoom).toLong() - 1
        renderedWindow?.let { current ->
            if (current.dates.first().toEpochDay() == firstDay && current.dates.last().toEpochDay() == lastDay) return current
        }
        val dates = viewport.visibleDates()
        val days = dates.associateWith { date ->
            dayCache[date] ?: buildDay(date, zone).also { day ->
                dayCache[date] = day
                // At most eight days are visible; retain nearby days without keeping a semester of cards.
                while (dayCache.size > 32) dayCache.remove(dayCache.keys.first())
            }
        }
        val items = days.values.flatMap { day -> day.events.map { it.item } }.distinctBy { it.layoutKey }.sortedBy { it.span.start }
        val conflicts = days.values.flatMap { it.conflicts }.distinctBy { it.first.layoutKey to it.second.layoutKey }
        return TimelineRenderWindow(dates, days, items, conflicts, itemSource).also { renderedWindow = it }
    }

    private fun updateSource(data: LocalSnapshot, now: Instant) {
        val previous = source
        val sameEntities = previous != null && previous.tasks === data.tasks && previous.plans === data.plans &&
            previous.records === data.records && previous.courses === data.courses && previous.occurrences === data.occurrences
        source = data
        if (!sameEntities) {
            sourceItems = data.timeline(now).sortedBy { it.span.start }
            itemSource.replace(sourceItems)
            runningIndices = sourceItems.indices.filter { index ->
                val item = sourceItems[index]
                item.kind == ItemKind.RECORD && (item.entity as? TimeRecordEntity)?.endAt == null
            }
            sourceNow = now
            dayCache.clear()
            renderedWindow = null
        } else if (runningIndices.isNotEmpty() && sourceNow != now) {
            val updated = sourceItems.toMutableList()
            val spanChanges = ArrayList<Pair<TimeSpan, TimeSpan>>()
            runningIndices.forEach { index ->
                val item = sourceItems[index]
                val span = TimeSpan(item.span.start, maxOf(item.span.start.plusMillis(1), now))
                if (span != item.span) {
                    spanChanges += item.span to span
                    val current = item.copy(span = span)
                    updated[index] = current
                    itemSource.update(current)
                }
            }
            if (spanChanges.isNotEmpty()) {
                sourceItems = updated
                val affectedDates = dayCache.values.filter { day ->
                    spanChanges.any { (oldSpan, newSpan) -> oldSpan.clip(day.window) != newSpan.clip(day.window) }
                }.map { it.date }
                affectedDates.forEach(dayCache::remove)
                if (renderedWindow?.dates?.any { it in affectedDates } == true) renderedWindow = null
            }
            sourceNow = now
        }
    }

    private fun buildDay(date: LocalDate, zone: ZoneId): TimelineDayLayout {
        val window = TimeSpan(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
        val visible = sourceItems.filter { it.span.overlaps(window) }
        val conflicts = timelineConflicts(visible)
        val conflicting = conflicts.flatMap { listOf(it.first.layoutKey, it.second.layoutKey) }.toSet()
        val scheduled = visible.filter { it.kind != ItemKind.RECORD }
        val actual = visible.filter { it.kind == ItemKind.RECORD }
        fun lanes(items: List<TimelineItem>): Map<String, LanePlacement> =
            TimelineLayout.lanes(items.map { it.layoutKey to requireNotNull(it.span.clip(window)) }).associateBy { it.id }
        val scheduledLanes = lanes(scheduled)
        val actualLanes = lanes(actual)
        // Reserve the comparison strip only inside connected overlaps containing both kinds.
        // Unrelated morning sleep and afternoon courses retain the full width of their day.
        val mixedKeys = mutableSetOf<String>()
        val group = mutableListOf<TimelineItem>()
        var groupEnd: Instant? = null
        fun flushGroup() {
            if (group.any { it.kind == ItemKind.RECORD } && group.any { it.kind != ItemKind.RECORD })
                mixedKeys += group.map { it.layoutKey }
            group.clear()
        }
        visible.forEach { item ->
            if (groupEnd != null && item.span.start >= groupEnd) flushGroup()
            groupEnd = if (group.isEmpty()) item.span.end else maxOf(requireNotNull(groupEnd), item.span.end)
            group += item
        }
        flushGroup()
        val events = visible.map { item ->
            // Clip before splitting: a decades-long timer still creates just this one local day.
            val segment = TimelineLayout.split(requireNotNull(item.span.clip(window)), zone).single()
            val lane = (if (item.kind == ItemKind.RECORD) actualLanes else scheduledLanes).getValue(item.layoutKey)
            TimelineEventLayout(item, segment, lane, item.layoutKey in conflicting, item.layoutKey in mixedKeys)
        }
        val conflictSegments = conflicts.flatMap { conflict -> TimelineLayout.split(requireNotNull(conflict.overlap.clip(window)), zone) }
        val eventsByKey = events.associateBy { it.item.layoutKey }
        fun minuteOf(instant: Instant): Double = when {
            instant <= window.start -> 0.0
            instant >= window.end -> 1440.0
            else -> instant.atZone(zone).toLocalTime().toSecondOfDay() / 60.0
        }
        val groups = timelineConflictGroups(visible).map { group ->
            val members = group.map { eventsByKey.getValue(it.layoutKey) }
            TimelineConflictGroupLayout(
                group, group.minOf { minuteOf(it.span.start) }, group.maxOf { minuteOf(it.span.end) },
                members.any { it.splitActualLane },
            )
        }
        return TimelineDayLayout(date, window, events, conflicts, conflictSegments, groups)
    }
}
