package com.timeagent.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timeagent.data.PersonalMemoryEntity
import com.timeagent.domain.command.*
import com.timeagent.memory.PersonalMemoryRules
import com.timeagent.ui.components.UndoMessage
import com.timeagent.ui.components.UndoSnackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

data class PersonalMemoryDraft(val text: String, val sourceText: String = "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalMemoryScreen(
    model: TimeViewModel,
    onClose: () -> Unit,
    onOpenRecord: (String) -> Unit = {},
    initialDraft: PersonalMemoryDraft? = null,
    onInitialDraftConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val data by model.data.collectAsState()
    val busy by model.busy.collectAsState()
    val now by model.now.collectAsStateWithLifecycle()
    val memories = data.memories.filter { it.deletedAt == null }.sortedByDescending { it.updatedAt }
    var editing by remember { mutableStateOf<PersonalMemoryEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var prefill by remember { mutableStateOf<PersonalMemoryDraft?>(null) }
    var deleting by remember { mutableStateOf<PersonalMemoryEntity?>(null) }
    var exportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    var undoMessage by remember { mutableStateOf<UndoMessage?>(null) }
    UndoSnackbar(snackbar, undoMessage,
        onUndo = { token -> model.perform {
            model.repository.commands.execute(UndoCommand(UUID.randomUUID().toString(), token)).throwIfRejected()
        } },
        onConsumed = { if (undoMessage?.id == it) undoMessage = null })
    LaunchedEffect(initialDraft) {
        if (initialDraft != null) {
            prefill = initialDraft
            creating = true
            onInitialDraftConsumed()
        }
    }
    BackHandler { if (!busy) onClose() }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val bytes = exportBytes; exportBytes = null
        if (uri != null && bytes != null) model.perform {
            withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) } }
            message = "已导出 ${memories.size} 条个人记忆。文件包含个人信息，请妥善保存。"
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_personal_memory_screen_001)) }, navigationIcon = { IconButton(onClick = onClose, enabled = !busy) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } }, actions = {
            IconButton(onClick = { model.perform(onSuccess = { message = "已撤销最近操作" }) { model.repository.undoLastAction() } }, enabled = !busy && data.history.any { !it.undone }) { Icon(Icons.AutoMirrored.Rounded.Undo, "撤销最近操作") }
        })
    }, snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding()) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_personal_memory_screen_002), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { creating = true }, enabled = !busy, modifier = Modifier.weight(1f).testTag("memory-add")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_personal_memory_screen_003)) }
                OutlinedButton(onClick = { model.perform { val candidate = model.repository.proposeMemoryCandidate(); message = if (candidate == null) "最近没有可用于提出候选的实际记录。" else "已提出一条候选，请核对后再确认。" } }, enabled = !busy, modifier = Modifier.weight(1f).testTag("memory-propose")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_personal_memory_screen_004)) }
            }
            OutlinedButton(onClick = {
                val json = JSONArray()
                memories.forEach { item -> json.put(item.toJson()) }
                exportBytes = JSONObject().put("format", "time-agent-personal-memories-v1").put("memories", json).toString(2).toByteArray(Charsets.UTF_8)
                export.launch("personal-memories-${model.repository.now().atZone(model.zone).toLocalDate()}.json")
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("memory-export")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_personal_memory_screen_005)) }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            if (memories.isEmpty()) Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_personal_memory_screen_006), style = MaterialTheme.typography.bodyMedium)
            memories.forEach { item ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.kind.label(), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(when {
                                !item.enabled -> "已停用"
                                !item.confirmed -> "待确认候选"
                                PersonalMemoryRules.active(item, now.toEpochMilli()) -> "生效中"
                                item.validFrom != null && item.validFrom > now.toEpochMilli() -> "尚未生效"
                                item.validUntil != null && item.validUntil <= now.toEpochMilli() -> "已过期"
                                else -> "已确认 · 无有效期"
                            }, style = MaterialTheme.typography.labelMedium)
                        }
                        Text(item.text, fontWeight = FontWeight.SemiBold)
                        Text("来源：${item.sourceType.label()} · 更新 ${if (item.updatedAt > 0) Instant.ofEpochMilli(item.updatedAt).label(model.zone) else "时间未知"}${item.sourceText.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                        if (item.validFrom != null || item.validUntil != null) Text("有效期：${item.validFrom?.dateLabel(model.zone) ?: "不限起始"} 至 ${item.validUntil?.let { Instant.ofEpochMilli(it - 1).atZone(model.zone).toLocalDate().toString() } ?: "长期"}", style = MaterialTheme.typography.bodySmall)
                        Text(if (item.ruleType == "NONE") "无排程规则" else if (item.ruleType == "AVOID_WEEKDAY") "避免周${item.weekday} · ${item.keyword}" else "每日 ${item.startMinute.clock()}–${item.endMinute.clock()}${item.keyword.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                        Column {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                TextButton(onClick = { editing = item }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_edit)) }
                                TextButton(onClick = { model.perform { model.repository.saveMemory(item.copy(enabled = !item.enabled), item.version) } }, enabled = !busy) { Text(if (item.enabled) "停用" else "启用") }
                                if (!item.confirmed) TextButton(onClick = { model.perform { model.repository.saveMemory(item.copy(confirmed = true), item.version) } }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_personal_memory_screen_007)) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                TextButton(onClick = { deleting = item }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_delete)) }
                                if (item.sourceType == "OBSERVATION" && item.sourceId != null) TextButton(onClick = { onOpenRecord(item.sourceId) }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_personal_memory_screen_008)) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    if (creating) MemoryEditorDialog(model, null, busy, initialText = prefill?.text.orEmpty(), initialSource = prefill?.sourceText.orEmpty(), onClose = { creating = false; prefill = null })
    editing?.let { item -> MemoryEditorDialog(model, item, busy, onClose = { editing = null }) }
    deleting?.let { item ->
        val requestId = rememberSaveable(item.id, item.version) { UUID.randomUUID().toString() }
        AlertDialog(
            onDismissRequest = { if (!busy) deleting = null },
            title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_personal_memory_screen_009)) },
            text = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.memory_command_delete_impact)) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    model.perform(onSuccess = { deleting = null }) {
                        val result = model.repository.commands.execute(WritePersonalMemory(requestId,
                            PersonalMemoryMutation.Delete(item.id, item.version)))
                        result.throwIfRejected()
                        result.undoToken?.let { token ->
                            undoMessage = UndoMessage(result.requestId,
                                context.getString(com.timeagent.R.string.memory_command_deleted), token)
                        }
                    }
                }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }, enabled = !busy) {
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
internal fun MemoryEditorDialog(
    model: TimeViewModel,
    existing: PersonalMemoryEntity?,
    busy: Boolean,
    initialText: String = "",
    initialSource: String = "",
    onSaveDraft: ((PersonalMemoryEntity) -> Unit)? = null,
    onClose: () -> Unit,
) {
    val memoryId = rememberSaveable(existing?.id, initialText, initialSource) { existing?.id ?: UUID.randomUUID().toString() }
    var kind by remember(existing?.id) { mutableStateOf(existing?.kind ?: "PREFERENCE") }
    var text by remember(existing?.id, initialText) { mutableStateOf(existing?.text ?: initialText) }
    var source by remember(existing?.id, initialSource) { mutableStateOf(existing?.sourceText ?: initialSource) }
    var from by remember(existing?.id) { mutableStateOf(existing?.validFrom?.dateLabel(model.zone).orEmpty()) }
    var until by remember(existing?.id) { mutableStateOf(existing?.validUntil?.let { Instant.ofEpochMilli(it - 1).atZone(model.zone).toLocalDate().toString() }.orEmpty()) }
    var rule by remember(existing?.id) { mutableStateOf(existing?.ruleType ?: "NONE") }
    var keyword by remember(existing?.id) { mutableStateOf(existing?.keyword.orEmpty()) }
    var weekday by remember(existing?.id) { mutableStateOf(existing?.weekday?.toString() ?: "1") }
    var start by remember(existing?.id) { mutableStateOf(existing?.startMinute.clock()) }
    var end by remember(existing?.id) { mutableStateOf(existing?.endMinute.clock()) }
    val requestId = rememberSaveable(memoryId, kind, text, source, from, until, rule, keyword, weekday, start, end) {
        UUID.randomUUID().toString()
    }
    var validation by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(if (existing == null) "添加个人记忆" else "编辑个人记忆") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_personal_memory_screen_010), style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("PREFERENCE", "FACT", "AGREEMENT").forEach { value -> FilterChip(kind == value, { kind = value }, label = { Text(value.label()) }) } }
            OutlinedTextField(text, { text = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_personal_memory_screen_011)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(source, { source = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_personal_memory_screen_012)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(from, { from = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_personal_memory_screen_013)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(until, { until = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_personal_memory_screen_014)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_personal_memory_screen_015), style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("NONE", "AVOID_WEEKDAY", "TIME_WINDOW").forEach { value -> FilterChip(rule == value, { rule = value }, label = { Text(when(value) { "NONE" -> "无"; "AVOID_WEEKDAY" -> "避免星期"; else -> "每日时段" }) }) } }
            if (rule != "NONE") OutlinedTextField(keyword, { keyword = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_personal_memory_screen_016)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (rule == "AVOID_WEEKDAY") OutlinedTextField(weekday, { weekday = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_personal_memory_screen_017)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (rule == "TIME_WINDOW") {
                OutlinedTextField(start, { start = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_personal_memory_screen_018)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(end, { end = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_personal_memory_screen_019)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            validation?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        try {
            fun date(value: String): Long? = value.trim().takeIf(String::isNotEmpty)?.let { LocalDate.parse(it).atStartOfDay(model.zone).toInstant().toEpochMilli() }
            fun minute(value: String): Int? = value.trim().takeIf(String::isNotEmpty)?.let { LocalTime.parse(it).toSecondOfDay() / 60 }
            val now = model.repository.now().toEpochMilli()
            val base = existing ?: PersonalMemoryEntity(memoryId, kind, text.trim(), sourceType = "USER_INPUT", sourceText = source.trim(), createdAt = now, updatedAt = now)
            val validUntil = until.trim().takeIf(String::isNotEmpty)?.let { LocalDate.parse(it).plusDays(1).atStartOfDay(model.zone).toInstant().toEpochMilli() }
            val item = base.copy(kind = kind, text = text.trim(), sourceText = source.trim(), validFrom = date(from), validUntil = validUntil, ruleType = rule, keyword = keyword.trim(), weekday = if (rule == "AVOID_WEEKDAY") weekday.toIntOrNull() else null, startMinute = if (rule == "TIME_WINDOW") minute(start) else null, endMinute = if (rule == "TIME_WINDOW") minute(end) else null, updatedAt = now)
            PersonalMemoryRules.validate(item)
            if (onSaveDraft != null) onSaveDraft(item)
            else model.perform(onSuccess = onClose) { model.repository.saveMemory(item, existing?.version, requestId) }
        } catch (error: Exception) { validation = error.message ?: "请检查填写内容" }
    }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_save)) } }, dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } })
}

