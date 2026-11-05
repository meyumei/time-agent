package com.timeagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.timeagent.R
import com.timeagent.data.*
import com.timeagent.ui.components.DateField
import com.timeagent.ui.components.DurationField
import com.timeagent.projects.projectProgress
import com.timeagent.domain.command.CommandResult
import com.timeagent.domain.command.CommandStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val PROJECT_STATUSES = listOf("ACTIVE", "PAUSED", "COMPLETED")

/** Freezes the version shown by an open editor across configuration changes; never re-reads the latest row. */
private val projectEntitySaver: Saver<ProjectEntity?, ArrayList<String>> = Saver(
    save = { project ->
        if (project == null) arrayListOf("")
        else arrayListOf(
            project.id,
            project.name,
            project.dueAt?.toString() ?: "",
            project.status,
            project.createdAt.toString(),
            project.updatedAt.toString(),
            project.version.toString(),
            project.deletedAt?.toString() ?: ""
        )
    },
    restore = { values ->
        if (values.isEmpty() || values[0].isEmpty()) null
        else ProjectEntity(
            id = values[0],
            name = values[1],
            dueAt = values[2].toLongOrNull(),
            status = values[3],
            createdAt = values[4].toLong(),
            updatedAt = values[5].toLong(),
            version = values[6].toLong(),
            deletedAt = values[7].toLongOrNull()
        )
    }
)

private fun projectStatusLabel(status: String): String = when (status) {
    "ACTIVE" -> "进行中"
    "PAUSED" -> "已暂停"
    "COMPLETED" -> "已完成"
    else -> status
}

private fun taskStatusLabel(status: String): String = when (status) {
    "TODO" -> "待办"
    "IN_PROGRESS" -> "进行中"
    "COMPLETED" -> "已完成"
    else -> status
}

private fun formatDate(epochMillis: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toString()

private fun tryParseDate(text: String): LocalDate? =
    try {
        if (text.isEmpty()) null else LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE)
    } catch (e: Exception) {
        null
    }

