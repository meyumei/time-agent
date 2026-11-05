package com.timeagent.quickentry

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.timeagent.ui.components.TimeField
import com.timeagent.ui.components.DurationField
import com.timeagent.ui.components.AppTopBar
import com.timeagent.ui.rememberDraftExit
import kotlinx.coroutines.delay
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
    val context = LocalContext.current
    val store = remember(context) { TemplateEditorDraftStore(context) }
    val gson = remember { com.google.gson.Gson() }
    val restored = remember(template.id) { store.read(template.id) }
    val seenJson = rememberSaveable(template.id) { gson.toJson(restored?.template ?: template) }
    val originalJson = rememberSaveable(template.id) { gson.toJson(restored?.originalItems ?: initialItems) }
    val seen = remember(seenJson) { gson.fromJson(seenJson, PlanTemplateEntity::class.java) }
    var name by rememberSaveable(template.id) { mutableStateOf(restored?.name ?: seen.name) }
    var description by rememberSaveable(template.id) { mutableStateOf(restored?.description ?: seen.description) }
    var itemsJson by rememberSaveable(template.id) { mutableStateOf(restored?.items?.let(gson::toJson) ?: originalJson) }
    val items = remember(itemsJson) { gson.fromJson(itemsJson, Array<PlanTemplateItemEntity>::class.java).toList() }
    var editingJson by rememberSaveable(template.id) { mutableStateOf(restored?.editingItem?.let(gson::toJson)) }
    var itemFieldsJson by rememberSaveable(template.id) { mutableStateOf(gson.toJson(restored?.itemFields.orEmpty())) }
    val editing = remember(editingJson) { editingJson?.let { gson.fromJson(it, PlanTemplateItemEntity::class.java) } }
    val itemFields = remember(itemFieldsJson) { gson.fromJson(itemFieldsJson, Map::class.java).entries.associate { it.key.toString() to it.value.toString() } }
    var draftError by remember { mutableStateOf<String?>(null) }
    var completed by remember { mutableStateOf(false) }
    val saveRequestId = rememberSaveable(template.id, name, description, itemsJson) { UUID.randomUUID().toString() }
    val dirty = name != seen.name || description != seen.description || itemsJson != originalJson || editing != null
    fun draft() = TemplateEditorDraft(seen,
        gson.fromJson(originalJson, Array<PlanTemplateItemEntity>::class.java).toList(), name, description, items, editing, itemFields)
    fun keep() {
        if (store.save(draft())) { completed = true; onClose() }
        else draftError = context.getString(com.timeagent.R.string.template_draft_write_failed)
    }
    val dismiss = rememberDraftExit(template.id, listOf(name, description, itemsJson, editingJson, itemFieldsJson),
        state.busy, onDiscard = {
            if (store.remove(template.id)) { completed = true; onClose() }
            else draftError = context.getString(com.timeagent.R.string.template_draft_write_failed)
        }, onKeep = ::keep, initialFields = listOf(seen.name, seen.description, originalJson, null, "{}"))
    BackHandler(enabled = editing == null, onBack = dismiss)
    LaunchedEffect(name, description, itemsJson, editingJson, itemFieldsJson, completed) {
        if (!completed) {
            delay(2_000)
            val saved = if (dirty) store.save(draft()) else store.remove(template.id)
            if (!saved) draftError = context.getString(com.timeagent.R.string.template_draft_write_failed)
        }
    }
    fun update(next: List<PlanTemplateItemEntity>) { itemsJson = gson.toJson(next) }
    Scaffold(topBar = { AppTopBar(stringResource(com.timeagent.R.string.template_editor_title), dismiss) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_capture_template_dialog_002)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_template_editor_001)) }, modifier = Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            draftError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (restored != null) com.timeagent.ui.components.StatusBanner(stringResource(com.timeagent.R.string.template_draft_restored))
            items.forEach { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("第${item.dayOffset + 1}天 · ${item.title}")
                        Text("${if (item.kind == TEMPLATE_KIND_TASK) "任务" else "日程"} · ${item.startTime ?: "全天或无时间"} · ${templateDurationLabel(item)}")
                        Row {
                            TextButton(onClick = { itemFieldsJson = "{}"; editingJson = gson.toJson(item) }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_template_editor_002)) }
                            TextButton(onClick = { update(items.filterNot { it.id == item.id }) }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_template_editor_003)) }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { itemFieldsJson = "{}"; editingJson = gson.toJson(PlanTemplateItemEntity(UUID.randomUUID().toString(), template.id, title = "", startTime = "09:00", durationMinutes = 30)) }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_template_editor_004)) }
            Button(onClick = {
                scope.launch { if (model.saveTemplate(seen.copy(name = name, description = description), items, saveRequestId) != null) {
                    completed = true
                    if (!store.remove(template.id)) draftError = context.getString(com.timeagent.R.string.template_draft_cleanup_failed)
                    else onClose()
                } }
            }, enabled = !completed && !state.busy && name.isNotBlank() && items.isNotEmpty()) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_capture_template_dialog_004)) }
        }
    }
    editing?.let { item ->
        TemplateItemEditor(item, state, itemFields,
            onDraftChange = { itemFieldsJson = gson.toJson(it) }, onKeepDraft = ::keep,
            onDismiss = {
                val parentDirty = name != seen.name || description != seen.description || itemsJson != originalJson
                val saved = if (parentDirty) store.save(draft().copy(editingItem = null, itemFields = emptyMap()))
                    else store.remove(template.id)
                if (saved) { editingJson = null; itemFieldsJson = "{}" }
                else draftError = context.getString(com.timeagent.R.string.template_draft_write_failed)
            }, onSave = { saved ->
            update(if (items.any { it.id == saved.id }) items.map { if (it.id == saved.id) saved else it } else items + saved)
            editingJson = null
            itemFieldsJson = "{}"
        })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateItemEditor(
    item: PlanTemplateItemEntity, state: QuickEntryUiState, draft: Map<String, String>,
    onDraftChange: (Map<String, String>) -> Unit, onKeepDraft: () -> Unit,
    onDismiss: () -> Unit, onSave: (PlanTemplateItemEntity) -> Unit,
) {
    var title by rememberSaveable(item.id) { mutableStateOf(draft["title"] ?: item.title) }
    var kind by rememberSaveable(item.id) { mutableStateOf(draft["kind"] ?: item.kind) }
    var scheduleKind by rememberSaveable(item.id) { mutableStateOf(draft["scheduleKind"] ?: item.scheduleKind) }
    var day by rememberSaveable(item.id) { mutableStateOf(draft["day"] ?: (item.dayOffset + 1).toString()) }
    var minute by rememberSaveable(item.id) { mutableStateOf(draft["minute"] ?: item.startTime ?: "09:00") }
    var duration by rememberSaveable(item.id) { mutableIntStateOf(draft["duration"]?.toIntOrNull() ?: item.durationMinutes ?: 30) }
    var allDayDays by rememberSaveable(item.id) {
        mutableStateOf(draft["allDayDays"] ?: (if (item.scheduleKind == TEMPLATE_SCHEDULE_ALL_DAY) item.durationMinutes?.div(1440) ?: 1 else 1).toString())
    }
    var projectId by rememberSaveable(item.id) { mutableStateOf(if (draft.containsKey("projectId")) draft["projectId"]?.takeIf { it.isNotEmpty() } else item.projectId) }
    var tags by rememberSaveable(item.id) { mutableStateOf(draft["tags"] ?: item.tagIds) }
    var reminders by rememberSaveable(item.id) { mutableStateOf(draft["reminders"] ?: item.reminderOffsets) }
    var notes by rememberSaveable(item.id) { mutableStateOf(draft["notes"] ?: item.notes) }
    val fields = mapOf("title" to title, "kind" to kind, "scheduleKind" to scheduleKind, "day" to day,
        "minute" to minute, "duration" to duration.toString(), "allDayDays" to allDayDays,
        "projectId" to projectId.orEmpty(), "tags" to tags, "reminders" to reminders, "notes" to notes)
    SideEffect { onDraftChange(fields) }
    val originalFields = listOf(item.title, item.kind, item.scheduleKind, (item.dayOffset + 1).toString(),
        item.startTime ?: "09:00", (item.durationMinutes ?: 30).toString(),
        (if (item.scheduleKind == TEMPLATE_SCHEDULE_ALL_DAY) item.durationMinutes?.div(1440) ?: 1 else 1).toString(),
        item.projectId.orEmpty(), item.tagIds, item.reminderOffsets, item.notes)
    var asking by rememberSaveable(item.id) { mutableStateOf(false) }
    val dismiss = {
        if (!state.busy) {
            if (fields.values.toList() == originalFields) onDismiss() else asking = true
        }
    }
    val validReminders = reminders.isBlank() || reminders.split(',').all { it.trim().toIntOrNull()?.let { n -> n >= 0 } == true }
    AlertDialog(onDismissRequest = dismiss, title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_template_editor_005)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_template_editor_006)) })
                Row { listOf(TEMPLATE_KIND_SCHEDULE to "日程", TEMPLATE_KIND_TASK to "任务").forEach { (value, label) ->
                    FilterChip(kind == value, { kind = value }, label = { Text(label) })
                } }
                OutlinedTextField(day, { day = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_template_editor_007)) }, isError = day.toIntOrNull()?.let { it > 0 } != true)
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
                    OutlinedTextField(allDayDays, { allDayDays = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_template_editor_008)) },
                        isError = allDayDays.toIntOrNull()?.let { it in 1..(Int.MAX_VALUE / 1440) } != true)
                }
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_template_editor_009))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(projectId == null, { projectId = null }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_template_editor_010)) })
                    state.projects.filter { it.deletedAt == null }.forEach { project ->
                        FilterChip(projectId == project.id, { projectId = project.id }, label = { Text(project.name) })
                    }
                }
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_organization_activity_014))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.tags.forEach { tag ->
                        val selected = tags.split(',').filter { it.isNotBlank() }.toSet()
                        FilterChip(tag.id in selected, { tags = (if (tag.id in selected) selected - tag.id else selected + tag.id).joinToString(",") },
                            label = { Text(tag.name) })
                    }
                }
                OutlinedTextField(reminders, { reminders = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_template_editor_011)) }, isError = !validReminders)
                OutlinedTextField(notes, { notes = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.field_notes)) })
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
                allDayDays.toIntOrNull()?.let { it in 1..(Int.MAX_VALUE / 1440) } == true)) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_template_editor_012)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } })
    if (asking) AlertDialog(onDismissRequest = { asking = false },
        title = { Text(stringResource(com.timeagent.R.string.text_draft_exit_001)) },
        text = { Text(stringResource(com.timeagent.R.string.text_draft_exit_002)) },
        confirmButton = { Column {
            TextButton(onClick = { asking = false; onKeepDraft() }) { Text(stringResource(com.timeagent.R.string.text_draft_exit_003)) }
            TextButton(onClick = { asking = false; onDismiss() }) { Text(stringResource(com.timeagent.R.string.text_draft_exit_004)) }
        } },
        dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(com.timeagent.R.string.text_attachment_screen_023)) } })
}

internal fun templateDurationLabel(item: PlanTemplateItemEntity): String =
    if (item.kind == TEMPLATE_KIND_SCHEDULE && item.scheduleKind == TEMPLATE_SCHEDULE_ALL_DAY)
        "${item.durationMinutes?.div(1440) ?: 1} 天"
    else "${item.durationMinutes ?: 30} 分钟"
