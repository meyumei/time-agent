package com.timeagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.timeagent.R
import com.timeagent.data.LocalSnapshot
import com.timeagent.data.TagEntity
import com.timeagent.data.TaskEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val orderSaver = listSaver<List<String>, String>(save = { it }, restore = { it })
private val dueFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun isDone(t: TaskEntity) = t.status.equals("DONE", true) || t.status.equals("COMPLETED", true)
private fun isCancelled(t: TaskEntity) = t.status.equals("CANCELLED", true)
private fun colorFor(name: String) = Color(com.timeagent.projects.ProjectColor.argb(name))

private fun sortTasks(list: List<TaskEntity>, mode: String, order: List<String>) = when (mode) {
    TaskListPreferences.SORT_PRIORITY -> list.sortedWith(
        compareByDescending<TaskEntity> { it.priority }
            .thenBy { it.dueAt ?: Long.MAX_VALUE }
            .thenBy { it.id },
    )

    TaskListPreferences.SORT_MANUAL -> {
        val index = order.withIndex().associate { it.value to it.index }
        list.sortedWith(
            compareBy<TaskEntity> { index[it.id] ?: Int.MAX_VALUE }
                .thenBy { if (it.id in index) 0L else it.createdAt }
                .thenBy { it.id },
        )
    }

    else -> list.sortedWith(
        compareBy<TaskEntity> { it.dueAt ?: Long.MAX_VALUE }
            .thenByDescending { it.priority }
            .thenBy { it.id },
    )
}

