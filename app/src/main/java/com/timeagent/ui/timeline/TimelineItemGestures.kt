package com.timeagent.ui.timeline

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange

/** A second finger cancels the edit; lifting it must never commit the first finger's preview. */
internal suspend fun PointerInputScope.detectTimelineItemDrag(
    immediate: Boolean = false,
    onStart: () -> Unit = {},
    onDelta: (Offset) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val pointer = if (immediate) down else awaitLongPressOrCancellation(down.id)
        if (pointer == null) return@awaitEachGesture
        var ended = false
        try {
            onStart()
            pointer.consume()
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } > 1) break
                val change = event.changes.firstOrNull { it.id == pointer.id } ?: break
                if (change.isConsumed) break
                val delta = change.positionChange()
                change.consume()
                if (!change.pressed) {
                    onEnd()
                    ended = true
                    break
                }
                if (delta != Offset.Zero) onDelta(delta)
            }
        } finally {
            if (!ended) onCancel()
        }
    }
}
