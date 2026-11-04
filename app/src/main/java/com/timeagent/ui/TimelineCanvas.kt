package com.timeagent.ui

import android.view.MotionEvent
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import com.timeagent.ui.timeline.TimelinePreference
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import com.timeagent.core.*
import com.timeagent.data.*
import com.timeagent.ui.timeline.TimelineDayCountSwitch
import com.timeagent.ui.timeline.TimelineDragKind
import com.timeagent.ui.timeline.TimelineDragResult
import com.timeagent.ui.timeline.TimelineFilterButton
import com.timeagent.ui.timeline.TimelineFilterState
import com.timeagent.ui.timeline.minuteDelta
import com.timeagent.ui.timeline.preview
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

@Composable internal fun TimelineCanvas(
    rendered: TimelineRenderWindow, viewport: TimelineViewport, today: LocalDate, now: Instant, zone: ZoneId,
    onSelect: (TimelineItem) -> Unit, onViewport: ((TimelineViewport) -> TimelineViewport) -> Unit, modifier: Modifier = Modifier,
    onCreateAt: ((TimelineSelection) -> Unit)? = null,
    resolve: (TimelineItem) -> TimelineItem = { it },
    remindersByDate: Map<LocalDate, List<ReminderEntity>> = emptyMap(),
    onReminder: (ReminderEntity) -> Unit = {},
    onConflictGroup: (List<TimelineItem>) -> Unit = {},
    allDayItems: List<TimelineItem> = emptyList(),
    onDragCommit: (TimelineItem, TimelineDragResult) -> Unit = { _, _ -> },
) {
    val latestOnViewport by rememberUpdatedState(onViewport)
    val latestCreate by rememberUpdatedState(onCreateAt)
    val placementViewport = rememberUpdatedState(viewport)
    var anchor by remember { mutableStateOf<TimelineSelection?>(null) }
    var dragSelection by remember { mutableStateOf<TimelineSelection?>(null) }
    var pendingSelection by remember { mutableStateOf<TimelineSelection?>(null) }
    var pendingDrag by remember { mutableStateOf<PendingSelectionDrag?>(null) }
    var activeDrag by remember { mutableStateOf<ActiveTimelineDrag?>(null) }
    val released = remember { booleanArrayOf(false) }
    val observeRelease = remember<(MotionEvent) -> Unit> {
        { event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL -> released[0] = false
                MotionEvent.ACTION_UP -> released[0] = true
            }
        }
    }
    val horizontalScroll = rememberScrollState()
    var previousHeight by remember { mutableStateOf<Dp?>(null) }
    BoxWithConstraints(modifier.fillMaxWidth().clipToBounds().testTag("timeline-viewport")) {
        val gutter = 38.dp
        val header = 42.dp
        val labelInset = 7.dp
        val availableWidth = (maxWidth - gutter - 3.dp).coerceAtLeast(1.dp)
        val touchSlop = with(LocalDensity.current) { LocalViewConfiguration.current.touchSlop.toDp() }
        // Ignore week overflow smaller than touch slop; keep 336dp only for genuinely narrow screens.
        val graphWidth = if (viewport.dayCount == 7 && availableWidth < 336.dp - touchSlop) 336.dp else availableWidth
        val maxAllDay = rendered.dates.maxOf { date -> allDayItems.count { it.span.overlaps(TimeSpan(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())) } }
        val allDayHeight = when { maxAllDay == 0 -> 0.dp; maxAllDay == 1 -> 52.dp; else -> 100.dp }
        val bodyHeight = (maxHeight - header - allDayHeight).coerceAtLeast(16.dp)
        val graphHeight = (bodyHeight - labelInset * 2).coerceAtLeast(1.dp)
        val dayWidth = graphWidth / viewport.dayCount
        val minuteHeight = viewport.minuteHeight(graphHeight.value.toDouble()).dp
        val density = LocalDensity.current
        val widthPx = with(density) { graphWidth.toPx().toDouble() }
        val heightPx = with(density) { graphHeight.toPx().toDouble() }
        val dayWidthPx = with(density) { dayWidth.toPx() }
        val minuteHeightPx = with(density) { minuteHeight.toPx() }
        LaunchedEffect(graphHeight, viewport.zoom, viewport.overview) {
            val oldHeight = previousHeight
            previousHeight = graphHeight
            latestOnViewport { current ->
                val maxMinute = (1440.0 - current.visibleMinutes(graphHeight.value.toDouble())).coerceAtLeast(0.0)
                val shift = if (oldHeight != null && !current.overview) {
                    (oldHeight.value - graphHeight.value) / 2.0 / current.minuteHeight(graphHeight.value.toDouble())
                } else 0.0
                current.copy(firstMinute = (current.firstMinute + shift).coerceIn(0.0, maxMinute))
            }
        }
        fun updateDrag(item: TimelineItem, kind: TimelineDragKind, amount: Offset) {
            if (item.kind == ItemKind.COURSE || item.isPoint || item.isAllDay ||
                (item.kind == ItemKind.RECORD && (item.entity as? TimeRecordEntity)?.endAt == null)
            ) return
            val current = activeDrag
            val nextPixels = if (current?.item?.layoutKey == item.layoutKey && current.kind == kind) {
                current.deltaPixels + amount
            } else {
                amount
            }
            val deltaMinutes = minuteDelta(
                nextPixels.x / dayWidthPx * 1440f + nextPixels.y / minuteHeightPx,
                1f,
            )
            activeDrag = ActiveTimelineDrag(
                item,
                kind,
                nextPixels,
                preview(item.span.start.toEpochMilli(), item.span.end.toEpochMilli(), deltaMinutes, kind),
            )
        }
        fun finishDrag(item: TimelineItem) {
            val drag = activeDrag?.takeIf { it.item.layoutKey == item.layoutKey }
            activeDrag = null
            if (drag != null && (drag.result.startAt != item.span.start.toEpochMilli() || drag.result.endAt != item.span.end.toEpochMilli())) {
                onDragCommit(item, drag.result)
            }
        }
        fun cancelDrag(item: TimelineItem) {
            if (activeDrag?.item?.layoutKey == item.layoutKey) activeDrag = null
        }
        val gutterPx = with(density) { gutter.toPx() }
        val topPx = with(density) { (header + allDayHeight + labelInset).toPx() }
        val hitEvent by rememberUpdatedState<(Offset) -> Boolean> { point ->
            rendered.isOccupiedAt(
                placementViewport.value, graphWidth.value.toDouble(), graphHeight.value.toDouble(),
                ((point.x - gutterPx + horizontalScroll.value) / density.density).toDouble(), ((point.y - topPx) / density.density).toDouble(),
            )
        }
        val pick by rememberUpdatedState<(Offset) -> TimelineSelection?> { point ->
            if (point.x < gutterPx || point.x >= gutterPx + with(density) { availableWidth.toPx() }) null
            else TimelineSelection.at(placementViewport.value, (point.x - gutterPx + horizontalScroll.value).toDouble(), (point.y - topPx).toDouble(), widthPx, heightPx, density.density.toDouble())
        }
        val minuteAt by rememberUpdatedState<(Offset) -> Int> { point ->
            val current = placementViewport.value
            (current.firstMinute + (point.y - topPx) / current.minuteHeight(heightPx, density.density.toDouble())).roundToInt().coerceIn(0, 1440)
        }
        val pressSelection by rememberUpdatedState<(Offset) -> Boolean> { point ->
            val pending = pendingSelection
            val hit = pending?.let {
                selectionUiBounds(it, placementViewport.value, graphWidth.value.toDouble(), graphHeight.value.toDouble())
                    .hit(((point.x - gutterPx + horizontalScroll.value) / density.density).toDouble(), ((point.y - topPx) / density.density).toDouble())
            }
            if (latestCreate != null && pending != null && hit != null) {
                pendingDrag = PendingSelectionDrag(pending, minuteAt(point), hit)
                dragSelection = pending
                true
            } else {
                if (pending != null) pendingSelection = null
                false
            }
        }
        fun addPending() {
            val picked = pendingSelection ?: return
            pendingSelection = null; pendingDrag = null; dragSelection = null; anchor = null
            latestCreate?.invoke(picked)
        }
        val dates = rendered.dates
        val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)
        Box(Modifier.fillMaxSize().motionEventSpy(observeRelease).pointerInput(widthPx, heightPx, viewport.dayCount) {
            val flipThresholdPx = 48.dp.toPx()
            val pageWidthPx = (widthPx / viewport.dayCount * if (viewport.dayCount == 7) 7 else 1).toFloat()
            var gestureStartFirstDay: Double? = null
            var gestureStartDayCount = 0
            var gestureRemainingX = 0f
            var gesturePreviewX = 0f
            detectTimelineGestures(
                released = { released[0] },
                onTap = { point -> if (latestCreate != null && !hitEvent(point)) pick(point)?.let { latestCreate?.invoke(it) } },
                onSelectionPress = { pressSelection(it) },
                onSelectionStart = { point ->
                    val slot = if (latestCreate != null && !hitEvent(point)) pick(point) else null
                    pendingSelection = null; pendingDrag = null
                    anchor = slot; dragSelection = slot
                    slot != null
                },
                onSelectionMove = { point ->
                    val draft = pendingDrag
                    if (draft != null) dragSelection = draft.update(minuteAt(point))
                    else anchor?.let { dragSelection = TimelineSelection.range(it.date, it.startMinute, minuteAt(point)) }
                },
                onSelectionEnd = { commit ->
                    val picked = dragSelection
                    anchor = null; dragSelection = null; pendingDrag = null; pendingSelection = null
                    if (commit && picked != null) latestCreate?.invoke(picked)
                },
                onGestureEnd = { _, singlePointer ->
                    val start = gestureStartFirstDay
                    val startDayCount = gestureStartDayCount
                    val horizontal = gestureRemainingX
                    gestureStartFirstDay = null
                    gestureRemainingX = 0f
                    gesturePreviewX = 0f
                    if (start != null) {
                        val anchor = start
                        latestOnViewport { current ->
                            if (current.dayCount != startDayCount) current
                            else {
                                // Cancel or multi-touch only restores the anchor; only a deliberate single-finger swipe pages.
                                val page = if (startDayCount == 7) 7 else 1
                                val shift = when {
                                    !singlePointer -> 0
                                    horizontal <= -flipThresholdPx -> page
                                    horizontal >= flipThresholdPx -> -page
                                    else -> 0
                                }
                                if (shift != 0) horizontalScroll.dispatchRawDelta(-horizontalScroll.value.toFloat())
                                current.withFirstDay(anchor + shift.toDouble())
                            }
                        }
                    }
                },
            ) { centroid, pan, zoom, singlePointer ->
                pendingSelection = null
                latestOnViewport { current ->
                    if (gestureStartFirstDay == null) {
                        gestureStartFirstDay = kotlin.math.round(current.firstDay)
                        gestureStartDayCount = current.dayCount
                        gestureRemainingX = 0f
                        gesturePreviewX = 0f
                    }
                    val start = gestureStartFirstDay
                    val focusX = (centroid.x - gutterPx + horizontalScroll.value).toDouble()
                    val focusY = (centroid.y - topPx).toDouble()
                    if (start == null || current.dayCount != gestureStartDayCount) current
                    else if (!singlePointer) {
                        // Pinching is vertical only. Snap any one-finger date preview before scaling.
                        var next = current
                        if (gestureRemainingX != 0f || gesturePreviewX != 0f) {
                            next = current.withFirstDay(start)
                        }
                        gestureRemainingX = 0f
                        gesturePreviewX = 0f
                        next.transform(0.0, pan.y.toDouble(), zoom.toDouble(), focusX, focusY, widthPx, heightPx, density.density.toDouble())
                    } else {
                        var next = current
                        if (pan.x != 0f) {
                            var delta = pan.x
                            if (current.dayCount == 7) delta += horizontalScroll.dispatchRawDelta(-delta)
                            gestureRemainingX += delta
                            val preview = gestureRemainingX.coerceIn(-pageWidthPx, pageWidthPx)
                            val previewDelta = preview - gesturePreviewX
                            gesturePreviewX = preview
                            if (previewDelta != 0f) {
                                next = next.transform(previewDelta.toDouble(), 0.0, 1.0, focusX, focusY, widthPx, heightPx, density.density.toDouble())
                            }
                        }
                        if (pan.y != 0f) {
                            next = next.transform(0.0, pan.y.toDouble(), 1.0, focusX, focusY, widthPx, heightPx, density.density.toDouble())
                        }
                        next
                    }
                }
            }
        }.semantics { contentDescription = "连续时间轴"; stateDescription = "缩放${(viewport.zoom * 100).roundToInt()}%，左右滑动切换日期，双指上下缩放，点空白直接新建日程，长按拖动选择时段，拖动卡片调整时间" }) {
            Box(Modifier.offset(y = header + allDayHeight).width(gutter).height(bodyHeight).clipToBounds()) {
                TimelineHourLabels(gutter, bodyHeight, labelInset, minuteHeight, viewport.firstMinute)
            }
            if (allDayHeight > 0.dp) {
                Text("全天", Modifier.offset(y = header).width(gutter).padding(top = 12.dp), style = MaterialTheme.typography.labelSmall)
            }
            // A single scroller keeps dates, all-day chips and timed cards aligned; the hour ruler stays fixed.
            Box(Modifier.offset(x = gutter).width(availableWidth).fillMaxHeight().clipToBounds()) {
            Box(Modifier.horizontalScroll(horizontalScroll, enabled = false).width(graphWidth).fillMaxHeight()) {
            Box(Modifier.width(graphWidth).height(header).clipToBounds()) {
                dates.forEach { date -> key(date) {
                    TimelineDateHeader(date, today, dayWidth, header, placementViewport)
                } }
            }
            if (allDayHeight > 0.dp) {
                TimelineAllDayRow(
                    items = allDayItems,
                    dates = dates,
                    viewport = placementViewport,
                    dayWidth = dayWidth,
                    height = allDayHeight,
                    zone = zone,
                    onSelect = onSelect,
                    modifier = Modifier.offset(y = header).width(graphWidth),
                )
            }
            Box(Modifier.offset(y = header + allDayHeight).fillMaxWidth().height(bodyHeight).clipToBounds()) {
                Box(Modifier.offset(y = labelInset).width(graphWidth).height(graphHeight).clipToBounds()) {
                    Canvas(Modifier.matchParentSize()) {
                        val current = placementViewport.value
                        val dayWidthPx = dayWidth.toPx()
                        val minuteHeightPx = minuteHeight.toPx()
                        dates.forEach { date ->
                            val x = (dayWidthPx * (date.toEpochDay() - current.firstDay)).toFloat()
                            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f)
                        }
                        val right = (dayWidthPx * (dates.last().toEpochDay() + 1 - current.firstDay)).toFloat()
                        drawLine(gridColor, Offset(right, 0f), Offset(right, size.height), 1f)
                        repeat(25) { hour ->
                            val y = (minuteHeightPx * (hour * 60 - current.firstMinute)).toFloat()
                            drawLine(gridColor.copy(alpha = if (hour % 6 == 0) .65f else .3f), Offset(0f, y), Offset(size.width, y), 1f)
                        }
                    }
                    dates.forEach { date -> key(date) {
                        TimelineDay(
                            rendered.days.getValue(date), placementViewport, dayWidth, minuteHeight, graphHeight,
                            viewport.zoom, viewport.firstMinute, today, now, zone, rendered.conflictingKeys, onSelect,
                            resolve = resolve, points = rendered.items.filter { it.isPoint && it.span.start.atZone(zone).toLocalDate() == date }, reminders = remindersByDate[date].orEmpty(), onReminder = onReminder, onConflictGroup = onConflictGroup,
                            activeDrag = activeDrag, onDragDelta = ::updateDrag, onDragEnd = ::finishDrag, onDragCancel = ::cancelDrag,
                        )
                    } }
                    (dragSelection ?: pendingSelection)?.takeIf { it.date in dates }?.let { selected ->
                        TimelineSelectionOverlay(
                            selectionUiBounds(selected, viewport, graphWidth.value.toDouble(), graphHeight.value.toDouble()), selected,
                            pending = pendingSelection != null, onAdd = ::addPending,
                        )
                    }
                }
            }
            }
            }
            activeDrag?.let { drag ->
                Text(
                    "预览 ${Instant.ofEpochMilli(drag.result.startAt).label(zone)} – ${Instant.ofEpochMilli(drag.result.endAt).label(zone)}",
                    Modifier.align(Alignment.TopCenter).padding(top = 2.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (rendered.items.isEmpty()) Column(
                Modifier.align(Alignment.Center).background(MaterialTheme.colorScheme.surface.copy(alpha = .95f), RoundedCornerShape(12.dp)).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("今天还没有安排", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = {
                    val date = if (today in rendered.dates) today else rendered.dates[rendered.dates.size / 2]
                    val minute = (now.atZone(zone).hour * 60 / 15 * 15).coerceAtMost(1380)
                    latestCreate?.invoke(TimelineSelection(date, minute, minute + 60))
                }, enabled = latestCreate != null) { Text("添加日程") }
            }
        }
    }
}

