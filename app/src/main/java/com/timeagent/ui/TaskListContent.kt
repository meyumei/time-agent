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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.timeagent.data.LocalSnapshot
import com.timeagent.data.TaskEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dotColors = listOf(Color(0xFF5B8DEF), Color(0xFFE57373), Color(0xFF4DB6AC), Color(0xFF9575CD), Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784), Color(0xFFF06292))
private val orderSaver = listSaver<List<String>, String>(save = { it }, restore = { it })

private fun isDone(t: TaskEntity) = t.status.equals("DONE", true) || t.status.equals("COMPLETED", true)
private fun colorFor(name: String) = dotColors[(name.hashCode() and 0x7fffffff) % dotColors.size]
private fun sortTasks(list: List<TaskEntity>, mode: String, order: List<String>) = when (mode) {
    "PRIORITY" -> list.sortedWith(compareByDescending<TaskEntity> { it.priority }.thenBy { it.dueAt ?: Long.MAX_VALUE }.thenBy { it.id })
    "MANUAL" -> list.sortedWith(compareBy<TaskEntity> { order.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }.thenBy { it.dueAt ?: Long.MAX_VALUE }.thenBy { it.id })
    else -> list.sortedWith(compareBy<TaskEntity> { it.dueAt ?: Long.MAX_VALUE }.thenByDescending { it.priority }.thenBy { it.id })
}

@Composable
internal fun TaskListContent(
    data: LocalSnapshot, zone: ZoneId,
    onSelect: (TaskEntity) -> Unit, onCreate: () -> Unit, onStart: (String?) -> Unit,
    onToggle: (TaskEntity) -> Unit, onSchedule: (TaskEntity) -> Unit, onDelete: (TaskEntity) -> Unit,
    onQuickPreview: (String) -> Unit, reselectRevision: Int = 0,
) {
    val today = LocalDate.now(zone)
    val placements = remember(data.placements) { data.placements.associateBy { it.taskId } }
    val projectMap = remember(data.projects) { data.projects.filter { it.deletedAt == null }.associateBy { it.id } }
    val taskMap = remember(data.tasks) { data.tasks.associateBy { it.id } }
    var project by rememberSaveable { mutableStateOf<String?>(null) }; var sort by rememberSaveable { mutableStateOf("DUE") }; var showDone by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }; var order by rememberSaveable(stateSaver = orderSaver) { mutableStateOf(emptyList<String>()) }
    val listState = rememberLazyListState()
    LaunchedEffect(reselectRevision) { listState.scrollToItem(0) }
    val scoped = data.tasks.filter { it.deletedAt == null && (project == null || placements[it.id]?.projectId == project) }
    val active = sortTasks(scoped.filter { !isDone(it) }, sort, order)
    val done = scoped.filter { isDone(it) }.sortedByDescending { it.dueAt ?: 0L }
    val dates = active.associateWith { t -> t.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } }
    val todays = active.filter { dates[it]?.let { d -> !d.isAfter(today) } == true }
    val upcoming = active.filter { dates[it]?.let { d -> d.isAfter(today) } == true }
    val undated = active.filter { dates[it] == null }
    val shownProjects = projectMap.values.filter { p -> data.tasks.any { it.deletedAt == null && placements[it.id]?.projectId == p.id } }.sortedBy { it.name }
    fun kids(t: TaskEntity): Pair<Int, Int> {
        val c = data.placements.filter { it.parentTaskId == t.id }.mapNotNull { taskMap[it.taskId] }.filter { it.deletedAt == null }
        return c.count { isDone(it) } to c.size
    }
    fun move(id: String, delta: Int) {
        val list = order.toMutableList()
        if (id !in list) list.addAll(active.map { it.id })
        val i = list.indexOf(id)
        val j = i + delta
        if (i >= 0 && j in list.indices) { list[i] = list[j]; list[j] = id; order = list }
    }
    Column(Modifier.fillMaxSize().testTag("task-list")) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = project == null, onClick = { project = null }, label = { Text("全部") })
            shownProjects.forEach { p ->
                Spacer(Modifier.width(4.dp))
                FilterChip(selected = project == p.id, onClick = { project = if (project == p.id) null else p.id }, label = { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).background(colorFor(p.name), CircleShape)); Spacer(Modifier.width(4.dp)); Text(p.name) } })
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SortMenu(sort) { sort = it }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onCreate) { Icon(Icons.Filled.Add, contentDescription = "新建任务") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            if (todays.isNotEmpty()) { item(key = "h-today") { GroupHeader("今天", todays.size) }
                items(todays, key = { it.id }) { t -> TaskRow(t, zone, kids(t), sort == "MANUAL", onSelect, onToggle, onStart, onSchedule, onDelete, { move(t.id, -1) }, { move(t.id, 1) }) } }
            if (upcoming.isNotEmpty()) { item(key = "h-upcoming") { GroupHeader("即将到来", upcoming.size) }
                items(upcoming, key = { it.id }) { t -> TaskRow(t, zone, kids(t), sort == "MANUAL", onSelect, onToggle, onStart, onSchedule, onDelete, { move(t.id, -1) }, { move(t.id, 1) }) } }
            if (undated.isNotEmpty()) { item(key = "h-undated") { GroupHeader("无日期", undated.size) }
                items(undated, key = { it.id }) { t -> TaskRow(t, zone, kids(t), sort == "MANUAL", onSelect, onToggle, onStart, onSchedule, onDelete, { move(t.id, -1) }, { move(t.id, 1) }) } }
            if (active.isEmpty() && done.isEmpty()) item(key = "empty") { EmptyHint("暂无任务", "在下方输入任务开始记录") }
            item(key = "h-done") { DoneHeader(done.size, showDone) { showDone = !showDone } }
            if (showDone) items(done, key = { it.id }) { t -> TaskRow(t, zone, kids(t), false, onSelect, onToggle, onStart, onSchedule, onDelete, {}, {}) }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = text, onValueChange = { text = it; onQuickPreview(it) }, modifier = Modifier.weight(1f), placeholder = { Text("快速添加任务") }, singleLine = true)
            Spacer(Modifier.width(8.dp))
            Button(onClick = onCreate, enabled = text.isNotBlank()) { Text("添加") }
        }
    }
}

