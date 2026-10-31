package com.timeagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.timeagent.assistant.SpeechInputStatus

@Composable
fun VoiceInputBanner(status: SpeechInputStatus, text: String) {
    if (status !in setOf(SpeechInputStatus.STARTING, SpeechInputStatus.LISTENING, SpeechInputStatus.PROCESSING)) return
    val scroll = rememberScrollState()
    LaunchedEffect(text) { scroll.animateScrollTo(scroll.maxValue) }
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("voice-live-input")) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(when (status) {
                SpeechInputStatus.STARTING -> "正在开启麦克风…"
                SpeechInputStatus.LISTENING -> "正在听你说 · 松开识别，上滑取消"
                SpeechInputStatus.PROCESSING -> "正在整理语音…"
                else -> "本次输入"
            }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(text.ifBlank { "识别到的文字会显示在这里" }, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(scroll).semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}
