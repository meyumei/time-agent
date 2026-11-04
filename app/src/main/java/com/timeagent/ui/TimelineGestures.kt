package com.timeagent.ui

import androidx.compose.foundation.gestures.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import kotlin.math.abs

/** Once touch slop is crossed the dominant axis stays locked for the rest of the gesture. */
internal suspend fun PointerInputScope.detectTimelineGestures(
    released: () -> Boolean,
    onTap: (Offset) -> Unit,
    onSelectionPress: (Offset) -> Boolean,
    onSelectionStart: (Offset) -> Boolean,
    onSelectionMove: (Offset) -> Unit,
    onSelectionEnd: (Boolean) -> Unit,
    onGestureEnd: (Offset, Boolean) -> Unit,
    onTransform: (Offset, Offset, Float, Boolean) -> Unit,
) {
    val threshold = viewConfiguration.touchSlop
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
        var horizontalAxis: Boolean? = null
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
            if (event.changes.any { it.isConsumed } && !hadMultiplePointers && !selecting) {
                // Compose also consumes synthetic cancellation releases; only a native UP commits.
                if (active && event.changes.none { it.pressed } && released()) {
                    onGestureEnd(totalPan, true)
                    ended = true
                }
                return@awaitEachGesture
            }
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
                    if (event.changes.none { it.pressed }) { onSelectionEnd(released()); selecting = false; break }
                    continue
                }
            }
            val focus = event.calculateCentroid(useCurrent = false)
            val pan = event.calculatePan()
            totalPan += pan
            val scale = event.calculateZoom()
            val singlePointer = !hadMultiplePointers
            if (focus != Offset.Unspecified) {
                if (pan != Offset.Zero || scale != 1f) {
                    if (active) {
                        if (singlePointer) {
                            val horizontal = horizontalAxis ?: run {
                                val locked = abs(pan.x) >= abs(pan.y)
                                horizontalAxis = locked
                                locked
                            }
                            onTransform(focus, if (horizontal) Offset(pan.x, 0f) else Offset(0f, pan.y), 1f, true)
                        } else {
                            onTransform(focus, Offset(0f, pan.y), scale, false)
                        }
                    } else if (event.changes.count { it.pressed && it.previousPressed } >= 2) {
                        active = true
                        onTransform(focus, Offset(0f, pan.y), scale, false)
                    } else {
                        if (origin == null) origin = focus
                        travel += pan
                        if (travel.getDistance() > threshold) {
                            active = true
                            val horizontal = abs(travel.x) >= abs(travel.y)
                            horizontalAxis = horizontal
                            onTransform(requireNotNull(origin), if (horizontal) Offset(travel.x, 0f) else Offset(0f, travel.y), 1f, true)
                        }
                    }
                } else if (active && !singlePointer) {
                    // A pinch may reuse a previous single-finger preview; let the canvas snap it before zooming.
                    onTransform(focus, Offset.Zero, scale, false)
                }
            }
            if (active) event.changes.filter { it.positionChanged() }.forEach { it.consume() }
            if (event.changes.none { it.pressed }) {
                if (!active) {
                    if (released()) onTap(down.position)
                } else { onGestureEnd(totalPan, !hadMultiplePointers && released()); ended = true }
                break
            }
        } } finally {
            if (selecting) onSelectionEnd(false)
            if (!ended) onGestureEnd(totalPan, false)
        }
    }
}