private fun PersonalMemoryEntity.toJson() = JSONObject().apply {
    put("id", id); put("kind", kind); put("text", text); put("sourceType", sourceType); put("sourceText", sourceText); put("sourceId", sourceId ?: JSONObject.NULL)
    put("enabled", enabled); put("confirmed", confirmed); put("validFrom", validFrom ?: JSONObject.NULL); put("validUntil", validUntil ?: JSONObject.NULL)
    put("ruleType", ruleType); put("keyword", keyword); put("weekday", weekday ?: JSONObject.NULL); put("startMinute", startMinute ?: JSONObject.NULL); put("endMinute", endMinute ?: JSONObject.NULL)
    put("createdAt", createdAt); put("updatedAt", updatedAt); put("version", version)
}
private fun String.label() = when (this) { "PREFERENCE" -> "偏好"; "FACT" -> "可核对事实"; "AGREEMENT" -> "当前约定"; "EXPLICIT" -> "明确提供"; "OBSERVATION" -> "实际记录候选"; else -> "用户输入" }
private fun Long.dateLabel(zone: java.time.ZoneId) = Instant.ofEpochMilli(this).atZone(zone).toLocalDate().toString()
private fun Int?.clock() = this?.let { "%02d:%02d".format(it / 60, it % 60) }.orEmpty()
