package com.timeagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.timeagent.core.TimeInput
import com.timeagent.core.TimeSpan
import com.timeagent.data.LocalSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A grid selection always creates occupied time; changing its type keeps the same draft. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GridBlockEditorScreen(
    request: EditorRequest,
    data: LocalSnapshot,
    now: Instant,
    zone: ZoneId,
    busy: Boolean,
    saveError: String?,
    onDismiss: () -> Unit,
    onSave: (EditValues) -> Unit,
) {
    val initial = requireNotNull(request.selection).span(zone)
    val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm") }
    var kindName by rememberSaveable(request.id) { mutableStateOf(if (request.kind == EditorKind.RECORD) EditorKind.RECORD.name else EditorKind.PLAN.name) }
    val kind = EditorKind.valueOf(kindName)
    var newTask by rememberSaveable(request.id) { mutableStateOf(request.taskId == null) }
    var independent by rememberSaveable(request.id) { mutableStateOf(false) }
    var selectedTask by rememberSaveable(request.id) { mutableStateOf(request.taskId) }
    var title by rememberSaveable(request.id) { mutableStateOf(request.title.orEmpty()) }
    var notes by rememberSaveable(request.id) { mutableStateOf("") }
    var category by rememberSaveable(request.id) { mutableStateOf<String?>(null) }
    var startDate by rememberSaveable(request.id) { mutableStateOf(initial.start.atZone(zone).toLocalDate().toString()) }
    var startTime by rememberSaveable(request.id) { mutableStateOf(initial.start.atZone(zone).format(timeFormat)) }
    var endDate by rememberSaveable(request.id) { mutableStateOf(initial.end.atZone(zone).toLocalDate().toString()) }
    var endTime by rememberSaveable(request.id) { mutableStateOf(initial.end.atZone(zone).format(timeFormat)) }
    var futureConfirmed by rememberSaveable(request.id) { mutableStateOf(false) }
    var validation by rememberSaveable(request.id) { mutableStateOf<String?>(null) }
    var submitted by remember(request.id) { mutableStateOf(false) }
    // A failed write keeps this draft and releases the immediate double-click guard for retry.
    LaunchedEffect(busy, saveError) { if (!busy && saveError != null) submitted = false }
    val saving = busy || submitted
    val tasks = data.tasks.filter { it.deletedAt == null && it.status != "CANCELLED" }
    val candidate = runCatching {
        TimeSpan(TimeInput.parse(startDate, startTime, zone), TimeInput.parse(endDate, endTime, zone))
    }.getOrNull()
    val future = kind == EditorKind.RECORD && candidate?.end?.isAfter(now) == true
    val overlaps = candidate?.let { span ->
        data.timeline(now).filter { item ->
            !item.isPoint && (if (kind == EditorKind.RECORD) item.kind == ItemKind.RECORD else item.kind != ItemKind.RECORD) && span.overlaps(item.span)
        }
    }.orEmpty()
    val save: () -> Unit = {
        if (!busy && !submitted) {
            try {
                val span = TimeSpan(TimeInput.parse(startDate, startTime, zone), TimeInput.parse(endDate, endTime, zone))
                val createTask = kind == EditorKind.PLAN && newTask
                if (createTask || kind == EditorKind.PLAN && independent) require(title.isNotBlank()) { "日程名称不能为空" }
                if (kind == EditorKind.PLAN && !newTask && !independent) require(tasks.any { it.id == selectedTask }) { "请选择关联任务" }
                if (kind == EditorKind.RECORD && span.end.isAfter(now)) require(futureConfirmed) { "请确认仍作为实际记录保存" }
                submitted = true
                validation = null
                onSave(
                    EditValues(
                        title = title.trim(), notes = notes, estimatedMinutes = (span.seconds / 60).toInt().coerceIn(1, 10080),
                        priority = 1, dueAt = null, taskId = if (kind == EditorKind.PLAN && independent) null else selectedTask, categoryId = category,
                        start = span.start.toEpochMilli(), end = span.end.toEpochMilli(), allowFuture = futureConfirmed,
                        location = "", teacher = "", activityId = request.activityId, kind = kind, scheduleNewTask = createTask,
                    ),
                )
            } catch (e: Exception) {
                submitted = false
                validation = e.message?.takeIf { it.contains("必须") || it.contains("请选择") || it.contains("名称") || it.contains("确认") }
                    ?: "请检查日期和时间格式"
            }
        }
    }
    val dismiss: () -> Unit = { if (!saving) onDismiss() }
    BackHandler(onBack = dismiss)
    Scaffold(modifier = Modifier.fillMaxSize().testTag("grid-block-editor"), topBar = {
        TopAppBar(title = { Text("添加日程", fontWeight = FontWeight.Bold) },
            navigationIcon = { TextButton(onClick = dismiss, enabled = !saving) { Text("取消") } },
            actions = {
                TextButton(onClick = save, enabled = !saving && (!future || futureConfirmed), modifier = Modifier.testTag("grid-save")) {
                    Text(if (saving) "保存中…" else if (kind == EditorKind.PLAN && overlaps.isNotEmpty()) "仍然保存" else "保存")
                }
            })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(kind == EditorKind.PLAN, { kindName = EditorKind.PLAN.name; validation = null }, enabled = !saving,
                    label = { Text("计划") }, modifier = Modifier.testTag("grid-kind-plan"))
                FilterChip(kind == EditorKind.RECORD, { kindName = EditorKind.RECORD.name; validation = null }, enabled = !saving,
                    label = { Text("实际记录") }, modifier = Modifier.testTag("grid-kind-record"))
            }
            if (kind == EditorKind.PLAN) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(newTask, { newTask = true; independent = false; validation = null }, enabled = !saving,
                        label = { Text("新建任务") }, modifier = Modifier.testTag("grid-new-task"))
                    FilterChip(!newTask && !independent, { newTask = false; independent = false; validation = null }, enabled = !saving,
                        label = { Text("关联已有任务") }, modifier = Modifier.testTag("grid-existing-task"))
                    FilterChip(independent, { newTask = false; independent = true; validation = null }, enabled = !saving,
                        label = { Text("独立日程") }, modifier = Modifier.testTag("grid-independent-plan"))
                }
            }
            if (kind == EditorKind.RECORD || newTask || independent) {
                OutlinedTextField(title, { title = it }, enabled = !saving, label = { Text(if (kind == EditorKind.PLAN) (if (newTask) "任务名称" else "日程名称") else "记录标题（可选）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("grid-title"))
            }
            Text("时间以 ${zone.id} 显示", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(startDate, { startDate = it; futureConfirmed = false }, enabled = !saving, label = { Text("开始日期 YYYY-MM-DD") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("grid-start-date"))
            OutlinedTextField(startTime, { startTime = it; futureConfirmed = false }, enabled = !saving, label = { Text("开始时间 HH:mm") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("grid-start-time"))
            OutlinedTextField(endDate, { endDate = it; futureConfirmed = false }, enabled = !saving, label = { Text("结束日期 YYYY-MM-DD") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("grid-end-date"))
            OutlinedTextField(endTime, { endTime = it; futureConfirmed = false }, enabled = !saving, label = { Text("结束时间 HH:mm") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("grid-end-time"))
            if (kind == EditorKind.RECORD || !newTask && !independent) {
                Text(if (kind == EditorKind.PLAN) "关联任务（必选）" else "关联任务（可选）", style = MaterialTheme.typography.labelLarge)
                if (kind == EditorKind.RECORD) {
                    FilterChip(selectedTask == null, { selectedTask = null }, enabled = !saving,
                        label = { Text("不关联任务") }, modifier = Modifier.testTag("grid-no-task"))
                }
                tasks.forEach { task ->
                    FilterChip(selectedTask == task.id, { selectedTask = task.id }, enabled = !saving,
                        label = { Text(task.title) }, modifier = Modifier.testTag("grid-task-${task.id}"))
                }
                if (kind == EditorKind.PLAN && tasks.isEmpty()) Text("还没有可关联的任务，可以切换为新建任务。", style = MaterialTheme.typography.bodySmall)
            }
            if (kind == EditorKind.RECORD || newTask || independent) {
                Text("分类", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(category == null, { category = null }, enabled = !saving, label = { Text("未分类") })
                    data.categories.forEach { item ->
                        FilterChip(category == item.id, { category = item.id }, enabled = !saving, label = { Text(item.name) })
                    }
                }
                OutlinedTextField(notes, { notes = it }, enabled = !saving, label = { Text("备注") }, minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("grid-notes"))
            }
            if (overlaps.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                Text((if (kind == EditorKind.RECORD) "与已有实际记录重叠，仍可保存：" else "时间冲突，仍可保存：") + overlaps.take(4).joinToString("、") { it.title },
                    Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (future) {
                Text("这条记录包含未来时间；如尚未发生，建议改建计划。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(futureConfirmed, { futureConfirmed = it }, enabled = !saving, modifier = Modifier.testTag("grid-future-confirm"))
                    Text("我确认仍作为实际记录保存", style = MaterialTheme.typography.bodySmall)
                }
            }
            validation?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
