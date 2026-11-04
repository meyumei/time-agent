package com.timeagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.timeagent.extraction.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private class ChatExtractionViewModel(initialText: String) : ViewModel() {
    val text = mutableStateOf(initialText)
    val preview = mutableStateOf<ExtractionPreview?>(null)
    val edits = mutableStateOf<Map<String, ExtractionCandidate>>(emptyMap())
    val checked = mutableStateOf<Set<String>>(emptySet())
    val editing = mutableStateOf<String?>(null)
    val localError = mutableStateOf<String?>(if (initialText.length > 20_000) "聊天文字最多20000字，请删减后提取" else null)
    val lastSaved = mutableStateOf<ExtractionSaveResult?>(null)
}

private class ChatExtractionViewModelFactory(private val initialText: String) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatExtractionViewModel(initialText) as T
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatExtractionScreen(model: TimeViewModel, initialText: String = "", onClose: () -> Unit, onReply: ((String) -> Unit)? = null, onOpenSettings: (() -> Unit)? = null) {
    val state: ChatExtractionViewModel = viewModel(factory = ChatExtractionViewModelFactory(initialText))
    val busy by model.busy.collectAsState()
    val modelError by model.error.collectAsState()
    var text by state.text
    var preview by state.preview
    var edits by state.edits
    var checked by state.checked
    var editing by state.editing
    var localError by state.localError
    var lastSaved by state.lastSaved
    val aiAvailability = rememberAiAvailability(model)
    BackHandler(enabled = !busy) { onClose() }
    Scaffold(topBar = { TopAppBar(title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_chat_extraction_screen_001)) }, navigationIcon = {
        TextButton(onClick = onClose, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_back)) }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_chat_extraction_screen_002), style = MaterialTheme.typography.bodyMedium)
            AiAvailabilityBanner(
                availability = aiAvailability,
                onOpenSettings = onOpenSettings,
                detail = "提取候选与保存仍完全在本机完成，不需要模型；需要鱼协助时再去设置。",
            )
            OutlinedTextField(text, { value ->
                text = value; preview = null; edits = emptyMap(); checked = emptySet(); lastSaved = null
                localError = if (value.length > 20_000) "聊天文字最多20000字，请删减后提取" else null
            }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_chat_extraction_screen_003)) }, minLines = 6, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            Button(onClick = {
                runCatching { ChatTextExtractor.extract(text, model.repository.now(), model.zone) }
                    .onSuccess { preview = it; edits = it.items.associateBy { candidate -> candidate.id }; checked = emptySet(); localError = null; lastSaved = null }
                    .onFailure { localError = it.message }
            }, enabled = !busy && text.isNotBlank() && text.length <= 20_000) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_attachment_screen_012)) }
            if (onReply != null) OutlinedButton(onClick = { onReply(text) }, enabled = !busy && text.isNotBlank() && text.length <= 20000) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_chat_extraction_screen_004)) }
            if (localError != null) Text(localError!!, color = MaterialTheme.colorScheme.error)
            if (modelError != null) Text(modelError!!, color = MaterialTheme.colorScheme.error)
            preview?.let { current ->
                Text("候选 ${current.items.size} 条；默认全部未选中。日期以提取当天为准。", style = MaterialTheme.typography.bodySmall)
                current.items.forEach { base ->
                    val item = edits[base.id] ?: base
                    StructuredDraftPreview(
                        title = "${kindName(item.kind)} · ${item.title}",
                        before = base.original,
                        after = timeSummary(item, model.zone),
                        warnings = if (base.needsCommitment) listOf("这可能是他人的话或邀请，需明确确认属于你。") else emptyList(),
                    ) {
                            Row {
                                Checkbox(checked = base.id in checked, onCheckedChange = { value ->
                                    checked = if (value) checked + base.id else checked - base.id
                                }, enabled = !busy)
                                TextButton(onClick = { checked = if (base.id in checked) checked - base.id else checked + base.id }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_chat_extraction_screen_005)) }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { editing = base.id }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_chat_extraction_screen_006)) }
                            }
                    }
                }
                current.ignored.forEach { Text("未提取：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_chat_extraction_screen_007), style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    if (current.sourceText != text) { localError = "原文已变化，请重新提取"; return@Button }
                    val chosen = current.items.filter { it.id in checked }.map { edits.getValue(it.id).copy(acceptedAsMine = true) }
                    model.perform(onSuccess = { localError = "已保存 ${lastSaved?.created ?: 0} 条，跳过 ${lastSaved?.skipped ?: 0} 条" }) {
                        lastSaved = saveExtraction(model.repository, current, chosen)
                    }
                }, enabled = !busy && checked.isNotEmpty()) { Text(if (busy) "保存中…" else "保存已确认事项") }
                lastSaved?.let { saved ->
                    Text("已保存 ${saved.created} 条，跳过 ${saved.skipped} 条")
                    if (saved.created > 0) TextButton(onClick = {
                        model.perform(onSuccess = { lastSaved = null; localError = "已撤销；原事项不会重复导入" }) {
                            model.repository.undoLastAction(saved.requestId)
                        }
                    }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_chat_extraction_screen_008)) }
                }
            }
        }
    }
    editing?.let { id ->
        val current = edits[id]
        if (current != null) ExtractionEditDialog(current, model.zone, onDismiss = { editing = null }) { edited ->
            edits = edits + (id to edited); editing = null; localError = null
        }
    }
}