@Composable
internal fun TaskListContent(
    data: LocalSnapshot, zone: ZoneId,
    onSelect: (TaskEntity) -> Unit, onCreate: () -> Unit, onStart: (String?) -> Unit,
    onToggle: (TaskEntity) -> Unit, onSchedule: (TaskEntity) -> Unit, onDelete: (TaskEntity) -> Unit,
    onQuickPreview: (String) -> Unit, reselectRevision: Int = 0,
    tags: List<TagEntity> = emptyList(),
    taskTagIds: Map<String, Set<String>> = emptyMap(),
    now: Instant = Instant.now(),
    onQuickDraft: ((QuickTaskDraft) -> Unit)? = null,
) {
    val today = now.atZone(zone).toLocalDate()
    val context = LocalContext.current
    val preferences = remember(context.applicationContext) { TaskListPreferences(context) }
    val placements = remember(data.placements) { data.placements.associateBy { it.taskId } }
    val projectMap = remember(data.projects) { data.projects.filter { it.deletedAt == null }.associateBy { it.id } }
    val taskMap = remember(data.tasks) { data.tasks.associateBy { it.id } }

    var project by rememberSaveable { mutableStateOf(preferences.readProject()) }
    var tag by rememberSaveable { mutableStateOf(preferences.readTag()) }
    var sort by rememberSaveable { mutableStateOf(preferences.readSort()) }
    var showDone by rememberSaveable { mutableStateOf(preferences.readShowDone()) }
    var text by rememberSaveable { mutableStateOf("") }
    var order by rememberSaveable(stateSaver = orderSaver) { mutableStateOf(emptyList<String>()) }

    val listState = rememberLazyListState()
    LaunchedEffect(reselectRevision) { listState.scrollToItem(0) }

    val selectedProject = project
    val selectedTag = tag
    val scoped = data.tasks.filter { task ->
        task.deletedAt == null && !isCancelled(task) &&
            (selectedProject == null || placements[task.id]?.projectId == selectedProject) &&
            (selectedTag == null || taskTagIds[task.id]?.contains(selectedTag) == true)
    }
    val active = sortTasks(scoped.filter { !isDone(it) }, sort, order)
    val done = scoped.filter { isDone(it) }.sortedByDescending { it.dueAt ?: 0L }
    val activeIds = active.map { it.id }

    // Manual order is visual state only. New ids are appended; stale ids are filtered out.
    LaunchedEffect(selectedProject, selectedTag, sort, activeIds) {
        if (sort != TaskListPreferences.SORT_MANUAL || activeIds.isEmpty()) return@LaunchedEffect
        val loaded = preferences.loadOrder(selectedProject, selectedTag, activeIds)
        if (loaded != order) {
            order = loaded
            preferences.saveOrder(selectedProject, selectedTag, loaded)
        }
    }

    val dates = active.associateWith { task ->
        task.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    }
    val todays = active.filter { dates[it]?.let { date -> !date.isAfter(today) } == true }
    val upcoming = active.filter { dates[it]?.let { date -> date.isAfter(today) } == true }
    val undated = active.filter { dates[it] == null }
    val shownProjects = projectMap.values
        .filter { candidate ->
            data.tasks.any {
                it.deletedAt == null && !isCancelled(it) &&
                    placements[it.id]?.projectId == candidate.id
            }
        }
        .sortedBy { it.name }
    val shownTags = remember(tags) { tags.sortedBy { it.name } }

    fun kids(t: TaskEntity): Pair<Int, Int> {
        val children = data.placements.filter { it.parentTaskId == t.id }
            .mapNotNull { taskMap[it.taskId] }
            .filter { it.deletedAt == null && !isCancelled(it) }
        return children.count { isDone(it) } to children.size
    }

    fun move(id: String, delta: Int) {
        val list = order.toMutableList()
        if (id !in list) list.addAll(active.map { it.id })
        val from = list.indexOf(id)
        val to = from + delta
        if (from >= 0 && to in list.indices) {
            list[from] = list[to]
            list[to] = id
            order = list
            preferences.saveOrder(selectedProject, selectedTag, list)
        }
    }

    val draftResult = remember(text, today, zone, data.projects, tags) {
        TaskQuickDraftParser.parse(
            input = text,
            now = now,
            zone = zone,
            projects = data.projects,
            tags = tags,
            snapshot = data,
        )
    }

    val submit: () -> Unit = {
        if (text.isNotBlank()) {
            val draft = draftResult.draft
            if (onQuickDraft != null && draft != null) onQuickDraft.invoke(draft) else onQuickPreview(text)
        }
    }

    Column(Modifier.fillMaxSize().testTag("task-list")) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = project == null,
                onClick = {
                    project = null
                    preferences.saveProject(null)
                },
                label = { Text(stringResource(R.string.task_list_filter_all)) },
            )
            shownProjects.forEach { candidate ->
                Spacer(Modifier.width(4.dp))
                FilterChip(
                    selected = project == candidate.id,
                    onClick = {
                        val next = if (project == candidate.id) null else candidate.id
                        project = next
                        preferences.saveProject(next)
                    },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(colorFor(candidate.name), CircleShape))
                            Spacer(Modifier.width(4.dp))
                            Text(candidate.name)
                        }
                    },
                )
            }
        }

        if (shownTags.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.task_list_filter_tags),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(end = 4.dp),
                )
                FilterChip(
                    selected = tag == null,
                    onClick = {
                        tag = null
                        preferences.saveTag(null)
                    },
                    label = { Text(stringResource(R.string.task_list_filter_all_tags)) },
                )
                shownTags.forEach { candidate ->
                    Spacer(Modifier.width(4.dp))
                    FilterChip(
                        selected = tag == candidate.id,
                        onClick = {
                            val next = if (tag == candidate.id) null else candidate.id
                            tag = next
                            preferences.saveTag(next)
                        },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(8.dp)
                                        .background(Color(candidate.color.toInt()), CircleShape),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(candidate.name)
                            }
                        },
                        modifier = Modifier.testTag("task-filter-tag-${candidate.id}"),
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SortMenu(sort) {
                sort = it
                preferences.saveSort(it)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onCreate) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.task_list_add_task))
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            if (todays.isNotEmpty()) {
                item(key = "h-today") { GroupHeader(stringResource(R.string.task_list_today), todays.size) }
                items(todays, key = { it.id }) { task ->
                    TaskRow(task, zone, kids(task), sort == TaskListPreferences.SORT_MANUAL, onSelect, onToggle, onStart, onSchedule, onDelete, { move(task.id, -1) }, { move(task.id, 1) })
                }
            }
            if (upcoming.isNotEmpty()) {
                item(key = "h-upcoming") { GroupHeader(stringResource(R.string.task_list_upcoming), upcoming.size) }
                items(upcoming, key = { it.id }) { task ->
                    TaskRow(task, zone, kids(task), sort == TaskListPreferences.SORT_MANUAL, onSelect, onToggle, onStart, onSchedule, onDelete, { move(task.id, -1) }, { move(task.id, 1) })
                }
            }
            if (undated.isNotEmpty()) {
                item(key = "h-undated") { GroupHeader(stringResource(R.string.task_list_undated), undated.size) }
                items(undated, key = { it.id }) { task ->
                    TaskRow(task, zone, kids(task), sort == TaskListPreferences.SORT_MANUAL, onSelect, onToggle, onStart, onSchedule, onDelete, { move(task.id, -1) }, { move(task.id, 1) })
                }
            }
            if (active.isEmpty() && done.isEmpty()) {
                item(key = "empty") {
                    TaskListEmptyHint(
                        stringResource(R.string.task_list_empty_title),
                        stringResource(R.string.task_list_empty_hint),
                    )
                }
            }
            item(key = "h-done") {
                DoneHeader(done.size, showDone) {
                    showDone = !showDone
                    preferences.saveShowDone(showDone)
                }
            }
            if (showDone) {
                items(done, key = { it.id }) { task ->
                    TaskRow(task, zone, kids(task), false, onSelect, onToggle, onStart, onSchedule, onDelete, {}, {})
                }
            }
        }

        if (text.isNotBlank()) {
            QuickDraftPreview(draftResult, zone)
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    onQuickPreview(it)
                },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.task_list_quick_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = submit,
                enabled = text.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.task_list_quick_add))
            }
        }
    }
}

