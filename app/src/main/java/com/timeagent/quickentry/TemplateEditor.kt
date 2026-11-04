package com.timeagent.quickentry

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.timeagent.ui.components.TimeField
import com.timeagent.ui.components.DurationField
import com.timeagent.ui.components.AppTopBar
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.util.UUID

/** A real template editor: every item field is persisted before a separate apply preview. */
@Composable
internal fun TemplateEditor(
    model: QuickEntryModel, template: PlanTemplateEntity, initialItems: List<PlanTemplateItemEntity>, onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state by model.state.collectAsState()
    var name by rememberSaveable(template.id) { mutableStateOf(template.name) }
    var description by rememberSaveable(template.id) { mutableStateOf(template.description) }
    var itemsJson by rememberSaveable(template.id) { mutableStateOf(com.google.gson.Gson().toJson(initialItems)) }
    val items = remember(itemsJson) { com.google.gson.Gson().fromJson(itemsJson, Array<PlanTemplateItemEntity>::class.java).toList() }
    var editing by remember { mutableStateOf<PlanTemplateItemEntity?>(null) }
    fun update(next: List<PlanTemplateItemEntity>) { itemsJson = com.google.gson.Gson().toJson(next) }
    Scaffold(topBar = { AppTopBar("编辑计划模板", onClose) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("模板名称") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text("说明") }, modifier = Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            items.forEach { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("第${item.dayOffset + 1}天 · ${item.title}")
                        Text("${if (item.kind == TEMPLATE_KIND_TASK) "任务" else "日程"} · ${item.startTime ?: "全天或无时间"} · ${templateDurationLabel(item)}")
                        Row {
                            TextButton(onClick = { editing = item }) { Text("编辑条目") }
                            TextButton(onClick = { update(items.filterNot { it.id == item.id }) }) { Text("移除此条") }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { editing = PlanTemplateItemEntity(UUID.randomUUID().toString(), template.id, title = "", startTime = "09:00", durationMinutes = 30) }) { Text("添加日程或任务") }
            Button(onClick = {
                scope.launch { if (model.saveTemplate(template.copy(name = name, description = description), items) != null) onClose() }
            }, enabled = !state.busy && name.isNotBlank() && items.isNotEmpty()) { Text("保存模板") }
        }
    }
    editing?.let { item ->
        TemplateItemEditor(item, state, onDismiss = { editing = null }, onSave = { saved ->
            update(if (items.any { it.id == saved.id }) items.map { if (it.id == saved.id) saved else it } else items + saved)
            editing = null
        })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateItemEditor(
    item: PlanTemplateItemEntity, state: QuickEntryUiState, onDismiss: () -> Unit, onSave: (PlanTemplateItemEntity) -> Unit,
) {
    var title by rememberSaveable(item.id) { mutableStateOf(item.title) }
    var kind by rememberSaveable(item.id) { mutableStateOf(item.kind) }
    var scheduleKind by rememberSaveable(item.id) { mutableStateOf(item.scheduleKind) }
    var day by rememberSaveable(item.id) { mutableStateOf((item.dayOffset + 1).toString()) }
    var minute by rememberSaveable(item.id) { mutableStateOf(item.startTime ?: "09:00") }
    var duration by rememberSaveable(item.id) { mutableIntStateOf(item.durationMinutes ?: 30) }
    var allDayDays by rememberSaveable(item.id) {
        mutableStateOf((if (item.scheduleKind == TEMPLATE_SCHEDULE_ALL_DAY) item.durationMinutes?.div(1440) ?: 1 else 1).toString())
    }
    var projectId by rememberSaveable(item.id) { mutableStateOf(item.projectId) }
    var tags by rememberSaveable(item.id) { mutableStateOf(item.tagIds) }
    var reminders by rememberSaveable(item.id) { mutableStateOf(item.reminderOffsets) }
    var notes by rememberSaveable(item.id) { mutableStateOf(item.notes) }
    val validReminders = reminders.isBlank() || reminders.split(',').all { it.trim().toIntOrNull()?.let { n -> n >= 0 } == true }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("模板条目") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("标题") })
                Row { listOf(TEMPLATE_KIND_SCHEDULE to "日程", TEMPLATE_KIND_TASK to "任务").forEach { (value, label) ->
                    FilterChip(kind == value, { kind = value }, label = { Text(label) })
                } }
                OutlinedTextField(day, { day = it }, label = { Text("第几天（从1起）") }, isError = day.toIntOrNull()?.let { it > 0 } != true)
                if (kind == TEMPLATE_KIND_SCHEDULE) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("TIMED" to "时段", "POINT" to "提醒点", "ALL_DAY" to "全天").forEach { (value, label) ->
                            FilterChip(scheduleKind == value, { scheduleKind = value }, label = { Text(label) })
                        }
                    }
                }
                if (scheduleKind != "ALL_DAY" || kind == TEMPLATE_KIND_TASK) {
                    TimeField("开始时刻", LocalTime.parse(minute), { minute = it.toString() })
                    DurationField(duration, { duration = it })
                } else {
                    OutlinedTextField(allDayDays, { allDayDays = it }, label = { Text("持续天数") },
                        isError = allDayDays.toIntOrNull()?.let { it in 1..(Int.MAX_VALUE / 1440) } != true)
                }
                Text("项目")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(projectId == null, { projectId = null }, label = { Text("无项目") })
                    state.projects.filter { it.deletedAt == null }.forEach { project ->
                        FilterChip(projectId == project.id, { projectId = project.id }, label = { Text(project.name) })
                    }
                }
                Text("标签")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.tags.forEach { tag ->
                        val selected = tags.split(',').filter { it.isNotBlank() }.toSet()
                        FilterChip(tag.id in selected, { tags = (if (tag.id in selected) selected - tag.id else selected + tag.id).joinToString(",") },
                            label = { Text(tag.name) })
                    }
                }
                OutlinedTextField(reminders, { reminders = it }, label = { Text("提前提醒（分钟，逗号分隔）") }, isError = !validReminders)
                OutlinedTextField(notes, { notes = it }, label = { Text("备注") })
            }
        }, confirmButton = { TextButton(onClick = {
            onSave(item.copy(title = title.trim(), kind = kind, dayOffset = requireNotNull(day.toIntOrNull()) - 1,
                startTime = if (scheduleKind == "ALL_DAY" && kind == TEMPLATE_KIND_SCHEDULE) null else minute,
                durationMinutes = if (kind == TEMPLATE_KIND_SCHEDULE && scheduleKind == TEMPLATE_SCHEDULE_ALL_DAY)
                    requireNotNull(allDayDays.toIntOrNull()) * 1440 else duration,
                scheduleKind = scheduleKind, projectId = projectId, tagIds = tags,
                reminderOffsets = reminders, notes = notes))
        }, enabled = title.isNotBlank() && day.toIntOrNull()?.let { it > 0 } == true && duration > 0 && validReminders &&
            (kind != TEMPLATE_KIND_SCHEDULE || scheduleKind != TEMPLATE_SCHEDULE_ALL_DAY ||
                allDayDays.toIntOrNull()?.let { it in 1..(Int.MAX_VALUE / 1440) } == true)) { Text("保存条目") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

internal fun templateDurationLabel(item: PlanTemplateItemEntity): String =
    if (item.kind == TEMPLATE_KIND_SCHEDULE && item.scheduleKind == TEMPLATE_SCHEDULE_ALL_DAY)
        "${item.durationMinutes?.div(1440) ?: 1} 天"
    else "${item.durationMinutes ?: 30} 分钟"
