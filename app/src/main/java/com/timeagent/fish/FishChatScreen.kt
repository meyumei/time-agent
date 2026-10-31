package com.timeagent.fish

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun FishChatScreen(
    session: FishSession, onSettings: () -> Unit, onCompanion: () -> Unit,
    requestInput: Boolean = false, onInputFocused: () -> Unit = {},
    briefing: @Composable () -> Unit = {},
) {
    val chat by session.chat.collectAsStateWithLifecycle()
    val runtime by session.runtime.state.collectAsStateWithLifecycle()
    val message by session.message.collectAsStateWithLifecycle()
    val tool by session.tool.collectAsStateWithLifecycle()
    val draft by session.draft.collectAsStateWithLifecycle()
    val companionConfig by session.companion.settings.collectAsStateWithLifecycle()
    val profile by session.character.profile.collectAsStateWithLifecycle()
    val voice by session.voice.status.collectAsStateWithLifecycle()
    val input = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(requestInput) {
        if (requestInput) { input.requestFocus(); keyboard?.show(); onInputFocused() }
    }
    val list = rememberLazyListState()
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    // Keep the reading position through a resize; only follow the end if it was visible.
    var followEnd by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (scrolling) followEnd = false
            else if (list.layoutInfo.totalItemsCount > 0) followEnd = !list.canScrollForward
        }
    }
    LaunchedEffect(chat.turns.size, chat.turns.lastOrNull()?.text) {
        if (followEnd && chat.turns.isNotEmpty()) list.scrollToItem(chat.turns.size, Int.MAX_VALUE)
    }
    LaunchedEffect(imeBottom) {
        if (followEnd && chat.turns.isNotEmpty()) list.scrollToItem(chat.turns.size, Int.MAX_VALUE)
    }
    Column(Modifier.fillMaxSize().padding(horizontal=14.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(54.dp).testTag("fish-chat-header"), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(profile.name, style=MaterialTheme.typography.titleLarge)
                Text(if (voice.speaking) "正在说话" else if (chat.busy) "正在陪你处理" else if (runtime.ready) "你的管家 · 我在" else runtime.stage,
                    style=MaterialTheme.typography.labelSmall, maxLines=1, overflow=TextOverflow.Ellipsis)
            }
            IconButton(onClick=onCompanion) { Icon(Icons.Rounded.MoreHoriz, "大肥鱼的一切") }
        }
        (runtime.error ?: message ?: tool)?.let { Text(it,style=MaterialTheme.typography.bodySmall,
            maxLines=2,overflow=TextOverflow.Ellipsis,
            color=if(runtime.error!=null || message!=null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        runtime.progress?.let { LinearProgressIndicator(progress={it.coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth()) }
        if (!runtime.ready) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={session.start()},enabled=!chat.busy) { Text("准备并启动") }
            TextButton(onClick=onSettings) { Text("模型设置") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("fish-chat-messages"),state=list,verticalArrangement=Arrangement.spacedBy(10.dp),
            contentPadding=PaddingValues(bottom=8.dp)) {
            item(key="briefing") { briefing() }
            if(chat.turns.isEmpty()) item { Text(profile.greeting,style=MaterialTheme.typography.bodyMedium) }
            items(chat.turns) { turn ->
                Surface(color=if(turn.role=="user") MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth()) {
                    val text=(if(turn.role=="user") "你：" else "")+fishPlainText(turn.text)
                    val linked=remember(text) { buildAnnotatedString {
                        var offset=0
                        Regex("https?://[^\\s<>\"）。，！？]+" ).findAll(text).forEach { link ->
                            append(text.substring(offset,link.range.first))
                            withLink(LinkAnnotation.Url(link.value)) { append(link.value) }
                            offset=link.range.last+1
                        }
                        append(text.substring(offset))
                    } }
                    Column(Modifier.padding(12.dp)) {
                        Text(linked,style=MaterialTheme.typography.bodyMedium)
                        FishMessageSticker(turn.text, companionConfig.appearance)
                        if (turn.role != "user") TextButton(onClick={session.voice.speak(turn.text)}) { Text("朗读") }
                    }
                }
            }
        }
        if(chat.busy) Row { CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp);TextButton(onClick={session.cancel()}) { Text("停止本轮回复") } }
        if (voice.message.isNotBlank()) Row(verticalAlignment=Alignment.CenterVertically) {
            Text(voice.message, Modifier.weight(1f), style=MaterialTheme.typography.labelSmall)
            TextButton(onClick={session.voice.stop()}) { Text(if (voice.preparing || voice.speaking) "停止播放" else "关闭") }
        }
        Row(Modifier.fillMaxWidth().padding(bottom=8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
            FishStickerPicker(session)
            OutlinedTextField(draft,session::editDraft,modifier=Modifier.weight(1f).focusRequester(input).testTag("fish-chat-input"),placeholder={Text("说说你正在想的事…")},maxLines=3)
            Button(onClick={session.send(draft)},enabled=draft.isNotBlank() && runtime.ready && !chat.busy) { Text("发送") }
        }
    }
}
