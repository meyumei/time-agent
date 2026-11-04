package com.timeagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import com.timeagent.ui.timeline.TimelineDragKind
import com.timeagent.ui.timeline.detectTimelineItemDrag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val MarkerRowHeight = 48.dp
private val MarkerTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private data class PlacedMarker(val item: TimelineItem, val top: Float, val anchor: Float)

@Composable
internal fun TimelinePointMarkers(
    points: List<TimelineItem>,
    zone: ZoneId,
    minuteHeight: Dp,
    firstMinute: Double,
    graphHeight: Dp,
    onSelect: (TimelineItem) -> Unit,
    onDragDelta: (TimelineItem, TimelineDragKind, Offset) -> Unit = { _, _, _ -> },
    onDragEnd: (TimelineItem) -> Unit = {},
    onDragCancel: (TimelineItem) -> Unit = {},
    onAdjust: (TimelineItem, TimelineDragKind, Int) -> Unit = { _, _, _ -> },
    selectedKey: String? = null,
) {
    val groups = markerGroups(points, zone, minuteHeight, firstMinute, graphHeight).flatMap { group ->
        val selected = group.firstOrNull { it.item.layoutKey == selectedKey }
        if (selected == null || group.size == 1) listOf(group)
        else listOf(group.filterNot { it == selected }, listOf(selected))
    }
    var pending by remember { mutableStateOf<List<TimelineItem>?>(null) }

    Box(modifier = Modifier.fillMaxWidth().height(graphHeight)) {
        groups.forEach { group ->
            val first = group.first().item
            val merged = group.size > 1
            Box(Modifier.offset(y = group.first().anchor.dp).fillMaxWidth().height(1.dp)
                .background(MaterialTheme.colorScheme.primary))
            Icon(Icons.Rounded.Flag, null,
                Modifier.offset(y = group.first().anchor.dp - 7.dp).size(14.dp),
                tint = MaterialTheme.colorScheme.primary)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = group.first().top.dp)
                    .height(MarkerRowHeight)
                    .clip(MaterialTheme.shapes.small)
                    .then(if (!merged) Modifier.pointerInput(first.layoutKey) {
                        detectTimelineItemDrag(onStart = { onDragDelta(first, TimelineDragKind.MOVE, Offset.Zero) },
                            onDelta = { onDragDelta(first, TimelineDragKind.MOVE, it) },
                            onEnd = { onDragEnd(first) }, onCancel = { onDragCancel(first) })
                    } else Modifier)
                    .clickable {
                        if (merged) {
                            pending = group.map { it.item }.sortedBy { it.span.start }
                        } else {
                            onSelect(first)
                        }
                    }
                    .semantics {
                        contentDescription = if (merged) "${group.size} 条提醒点" else "提醒点 ${first.title}"
                        if (merged) stateDescription = "${group.size} 条提醒"
                        if (!merged && first.dragReadOnlyReason == null) customActions = listOf(
                            CustomAccessibilityAction("整体提前15分钟") { onAdjust(first, TimelineDragKind.MOVE, -15); true },
                            CustomAccessibilityAction("整体后移15分钟") { onAdjust(first, TimelineDragKind.MOVE, 15); true },
                        )
                    }
                    .padding(start = 16.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = first.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (merged) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "+${group.size - 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    val dialogItems = pending
    if (dialogItems != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            confirmButton = {
                TextButton(onClick = { pending = null }) {
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_close))
                }
            },
            title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_timeline_point_markers_001)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    dialogItems.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable {
                                    pending = null
                                    onSelect(item)
                                }
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = MarkerTimeFormat.format(item.span.start.atZone(zone).toLocalTime()),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            },
        )
    }
}

private fun markerGroups(
    points: List<TimelineItem>,
    zone: ZoneId,
    minuteHeight: Dp,
    firstMinute: Double,
    graphHeight: Dp,
): List<List<PlacedMarker>> {
    val maxTop = (graphHeight.value - MarkerRowHeight.value).coerceAtLeast(0f)
    val placed = points.mapNotNull { item ->
        val local = item.span.start.atZone(zone).toLocalTime()
        val minute = local.hour * 60 + local.minute + local.second / 60.0
        val y = (minuteHeight.value * (minute - firstMinute)).toFloat()
        if (y < 0f || y > graphHeight.value) {
            null
        } else {
            PlacedMarker(item, (y - MarkerRowHeight.value / 2).coerceIn(0f, maxTop), y)
        }
    }.sortedBy { it.top }

    val groups = mutableListOf<MutableList<PlacedMarker>>()
    for (marker in placed) {
        val current = groups.lastOrNull()
        if (current != null && marker.top < current.last().top + MarkerRowHeight.value) {
            current.add(marker)
        } else {
            groups.add(mutableListOf(marker))
        }
    }
    return groups
}