private fun mondayOf(now: Instant, zone: ZoneId): LocalDate {
    val today = now.atZone(zone).toLocalDate()
    return today.minusDays((today.dayOfWeek.value - 1).toLong())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    data: LocalSnapshot,
    model: TimeViewModel,
    now: Instant,
    zone: ZoneId,
    onTask: (TaskEntity) -> Unit,
    onClose: () -> Unit,
    initialProjectId: String? = null,
    onSchedule: ((TaskEntity) -> Unit)? = null,
) {
    val context = LocalContext.current
    val deleteSnackbar = remember { SnackbarHostState() }
    var deleteUndo by remember { mutableStateOf<com.timeagent.ui.components.UndoMessage?>(null) }
    com.timeagent.ui.components.UndoSnackbar(deleteSnackbar, deleteUndo,
        onUndo = { token -> model.perform {
            val result = model.repository.commands.execute(com.timeagent.domain.command.UndoCommand(
                UUID.randomUUID().toString(), token))
            check(result.status in setOf(com.timeagent.domain.command.CommandStatus.APPLIED,
                com.timeagent.domain.command.CommandStatus.DUPLICATE)) { result.message }
        } },
        onConsumed = { if (deleteUndo?.id == it) deleteUndo = null })
    val projects = remember(data.projects) {
        data.projects.filter { it.deletedAt == null }.sortedBy { it.createdAt }
    }
    val tasks = remember(data.tasks) { data.tasks.filter { it.deletedAt == null && it.status != "CANCELLED" } }
    val placements = remember(data.placements) { data.placements.associateBy { it.taskId } }
    val weekStart = mondayOf(now, zone)

    var detailId by rememberSaveable(initialProjectId) { mutableStateOf(initialProjectId) }
    val detail = projects.firstOrNull { it.id == detailId }
    BackHandler(enabled = detailId != null) { detailId = null }
    val draftStore = remember(context) { ProjectEditorDraftStore(context) }
    var editingDraftId by rememberSaveable { mutableStateOf<String?>(null) }
    val projectDrafts = remember(editingDraftId, data.projects) { draftStore.all() }
    fun editProject(project: ProjectEntity?) {
        val draft = project?.let { draftStore.read(it.id) } ?: ProjectEditorDraft.create(project,
            project?.dueAt?.let { formatDate(it, zone) }.orEmpty())
        if (draftStore.save(draft)) editingDraftId = draft.id
        else model.report(context.getString(R.string.project_draft_write_failed))
    }
    var deleting by rememberSaveable(stateSaver = projectEntitySaver) { mutableStateOf<ProjectEntity?>(null) }
    var addingExistingFor by rememberSaveable(stateSaver = projectEntitySaver) { mutableStateOf<ProjectEntity?>(null) }
    var addingTaskFor by rememberSaveable(stateSaver = projectEntitySaver) { mutableStateOf<ProjectEntity?>(null) }
    var addingTaskParentId by rememberSaveable { mutableStateOf<String?>(null) }
    var goalFor by rememberSaveable(stateSaver = projectEntitySaver) { mutableStateOf<ProjectEntity?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(deleteSnackbar) },
        topBar = {
            TopAppBar(
                title = { Text(detail?.name ?: "项目") },
                navigationIcon = { TextButton(onClick = { if (detailId != null) detailId = null else onClose() }) { Text(if (detailId != null) "项目列表" else "返回") } },
                actions = {
                    TextButton(onClick = { model.perform { model.repository.undoLastAction() } }, enabled = data.history.any { !it.undone }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_undo)) }
                    TextButton(onClick = { model.perform { model.repository.redoLastAction() } }, enabled = data.history.any { it.undone && it.redoable }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_redo)) }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            if (detailId == null) item {
                Button(
                    onClick = { editProject(null) },
                    modifier = Modifier.testTag("project-create")
                ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_001)) }
            }
            items(projectDrafts, key = { "project-draft:${it.id}" }) { draft ->
                OutlinedButton(onClick = { editingDraftId = draft.id }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.project_draft_resume,
                        draft.name.ifBlank { stringResource(R.string.project_draft_untitled) }))
                }
            }
            if (projects.isEmpty()) {
                item { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_002)) }
            }
            items(if (detail != null) listOf(detail) else projects, key = { it.id }) { project ->
                val owned = tasks.filter { placements[it.id]?.projectId == project.id }
                val projectTasks = owned
                val weekProgress = remember(project.id, data, weekStart, now) {
                    projectProgress(data, project.id, weekStart, now, zone)
                }
                val weekTargetMinutes = remember(project.id, data.weeklyGoals, weekStart) {
                    data.weeklyGoals
                        .firstOrNull { it.projectId == project.id && it.weekStart == weekStart.toString() }
                        ?.targetMinutes?.toLong()
                }
                if (detail != null) {
                    val completed = owned.count { it.status == "COMPLETED" }
                    Text("已完成 $completed / ${owned.size} 项", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { if (owned.isEmpty()) 0f else completed.toFloat() / owned.size }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    if (project.status != "COMPLETED") {
                        val next = owned
                            .filter { it.status in setOf("TODO", "IN_PROGRESS") }
                            .sortedBy { it.dueAt ?: Long.MAX_VALUE }
                            .firstOrNull()
                        if (next != null) {
                            val plannedMinutes = data.plans
                                .filter {
                                    it.taskId == next.id && it.status == "SCHEDULED" &&
                                        it.deletedAt == null && it.kind != "POINT" && it.endAt > it.startAt
                                }
                                .sumOf { plan ->
                                    (plan.endAt - plan.startAt) / 1000L
                                } / 60
                            val nextRecords = data.records.filter { it.taskId == next.id }
                            val actualSpans = nextRecords.flatMap { data.actualSpans(it, now) }
                            val actualMinutes = actualSpans.sumOf { it.seconds } / 60
                            Button(onClick = { onTask(next) }, modifier = Modifier.fillMaxWidth().testTag("project-next-task")) { Text("下一步 · ${next.title}") }
                            Text(
                                text = stringResource(R.string.project_next_plan_actual, plannedMinutes, actualMinutes) +
                                    if (actualSpans.isEmpty()) stringResource(R.string.project_next_no_actual)
                                    else stringResource(R.string.project_next_actual_sources, nextRecords.size),
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (onSchedule != null) {
                                OutlinedButton(
                                    onClick = { onSchedule(next) },
                                    modifier = Modifier.fillMaxWidth().testTag("project-schedule-next")
                                ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_003)) }
                            }
                        }
                    }
                }
                ProjectSection(
                    project = project,
                    projectTasks = projectTasks,
                    placements = placements,
                    zone = zone,
                    weekTargetMinutes = weekTargetMinutes,
                    weekRecordedMinutes = weekProgress.recordedMinutes,
                    weekHasRecords = weekProgress.records.isNotEmpty(),
                    expanded = project.id == detailId,
                    onToggle = {
                        detailId = project.id
                    },
                    onTask = onTask,
                    onEdit = { editProject(project) },
                    onDelete = { deleting = project },
                    onAddExisting = { addingExistingFor = project },
                    onAddTask = {
                        addingTaskParentId = null
                        addingTaskFor = project
                    },
                    onAddSubtask = { parent ->
                        addingTaskParentId = parent.id
                        addingTaskFor = project
                    },
                    onGoal = { goalFor = project },
                    onUnassign = { task ->
                        model.perform(
                            action = {
                                model.repository.savePlacement(TaskPlacementEntity(taskId = task.id), placements[task.id]?.version)
                            }
                        )
                    }
                )
            }
        }
    }

    editingDraftId?.let { id ->
        val draft = remember(id) { draftStore.read(id) }
        val busy by model.busy.collectAsState()
        if (draft != null) ProjectEditDialog(
            initial = draft,
            zone = zone,
            today = now.atZone(zone).toLocalDate(),
            busy = busy,
            onDraftChange = draftStore::save,
            onRemoveDraft = { draftStore.remove(id) },
            onDismiss = { editingDraftId = null },
            onSave = { project, expectedVersion, requestId ->
                val result = model.repository.commands.execute(com.timeagent.domain.command.WriteProject(
                    requestId, com.timeagent.domain.command.ProjectMutation.Save(project, expectedVersion)))
                if (result.status in setOf(CommandStatus.APPLIED, CommandStatus.DUPLICATE)) {
                    result.undoToken?.let { token ->
                        deleteUndo = com.timeagent.ui.components.UndoMessage(result.requestId,
                            context.getString(R.string.project_draft_saved), token)
                    }
                }
                result
            })
        else LaunchedEffect(id) { editingDraftId = null }
    }

    deleting?.let { project ->
        val deleteRequestId = rememberSaveable(project.id, project.version) { UUID.randomUUID().toString() }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_004)) },
            text = {
                Text(stringResource(R.string.project_delete_confirmation, project.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.perform(
                            onSuccess = { deleting = null },
                            action = {
                                val result = model.repository.commands.execute(com.timeagent.domain.command.WriteProject(
                                    deleteRequestId, com.timeagent.domain.command.ProjectMutation.Delete(project.id, project.version)))
                                check(result.status in setOf(com.timeagent.domain.command.CommandStatus.APPLIED,
                                    com.timeagent.domain.command.CommandStatus.DUPLICATE)) { result.message }
                                result.undoToken?.let { deleteUndo = com.timeagent.ui.components.UndoMessage(
                                    result.requestId, context.getString(R.string.project_deleted_undo), it) }
                            }
                        )
                    }
                ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_005)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) }
            }
        )
    }

    addingExistingFor?.let { project ->
        AddExistingTaskDialog(
            tasks = tasks,
            placements = placements,
            onDismiss = { addingExistingFor = null },
            onConfirm = { task ->
                val existing = placements[task.id]
                model.perform(
                    onSuccess = { addingExistingFor = null },
                    action = {
                        model.repository.savePlacement(
                            TaskPlacementEntity(
                                taskId = task.id,
                                projectId = project.id,
                                parentTaskId = null,
                                version = existing?.version ?: 0
                            ),
                            expectedVersion = existing?.version
                        )
                    }
                )
            }
        )
    }

    addingTaskFor?.let { project ->
        val saving by model.busy.collectAsState()
        val newTaskId = rememberSaveable(project.id, addingTaskParentId) { UUID.randomUUID().toString() }
        val roots = tasks.filter {
            val p = placements[it.id]
            p != null && p.projectId == project.id && p.parentTaskId == null
        }
        AddTaskDialog(
            roots = roots,
            initialParentId = addingTaskParentId,
            busy = saving,
            onDismiss = {
                addingTaskFor = null
                addingTaskParentId = null
            },
            onConfirm = { title, minutes, parentId, requestId ->
                val entity = TaskEntity(
                    id = newTaskId,
                    title = title,
                    estimatedMinutes = minutes,
                    status = "TODO",
                    version = 0
                )
                model.perform(
                    onSuccess = {
                        addingTaskFor = null
                        addingTaskParentId = null
                    },
                    action = {
                        val result = model.repository.commands.execute(com.timeagent.domain.command.WriteProject(
                            requestId, com.timeagent.domain.command.ProjectMutation.CreateTask(
                                entity, project.id, project.version, parentId, parentId?.let { placements[it]?.version })))
                        check(result.status in setOf(com.timeagent.domain.command.CommandStatus.APPLIED,
                            com.timeagent.domain.command.CommandStatus.DUPLICATE)) { result.message }
                        result.undoToken?.let { token ->
                            deleteUndo = com.timeagent.ui.components.UndoMessage(result.requestId,
                                context.getString(R.string.project_task_created_undo), token)
                        }
                    }
                )
            }
        )
    }

    goalFor?.let { project ->
        WeeklyGoalDialog(
            project = project,
            projects = projects,
            activities = data.activities,
            goals = data.weeklyGoals,
            now = now,
            zone = zone,
            onDismiss = { goalFor = null },
            onSave = { goal, expectedVersion ->
                model.perform(
                    onSuccess = { goalFor = null },
                    action = { model.repository.saveWeeklyGoal(goal, expectedVersion) }
                )
            }
        )
    }
}