private fun kindName(kind: ExtractionKind) = when (kind) {
    ExtractionKind.INBOX -> "收件箱"
    ExtractionKind.TASK -> "任务"
    ExtractionKind.REMINDER -> "提醒"
    ExtractionKind.PLAN -> "计划"
}

private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun timeSummary(item: ExtractionCandidate, zone: java.time.ZoneId): String = when (item.kind) {
    ExtractionKind.INBOX -> "无日期"
    ExtractionKind.TASK -> item.dueAt?.let { "截止：${Instant.ofEpochMilli(it).atZone(zone).toLocalDate()} 23:59:59" } ?: "未设置截止日期"
    ExtractionKind.REMINDER -> item.startAt?.let { "提醒：${dateTimeFormat.format(Instant.ofEpochMilli(it).atZone(zone))}" } ?: "需设置提醒时间"
    ExtractionKind.PLAN -> "${item.startAt?.let { dateTimeFormat.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "需设置开始"} → ${item.endAt?.let { dateTimeFormat.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "需设置结束"}"
}

@Composable
private fun ExtractionEditDialog(item: ExtractionCandidate, zone: java.time.ZoneId, onDismiss: () -> Unit, onSave: (ExtractionCandidate) -> Unit) {
    var title by rememberSaveable(item.id) { mutableStateOf(item.title) }
    var kind by rememberSaveable(item.id) { mutableStateOf(item.kind) }
    var date by rememberSaveable(item.id) { mutableStateOf(item.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() }.orEmpty()) }
    var start by rememberSaveable(item.id) { mutableStateOf(item.startAt?.let { dateTimeFormat.format(Instant.ofEpochMilli(it).atZone(zone)) }.orEmpty()) }
    var end by rememberSaveable(item.id) { mutableStateOf(item.endAt?.let { dateTimeFormat.format(Instant.ofEpochMilli(it).atZone(zone)) }.orEmpty()) }
    var error by rememberSaveable(item.id) { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_chat_extraction_screen_009)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("原文：${item.original}", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(title, { title = it.take(200) }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_template_editor_006)) }, modifier = Modifier.fillMaxWidth())
            ExtractionKind.entries.forEach { option ->
                Row { RadioButton(selected = kind == option, onClick = { kind = option }); TextButton(onClick = { kind = option }) { Text(kindName(option)) } }
            }
            if (kind == ExtractionKind.TASK) OutlinedTextField(date, { date = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_chat_extraction_screen_010)) }, modifier = Modifier.fillMaxWidth())
            if (kind == ExtractionKind.REMINDER || kind == ExtractionKind.PLAN) OutlinedTextField(start, { start = it }, label = { Text("${if (kind == ExtractionKind.REMINDER) "提醒" else "开始"} yyyy-MM-dd HH:mm") }, modifier = Modifier.fillMaxWidth())
            if (kind == ExtractionKind.PLAN) OutlinedTextField(end, { end = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_chat_extraction_screen_011)) }, modifier = Modifier.fillMaxWidth())
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        try {
            val due = if (kind == ExtractionKind.TASK) date.trim().takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1 } else null
            val first = if (kind == ExtractionKind.REMINDER || kind == ExtractionKind.PLAN) start.trim().takeIf { it.isNotEmpty() }?.let { LocalDateTime.parse(it, dateTimeFormat).atZone(zone).toInstant().toEpochMilli() } else null
            val last = if (kind == ExtractionKind.PLAN) end.trim().takeIf { it.isNotEmpty() }?.let { LocalDateTime.parse(it, dateTimeFormat).atZone(zone).toInstant().toEpochMilli() } else null
            onSave(item.copy(title = title.trim(), kind = kind,
                startAt = if (kind == ExtractionKind.REMINDER || kind == ExtractionKind.PLAN) first else null,
                endAt = if (kind == ExtractionKind.PLAN) last else null,
                dueAt = if (kind == ExtractionKind.TASK) due else null))
        } catch (_: Exception) { error = "日期或时间格式无效" }
    }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_chat_extraction_screen_012)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } })
}
