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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.timeagent.data.PersonalMemoryEntity
import com.timeagent.memory.PersonalMemoryRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalMemoryScreen(model: TimeViewModel, onClose: () -> Unit, onOpenRecord: (String) -> Unit = {}) {
    val context = LocalContext.current
    val data by model.data.collectAsState()
    val busy by model.busy.collectAsState()
    val now by model.now.collectAsState()
    val memories = data.memories.filter { it.deletedAt == null }.sortedByDescending { it.updatedAt }
    var editing by remember { mutableStateOf<PersonalMemoryEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<PersonalMemoryEntity?>(null) }
    var exportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    BackHandler { if (!busy) onClose() }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val bytes = exportBytes; exportBytes = null
        if (uri != null && bytes != null) model.perform {
            withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) } }
            message = "已导出 ${memories.size} 条个人记忆。文件包含个人信息，请妥善保存。"
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("个人记忆") }, navigationIcon = { IconButton(onClick = onClose, enabled = !busy) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } }, actions = {
            IconButton(onClick = { model.perform(onSuccess = { message = "已撤销最近操作" }) { model.repository.undoLastAction() } }, enabled = !busy && data.history.any { !it.undone }) { Icon(Icons.AutoMirrored.Rounded.Undo, "撤销最近操作") }
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("记忆用于保存你明确提供或从实际记录中提出的内容。普通文字不会自动限制排程；只有设置了排程约定规则的记忆才会参与检查。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { creating = true }, enabled = !busy, modifier = Modifier.weight(1f).testTag("memory-add")) { Text("添加记忆") }
                OutlinedButton(onClick = { model.perform { val candidate = model.repository.proposeMemoryCandidate(); message = if (candidate == null) "最近没有可用于提出候选的实际记录。" else "已提出一条候选，请核对后再确认。" } }, enabled = !busy, modifier = Modifier.weight(1f).testTag("memory-propose")) { Text("从最近实际记录提出候选") }
            }
            OutlinedButton(onClick = {
                val json = JSONArray()
                memories.forEach { item -> json.put(item.toJson()) }
                exportBytes = JSONObject().put("format", "time-agent-personal-memories-v1").put("memories", json).toString(2).toByteArray(Charsets.UTF_8)
                export.launch("personal-memories-${model.repository.now().atZone(model.zone).toLocalDate()}.json")
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("memory-export")) { Text("导出个人记忆 JSON") }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            if (memories.isEmpty()) Text("还没有个人记忆。", style = MaterialTheme.typography.bodyMedium)
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
                                TextButton(onClick = { editing = item }, enabled = !busy) { Text("编辑") }
                                TextButton(onClick = { model.perform { model.repository.saveMemory(item.copy(enabled = !item.enabled), item.version) } }, enabled = !busy) { Text(if (item.enabled) "停用" else "启用") }
                                if (!item.confirmed) TextButton(onClick = { model.perform { model.repository.saveMemory(item.copy(confirmed = true), item.version) } }, enabled = !busy) { Text("确认候选") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                TextButton(onClick = { deleting = item }, enabled = !busy) { Text("删除") }
                                if (item.sourceType == "OBSERVATION" && item.sourceId != null) TextButton(onClick = { onOpenRecord(item.sourceId) }) { Text("查看记录") }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    if (creating) MemoryEditorDialog(model, null, busy, onClose = { creating = false })
    editing?.let { item -> MemoryEditorDialog(model, item, busy, onClose = { editing = null }) }
    deleting?.let { item -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这条记忆？") }, text = { Text("删除后可以撤销恢复。") }, confirmButton = { TextButton(onClick = { deleting = null; model.perform { model.repository.deleteMemory(item.id, item.version) } }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
private fun MemoryEditorDialog(model: TimeViewModel, existing: PersonalMemoryEntity?, busy: Boolean, onClose: () -> Unit) {
    var kind by remember(existing?.id) { mutableStateOf(existing?.kind ?: "PREFERENCE") }
    var text by remember(existing?.id) { mutableStateOf(existing?.text.orEmpty()) }
    var source by remember(existing?.id) { mutableStateOf(existing?.sourceText.orEmpty()) }
    var from by remember(existing?.id) { mutableStateOf(existing?.validFrom?.dateLabel(model.zone).orEmpty()) }
    var until by remember(existing?.id) { mutableStateOf(existing?.validUntil?.let { Instant.ofEpochMilli(it - 1).atZone(model.zone).toLocalDate().toString() }.orEmpty()) }
    var rule by remember(existing?.id) { mutableStateOf(existing?.ruleType ?: "NONE") }
    var keyword by remember(existing?.id) { mutableStateOf(existing?.keyword.orEmpty()) }
    var weekday by remember(existing?.id) { mutableStateOf(existing?.weekday?.toString() ?: "1") }
    var start by remember(existing?.id) { mutableStateOf(existing?.startMinute.clock()) }
    var end by remember(existing?.id) { mutableStateOf(existing?.endMinute.clock()) }
    var validation by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(if (existing == null) "添加个人记忆" else "编辑个人记忆") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("类型：偏好 / 可核对事实 / 当前约定", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("PREFERENCE", "FACT", "AGREEMENT").forEach { value -> FilterChip(kind == value, { kind = value }, label = { Text(value.label()) }) } }
            OutlinedTextField(text, { text = it }, label = { Text("记忆内容") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(source, { source = it }, label = { Text("来源原文（可选）") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(from, { from = it }, label = { Text("生效日期 YYYY-MM-DD（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(until, { until = it }, label = { Text("失效日期，含当天（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("排程规则", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("NONE", "AVOID_WEEKDAY", "TIME_WINDOW").forEach { value -> FilterChip(rule == value, { rule = value }, label = { Text(when(value) { "NONE" -> "无"; "AVOID_WEEKDAY" -> "避免星期"; else -> "每日时段" }) }) } }
            if (rule != "NONE") OutlinedTextField(keyword, { keyword = it }, label = { Text("适用任务关键词") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (rule == "AVOID_WEEKDAY") OutlinedTextField(weekday, { weekday = it }, label = { Text("星期 1–7（周一至周日）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (rule == "TIME_WINDOW") {
                OutlinedTextField(start, { start = it }, label = { Text("开始 HH:mm") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(end, { end = it }, label = { Text("结束 HH:mm") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            validation?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        try {
            fun date(value: String): Long? = value.trim().takeIf(String::isNotEmpty)?.let { LocalDate.parse(it).atStartOfDay(model.zone).toInstant().toEpochMilli() }
            fun minute(value: String): Int? = value.trim().takeIf(String::isNotEmpty)?.let { LocalTime.parse(it).toSecondOfDay() / 60 }
            val now = model.repository.now().toEpochMilli()
            val base = existing ?: PersonalMemoryEntity(UUID.randomUUID().toString(), kind, text.trim(), sourceType = "USER_INPUT", sourceText = source.trim(), createdAt = now, updatedAt = now)
            val validUntil = until.trim().takeIf(String::isNotEmpty)?.let { LocalDate.parse(it).plusDays(1).atStartOfDay(model.zone).toInstant().toEpochMilli() }
            val item = base.copy(kind = kind, text = text.trim(), sourceText = source.trim(), validFrom = date(from), validUntil = validUntil, ruleType = rule, keyword = keyword.trim(), weekday = if (rule == "AVOID_WEEKDAY") weekday.toIntOrNull() else null, startMinute = if (rule == "TIME_WINDOW") minute(start) else null, endMinute = if (rule == "TIME_WINDOW") minute(end) else null, updatedAt = now)
            PersonalMemoryRules.validate(item)
            model.perform(onSuccess = onClose) { model.repository.saveMemory(item, existing?.version) }
        } catch (error: Exception) { validation = error.message ?: "请检查填写内容" }
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("取消") } })
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
