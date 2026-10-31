package com.timeagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timeagent.data.ActivityTemplateEntity
import java.util.UUID

fun ActivityTemplateEntity.fullName(all: List<ActivityTemplateEntity>): String = parentId?.let { parent -> all.firstOrNull { it.id == parent }?.name?.let { "$it / $name" } } ?: name

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ActivityTemplatesScreen(model: TimeViewModel, onUse: (ActivityTemplateEntity) -> Unit, onClose: () -> Unit, onNote: (ActivityTemplateEntity) -> Unit = {}) {
    val data by model.data.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ActivityTemplateEntity?>(null) }
    var archive by remember { mutableStateOf<ActivityTemplateEntity?>(null) }
    val visible = data.activities.filter { it.archivedAt == null }
    val current = editing
    BackHandler { if (!busy) { if (editing != null) editing = null else onClose() } }
    if (current != null) {
        var name by rememberSaveable(current.id) { mutableStateOf(current.name) }
        var minutes by rememberSaveable(current.id) { mutableStateOf(current.defaultMinutes.toString()) }
        var category by rememberSaveable(current.id) { mutableStateOf(current.categoryId) }
        var validation by remember(current.id) { mutableStateOf<String?>(null) }
        Scaffold(topBar = { TopAppBar(title = { Text(if (current.createdAt == 0L) "新增活动模板" else "编辑活动模板") }, navigationIcon = { TextButton(onClick = { editing = null }, enabled = !busy) { Text("取消") } }, actions = {
            TextButton(enabled = !busy, onClick = {
                val duration = minutes.toIntOrNull()
                if (name.isBlank() || duration == null || duration !in 1..10080) validation = "请填写名称和 1–10080 分钟的建议时长"
                else model.perform(onSuccess = { editing = null }) { model.repository.saveActivity(current.copy(name = name, defaultMinutes = duration, categoryId = category), current.version.takeIf { current.createdAt != 0L }) }
            }) { Text(if (busy) "保存中…" else "保存") }
        }) }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(current.parentId?.let { id -> "所属大模块：${data.activities.firstOrNull { it.id == id }?.name.orEmpty()}" } ?: "这是一个大模块，可以继续添加子模块。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, label = { Text("活动名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TimelineColorChoice("activity:${current.id}", name.ifBlank { "新活动模块" })
                OutlinedTextField(minutes, { minutes = it }, label = { Text("建议耗时（分钟）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("分类", style = MaterialTheme.typography.labelLarge)
                data.categories.forEach { item -> FilterChip(selected = category == item.id, onClick = { category = item.id }, label = { Text(item.name) }) }
                Text("从模板新建时会带入名称、时长和分类，仍可以修改。", style = MaterialTheme.typography.bodySmall)
                validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    } else {
        Scaffold(topBar = { TopAppBar(title = { Text("活动模板") }, navigationIcon = { IconButton(onClick = onClose, enabled = !busy) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } }, actions = {
            IconButton(onClick = { model.perform { model.repository.undoLastAction() } }, enabled = !busy && data.history.any { !it.undone }) { Icon(Icons.AutoMirrored.Rounded.Undo, "撤销最近操作") }
            IconButton(onClick = { model.perform { model.repository.redoLastAction() } }, enabled = !busy && data.history.any { it.undone && it.redoable }) { Icon(Icons.AutoMirrored.Rounded.Redo, "重做") }
        }) }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("先准备常用活动，再选择它创建任务、安排计划或计时。", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { editing = ActivityTemplateEntity(UUID.randomUUID().toString(), "", categoryId = "life", sortOrder = visible.size) }, enabled = !busy) { Icon(Icons.Rounded.Add, null); Text("新增大模块") }
                visible.filter { it.parentId == null }.forEach { parent ->
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(parent.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                IconButton(onClick = { editing = parent }, enabled = !busy) { Icon(Icons.Rounded.Edit, "编辑 ${parent.name}") }
                                TextButton(onClick = { onUse(parent) }, enabled = !busy) { Text("使用") }
                                TextButton(onClick = { onNote(parent) }, enabled = !busy) { Text("记笔记") }
                            }
                            Text("建议 ${parent.defaultMinutes.toLong().minutesLabel()}", style = MaterialTheme.typography.bodySmall)
                            TimelineColorChoice("activity:${parent.id}", parent.name)
                            visible.filter { it.parentId == parent.id }.forEach { child ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { onUse(child) }, modifier = Modifier.weight(1f), enabled = !busy) { Text("${child.name} · ${child.defaultMinutes.toLong().minutesLabel()}") }
                                    IconButton(onClick = { editing = child }, enabled = !busy) { Icon(Icons.Rounded.Edit, "编辑 ${child.name}") }
                                    TextButton(onClick = { onNote(child) }, enabled = !busy) { Text("记笔记") }
                                    TextButton(onClick = { archive = child }, enabled = !busy) { Text("归档") }
                                }
                            }
                            Row {
                                TextButton(onClick = { editing = ActivityTemplateEntity(UUID.randomUUID().toString(), "", parent.id, parent.defaultMinutes, parent.categoryId, visible.size) }, enabled = !busy) { Text("添加子模块") }
                                TextButton(onClick = { archive = parent }, enabled = !busy) { Text("归档大模块") }
                            }
                        }
                    }
                }
                if (data.activities.any { it.archivedAt != null }) Text("已归档模板不再出现在新建列表，历史任务保留关联。刚才的归档可用右上角撤销恢复。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    archive?.let { item -> AlertDialog(onDismissRequest = { archive = null }, title = { Text("归档 ${item.name}？") }, text = { Text("保留历史任务中的活动关联。大模块有未归档的子模块时，需要先处理子模块。") }, confirmButton = { TextButton(enabled = !busy, onClick = { model.perform(onSuccess = { archive = null }) { model.repository.archiveActivity(item.id, item.version) } }) { Text("归档") } }, dismissButton = { TextButton(onClick = { archive = null }) { Text("取消") } }) }
}
