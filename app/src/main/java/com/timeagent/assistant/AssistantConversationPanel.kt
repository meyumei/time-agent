package com.timeagent.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Compact conversation above the calendar. Voice, follow-up and actions never replace the page. */
@Composable
fun AssistantConversationPanel(state: AssistantChatState, draft: String, onDraft: (String) -> Unit,
    expanded: Boolean, onExpanded: (Boolean) -> Unit, onSend: (String) -> Unit, onSettings: () -> Unit,
    suggestion: String? = null, onSuggestion: () -> Unit = {},
    turnDecoration: @Composable (ConversationTurn) -> Unit = {},
    inputAccessory: @Composable () -> Unit = {}) {
    if (!expanded && state.turns.isEmpty()) return
    if (!expanded) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp).testTag("assistant-conversation")) {
            Row(Modifier.fillMaxWidth().height(48.dp).clickable { onExpanded(true) }.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
                val summary = when {
                    state.busy -> "正在处理…"
                    state.pending != null -> "待确认 · ${state.pending.title}"
                    else -> state.turns.lastOrNull { it.role == "assistant" }?.text?.let { com.timeagent.fish.fishPlainText(it) }?.lineSequence()?.firstOrNull() ?: "时间助手"
                }
                Text(summary, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                if (state.undoId != null && state.pending == null) IconButton(onClick = { onSend("撤销这次") }, enabled = !state.busy, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.History, "撤销这次", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { onExpanded(true) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.ExpandMore, "展开助手", Modifier.size(20.dp)) }
            }
        }
        return
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp).testTag("assistant-conversation")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
                Text(if (state.busy) "正在处理…" else "时间助手", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f).padding(start = 6.dp))
                IconButton(onClick = onSettings, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Tune, "助手设置", Modifier.size(18.dp)) }
                IconButton(onClick = { onExpanded(!expanded) }, modifier = Modifier.size(32.dp)) { Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "收起助手" else "展开助手", Modifier.size(20.dp)) }
            }
            if (expanded) {
                val scroll = rememberScrollState()
                LaunchedEffect(state.turns.size, state.busy) { scroll.scrollTo(scroll.maxValue) }
                Column(Modifier.fillMaxWidth().heightIn(max = 145.dp).verticalScroll(scroll).testTag("assistant-messages"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.turns.isEmpty()) Text("按住下方圆圈说话，或输入一句话。记想法、设提醒、安排日程会直接执行，可随时说“撤销”或“恢复刚才撤销的操作”。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.turns.takeLast(8).forEach { turn ->
                        Text((if (turn.role == "user") "你：" else "") + com.timeagent.fish.fishPlainText(turn.text), style = MaterialTheme.typography.bodySmall,
                            color = if (turn.role == "user") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (turn.role == "user") FontWeight.Normal else FontWeight.Medium)
                        turnDecoration(turn)
                    }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                inputAccessory()
                if (suggestion != null) TextButton(enabled = !state.busy, onClick = onSuggestion, modifier = Modifier.testTag("voice-command-suggestion").heightIn(min = 32.dp), contentPadding = PaddingValues(0.dp)) { Text("可能想说：$suggestion", style = MaterialTheme.typography.labelMedium) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(draft, { onDraft(it.take(2000)) }, placeholder = { Text("继续说，或输入补充…", style = MaterialTheme.typography.bodySmall) }, maxLines = 2,
                        enabled = !state.busy, shape = RoundedCornerShape(14.dp), textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).testTag("assistant-input"))
                    IconButton(onClick = { onSend(draft) }, enabled = !state.busy && draft.isNotBlank(), modifier = Modifier.testTag("assistant-send")) { Icon(Icons.AutoMirrored.Rounded.Send, "发送给助手", tint = MaterialTheme.colorScheme.primary) }
                }
            } else state.turns.lastOrNull()?.let { Text(it.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth()) }
            if (state.pending != null || state.undoId != null) Row {
                if (state.pending != null) {
                    TextButton(onClick = { onSend("确认") }, enabled = !state.busy, modifier = Modifier.height(34.dp)) { Text(if (state.pending.needsFutureApproval) "确认记录未来时间" else "确认执行") }
                    TextButton(onClick = { onSend("取消") }, enabled = !state.busy, modifier = Modifier.height(34.dp)) { Text("取消") }
                } else TextButton(onClick = { onSend("撤销这次") }, enabled = !state.busy, modifier = Modifier.height(34.dp)) { Icon(Icons.Rounded.History, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("撤销这次") }
            }
        }
    }
}

@Composable
fun ConversationSettingsDialog(settings: AssistantSettings, runtimeMode: Boolean = false, onSaved: () -> Unit = {}, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(settings.model()) }
    var enabled by remember { mutableStateOf(settings.cloudEnabled()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(if (runtimeMode) "角色的模型设置" else "助手设置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (runtimeMode) Text("语音在本机识别。与内置角色交流时，文字、相关共同记忆和它按需查询的业务结果会用于模型回复。修改运行中的模型设置后，需要重新启动角色，历史会保留。",style=MaterialTheme.typography.bodySmall)
            else {
            Text("中文语音在本机识别。明确的记录、任务和计划无需 API Key，可直接执行并撤销。", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("智能理解 · DeepSeek", Modifier.weight(1f)); Switch(enabled, { enabled = it }, enabled = !busy) }
            Text("开启后，本地未理解的表达会自动发送给 DeepSeek，含本次文字、最近 8 条对话，以及有限的任务、课程、计划、记录活动名与时间、提醒和想法标题。不发送录音、任务描述、记录备注、想法或笔记正文、操作历史。", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(key, { key = it }, label = { Text(if (settings.hasApiKey()) "替换 Key（留空保留）" else "DeepSeek API Key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy)
            OutlinedTextField(model, { model = it }, label = { Text("模型名") }, singleLine = true, enabled = !busy)
            Text(if(runtimeMode) "密钥保存在本机，供内置运行环境连接模型服务。" else "密钥仅由本机 Keystore 加密保存。", style = MaterialTheme.typography.labelSmall)
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        busy = true
        scope.launch {
            try {
                require(runtimeMode || !enabled || key.isNotBlank() || settings.hasApiKey()) { "开启智能理解前请填写 API Key。" }
                settings.saveModel(model.trim()); if (key.isNotBlank()) settings.saveApiKey(key)
                if (!runtimeMode) settings.setCloudEnabled(enabled)
                key = ""; onSaved(); onClose()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "保存失败，请重试。" }
            finally { busy = false }
        }
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("关闭") } })
}