@Composable
private fun ProjectSection(
    project: ProjectEntity,
    projectTasks: List<TaskEntity>,
    placements: Map<String, TaskPlacementEntity>,
    zone: ZoneId,
    weekTargetMinutes: Long?,
    weekRecordedMinutes: Long,
    weekHasRecords: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onTask: (TaskEntity) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddExisting: () -> Unit,
    onAddTask: () -> Unit,
    onAddSubtask: (TaskEntity) -> Unit,
    onGoal: () -> Unit,
    onUnassign: (TaskEntity) -> Unit
) {
    val inProject = projectTasks.map { it.id }.toSet()
    val roots = projectTasks.filter { t ->
        val parent = placements[t.id]?.parentTaskId
        parent == null || parent !in inProject
    }
    val dueText = project.dueAt?.let { formatDate(it, zone) } ?: "未设置"

    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("project-${project.id}")
                    .clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = project.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${projectStatusLabel(project.status)} · 截止 $dueText · 任务 ${projectTasks.size} 个",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    text = if (expanded) "项目详情" else "查看",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            val weekText = when {
                weekTargetMinutes != null && weekHasRecords -> stringResource(
                    R.string.project_week_progress,
                    weekRecordedMinutes,
                    weekTargetMinutes,
                    (weekRecordedMinutes * 100 / weekTargetMinutes).coerceAtMost(100)
                )
                weekTargetMinutes != null -> stringResource(R.string.project_week_target_no_records, weekTargetMinutes)
                weekHasRecords -> stringResource(R.string.project_week_no_target_progress, weekRecordedMinutes)
                else -> stringResource(R.string.project_week_no_target_no_records)
            }
            Text(text = weekText, style = MaterialTheme.typography.bodySmall)
            if (weekTargetMinutes != null && weekTargetMinutes > 0) {
                LinearProgressIndicator(
                    progress = { (weekRecordedMinutes.toFloat() / weekTargetMinutes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onEdit) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_chat_extraction_screen_006)) }
                    TextButton(onClick = onDelete) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_delete)) }
                    TextButton(onClick = onGoal) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_014)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = onAddExisting,
                        modifier = Modifier.testTag("project-add-existing-${project.id}")
                    ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_006)) }
                    TextButton(
                        onClick = onAddTask,
                        modifier = Modifier.testTag("project-add-task-${project.id}")
                    ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_new_task)) }
                }
                Text(
                    text = androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_007),
                    style = MaterialTheme.typography.bodySmall
                )

                if (roots.isEmpty()) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_008),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    roots.forEach { root ->
                        TaskRow(
                            task = root,
                            indented = false,
                            onOpen = { onTask(root) },
                            onUnassign = { onUnassign(root) },
                            onAddSubtask = { onAddSubtask(root) }
                        )
                        projectTasks
                            .filter { it.id != root.id && placements[it.id]?.parentTaskId == root.id }
                            .forEach { child ->
                                TaskRow(
                                    task = child,
                                    indented = true,
                                    onOpen = { onTask(child) },
                                    onUnassign = { onUnassign(child) },
                                    onAddSubtask = null
                                )
                            }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: TaskEntity,
    indented: Boolean,
    onOpen: () -> Unit,
    onUnassign: () -> Unit,
    onAddSubtask: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indented) 20.dp else 0.dp, top = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (indented) "└ ${task.title}" else task.title,
                modifier = Modifier.weight(1f).clickable(onClick = onOpen)
            )
            TextButton(onClick = onOpen) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_focus_screen_017)) }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "预计 ${task.estimatedMinutes} 分钟 · ${taskStatusLabel(task.status)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onUnassign) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_009)) }
            if (onAddSubtask != null) {
                TextButton(onClick = onAddSubtask) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_010)) }
            }
        }
    }
}

