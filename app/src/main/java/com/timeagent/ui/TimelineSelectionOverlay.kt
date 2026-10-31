package com.timeagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timeagent.core.TimelineSelection
import com.timeagent.core.TimelineViewport
import kotlin.math.roundToInt

internal enum class PendingSelectionPart { MOVE, START, END }

internal data class PendingSelectionDrag(val original: TimelineSelection, val pressedMinute: Int, val part: PendingSelectionPart) {
    fun update(minute: Int): TimelineSelection {
        val delta = ((minute - pressedMinute) / 15.0).roundToInt() * 15
        return when (part) {
            PendingSelectionPart.MOVE -> {
                val duration = original.endMinute - original.startMinute
                val start = (original.startMinute + delta).coerceIn(0, 1440 - duration)
                original.copy(startMinute = start, endMinute = start + duration)
            }
            PendingSelectionPart.START -> original.copy(startMinute = (original.startMinute + delta).coerceIn(0, original.endMinute - 15))
            PendingSelectionPart.END -> original.copy(endMinute = (original.endMinute + delta).coerceIn(original.startMinute + 15, 1440))
        }
    }
}

internal data class TimelineSelectionUiBounds(val body: TimelineCardBounds, val handle: TimelineCardBounds) {
    fun hit(x: Double, y: Double): PendingSelectionPart? = when {
        x >= handle.x - 6 && x < handle.x + handle.width + 6 && y >= handle.y - 4 && y < handle.y + handle.height + 4 ->
            if (y < handle.y + handle.height / 2) PendingSelectionPart.START else PendingSelectionPart.END
        body.contains(x, y) -> PendingSelectionPart.MOVE
        else -> null
    }
}

internal fun selectionUiBounds(selection: TimelineSelection, viewport: TimelineViewport, width: Double, height: Double): TimelineSelectionUiBounds {
    val dayWidth = viewport.dayWidth(width)
    val minuteHeight = viewport.minuteHeight(height)
    val x = dayWidth * (selection.date.toEpochDay() - viewport.firstDay) + 1
    val y = minuteHeight * (selection.startMinute - viewport.firstMinute)
    val body = TimelineCardBounds(x, y, (dayWidth - 2).coerceAtLeast(1.0), (minuteHeight * (selection.endMinute - selection.startMinute)).coerceAtLeast(1.0))
    val handleX = if (x + dayWidth + 24 <= width) x + dayWidth + 2 else (x - 25).coerceAtLeast(0.0)
    val handleY = (y + body.height / 2 - 18).coerceIn(0.0, (height - 36).coerceAtLeast(0.0))
    return TimelineSelectionUiBounds(body, TimelineCardBounds(handleX, handleY, 22.0, 36.0))
}

/** Shared geometry keeps the white outline, resize handles and touch routing aligned. */
@Composable internal fun TimelineSelectionOverlay(bounds: TimelineSelectionUiBounds, selection: TimelineSelection, pending: Boolean, onAdd: () -> Unit) {
    val body = bounds.body
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.offset(x = body.x.dp, y = body.y.dp).width(body.width.dp).height(body.height.dp)
            .clip(shape).background(Color(0xFFFF2C49).copy(alpha = .50f)).border(2.dp, Color.White.copy(alpha = .85f), shape)
            .testTag(if (pending) "timeline-pending-selection" else "timeline-range-selection")
            .semantics { contentDescription = "已选 ${minuteLabel(selection.startMinute)}至${minuteLabel(selection.endMinute)}" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().testTag("timeline-pending-add").semantics {
            role = Role.Button
            contentDescription = "添加选中时段"
            onClick { onAdd(); true }
        }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, null, Modifier.size(17.dp), tint = Color.White)
        }
    }
    val labelY = if (body.y >= 16) body.y - 15 else body.y + body.height + 2
    Text("${minuteLabel(selection.startMinute)}–${minuteLabel(selection.endMinute)}",
        Modifier.offset(x = body.x.dp, y = labelY.dp).clip(RoundedCornerShape(4.dp))
            .background(Color(0xFFE86C8D)).padding(horizontal = 4.dp, vertical = 1.dp),
        color = Color.White, fontSize = 8.sp, lineHeight = 10.sp, maxLines = 1)
    if (pending) {
        val handle = bounds.handle
        Column(Modifier.offset(x = handle.x.dp, y = handle.y.dp).width(handle.width.dp).height(handle.height.dp)
            .clip(RoundedCornerShape(10.dp)).background(Color.White), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().weight(1f).testTag("timeline-selection-start-handle")
                .semantics { contentDescription = "拖动调整开始时间" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.KeyboardArrowUp, null, Modifier.size(15.dp), tint = Color(0xFF64717D))
            }
            Box(Modifier.fillMaxWidth().weight(1f).testTag("timeline-selection-end-handle")
                .semantics { contentDescription = "拖动调整结束时间" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(15.dp), tint = Color(0xFF64717D))
            }
        }
    }
}

private fun minuteLabel(minute: Int): String = "${(minute / 60).toString().padStart(2, '0')}:${(minute % 60).toString().padStart(2, '0')}"
