package com.timeagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.timeagent.assistant.AssistantChatState
import com.timeagent.assistant.AssistantConversationPanel
import com.timeagent.fish.FishSession
import com.timeagent.ui.design.LocalPresentation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSheet(
    chat: AssistantChatState, draft: String, onDraft: (String) -> Unit, onSend: (String) -> Unit,
    voice: HomeVoiceState, liveText: String, suggestion: String?, onSuggestion: () -> Unit,
    onClose: () -> Unit, onSettings: () -> Unit, fish: FishSession?,
) {
    BackHandler(onBack = onClose)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("voice-sheet")) {
        Column(Modifier.fillMaxWidth().imePadding().padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("语音与文字", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onClose) { Text("关闭面板") }
            }
            if (LocalPresentation.current.companion && fish != null) {
                com.timeagent.fish.CharacterSprite(fish, Modifier.size(64.dp))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(12, 24, 36, 24, 12).forEach { height ->
                        Surface(Modifier.width(4.dp).height(height.dp), color = MaterialTheme.colorScheme.primary,
                            shape = MaterialTheme.shapes.small) {}
                    }
                }
            }
            VoiceInputBanner(voice.status, liveText)
            AssistantConversationPanel(chat, draft, onDraft, true, { if (!it) onClose() }, onSend, onSettings,
                suggestion, onSuggestion, inputAccessory = { fish?.let { com.timeagent.fish.FishStickerPicker(it) } })
            HomeVoiceDock(enabled = !chat.busy, onRecognized = onSend, onMessage = {},
                onTextEntry = {}, onOpenPanel = {}, state = voice)
        }
    }
}