@Composable
private fun ProjectEditDialog(
    initial: ProjectEditorDraft,
    zone: ZoneId,
    today: LocalDate,
    busy: Boolean,
    onDraftChange: (ProjectEditorDraft) -> Boolean,
    onRemoveDraft: () -> Boolean,
    onDismiss: () -> Unit,
    onSave: suspend (ProjectEntity, Long?, String) -> CommandResult,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var submitting by remember { mutableStateOf(false) }
    var completed by remember(initial.id) { mutableStateOf(initial.saved) }
    var error by remember { mutableStateOf<String?>(null) }
    val name = draft.name
    val dueText = draft.dueText
    val status = draft.status
    val editing = !busy && !submitting && !completed
    fun update(next: ProjectEditorDraft) {
        if (busy || submitting || completed || next == draft) return
        draft = next.copy(requestId = UUID.randomUUID().toString())
        error = if (onDraftChange(draft)) null else context.getString(R.string.project_draft_write_failed)
    }
    fun cleanup() {
        if (onRemoveDraft()) onDismiss()
        else error = context.getString(if (completed) R.string.project_draft_cleanup_failed else R.string.project_draft_write_failed)
    }
    val dismiss = rememberDraftExit(initial.id, listOf(name, dueText, status), !editing,
        onDiscard = ::cleanup,
        onKeep = {
            if (onDraftChange(draft)) onDismiss()
            else error = context.getString(R.string.project_draft_write_failed)
        }, initialFields = listOf(initial.original?.name.orEmpty(), initial.initialDueText,
            initial.original?.status ?: "ACTIVE"))

    AlertDialog(
        onDismissRequest = { if (completed) cleanup() else dismiss() },
        title = {
            Text(
                stringResource(
                    if (initial.original == null) R.string.project_editor_title_create
                    else R.string.project_editor_title_edit
                )
            )
        },
        text = {
            if (completed) Column {
                Text(stringResource(R.string.project_draft_saved))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } else Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { update(draft.copy(name = it)) },
                    enabled = editing,
                    label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_011)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("project-name")
                )
                Spacer(Modifier.height(8.dp))
                val dueDate = tryParseDate(dueText.trim())
                if (dueText.isBlank()) {
                    OutlinedButton(
                        onClick = { update(draft.copy(dueText = today.toString())) },
                        enabled = editing,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.project_editor_due_set)) }
                } else {
                    DateField(
                        label = stringResource(R.string.text_project_screens_012),
                        value = dueDate ?: today,
                        onValueChange = { update(draft.copy(dueText = it.toString())) },
                        error = if (dueDate == null) context.getString(R.string.project_editor_error_due_format) else null
                    )
                    TextButton(onClick = { update(draft.copy(dueText = "")) }, enabled = editing) {
                        Text(stringResource(R.string.project_editor_due_clear))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(text = androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_013), style = MaterialTheme.typography.bodySmall)
                Row {
                    PROJECT_STATUSES.forEach { option ->
                        val label = when (option) {
                            "ACTIVE" -> stringResource(R.string.project_editor_status_active)
                            "PAUSED" -> stringResource(R.string.project_editor_status_paused)
                            "COMPLETED" -> stringResource(R.string.project_editor_status_completed)
                            else -> option
                        }
                        TextButton(onClick = { update(draft.copy(status = option)) }, enabled = editing) {
                            Text(
                                text = if (status == option) stringResource(R.string.project_editor_option_selected, label)
                                else label
                            )
                        }
                    }
                }
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (completed) { cleanup(); return@TextButton }
                    if (busy || submitting) return@TextButton
                    val pending = draft
                    val trimmedName = pending.name.trim()
                    if (trimmedName.isEmpty()) {
                        error = context.getString(R.string.project_editor_error_name_required)
                        return@TextButton
                    }
                    val due = pending.dueText.trim()
                    val dueAt: Long?
                    if (due.isEmpty()) {
                        dueAt = null
                    } else {
                        val parsed = tryParseDate(due)
                        if (parsed == null) {
                            error = context.getString(R.string.project_editor_error_due_format)
                            return@TextButton
                        }
                        dueAt = parsed.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
                    }
                    if (!onDraftChange(pending)) {
                        error = context.getString(R.string.project_draft_write_failed)
                        return@TextButton
                    }
                    val original = pending.original
                    val project = (original ?: ProjectEntity(pending.id, "")).copy(
                        name = trimmedName, dueAt = dueAt, status = pending.status)
                    val requestId = pending.requestId
                    submitting = true
                    scope.launch {
                        try {
                            val result = onSave(project, original?.version, requestId)
                            if (result.status in setOf(CommandStatus.APPLIED, CommandStatus.DUPLICATE)) {
                                completed = true
                                draft = pending.copy(saved = true)
                                onDraftChange(draft)
                                cleanup()
                            } else error = result.message
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            error = failure.message ?: context.getString(R.string.project_draft_save_failed)
                        } finally {
                            submitting = false
                        }
                    }
                },
                enabled = !busy && !submitting,
                modifier = Modifier.testTag("project-save")
            ) { Text(stringResource(if (completed) R.string.action_close else R.string.action_save)) }
        },
        dismissButton = {
            if (!completed) TextButton(onClick = dismiss, enabled = editing) {
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun AddExistingTaskDialog(
    tasks: List<TaskEntity>,
    placements: Map<String, TaskPlacementEntity>,
    onDismiss: () -> Unit,
    onConfirm: (TaskEntity) -> Unit
) {
    val candidates = remember(tasks, placements) {
        tasks.filter { t ->
            val p = placements[t.id]
            p == null || (p.projectId == null && p.parentTaskId == null)
        }.sortedBy { it.title }
    }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_006)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 400.dp)) {
                Text(
                    text = androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_014),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                if (candidates.isEmpty()) {
                    Text(text = androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_015), style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                    ) {
                        items(candidates, key = { it.id }) { task ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedId = task.id }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedId == task.id,
                                    onClick = { selectedId = task.id }
                                )
                                Text(text = task.title)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val target = candidates.firstOrNull { it.id == selectedId }
                    if (target != null) onConfirm(target)
                },
                enabled = selectedId != null
            ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_016)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) }
        }
    )
}

