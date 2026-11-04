package com.timeagent.quickentry

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timeagent.ui.components.DateField
import com.timeagent.ui.components.UndoMessage
import com.timeagent.ui.components.UndoSnackbar
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val TEMPLATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanTemplateScreen(
    model: QuickEntryModel,
    onClose: () -> Unit,
    initialDate: LocalDate? = null,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val zone = model.zone
    val snackbar = remember { SnackbarHostState() }
    var undoMessage by remember { mutableStateOf<UndoMessage?>(null) }
    var captureOpen by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf<PlanTemplateEntity?>(null) }
    var deleting by remember { mutableStateOf<PlanTemplateEntity?>(null) }
    var editing by remember { mutableStateOf<PlanTemplateEntity?>(null) }

    editing?.let { template ->
        TemplateEditor(model, template, state.templateItems[template.id].orEmpty()) { editing = null }
        return
    }

    LaunchedEffect(model) { model.refresh() }
    BackHandler(onBack = onClose)
    UndoSnackbar(
        snackbar, undoMessage,
        onUndo = { token ->
            undoMessage = null
            scope.launch { model.undo(token) }
        },
        onConsumed = { id -> if (undoMessage?.id == id) undoMessage = null },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_plan_template_screen_001), fontWeight = FontWeight.Bold) },
                navigationIcon = { TextButton(onClick = onClose) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_close)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_plan_template_screen_002), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_plan_template_screen_003),
                            style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { captureOpen = true }, enabled = !state.busy) {
                            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_plan_template_screen_004))
                        }
                        TextButton(onClick = { editing = PlanTemplateEntity(UUID.randomUUID().toString(), "") }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_plan_template_screen_005)) }
                    }
                }
            }
            state.error?.let { message ->
                item {
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = { model.clearMessages() }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_acknowledge)) }
                        }
                    }
                }
            }
            state.notice?.let { message ->
                item {
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, Modifier.weight(1f))
                            TextButton(onClick = { model.clearMessages() }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_acknowledge)) }
                        }
                    }
                }
            }
            if (state.loading && state.templates.isEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            } else if (state.templates.isEmpty()) {
                item {
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_plan_template_screen_006),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
                }
            } else {
                items(state.templates, key = { it.id }) { template ->
                    TemplateCard(
                        template = template,
                        items = state.templateItems[template.id].orEmpty(),
                        state = state,
                        zone = zone,
                        busy = state.busy,
                        onApply = { applying = template },
                        onDelete = { deleting = template },
                        onEdit = { editing = template },
                    )
                }
            }
        }
    }

    if (captureOpen) {
        CaptureTemplateDialog(
            model = model,
            state = state,
            initialDate = initialDate ?: LocalDate.now(zone),
            onDismiss = { captureOpen = false },
            onSaved = { outcome ->
                captureOpen = false
                outcome.undoToken?.let {
                    undoMessage = UndoMessage(UUID.randomUUID().toString(),
                        "已保存模板“${outcome.template.name}”", it)
                }
            },
        )
    }

    applying?.let { template ->
        ApplyTemplateDialog(
            model = model,
            template = template,
            state = state,
            zone = zone,
            initialDate = initialDate ?: LocalDate.now(zone),
            onDismiss = { applying = null },
            onApplied = { outcome, itemCount ->
                applying = null
                outcome.undoToken?.let {
                    undoMessage = UndoMessage(UUID.randomUUID().toString(),
                        "已应用 $itemCount 条，可一次撤销", it)
                }
                outcome.warnings.firstOrNull()?.let { model.notify(it) }
            },
        )
    }

    deleting?.let { template ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) deleting = null },
            title = { Text("删除模板“${template.name}”？") },
            text = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_plan_template_screen_007)) },
            confirmButton = {
                TextButton(
                    enabled = !state.busy,
                    onClick = {
                        scope.launch {
                            val token = model.deleteTemplate(template.id, template.version)
                            deleting = null
                            token?.let {
                                undoMessage = UndoMessage(UUID.randomUUID().toString(), "已删除模板", it)
                            }
                        }
                    },
                ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }, enabled = !state.busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun TemplateCard(
    template: PlanTemplateEntity,
    items: List<PlanTemplateItemEntity>,
    state: QuickEntryUiState,
    zone: ZoneId,
    busy: Boolean,
    onApply: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    var expanded by remember(template.id) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(template.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${items.size} 条 · 版本 ${template.version} · 更新于 ${formatUpdated(template.updatedAt, zone)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "查看") }
            }
            if (template.description.isNotBlank()) {
                Text(template.description, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onApply, enabled = !busy && items.isNotEmpty()) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_plan_template_screen_008)) }
                TextButton(onClick = onDelete, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_delete)) }
                TextButton(onClick = onEdit, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_edit)) }
            }
            if (expanded) {
                items.forEach { item ->
                    val kind = if (item.kind == TEMPLATE_KIND_TASK) "任务" else scheduleKindLabel(item.scheduleKind)
                    val time = item.startTime ?: if (item.scheduleKind == TEMPLATE_SCHEDULE_ALL_DAY) "全天" else "未设时间"
                    Text("· 第 ${item.dayOffset + 1} 天 $time · $kind · ${item.title}",
                        style = MaterialTheme.typography.bodyMedium)
                    val meta = listOfNotNull(
                        item.durationMinutes?.let { templateDurationLabel(item) },
                        state.projectLabel(item.projectId)?.let { "项目：$it" },
                        item.tagIdList.takeIf { it.isNotEmpty() }?.let { "标签 ${it.size} 个" },
                        item.reminderOffsetList.takeIf { it.isNotEmpty() }?.let { "提醒偏移 ${it.joinToString("/")} 分钟" },
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Text(meta, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureTemplateDialog(
    model: QuickEntryModel,
    state: QuickEntryUiState,
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onSaved: (TemplateSaveOutcome) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(initialDate) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_plan_template_screen_004)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(100) },
                    label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_capture_template_dialog_002)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it.take(1000) },
                    label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_plan_template_screen_009)) }, modifier = Modifier.fillMaxWidth())
                DateField("要保存的日期", date, { date = it })
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_plan_template_screen_010),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (name.isBlank()) Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_plan_template_screen_011), style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.busy && name.isNotBlank(),
                onClick = {
                    if (name.isBlank()) {
                        error = "请填写模板名称"
                    } else {
                        scope.launch {
                            val outcome = model.saveDayAsTemplate(name, date, description)
                            if (outcome != null) onSaved(outcome) else error = "保存失败，请检查这一天的日程"
                        }
                    }
                },
            ) { Text(if (state.busy) "保存中…" else "保存模板") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } },
    )
}