/** Read fractional x only during placement so a date drag can skip its text composition. */
@Composable private fun TimelineDateHeader(date: LocalDate, today: LocalDate, dayWidth: Dp, header: Dp, viewport: State<TimelineViewport>) {
    val isToday = date == today
    Column(Modifier.offset {
        IntOffset((dayWidth.toPx() * (date.toEpochDay() - viewport.value.firstDay)).roundToInt(), 0)
    }.width(dayWidth).height(header).semantics { contentDescription = "$date ${DayNames[date.dayOfWeek.value - 1]}" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(DayNames[date.dayOfWeek.value - 1], fontSize = 10.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Surface(color = if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent, shape = RoundedCornerShape(7.dp)) {
            Text(date.dayOfMonth.toString(), Modifier.padding(horizontal = 7.dp, vertical = 2.dp), fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, color = if (isToday) Color.White else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable private fun TimelineHourLabels(gutter: Dp, bodyHeight: Dp, labelInset: Dp, minuteHeight: Dp, firstMinute: Double) {
    repeat(25) { hour ->
        val y = labelInset + minuteHeight * (hour * 60 - firstMinute).toFloat()
        if (y >= 0.dp && y <= bodyHeight) {
            Text("%02d:00".format(hour), Modifier.offset(y = (y - 7.dp).coerceIn(0.dp, bodyHeight - 14.dp)).width(gutter - 5.dp), fontSize = 9.sp, lineHeight = 12.sp, textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A complete day's lane assignment stays fixed while its parent moves horizontally. */
@Composable private fun TimelineDay(
    day: TimelineDayLayout, viewport: State<TimelineViewport>, dayWidth: Dp, minuteHeight: Dp, graphHeight: Dp,
    zoom: Double, firstMinute: Double, today: LocalDate, now: Instant, zone: ZoneId, conflictingKeys: Set<String>,
    onSelect: (TimelineItem) -> Unit,
    resolve: (TimelineItem) -> TimelineItem,
    points: List<TimelineItem>,
    reminders: List<ReminderEntity>,
    onReminder: (ReminderEntity) -> Unit,
    onConflictGroup: (List<TimelineItem>) -> Unit,
    activeDrag: ActiveTimelineDrag?,
    onDragDelta: (TimelineItem, TimelineDragKind, Offset) -> Unit,
    onDragEnd: (TimelineItem) -> Unit,
    onDragCancel: (TimelineItem) -> Unit,
) {
    val visibleMinutes = graphHeight.value / minuteHeight.value.toDouble()
    Box(Modifier.offset {
        IntOffset((dayWidth.toPx() * (day.date.toEpochDay() - viewport.value.firstDay)).roundToInt(), 0)
    }.width(dayWidth).height(graphHeight)) {
        day.conflictGroups.filter { viewport.value.dayCount != 7 }.forEach { group ->
            val bounds = day.groupBounds(group, dayWidth.value.toDouble(), minuteHeight.value.toDouble(), firstMinute, visibleMinutes)
            if (bounds != null) {
                TimelineConflictGroupCard(
                    items = group.items,
                    zone = zone,
                    modifier = Modifier
                        .offset(x = bounds.x.dp, y = bounds.y.dp)
                        .width(bounds.width.dp)
                        .height(bounds.height.dp)
                        .testTag("timeline-conflict-group"),
                    cardSize = DpSize(bounds.width.dp, bounds.height.dp),
                    onClick = { onConflictGroup(group.items.map(resolve)) },
                )
            }
        }
        day.events.sortedBy { if (it.item.kind == ItemKind.RECORD) 0 else 1 }.forEach { event -> key(event.item.kind, event.item.id) {
            val grouped = viewport.value.dayCount != 7 && event.item.kind != ItemKind.RECORD && event.item.layoutKey in day.groupedKeys
            if (!grouped) {
                val live = resolve(event.item)
                val dragging = activeDrag?.takeIf { it.item.layoutKey == live.layoutKey }
                val display = dragging?.let {
                    live.copy(span = TimeSpan(Instant.ofEpochMilli(it.result.startAt), Instant.ofEpochMilli(it.result.endAt)))
                } ?: live
                val displayEvent = if (dragging == null) event else {
                    val segment = TimelineLayout.split(display.span, zone).firstOrNull { it.date == day.date } ?: event.segment
                    event.copy(item = display, segment = segment)
                }
                val bounds = day.bounds(displayEvent, dayWidth.value.toDouble(), minuteHeight.value.toDouble(), firstMinute, visibleMinutes)
                if (bounds != null) {
                    TimelineEventCard(
                        display, zone, zoom, display.layoutKey in conflictingKeys,
                        Modifier.offset(x = bounds.x.dp, y = bounds.y.dp).width(bounds.width.dp).height(bounds.height.dp),
                        cardSize = DpSize(bounds.width.dp, bounds.height.dp),
                        overlayPlan = viewport.value.dayCount == 7,
                        compactView = viewport.value.dayCount == 7,
                        onClick = {
                            val overlapping = if (viewport.value.dayCount == 7) day.events
                                .filter { it.item.span.overlaps(live.span) }.map { resolve(it.item) }
                                .distinctBy { it.layoutKey } else listOf(live)
                            if (overlapping.size > 1) onConflictGroup(overlapping) else onSelect(live)
                        },
                        onDragDelta = { kind, amount -> onDragDelta(live, kind, amount) },
                        onDragEnd = { onDragEnd(live) },
                        onDragCancel = { onDragCancel(live) },
                    )
                }
            }
        } }
        TimelinePointMarkers(points, zone, minuteHeight, firstMinute, graphHeight, onSelect)
        reminders.forEach { reminder ->
            val minute = Instant.ofEpochMilli(reminder.remindAt).atZone(zone).toLocalTime().toSecondOfDay() / 60.0
            val y = minuteHeight * (minute - firstMinute).toFloat()
            if (y in 0.dp..graphHeight) {
                Box(
                    Modifier.offset(x = (dayWidth - 48.dp).coerceAtLeast(0.dp), y = y - 24.dp).size(48.dp)
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) { onReminder(reminder) }
                        .padding(18.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary)
                        .semantics { contentDescription = "提醒 ${reminder.title}" }
                        .testTag("timeline-reminder-${reminder.id}"),
                )
            }
        }
        if (day.date == today) {
            val minute = now.atZone(zone).toLocalTime().toSecondOfDay() / 60.0
            val y = minuteHeight * (minute - firstMinute).toFloat()
            if (y in 0.dp..graphHeight) Box(Modifier.offset(y = y).width(dayWidth).height(1.dp).background(MaterialTheme.colorScheme.error))
        }
    }
}

@Composable fun EmptyHint(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(32.dp), tint = Indigo)
        Spacer(Modifier.height(12.dp)); Text(title, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp)); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