@Composable
private fun AddTaskDialog(
    roots: List<TaskEntity>,
    initialParentId: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (title: String, minutes: Int, parentId: String?, requestId: String) -> Unit
) {
    val context = LocalContext.current
    var title by rememberSaveable { mutableStateOf("") }
    var minutesText by rememberSaveable { mutableStateOf("30") }
    var parentId by rememberSaveable { mutableStateOf(initialParentId) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val requestId = rememberSaveable(title, minutesText, parentId) { UUID.randomUUID().toString() }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_new_task)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; error = null },
                    enabled = !busy,
                    label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_editors_009)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                DurationField(
                    minutes = minutesText.trim().toIntOrNull()?.takeIf { it > 0 } ?: 0,
                    onChange = { },
                    textValue = minutesText,
                    onTextChange = { if (!busy) { minutesText = it; error = null } }
                )
                Spacer(Modifier.height(8.dp))
                Text(text = androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_018), style = MaterialTheme.typography.bodySmall)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy) { parentId = null }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = parentId == null, onClick = { parentId = null }, enabled = !busy)
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_019))
                }
                roots.forEach { root ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy) { parentId = root.id }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = parentId == root.id, onClick = { parentId = root.id }, enabled = !busy)
                        Text(root.title)
                    }
                }
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = title.trim()
                    if (trimmed.isEmpty()) {
                        error = context.getString(R.string.project_editor_error_task_name_required)
                        return@TextButton
                    }
                    val minutes = minutesText.trim().toIntOrNull()
                    if (minutes == null || minutes <= 0) {
                        error = context.getString(R.string.project_editor_error_minutes_positive)
                        return@TextButton
                    }
                    onConfirm(trimmed, minutes, parentId, requestId)
                },
                enabled = !busy
            ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) }
        }
    )
}

