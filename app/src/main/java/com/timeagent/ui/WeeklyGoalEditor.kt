package com.timeagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.timeagent.R
import com.timeagent.data.*
import com.timeagent.domain.command.*
import com.timeagent.ui.components.DateField
import com.timeagent.ui.components.DurationField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private fun parseGoalDate(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()

@Composable
internal fun WeeklyGoalEditor(
    project: ProjectEntity?,
    data: LocalSnapshot,
    model: TimeViewModel,
    now: Instant,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onSaved: (CommandResult) -> Unit = {},
) {
    val context = LocalContext.current
    val store = remember(context) { WeeklyGoalEditorDraftStore(context) }
    val projects = data.projects.filter { it.deletedAt == null }
    val activities = data.activities.filter { it.archivedAt == null }
    var editingId by rememberSaveable(project?.id) { mutableStateOf<String?>(null) }
    val drafts = remember(project?.id) { store.all().filter { project == null || it.scopeProjectId == project.id || it.fields.projectId == project.id } }
    var error by remember { mutableStateOf<String?>(null) }
    fun create() {
        val week = now.atZone(zone).toLocalDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val draft = WeeklyGoalEditorDraft.create(project?.id, week.toString(), data.weeklyGoals)
        if (store.save(draft)) editingId = draft.id
        else error = context.getString(R.string.goal_draft_write_failed)
    }
    LaunchedEffect(Unit) { if (editingId == null && drafts.isEmpty()) create() }
    val id = editingId
    if (id == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.goal_draft_picker_title)) },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                drafts.forEach { draft ->
                    val fields = draft.fields
                    val target = if (fields.useProject) projects.find { it.id == fields.projectId }?.name
                        else activities.find { it.id == fields.activityId }?.name
                    TextButton(onClick = { editingId = draft.id }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.goal_draft_resume,
                            target ?: stringResource(R.string.goal_draft_no_target), fields.weekText))
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(onClick = ::create) { Text(stringResource(R.string.goal_draft_create)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
    } else {
        val draft = remember(id) { store.read(id) }
        val busy by model.busy.collectAsState()
        if (draft != null) key(id) {
            WeeklyGoalDialog(draft, projects, activities, now.atZone(zone).toLocalDate(), busy,
                onDraftChange = store::save,
                onRemoveDraft = { store.remove(id) },
                onDismiss = onDismiss,
                onSave = { goal, expectedVersion, requestId ->
                    val result = model.repository.commands.execute(WriteProject(requestId, ProjectMutation.SaveGoal(goal, expectedVersion)))
                    if (result.status in setOf(CommandStatus.APPLIED, CommandStatus.DUPLICATE)) onSaved(result)
                    result
                })
        } else LaunchedEffect(id) { editingId = null; error = context.getString(R.string.goal_draft_write_failed) }
    }
}