@Composable
private fun ApplyTemplateDialog(
    model: QuickEntryModel,
    template: PlanTemplateEntity,
    state: QuickEntryUiState,
    zone: ZoneId,
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onApplied: (TemplateApplyOutcome, Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var date by remember(template.id) { mutableStateOf(initialDate) }
    var preview by remember(template.id) { mutableStateOf<TemplatePreview?>(null) }
    var selected by remember(template.id) { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember(template.id) { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(template.id, date) {
        loading = true
        error = null
        val value = model.previewTemplate(template.id, date)
        preview = value
        selected = value?.items.orEmpty().map { it.item.id }.toSet()
        loading = false
    }

    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text("应用模板“${template.name}”") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField("起始日期", date, { date = it })
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_plan_template_screen_012), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (loading) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                } else {
                    val current = preview
                    if (current == null) {
                        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_plan_template_screen_013), color = MaterialTheme.colorScheme.error)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { selected = current.items.map { it.item.id }.toSet() }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_plan_template_screen_014)) }
                            TextButton(onClick = { selected = emptySet() }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_plan_template_screen_015)) }
                        }
                        current.items.forEach { entry ->
                            val checked = entry.item.id in selected
                            Row(verticalAlignment = Alignment.Top) {
                                Checkbox(checked = checked, enabled = !state.busy, onCheckedChange = { on ->
                                    selected = if (on == true) selected + entry.item.id else selected - entry.item.id
                                })
                                Column(Modifier.weight(1f).padding(top = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("第 ${entry.item.dayOffset + 1} 天 · ${previewTime(entry, zone)} · ${entry.item.title}",
                                        style = MaterialTheme.typography.bodyMedium)
                                    val meta = listOfNotNull(
                                        if (entry.item.kind == TEMPLATE_KIND_TASK) "任务"
                                        else scheduleKindLabel(entry.item.scheduleKind),
                                        entry.item.durationMinutes?.let { templateDurationLabel(entry.item) },
                                        state.projectLabel(entry.item.projectId)?.let { "项目：$it" },
                                    ).joinToString(" · ")
                                    if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    entry.warnings.forEach {
                                        Text(it, style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                        if (selected.isEmpty()) {
                            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_plan_template_screen_016), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            val current = preview
            TextButton(
                enabled = !state.busy && !loading && current != null && selected.isNotEmpty(),
                onClick = {
                    if (current != null) {
                        scope.launch {
                            val outcome = model.applyTemplate(
                                template.id, date, selected.toList(), current.template.version,
                            )
                            if (outcome != null) {
                                val count = outcome.createdScheduleIds.size + outcome.createdTaskIds.size
                                onApplied(outcome, count)
                            } else {
                                error = "应用失败，尚未写入；请核对后重试"
                            }
                        }
                    }
                },
            ) { Text(if (state.busy) "应用中…" else "批量创建 ${selected.size} 条") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } },
    )
}

private fun previewTime(entry: TemplatePreviewItem, zone: ZoneId): String {
    if (entry.item.kind == TEMPLATE_KIND_TASK) return "任务"
    if (entry.item.scheduleKind == TEMPLATE_SCHEDULE_ALL_DAY) return "全天"
    val start = entry.startAt ?: return "时间无效"
    val end = entry.endAt ?: return TEMPLATE_TIME_FORMAT.format(Instant.ofEpochMilli(start).atZone(zone))
    if (entry.item.scheduleKind == TEMPLATE_SCHEDULE_POINT) {
        return TEMPLATE_TIME_FORMAT.format(Instant.ofEpochMilli(start).atZone(zone)) + " 提醒点"
    }
    return TEMPLATE_TIME_FORMAT.format(Instant.ofEpochMilli(start).atZone(zone)) + "–" +
        TEMPLATE_TIME_FORMAT.format(Instant.ofEpochMilli(end).atZone(zone))
}

private fun scheduleKindLabel(kind: String): String = when (kind) {
    TEMPLATE_SCHEDULE_POINT -> "提醒点"
    TEMPLATE_SCHEDULE_ALL_DAY -> "全天"
    else -> "日程"
}

private fun formatUpdated(updatedAt: Long, zone: ZoneId): String {
    if (updatedAt <= 0) return "尚未保存"
    return Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate().toString()
}