@Composable
internal fun WeeklyGoalDialog(
    project: ProjectEntity?,
    projects: List<ProjectEntity>,
    activities: List<ActivityTemplateEntity>,
    goals: List<WeeklyGoalEntity>,
    now: Instant,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onSave: (WeeklyGoalEntity, Long?) -> Unit
) {
    val context = LocalContext.current
    var useProject by rememberSaveable { mutableStateOf(project != null) }
    val seenGoalsJson = rememberSaveable { com.google.gson.Gson().toJson(goals) }
    val seenGoals = remember(seenGoalsJson) {
        com.google.gson.Gson().fromJson(seenGoalsJson, Array<WeeklyGoalEntity>::class.java).toList()
    }
    var selectedProjectId by rememberSaveable { mutableStateOf<String?>(project?.id) }
    var selectedActivityId by rememberSaveable { mutableStateOf<String?>(null) }
    var weekText by rememberSaveable { mutableStateOf(mondayOf(now, zone).toString()) }
    var minutesText by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_014)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(text = androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_020), style = MaterialTheme.typography.bodySmall)
                Row {
                    val projectType = stringResource(R.string.project_editor_goal_type_project)
                    val activityType = stringResource(R.string.project_editor_goal_type_activity)
                    TextButton(onClick = { useProject = true; error = null }) {
                        Text(
                            if (useProject) stringResource(R.string.project_editor_option_selected, projectType)
                            else projectType
                        )
                    }
                    TextButton(onClick = { useProject = false; error = null }) {
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
                                .clickable { selectedProjectId = p.id; error = null }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedProjectId == p.id,
                                onClick = { selectedProjectId = p.id; error = null }
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
                                .clickable { selectedActivityId = activity.id; error = null }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedActivityId == activity.id,
                                onClick = { selectedActivityId = activity.id; error = null }
                            )
                            Text(activity.name)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                val parsedWeek = tryParseDate(weekText.trim())
                DateField(
                    label = stringResource(R.string.text_project_screens_022),
                    value = parsedWeek ?: mondayOf(now, zone),
                    onValueChange = { weekText = it.toString(); error = null },
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
                    onTextChange = { minutesText = it; error = null }
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
                    val weekDate = tryParseDate(weekText.trim())
                    if (weekDate == null) {
                        error = context.getString(R.string.project_editor_error_week_format)
                        return@TextButton
                    }
                    if (weekDate.dayOfWeek != DayOfWeek.MONDAY) {
                        error = context.getString(R.string.project_editor_error_week_monday)
                        return@TextButton
                    }
                    val minutes = minutesText.trim().toIntOrNull()
                    if (minutes == null || minutes <= 0) {
                        error = context.getString(R.string.project_editor_error_goal_minutes_positive)
                        return@TextButton
                    }
                    val targetProjectId = if (useProject) selectedProjectId else null
                    val targetActivityId = if (useProject) null else selectedActivityId
                    if (targetProjectId == null && targetActivityId == null) {
                        error = context.getString(R.string.project_editor_error_goal_target_required)
                        return@TextButton
                    }
                    val week = weekDate.toString()
                    val existing = seenGoals.firstOrNull {
                        it.weekStart == week &&
                            it.projectId == targetProjectId &&
                            it.activityId == targetActivityId
                    }
                    val goal = WeeklyGoalEntity(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        projectId = targetProjectId,
                        activityId = targetActivityId,
                        weekStart = week,
                        targetMinutes = minutes,
                        version = existing?.version ?: 0
                    )
                    onSave(goal, existing?.version)
                }
            ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) }
        }
    )
}

