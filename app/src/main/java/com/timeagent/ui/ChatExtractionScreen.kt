package com.timeagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.timeagent.extraction.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatExtractionScreen(model: TimeViewModel, initialText: String = "", onClose: () -> Unit, onReply: ((String) -> Unit)? = null) {
    val busy by model.busy.collectAsState()
    val modelError by model.error.collectAsState()
    var text by remember { mutableStateOf(initialText) }
    var preview by remember { mutableStateOf<ExtractionPreview?>(null) }
    var edits by remember { mutableStateOf<Map<String, ExtractionCandidate>>(emptyMap()) }
    var checked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var editing by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(if (initialText.length > 20_000) "聊天文字最多20000字，请删减后提取" else null) }
    var lastSaved by remember { mutableStateOf<ExtractionSaveResult?>(null) }
    LaunchedEffect(initialText) {
        if (initialText.isNotBlank() && initialText != text) {
            text = initialText; preview = null; edits = emptyMap(); checked = emptySet(); lastSaved = null
            localError = if (initialText.length > 20_000) "聊天文字最多20000字，请删减后提取" else null
        }
    }
    BackHandler(enabled = !busy) { onClose() }
    Scaffold(topBar = { TopAppBar(title = { Text("聊天文字提取") }, navigationIcon = {
        TextButton(onClick = onClose, enabled = !busy) { Text("返回") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("粘贴或分享聊天文字后，逐条检查并确认属于你的事项。文字仅在本机处理。", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(text, { value ->
                text = value; preview = null; edits = emptyMap(); checked = emptySet(); lastSaved = null
                localError = if (value.length > 20_000) "聊天文字最多20000字，请删减后提取" else null
            }, label = { Text("聊天文字（最多20000字）") }, minLines = 6, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            Button(onClick = {
                runCatching { ChatTextExtractor.extract(text, model.repository.now(), model.zone) }
                    .onSuccess { preview = it; edits = it.items.associateBy { candidate -> candidate.id }; checked = emptySet(); localError = null; lastSaved = null }
                    .onFailure { localError = it.message }
            }, enabled = !busy && text.isNotBlank() && text.length <= 20_000) { Text("提取候选事项") }
            if (onReply != null) OutlinedButton(onClick = { onReply(text) }, enabled = !busy && text.isNotBlank() && text.length <= 20000) { Text("结合空档起草回复") }
            if (localError != null) Text(localError!!, color = MaterialTheme.colorScheme.error)
            if (modelError != null) Text(modelError!!, color = MaterialTheme.colorScheme.error)
            preview?.let { current ->
                Text("候选 ${current.items.size} 条；默认全部未选中。日期以提取当天为准。", style = MaterialTheme.typography.bodySmall)
                current.items.forEach { base ->
                    val item = edits[base.id] ?: base
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("原文：${base.original}", style = MaterialTheme.typography.bodySmall)
                            Text("${kindName(item.kind)} · ${item.title}")
                            Text(timeSummary(item, model.zone), style = MaterialTheme.typography.bodySmall)
                            if (base.needsCommitment) Text("这可能是他人的话或邀请，需明确确认属于你。", style = MaterialTheme.typography.bodySmall)
                            Row {
                                Checkbox(checked = base.id in checked, onCheckedChange = { value ->
                                    checked = if (value) checked + base.id else checked - base.id
                                }, enabled = !busy)
                                TextButton(onClick = { checked = if (base.id in checked) checked - base.id else checked + base.id }, enabled = !busy) { Text("确认为我的事项") }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { editing = base.id }, enabled = !busy) { Text("修改") }
                            }
                        }
                    }
                }
                current.ignored.forEach { Text("未提取：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("仅有日期的事项会作为任务，截止时间取该日期的23:59:59；可在保存前修改。", style = MaterialTheme.typography.bodySmall)
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
                    }, enabled = !busy) { Text("撤销本批保存") }
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
    var title by remember(item.id) { mutableStateOf(item.title) }
    var kind by remember(item.id) { mutableStateOf(item.kind) }
    var date by remember(item.id) { mutableStateOf(item.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() }.orEmpty()) }
    var start by remember(item.id) { mutableStateOf(item.startAt?.let { dateTimeFormat.format(Instant.ofEpochMilli(it).atZone(zone)) }.orEmpty()) }
    var end by remember(item.id) { mutableStateOf(item.endAt?.let { dateTimeFormat.format(Instant.ofEpochMilli(it).atZone(zone)) }.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("修改候选事项") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("原文：${item.original}", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(title, { title = it.take(200) }, label = { Text("标题") }, modifier = Modifier.fillMaxWidth())
            ExtractionKind.entries.forEach { option ->
                Row { RadioButton(selected = kind == option, onClick = { kind = option }); TextButton(onClick = { kind = option }) { Text(kindName(option)) } }
            }
            if (kind == ExtractionKind.TASK) OutlinedTextField(date, { date = it }, label = { Text("截止日期 yyyy-MM-dd（必填）") }, modifier = Modifier.fillMaxWidth())
            if (kind == ExtractionKind.REMINDER || kind == ExtractionKind.PLAN) OutlinedTextField(start, { start = it }, label = { Text("${if (kind == ExtractionKind.REMINDER) "提醒" else "开始"} yyyy-MM-dd HH:mm") }, modifier = Modifier.fillMaxWidth())
            if (kind == ExtractionKind.PLAN) OutlinedTextField(end, { end = it }, label = { Text("结束 yyyy-MM-dd HH:mm") }, modifier = Modifier.fillMaxWidth())
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
    }) { Text("应用") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