@Composable
private fun TaskRow(task: TaskEntity, zone: ZoneId, progress: Pair<Int, Int>, manual: Boolean, onSelect: (TaskEntity) -> Unit, onToggle: (TaskEntity) -> Unit, onStart: (String?) -> Unit, onSchedule: (TaskEntity) -> Unit, onDelete: (TaskEntity) -> Unit, onUp: () -> Unit, onDown: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val done = isDone(task)
    Row(Modifier.fillMaxWidth().clickable { onSelect(task) }.padding(end = 4.dp).testTag("task-${task.id}"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Checkbox(checked = done, onCheckedChange = { onToggle(task) }, modifier = Modifier.testTag("task-checkbox-${task.id}")) }
        Column(Modifier.weight(1f)) {
            Text(task.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, textDecoration = if (done) TextDecoration.LineThrough else null)
            Row(verticalAlignment = Alignment.CenterVertically) {
                task.dueAt?.let { Text(DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(zone).format(Instant.ofEpochMilli(it)), style = MaterialTheme.typography.labelSmall) }
                if (progress.second > 0) { Spacer(Modifier.width(8.dp)); Text("${progress.first}/${progress.second}", style = MaterialTheme.typography.labelSmall) }
            }
        }
        IconButton(onClick = { onStart(task.id) }) { Icon(Icons.Filled.PlayArrow, contentDescription = "开始计时") }
        Box {
            IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "更多操作") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("计划") }, onClick = { open = false; onSchedule(task) })
                DropdownMenuItem(text = { Text("编辑") }, onClick = { open = false; onSelect(task) })
                DropdownMenuItem(text = { Text(if (done) "标记未完成" else "完成") }, onClick = { open = false; onToggle(task) })
                if (manual) { DropdownMenuItem(text = { Text("上移") }, onClick = { open = false; onUp() }); DropdownMenuItem(text = { Text("下移") }, onClick = { open = false; onDown() }) }
                DropdownMenuItem(text = { Text("删除") }, onClick = { open = false; onDelete(task) })
            }
        }
    }
}

@Composable
private fun GroupHeader(text: String, count: Int) = Text("$text · $count", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
@Composable
private fun DoneHeader(count: Int, open: Boolean, toggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = toggle).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("已完成 · $count", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(8.dp))
        Text(if (open) "收起" else "展开", style = MaterialTheme.typography.labelMedium)
    }
}
@Composable
private fun SortMenu(sort: String, onSort: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("排序：" + when (sort) { "PRIORITY" -> "优先级"; "MANUAL" -> "手动"; else -> "截止时间" }) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf("DUE" to "截止时间", "PRIORITY" to "优先级", "MANUAL" to "手动").forEach { (k, v) -> DropdownMenuItem(text = { Text(v) }, onClick = { onSort(k); open = false }) }
        }
    }
}