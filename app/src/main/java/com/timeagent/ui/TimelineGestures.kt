package com.timeagent.ui

import androidx.compose.foundation.gestures.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp

/** Short date drags retain their initial travel once a deliberate gesture is recognized. */
internal suspend fun PointerInputScope.detectTimelineGestures(
    onTap: (Offset) -> Unit,
    onSelectionPress: (Offset) -> Boolean,
    onSelectionStart: (Offset) -> Boolean,
    onSelectionMove: (Offset) -> Unit,
    onSelectionEnd: (Boolean) -> Unit,
    onGestureEnd: (Offset, Boolean) -> Unit,
    onTransform: (Offset, Offset, Float, Boolean) -> Unit,
) {
    val threshold = minOf(viewConfiguration.touchSlop, 4.dp.toPx())
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downWasConsumed = down.isConsumed
        var active = false
        var hadMultiplePointers = false
        var selecting = onSelectionPress(down.position)
        if (selecting) down.consume()
        var waited = selecting
        var selectionMoved = false
        var origin: Offset? = null
        var travel = Offset.Zero
        var totalPan = Offset.Zero
        var ended = false
        var lastTime = down.uptimeMillis
        try { while (true) {
            val remaining = (down.uptimeMillis + viewConfiguration.longPressTimeoutMillis - lastTime).coerceAtLeast(1)
            val event = if (!active && !selecting && !waited) withTimeoutOrNull(remaining) { awaitPointerEvent() } else awaitPointerEvent()
            if (event == null) {
                waited = true
                selecting = !downWasConsumed && onSelectionStart(down.position)
                continue
            }
            lastTime = event.changes.firstOrNull()?.uptimeMillis ?: lastTime
            if (event.changes.count { it.pressed } > 1) {
                hadMultiplePointers = true
                active = true
                waited = true
                if (selecting) { onSelectionEnd(false); selecting = false }
            }
            // A second down may be consumed by a clickable child; the calendar owns multitouch.
            if (event.changes.any { it.isConsumed } && !hadMultiplePointers && !selecting) return@awaitEachGesture
            if (selecting) {
                if (event.changes.count { it.pressed } > 1) { onSelectionEnd(false); selecting = false; active = true }
                else {
                    event.changes.firstOrNull { it.id == down.id }?.let {
                        if (it.positionChanged() && (selectionMoved || (it.position - down.position).getDistance() > threshold)) {
                            selectionMoved = true
                            onSelectionMove(it.position)
                        }
                    }
                    event.changes.forEach { it.consume() }
                    if (event.changes.none { it.pressed }) { onSelectionEnd(true); selecting = false; break }
                    continue
                }
            }
            val focus = event.calculateCentroid(useCurrent = false)
            val pan = event.calculatePan()
            totalPan += pan
            val scale = event.calculateZoom()
            if (focus != Offset.Unspecified && (pan != Offset.Zero || scale != 1f)) {
                if (active) onTransform(focus, pan, scale, !hadMultiplePointers)
                else if (event.changes.count { it.pressed && it.previousPressed } >= 2) {
                    active = true
                    onTransform(focus, pan, scale, false)
                } else {
                    if (origin == null) origin = focus
                    travel += pan
                    if (travel.getDistance() > threshold) {
                        active = true
                        onTransform(requireNotNull(origin), travel, 1f, true)
                    }
                }
            }
            if (active) event.changes.filter { it.positionChanged() }.forEach { it.consume() }
            if (event.changes.none { it.pressed }) {
                if (!active) onTap(down.position)
                else { onGestureEnd(totalPan, !hadMultiplePointers); ended = true }
                break
            }
        } } finally {
            if (selecting) onSelectionEnd(false)
            if (!ended) onGestureEnd(totalPan, false)
        }
    }
}
