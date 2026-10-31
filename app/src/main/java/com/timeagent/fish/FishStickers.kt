package com.timeagent.fish

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timeagent.core.*

private val marker = Regex("\\[\\[fish:(happy|thinking|concerned|sleeping|wave|apologetic)]]")
private val frames = mapOf("happy" to 7, "thinking" to 8, "concerned" to 14, "sleeping" to 13, "wave" to 5, "apologetic" to 15)
private val labels = mapOf("happy" to "开心", "thinking" to "想一想", "concerned" to "抱抱", "sleeping" to "晚安", "wave" to "嗨", "apologetic" to "抱歉")

fun fishPlainText(text: String): String = marker.replace(text, "").trim()

@Composable
fun FishMessageSticker(text: String, appearance: FishAppearance, size: Int = 76) {
    val kind = marker.find(text)?.groupValues?.get(1) ?: return
    FishSprite(appearance, FishPresence(), Modifier.size(size.dp).testTag("fish-sticker-$kind"),
        animate = false, fixedFrame = frames.getValue(kind))
}

@Composable
fun FishStickerPicker(session: FishSession) {
    var open by remember { mutableStateOf(false) }
    val config by session.companion.settings.collectAsStateWithLifecycle()
    val runtime by session.runtime.state.collectAsStateWithLifecycle()
    val chat by session.chat.collectAsStateWithLifecycle()
    Box {
        TextButton(onClick = { open = !open }, modifier = Modifier.testTag("fish-sticker-toggle")) { Text("表情") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            frames.forEach { (kind, index) ->
                DropdownMenuItem(text = { Text(labels.getValue(kind)) },
                    leadingIcon = { FishSprite(config.appearance, FishPresence(), Modifier.size(42.dp), animate = false, fixedFrame = index) },
                    onClick = { session.send("${labels.getValue(kind)} [[fish:$kind]]"); open = false },
                    enabled = runtime.ready && !chat.busy)
            }
        }
    }
}
