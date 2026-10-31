package com.timeagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Recording starts only after a deliberate hold; a short tap never starts the microphone. */
@Composable
fun HoldToTalkButton(
    enabled: Boolean = true, processing: Boolean = false,
    onStart: () -> Boolean, onFinish: () -> Unit, onCancel: () -> Unit,
    onTap: () -> Unit = {},
    onTextEntry: () -> Unit = onTap,
    resetSignal: Int = 0,
    preparing: Boolean = false,
    starting: Boolean = false,
    resultReady: Boolean = false,
) {
    val start by rememberUpdatedState(onStart)
    val finish by rememberUpdatedState(onFinish)
    val cancel by rememberUpdatedState(onCancel)
    val tap by rememberUpdatedState(onTap)
    val textEntry by rememberUpdatedState(onTextEntry)
    var pressed by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var accessibleHeld by remember { mutableStateOf(false) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val speaking = pressed || accessibleHeld
    LaunchedEffect(resetSignal, enabled) { if (accessibleHeld) { accessibleHeld = false; cancel() } }
    DisposableEffect(Unit) { onDispose { if (accessibleHeld) { accessibleHeld = false; cancel() } } }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(54.dp).onGloballyPositioned { coordinates = it }.clip(CircleShape)
            .background(if (cancelling) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            .border(if (speaking) 3.dp else 1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = if (speaking) .5f else .12f), CircleShape)
            .semantics {
                contentDescription = "按住说话"
                role = Role.Button
                stateDescription = when { cancelling -> "松开取消"; resultReady && accessibleHeld -> "识别已完成，点击查看或选择取消"; resultReady && pressed -> "识别已完成，松开查看，上滑取消"; preparing -> "正在准备离线中文识别"; starting -> "正在开启麦克风"; processing && pressed -> "录音已结束，正在识别；松开后查看"; processing -> "正在识别"; accessibleHeld -> "正在聆听，点击结束或选择取消语音输入"; pressed -> "正在聆听，松开发送，上滑取消"; else -> "长按开始，松开识别" }
                if (!enabled) disabled()
                onLongClick("开始语音输入") {
                    if (!enabled || preparing || processing || speaking) false
                    else start().also { accessibleHeld = it }
                }
                onClick(if (accessibleHeld) "结束并识别" else "使用文字输入") {
                    if (accessibleHeld) { accessibleHeld = false; finish() } else textEntry()
                    true
                }
                customActions = if (accessibleHeld) listOf(CustomAccessibilityAction("取消语音输入") { accessibleHeld = false; cancel(); true }) else emptyList()
            }
            .pointerInput(enabled, resetSignal) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val origin = coordinates?.localToWindow(down.position) ?: down.position
                    var started = false
                    var released = false
                    try {
                        val beforeHold = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            var change = down
                            while (change.pressed) {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } > 1 || event.changes.any { it.isConsumed }) return@withTimeoutOrNull false
                                change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                            }
                            true
                        }
                        if (beforeHold != null) { if (beforeHold) tap(); return@awaitEachGesture }
                        started = start()
                        if (!started) return@awaitEachGesture
                        pressed = true
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || event.changes.count { it.pressed } > 1 || event.changes.any { it.isConsumed }) {
                                cancel(); released = true; break
                            }
                            // Compare real finger movement; a moving layout must not cancel the recording.
                            val position = coordinates?.localToWindow(change.position) ?: change.position
                            cancelling = position.y < origin.y - 80.dp.toPx()
                            change.consume()
                            if (!change.pressed) {
                                released = true
                                if (cancelling) cancel() else finish()
                                break
                            }
                        }
                    } finally {
                        if (started && !released) cancel()
                        pressed = false; cancelling = false
                    }
                }
            }, contentAlignment = Alignment.Center) {
            if (preparing || starting || (processing && !speaking)) CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            else Icon(if (cancelling) Icons.Rounded.Close else Icons.Rounded.Mic, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(25.dp))
        }
        Text(if (cancelling) "松开取消" else if (resultReady && accessibleHeld) "点击查看" else if (resultReady && pressed) "松开查看" else if (preparing) "准备语音" else if (starting) "开启麦克风" else if (processing && pressed) "松开等待" else if (processing) "正在识别" else if (accessibleHeld) "点击结束" else if (pressed) "松开识别" else "按住说话", fontSize = 9.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 3.dp))
    }
}
