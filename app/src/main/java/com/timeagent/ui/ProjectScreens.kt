package com.timeagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.timeagent.data.*
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val PROJECT_STATUSES = listOf("ACTIVE", "PAUSED", "COMPLETED")

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

    var detailId by rememberSaveable(initialProjectId) { mutableStateOf(initialProjectId) }
    val detail = projects.firstOrNull { it.id == detailId }
    BackHandler(enabled = detailId != null) { detailId = null }
    var showCreate by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ProjectEntity?>(null) }
    var deleting by remember { mutableStateOf<ProjectEntity?>(null) }
    var addingExistingFor by remember { mutableStateOf<ProjectEntity?>(null) }
    var addingTaskFor by remember { mutableStateOf<ProjectEntity?>(null) }
    var addingTaskParentId by remember { mutableStateOf<String?>(null) }
    var goalFor by remember { mutableStateOf<ProjectEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.name ?: "项目") },
                navigationIcon = { TextButton(onClick = { if (detailId != null) detailId = null else onClose() }) { Text(if (detailId != null) "项目列表" else "返回") } },
                actions = {
                    TextButton(onClick = { model.perform { model.repository.undoLastAction() } }, enabled = data.history.any { !it.undone }) { Text("撤销") }
                    TextButton(onClick = { model.perform { model.repository.redoLastAction() } }, enabled = data.history.any { it.undone && it.redoable }) { Text("重做") }
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
                ) { Text("新建项目") }
            }
            if (projects.isEmpty()) {
                item { Text("还没有项目，先新建一个。") }
            }
            items(if (detail != null) listOf(detail) else projects, key = { it.id }) { project ->
                val owned = tasks.filter { placements[it.id]?.projectId == project.id }
                val projectTasks = owned
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
                                .filter { it.taskId == next.id && it.status == "SCHEDULED" && it.endAt > it.startAt }
                                .sumOf { (it.endAt - it.startAt) / 60_000L }
                            val nextRecords = data.records.filter { it.taskId == next.id }
                            val actualMinutes = nextRecords.sumOf { record ->
                                val start = Instant.ofEpochMilli(record.startAt)
                                val end = minOf(record.endAt?.let(Instant::ofEpochMilli) ?: now, now)
                                if (end.isAfter(start)) Duration.between(start, end).seconds / 60 else 0L
                            }
                            Button(onClick = { onTask(next) }, modifier = Modifier.fillMaxWidth().testTag("project-next-task")) { Text("下一步 · ${next.title}") }
                            Text(
                                text = "下一步投入：计划 $plannedMinutes 分钟 · 实际 $actualMinutes 分钟" +
                                    if (nextRecords.isEmpty()) "（暂无实际记录）" else "（实际来自 ${nextRecords.size} 条记录）",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (onSchedule != null) {
                                OutlinedButton(
                                    onClick = { onSchedule(next) },
                                    modifier = Modifier.fillMaxWidth().testTag("project-schedule-next")
                                ) { Text("安排下一步") }
                            }
                        }
                    }
                }
                ProjectSection(
                    project = project,
                    projectTasks = projectTasks,
                    placements = placements,
                    zone = zone,
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
            title = { Text("删除项目") },
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
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
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

            if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onEdit) { Text("修改") }
                    TextButton(onClick = onDelete) { Text("删除") }
                    TextButton(onClick = onGoal) { Text("周目标") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = onAddExisting,
                        modifier = Modifier.testTag("project-add-existing-${project.id}")
                    ) { Text("加入已有任务") }
                    TextButton(
                        onClick = onAddTask,
                        modifier = Modifier.testTag("project-add-task-${project.id}")
                    ) { Text("新建任务") }
                }
                Text(
                    text = "缩进的行是一级子任务。点「打开」进入任务详情后可计时、排程或完成；完成子任务不会自动完成父任务。",
                    style = MaterialTheme.typography.bodySmall
                )

                if (roots.isEmpty()) {
                    Text(
                        text = "该项目暂无归属任务，可用上方按钮新建或加入已有任务。",
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
            TextButton(onClick = onOpen) { Text("打开") }
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
            TextButton(onClick = onUnassign) { Text("解除归属") }
            if (onAddSubtask != null) {
                TextButton(onClick = onAddSubtask) { Text("新增子任务") }
            }
        }
    }
}