@Composable
private fun WeeklyGoalDialog(
    initial: WeeklyGoalEditorDraft,
    projects: List<ProjectEntity>,
    activities: List<ActivityTemplateEntity>,
    today: LocalDate,
    busy: Boolean,
    onDraftChange: (WeeklyGoalEditorDraft) -> Boolean,
    onRemoveDraft: () -> Boolean,
    onDismiss: () -> Unit,
    onSave: suspend (WeeklyGoalEntity, Long?, String) -> CommandResult,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var completed by remember(initial.id) { mutableStateOf(initial.saved) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val useProject = draft.fields.useProject
    val selectedProjectId = draft.fields.projectId
    val selectedActivityId = draft.fields.activityId
    val weekText = draft.fields.weekText
    val minutesText = draft.fields.minutesText
    val editing = !busy && !submitting && !completed
    fun update(fields: WeeklyGoalFields) {
        if (!editing || fields == draft.fields) return
        draft = draft.copy(fields = fields, requestId = UUID.randomUUID().toString())
        error = if (onDraftChange(draft)) null else context.getString(R.string.goal_draft_write_failed)
    }
    fun cleanup() {
        if (onRemoveDraft()) onDismiss()
        else error = context.getString(if (completed) R.string.goal_draft_cleanup_failed else R.string.goal_draft_write_failed)
    }
    val dismiss = rememberDraftExit(initial.id, draft.fields.values(), !editing,
        onDiscard = ::cleanup,
        onKeep = {
            if (onDraftChange(draft)) onDismiss()
            else error = context.getString(R.string.goal_draft_write_failed)
        }, initialFields = initial.baseline.values())

    AlertDialog(
        onDismissRequest = { if (completed) cleanup() else dismiss() },
        title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_014)) },
        text = {
            if (completed) Column {
                Text(stringResource(R.string.goal_draft_saved))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } else Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(text = androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_020), style = MaterialTheme.typography.bodySmall)
                Row {
                    val projectType = stringResource(R.string.project_editor_goal_type_project)
                    val activityType = stringResource(R.string.project_editor_goal_type_activity)
                    TextButton(onClick = { update(draft.fields.copy(useProject = true)) }, enabled = editing) {
                        Text(
                            if (useProject) stringResource(R.string.project_editor_option_selected, projectType)
                            else projectType
                        )
                    }
                    TextButton(onClick = { update(draft.fields.copy(useProject = false)) }, enabled = editing) {
                        Text(
                            if (!useProject) stringResource(R.string.project_editor_option_selected, activityType)
                            else activityType
                        )
                    }
                }
                if (useProject) {
                    projects.forEach { p ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = editing) { update(draft.fields.copy(projectId = p.id)) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedProjectId == p.id, enabled = editing,
                                onClick = { update(draft.fields.copy(projectId = p.id)) }
                            )
                            Text(p.name)
                        }
                    }
                } else {
                    if (activities.isEmpty()) {
                        Text(text = androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_021), style = MaterialTheme.typography.bodySmall)
                    }
                    activities.forEach { activity ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = editing) { update(draft.fields.copy(activityId = activity.id)) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedActivityId == activity.id, enabled = editing,
                                onClick = { update(draft.fields.copy(activityId = activity.id)) }
                            )
                            Text(activity.name)
                        }
                    }
                }
                val targetMissing = if (useProject) {
                    selectedProjectId != null && projects.none { it.id == selectedProjectId }
                } else selectedActivityId != null && activities.none { it.id == selectedActivityId }
                if (targetMissing) Text(stringResource(R.string.goal_draft_missing_target), color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                val parsedWeek = parseGoalDate(weekText.trim())
                DateField(
                    label = stringResource(R.string.text_project_screens_022),
                    value = parsedWeek ?: today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                    onValueChange = { update(draft.fields.copy(weekText = it.toString())) },
                    error = if (weekText.isNotBlank() && parsedWeek == null) {
                        context.getString(R.string.project_editor_error_week_format)
                    } else {
                        null
                    }
                )
                Spacer(Modifier.height(8.dp))
                DurationField(
                    minutes = minutesText.trim().toIntOrNull()?.takeIf { it > 0 } ?: 0,
                    onChange = { },
                    textValue = minutesText,
                    onTextChange = { update(draft.fields.copy(minutesText = it)) }
                )
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    text = androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_024),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (completed) { cleanup(); return@TextButton }
                    if (busy || submitting) return@TextButton
                    val pending = draft
                    val fields = pending.fields
                    val weekDate = parseGoalDate(fields.weekText.trim())
                    if (weekDate == null) {
                        error = context.getString(R.string.project_editor_error_week_format)
                        return@TextButton
                    }
                    if (weekDate.dayOfWeek != DayOfWeek.MONDAY) {
                        error = context.getString(R.string.project_editor_error_week_monday)
                        return@TextButton
                    }
                    val minutes = fields.minutesText.trim().toIntOrNull()
                    if (minutes == null || minutes <= 0) {
                        error = context.getString(R.string.project_editor_error_goal_minutes_positive)
                        return@TextButton
                    }
                    val targetProjectId = if (fields.useProject) fields.projectId else null
                    val targetActivityId = if (fields.useProject) null else fields.activityId
                    if (targetProjectId == null && targetActivityId == null) {
                        error = context.getString(R.string.project_editor_error_goal_target_required)
                        return@TextButton
                    }
                    val week = weekDate.toString()
                    val existing = pending.seenGoals.firstOrNull {
                        it.weekStart == week &&
                            it.projectId == targetProjectId &&
                            it.activityId == targetActivityId
                    }
                    val goal = WeeklyGoalEntity(
                        id = existing?.id ?: pending.id,
                        projectId = targetProjectId,
                        activityId = targetActivityId,
                        weekStart = week,
                        targetMinutes = minutes,
                        version = existing?.version ?: 0
                    )
                    if (!onDraftChange(pending)) {
                        error = context.getString(R.string.goal_draft_write_failed)
                        return@TextButton
                    }
                    submitting = true
                    scope.launch {
                        try {
                            val result = onSave(goal, existing?.version, pending.requestId)
                            if (result.status in setOf(CommandStatus.APPLIED, CommandStatus.DUPLICATE)) {
                                completed = true
                                draft = pending.copy(saved = true)
                                onDraftChange(draft)
                                cleanup()
                            } else error = result.message
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            error = failure.message ?: context.getString(R.string.goal_draft_save_failed)
                        } finally {
                            submitting = false
                        }
                    }
                },
                enabled = !busy && !submitting
            ) { Text(stringResource(if (completed) R.string.action_close else R.string.action_save)) }
        },
        dismissButton = {
            if (!completed) TextButton(onClick = dismiss, enabled = editing) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