@Composable
private fun QuickDraftPreview(result: QuickTaskDraftResult, zone: ZoneId) {
    val draft = result.draft ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            draft.dueAt?.let { dueAt ->
                PreviewChip(
                    stringResource(
                        R.string.task_list_draft_due_chip,
                        dueFormatter.withZone(zone).format(Instant.ofEpochMilli(dueAt)),
                    ),
                )
            }
            result.project?.let { project ->
                PreviewChip(
                    stringResource(R.string.task_list_draft_project_chip, project.name),
                    colorFor(project.name),
                )
            }
            result.tags.forEach { tag ->
                PreviewChip(
                    stringResource(R.string.task_list_draft_tag_chip, tag.name),
                    Color(tag.color.toInt()),
                )
            }
        }

        Text(
            stringResource(R.string.task_list_draft_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        val issueText = when (val issue = result.issue) {
            is QuickTaskDraftIssue.AmbiguousName -> stringResource(
                R.string.task_list_draft_ambiguous,
                issue.names.joinToString(stringResource(R.string.task_list_name_separator)),
            )

            is QuickTaskDraftIssue.MultipleDates -> stringResource(
                R.string.task_list_draft_multiple_dates,
                issue.count,
            )

            QuickTaskDraftIssue.UnreadableDate -> stringResource(R.string.task_list_draft_unreadable_date)
            QuickTaskDraftIssue.UnreadableTime -> stringResource(R.string.task_list_draft_unreadable_time)
            is QuickTaskDraftIssue.UnknownNames -> stringResource(
                R.string.task_list_draft_unknown_names,
                issue.names.joinToString(stringResource(R.string.task_list_name_separator)),
            )

            null -> null
        }
        if (issueText != null) {
            Text(
                issueText,
                style = MaterialTheme.typography.labelSmall,
                color = if (result.issue is QuickTaskDraftIssue.UnknownNames) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun PreviewChip(text: String, dotColor: Color? = null) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(end = 8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dotColor != null) {
                Box(Modifier.size(8.dp).background(dotColor, CircleShape))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskRow(
    task: TaskEntity, zone: ZoneId, progress: Pair<Int, Int>, manual: Boolean,
    onSelect: (TaskEntity) -> Unit, onToggle: (TaskEntity) -> Unit, onStart: (String?) -> Unit,
    onSchedule: (TaskEntity) -> Unit, onDelete: (TaskEntity) -> Unit,
    onUp: () -> Unit, onDown: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val done = isDone(task)
    val currentTask by rememberUpdatedState(task)
    val currentDone by rememberUpdatedState(done)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    // A completed task must not be restored by the same right-swipe gesture.
                    if (!currentDone) onToggle(currentTask)
                    false
                }

                SwipeToDismissBoxValue.EndToStart -> {
                    onSchedule(currentTask)
                    false
                }

                SwipeToDismissBoxValue.Settled -> true
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = !done,
        enableDismissFromEndToStart = true,
        modifier = Modifier.fillMaxWidth().testTag("task-swipe-${task.id}"),
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val background = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primaryContainer
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.secondaryContainer
                SwipeToDismissBoxValue.Settled -> Color.Transparent
            }
            Row(
                Modifier.fillMaxSize().background(background).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (direction == SwipeToDismissBoxValue.StartToEnd) {
                    Arrangement.Start
                } else {
                    Arrangement.End
                },
            ) {
                if (direction == SwipeToDismissBoxValue.StartToEnd) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.task_list_swipe_complete),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.labelLarge,
                    )
                } else if (direction == SwipeToDismissBoxValue.EndToStart) {
                    Text(
                        stringResource(R.string.task_list_swipe_schedule),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.DateRange,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        },
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .clickable { onSelect(task) }
                .padding(end = 4.dp)
                .heightIn(min = 48.dp)
                .testTag("task-${task.id}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Checkbox(
                    checked = done,
                    onCheckedChange = { onToggle(task) },
                    modifier = Modifier.testTag("task-checkbox-${task.id}"),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    task.dueAt?.let {
                        Text(
                            dueFormatter.withZone(zone).format(Instant.ofEpochMilli(it)),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (progress.second > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text("${progress.first}/${progress.second}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            IconButton(onClick = { onStart(task.id) }, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.task_list_start_timer),
                )
            }
            Box {
                IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.task_list_more),
                    )
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.task_list_schedule)) },
                        onClick = {
                            open = false
                            onSchedule(task)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.task_list_edit)) },
                        onClick = {
                            open = false
                            onSelect(task)
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (done) R.string.task_list_restore else R.string.task_list_complete,
                                ),
                            )
                        },
                        onClick = {
                            open = false
                            onToggle(task)
                        },
                    )
                    if (manual) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.task_list_move_up)) },
                            onClick = {
                                open = false
                                onUp()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.task_list_move_down)) },
                            onClick = {
                                open = false
                                onDown()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.task_list_delete)) },
                        onClick = {
                            open = false
                            onDelete(task)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(text: String, count: Int) = Text(
    stringResource(R.string.task_list_group_title, text, count),
    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    style = MaterialTheme.typography.titleSmall,
)

@Composable
private fun DoneHeader(count: Int, open: Boolean, toggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = toggle)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.task_list_done_header, count), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(if (open) R.string.task_list_collapse else R.string.task_list_expand),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun TaskListEmptyHint(title: String, hint: String) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SortMenu(sort: String, onSort: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = when (sort) {
        TaskListPreferences.SORT_PRIORITY -> stringResource(R.string.task_list_sort_priority)
        TaskListPreferences.SORT_MANUAL -> stringResource(R.string.task_list_sort_manual)
        else -> stringResource(R.string.task_list_sort_due)
    }
    val options = listOf(
        TaskListPreferences.SORT_DUE to stringResource(R.string.task_list_sort_due),
        TaskListPreferences.SORT_PRIORITY to stringResource(R.string.task_list_sort_priority),
        TaskListPreferences.SORT_MANUAL to stringResource(R.string.task_list_sort_manual),
    )
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.task_list_sort_label, label))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSort(key)
                        open = false
                    },
                )
            }
        }
    }
}