@Composable
private fun ProjectEditDialog(
    initial: ProjectEntity?,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onSave: (name: String, dueAt: Long?, status: String) -> Unit
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var dueText by remember(initial?.id) {
        mutableStateOf(initial?.dueAt?.let { formatDate(it, zone) } ?: "")
    }
    var status by remember(initial?.id) { mutableStateOf(initial?.status ?: "ACTIVE") }
    var error by remember(initial?.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建项目" else "修改项目") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("项目名称") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("project-name")
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = dueText,
                    onValueChange = { dueText = it; error = null },
                    label = { Text("截止日期 yyyy-MM-dd（可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(text = "状态", style = MaterialTheme.typography.bodySmall)
                Row {
                    PROJECT_STATUSES.forEach { option ->
                        TextButton(onClick = { status = option; error = null }) {
                            Text(
                                text = if (status == option) "✓ ${projectStatusLabel(option)}"
                                else projectStatusLabel(option)
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
                        error = "项目名称不能为空"
                        return@TextButton
                    }
                    val due = dueText.trim()
                    val dueAt: Long?
                    if (due.isEmpty()) {
                        dueAt = null
                    } else {
                        val parsed = tryParseDate(due)
                        if (parsed == null) {
                            error = "截止日期格式应为 yyyy-MM-dd"
                            return@TextButton
                        }
                        dueAt = parsed.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
                    }
                    onSave(trimmedName, dueAt, status)
                },
                modifier = Modifier.testTag("project-save")
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
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
    var selectedId by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入已有任务") },
        text = {
            Column(modifier = Modifier.heightIn(max = 400.dp)) {
                Text(
                    text = "只列出未归属任何项目的任务；已在其他项目里的任务不会显示，子任务也不能单独移入。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                if (candidates.isEmpty()) {
                    Text(text = "暂无可加入的任务。", style = MaterialTheme.typography.bodySmall)
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
            ) { Text("加入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
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
    var title by remember { mutableStateOf("") }
    var minutesText by remember { mutableStateOf("30") }
    var parentId by remember { mutableStateOf(initialParentId) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建任务") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; error = null },
                    label = { Text("任务名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { minutesText = it; error = null },
                    label = { Text("预计分钟") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(text = "父任务（可选，仅支持一级子任务）", style = MaterialTheme.typography.bodySmall)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { parentId = null }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = parentId == null, onClick = { parentId = null })
                    Text("作为根任务")
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
                        error = "任务名称不能为空"
                        return@TextButton
                    }
                    val minutes = minutesText.trim().toIntOrNull()
                    if (minutes == null || minutes <= 0) {
                        error = "预计分钟必须为正整数"
                        return@TextButton
                    }
                    onConfirm(trimmed, minutes, parentId)
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
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
    var useProject by remember { mutableStateOf(project != null) }
    var selectedProjectId by remember { mutableStateOf<String?>(project?.id) }
    var selectedActivityId by remember { mutableStateOf<String?>(null) }
    var weekText by remember { mutableStateOf(mondayOf(now, zone).toString()) }
    var minutesText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("周目标") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(text = "目标类型（二选一）", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { useProject = true; error = null }) {
                        Text(if (useProject) "✓ 项目" else "项目")
                    }
                    TextButton(onClick = { useProject = false; error = null }) {
                        Text(if (!useProject) "✓ 活动" else "活动")
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
                        Text(text = "暂无活动模板。", style = MaterialTheme.typography.bodySmall)
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
                OutlinedTextField(
                    value = weekText,
                    onValueChange = { weekText = it; error = null },
                    label = { Text("周起始（周一）yyyy-MM-dd") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { minutesText = it; error = null },
                    label = { Text("目标分钟数") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    text = "同一周、同一对象已有目标时会编辑原目标，可撤销。目标不会生成实际记录。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val weekDate = tryParseDate(weekText.trim())
                    if (weekDate == null) {
                        error = "周起始日期格式应为 yyyy-MM-dd"
                        return@TextButton
                    }
                    if (weekDate.dayOfWeek != DayOfWeek.MONDAY) {
                        error = "周起始日期必须是周一"
                        return@TextButton
                    }
                    val minutes = minutesText.trim().toIntOrNull()
                    if (minutes == null || minutes <= 0) {
                        error = "目标分钟数必须为正整数"
                        return@TextButton
                    }
                    val targetProjectId = if (useProject) selectedProjectId else null
                    val targetActivityId = if (useProject) null else selectedActivityId
                    if (targetProjectId == null && targetActivityId == null) {
                        error = "请明确选择项目或活动"
                        return@TextButton
                    }
                    val week = weekDate.toString()
                    val existing = goals.firstOrNull {
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
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

