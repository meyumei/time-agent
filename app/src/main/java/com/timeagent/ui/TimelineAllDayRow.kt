package com.timeagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import com.timeagent.ui.timeline.TimelineDragKind
import com.timeagent.ui.timeline.TimelineDragResult
import com.timeagent.ui.timeline.detectTimelineItemDrag
import com.timeagent.ui.timeline.previewCalendarDrag
import java.time.LocalDate
import java.time.ZoneId
import com.timeagent.core.TimeSpan

@Composable
internal fun TimelineAllDayRow(
    items: List<TimelineItem>,
    dates: List<LocalDate>,
    viewport: State<com.timeagent.core.TimelineViewport>,
    dayWidth: Dp,
    height: Dp,
    zone: ZoneId,
    onSelect: (TimelineItem) -> Unit,
    modifier: Modifier = Modifier,
    selectedKey: String? = null,
    activeDrag: ActiveTimelineDrag? = null,
    onDragDelta: (TimelineItem, TimelineDragKind, Offset) -> Unit = { _, _, _ -> },
    onDragEnd: (TimelineItem) -> Unit = {},
    onDragCancel: (TimelineItem) -> Unit = {},
    onAdjust: (TimelineItem, TimelineDragResult) -> Unit = { _, _ -> },
) {
    var expandedDate by remember { mutableStateOf<LocalDate?>(null) }
    Box(modifier.fillMaxWidth().height(height).clipToBounds()) {
        dates.forEach { date ->
            val dayItems = items.filter { item ->
                item.span.overlaps(
                    TimeSpan(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant()),
                )
            }
            Column(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset((dayWidth.toPx() * (date.toEpochDay() - viewport.value.firstDay)).toInt(), 0) }
                    .width(dayWidth)
                    .padding(horizontal = 2.dp, vertical = 2.dp),
            ) {
                val selected = dayItems.firstOrNull { it.layoutKey == selectedKey }
                listOfNotNull(selected ?: dayItems.firstOrNull()).forEach { item ->
                    val preview = activeDrag?.takeIf { it.item.layoutKey == item.layoutKey }
                    Surface(
                        color = if (item.layoutKey == selectedKey) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.secondaryContainer,
                        onClick = { onSelect(item) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .fillMaxWidth()
                            .pointerInput(item.layoutKey) {
                                detectTimelineItemDrag(onStart = { onDragDelta(item, TimelineDragKind.MOVE, Offset.Zero) },
                                    onDelta = { onDragDelta(item, TimelineDragKind.MOVE, it) },
                                    onEnd = { onDragEnd(item) }, onCancel = { onDragCancel(item) })
                            }
                            .semantics {
                                if (item.dragReadOnlyReason == null) customActions = listOf(
                                    CustomAccessibilityAction("整体后移一天") {
                                        onAdjust(item, previewCalendarDrag(item.span.start.toEpochMilli(), item.span.end.toEpochMilli(),
                                            0, 1, TimelineDragKind.MOVE, zone, 15, allDay = true)); true
                                    },
                                    CustomAccessibilityAction("结束延后一天") {
                                        onAdjust(item, previewCalendarDrag(item.span.start.toEpochMilli(), item.span.end.toEpochMilli(),
                                            0, 1, TimelineDragKind.RESIZE_END, zone, 15, allDay = true)); true
                                    },
                                )
                            }
                            .clip(MaterialTheme.shapes.small),
                    ) {
                        Box {
                            Text(if (preview == null) item.title else "${item.title} · ${java.time.Instant.ofEpochMilli(preview.result.startAt).atZone(zone).toLocalDate()}",
                                Modifier.padding(horizontal = 6.dp, vertical = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (dayItems.size > 1) TextButton(onClick = { expandedDate = date }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("+${dayItems.size - 1} 项全天")
                }
            }
        }
        items.firstOrNull { it.layoutKey == selectedKey && it.dragReadOnlyReason == null }?.let { item ->
            // Separate 48dp targets remain usable even when a seven-day column is only 48dp wide.
            Row(Modifier.align(Alignment.BottomCenter).background(MaterialTheme.colorScheme.surface)) {
                AllDayHandle(item, TimelineDragKind.RESIZE_START, Modifier,
                    onDragDelta, onDragEnd, onDragCancel)
                AllDayHandle(item, TimelineDragKind.RESIZE_END, Modifier,
                    onDragDelta, onDragEnd, onDragCancel)
            }
        }
    }
    expandedDate?.let { date ->
        AlertDialog(
            onDismissRequest = { expandedDate = null },
            title = { Text("$date · 全天") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    items.filter { it.span.overlaps(TimeSpan(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())) }
                        .forEach { item ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                                expandedDate = null
                                onSelect(item)
                            }.padding(12.dp)) { Text(item.title) }
                        }
                }
            },
            confirmButton = { TextButton(onClick = { expandedDate = null }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_close)) } },
        )
    }
}

@Composable
private fun AllDayHandle(item: TimelineItem, kind: TimelineDragKind, modifier: Modifier,
    onDelta: (TimelineItem, TimelineDragKind, Offset) -> Unit,
    onEnd: (TimelineItem) -> Unit, onCancel: (TimelineItem) -> Unit,
) {
    Box(modifier.size(48.dp).pointerInput(item.layoutKey, kind) {
        detectTimelineItemDrag(immediate = true, onStart = { onDelta(item, kind, Offset.Zero) },
            onDelta = { onDelta(item, kind, it) }, onEnd = { onEnd(item) }, onCancel = { onCancel(item) })
    }.semantics { contentDescription = if (kind == TimelineDragKind.RESIZE_START) "调整全天开始日期" else "调整全天结束日期" },
        contentAlignment = Alignment.Center) {
        Text(androidx.compose.ui.res.stringResource(if (kind == TimelineDragKind.RESIZE_START)
            com.timeagent.R.string.timeline_all_day_start else com.timeagent.R.string.timeline_all_day_end),
            color = MaterialTheme.colorScheme.primary)
    }
}
