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
    val projects = remember(data.projects) {
        data.projects.filter { it.deletedAt == null }.sortedBy { it.createdAt }
    }
    val tasks = remember(data.tasks) { data.tasks.filter { it.deletedAt == null && it.status != "CANCELLED" } }
    val placements = remember(data.placements) { data.placements.associateBy { it.taskId } }
    val weekStart = mondayOf(now, zone)

    var detailId by rememberSaveable(initialProjectId) { mutableStateOf(initialProjectId) }
    val detail = projects.firstOrNull { it.id == detailId }
    BackHandler(enabled = detailId != null) { detailId = null }
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable(stateSaver = projectEntitySaver) { mutableStateOf<ProjectEntity?>(null) }
    var deleting by rememberSaveable(stateSaver = projectEntitySaver) { mutableStateOf<ProjectEntity?>(null) }
    var addingExistingFor by rememberSaveable(stateSaver = projectEntitySaver) { mutableStateOf<ProjectEntity?>(null) }
    var addingTaskFor by rememberSaveable(stateSaver = projectEntitySaver) { mutableStateOf<ProjectEntity?>(null) }
    var addingTaskParentId by rememberSaveable { mutableStateOf<String?>(null) }
    var goalFor by rememberSaveable(stateSaver = projectEntitySaver) { mutableStateOf<ProjectEntity?>(null) }

    Scaffold(
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
                    onClick = { showCreate = true },
                    modifier = Modifier.testTag("project-create")
                ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_001)) }
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
                    onEdit = { editing = project },
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

    if (showCreate) {
        ProjectEditDialog(
            initial = null,
            zone = zone,
            today = now.atZone(zone).toLocalDate(),
            onDismiss = { showCreate = false },
            onSave = { name, dueAt, status ->
                val stamp = now.toEpochMilli()
                model.perform(
                    onSuccess = { showCreate = false },
                    action = {
                        model.repository.saveProject(
                            ProjectEntity(
                                id = UUID.randomUUID().toString(),
                                name = name,
                                dueAt = dueAt,
                                status = status,
                                createdAt = stamp,
                                updatedAt = stamp,
                                version = 0,
                                deletedAt = null
                            ),
                            expectedVersion = null
                        )
                    }
                )
            }
        )
    }

    editing?.let { project ->
        ProjectEditDialog(
            initial = project,
            zone = zone,
            today = now.atZone(zone).toLocalDate(),
            onDismiss = { editing = null },
            onSave = { name, dueAt, status ->
                model.perform(
                    onSuccess = { editing = null },
                    action = {
                        model.repository.saveProject(
                            ProjectEntity(
                                id = project.id,
                                name = name,
                                dueAt = dueAt,
                                status = status,
                                createdAt = project.createdAt,
                                updatedAt = now.toEpochMilli(),
                                version = project.version,
                                deletedAt = null
                            ),
                            expectedVersion = project.version
                        )
                    }
                )
            }
        )
    }

    deleting?.let { project ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_004)) },
            text = {
                Text("确定删除「${project.name}」吗？这是软删除：项目记录会保留，可用应用顶部的撤销按钮恢复；项目下的任务与历史归属保留，可在任务列表继续使用。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.perform(
                            onSuccess = { deleting = null },
                            action = { model.repository.deleteProject(project.id, project.version) }
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
        val roots = tasks.filter {
            val p = placements[it.id]
            p != null && p.projectId == project.id && p.parentTaskId == null
        }
        AddTaskDialog(
            roots = roots,
            initialParentId = addingTaskParentId,
            onDismiss = {
                addingTaskFor = null
                addingTaskParentId = null
            },
            onConfirm = { title, minutes, parentId ->
                val newTaskId = UUID.randomUUID().toString()
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
                        model.repository.assistantTransaction(UUID.randomUUID().toString()) {
                            model.repository.saveTask(entity, expectedVersion = null)
                            model.repository.savePlacement(
                                TaskPlacementEntity(
                                    taskId = newTaskId,
                                    projectId = project.id,
                                    parentTaskId = parentId,
                                    version = 0
                                ),
                                expectedVersion = null
                            )
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
    initial: ProjectEntity?,
    zone: ZoneId,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (name: String, dueAt: Long?, status: String) -> Unit
) {
    val context = LocalContext.current
    var name by rememberSaveable(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var dueText by rememberSaveable(initial?.id) {
        mutableStateOf(initial?.dueAt?.let { formatDate(it, zone) } ?: "")
    }
    var status by rememberSaveable(initial?.id) { mutableStateOf(initial?.status ?: "ACTIVE") }
    var error by rememberSaveable(initial?.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.project_editor_title_create
                    else R.string.project_editor_title_edit
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
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
                        onClick = { dueText = today.toString(); error = null },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.project_editor_due_set)) }
                } else {
                    DateField(
                        label = stringResource(R.string.text_project_screens_012),
                        value = dueDate ?: today,
                        onValueChange = { dueText = it.toString(); error = null },
                        error = if (dueDate == null) context.getString(R.string.project_editor_error_due_format) else null
                    )
                    TextButton(onClick = { dueText = ""; error = null }) {
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
                        TextButton(onClick = { status = option; error = null }) {
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
                    val trimmedName = name.trim()
                    if (trimmedName.isEmpty()) {
                        error = context.getString(R.string.project_editor_error_name_required)
                        return@TextButton
                    }
                    val due = dueText.trim()
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
                    onSave(trimmedName, dueAt, status)
                },
                modifier = Modifier.testTag("project-save")
            ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) }
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
    onDismiss: () -> Unit,
    onConfirm: (title: String, minutes: Int, parentId: String?) -> Unit
) {
    val context = LocalContext.current
    var title by rememberSaveable { mutableStateOf("") }
    var minutesText by rememberSaveable { mutableStateOf("30") }
    var parentId by rememberSaveable { mutableStateOf(initialParentId) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
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
                    label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_editors_009)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                DurationField(
                    minutes = minutesText.trim().toIntOrNull()?.takeIf { it > 0 } ?: 0,
                    onChange = { },
                    textValue = minutesText,
                    onTextChange = { minutesText = it; error = null }
                )
                Spacer(Modifier.height(8.dp))
                Text(text = androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_018), style = MaterialTheme.typography.bodySmall)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { parentId = null }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = parentId == null, onClick = { parentId = null })
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_019))
                }
                roots.forEach { root ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { parentId = root.id }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = parentId == root.id, onClick = { parentId = root.id })
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
                    onConfirm(trimmed, minutes, parentId)
                }
            ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) }
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

