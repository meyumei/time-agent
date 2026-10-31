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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.timeagent.data.*
import com.timeagent.reminders.NotificationAccess
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** The module a note belongs to; persisted as the existing NoteEntity target pair. */
data class NoteTarget(val type: String, val id: String, val title: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(model: TimeViewModel, data: LocalSnapshot, target: NoteTarget, onClose: () -> Unit) {
    val busy by model.busy.collectAsState()
    val existing = data.notes.firstOrNull { it.targetType == target.type && it.targetId == target.id }
    var title by rememberSaveable(target.type, target.id) { mutableStateOf(existing?.title.orEmpty()) }
    var body by rememberSaveable(target.type, target.id) { mutableStateOf(existing?.body.orEmpty()) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val dirty = title != existing?.title.orEmpty() || body != existing?.body.orEmpty()
    fun close() { if (dirty) confirmDiscard = true else onClose() }
    BackHandler { if (!busy) close() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("记笔记 · ${target.title}", maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = ::close, enabled = !busy, modifier = Modifier.testTag("note-back")) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                }
            },
            actions = {
                TextButton(
                    onClick = {
                        model.perform(onSuccess = onClose) {
                            model.repository.saveNote(
                                NoteEntity(
                                    id = existing?.id ?: UUID.randomUUID().toString(),
                                    targetType = target.type,
                                    targetId = target.id,
                                    title = title.trim(),
                                    body = body,
                                ),
                                existing?.version,
                            )
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.testTag("note-save"),
                ) { Text(if (busy) "保存中…" else "保存") }
            },
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("只关联到这一条内容，可随时回来继续编辑。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(title, { title = it }, label = { Text("笔记标题（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                body, { body = it },
                label = { Text("笔记内容") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp).testTag("note-body"),
                minLines = 8,
            )
            if (existing != null) Text("已有笔记 · 版本 ${existing.version}；其他窗口的新修改不会被覆盖。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("放弃未保存的笔记？") },
        text = { Text("草稿还没有保存，返回后会丢失。") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text("放弃并返回") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    model: TimeViewModel,
    data: LocalSnapshot,
    zone: ZoneId,
    busy: Boolean,
    onScheduleIdea: (IdeaEntity) -> Unit,
    onEditReminder: (ReminderEntity) -> Unit,
    onNewReminder: () -> Unit,
    onClose: () -> Unit,
    onOpenTask: (String) -> Unit = {},
) {
    val ideas = data.ideas.filter { it.deletedAt == null && it.status != "ARCHIVED" }.sortedByDescending { it.updatedAt }
    val unscheduled = ideas.filter { it.status == "UNSCHEDULED" }
    val reminders = data.reminders.sortedBy { it.remindAt }
    var editingIdea by remember { mutableStateOf<IdeaEntity?>(null) }
    var creatingIdea by remember { mutableStateOf(false) }
    var deleteReminder by remember { mutableStateOf<ReminderEntity?>(null) }
    BackHandler { if (!busy) onClose() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("任务点 / 未安排") },
            navigationIcon = { IconButton(onClick = onClose, enabled = !busy, modifier = Modifier.testTag("inbox-back")) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } },
            actions = {
                IconButton(onClick = { model.perform { model.repository.undoLastAction() } }, enabled = !busy && data.history.any { !it.undone }) { Icon(Icons.AutoMirrored.Rounded.Undo, "撤销最近操作") }
                IconButton(onClick = { model.perform { model.repository.redoLastAction() } }, enabled = !busy && data.history.any { it.undone && it.redoable }) { Icon(Icons.AutoMirrored.Rounded.Redo, "重做") }
            },
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NotificationAccess(onAccessChanged = {})
            val pendingSuggestions = data.suggestions.filter { it.status == "PENDING" }
            if (pendingSuggestions.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.testTag("inbox-suggestions")) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("助手建议", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text("根据你的任务、日程和睡眠自动生成；忽略后同类建议不会再出现。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        pendingSuggestions.forEach { suggestion ->
                            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(suggestion.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("suggestion-title-${suggestion.type}"))
                                    Text(suggestion.detail, style = MaterialTheme.typography.bodySmall)
                                    SuggestionActions(model, suggestion, busy, onOpenTask)
                                }
                            }
                        }
                    }
                }
            }
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("未安排", Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = { creatingIdea = true }, enabled = !busy, modifier = Modifier.testTag("idea-create")) { Icon(Icons.Rounded.Add, null); Text("想法") }
                    }
                    Text("先收下灵感，不占日历时间；需要时再安排为任务点提醒。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (unscheduled.isEmpty()) Text("还没有未安排的想法。", style = MaterialTheme.typography.bodySmall)
                    unscheduled.forEach { idea ->
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(idea.title, fontWeight = FontWeight.SemiBold)
                                if (idea.content.isNotBlank()) Text(idea.content, style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { editingIdea = idea }, enabled = !busy) { Text("编辑") }
                                    TextButton(onClick = { onScheduleIdea(idea) }, enabled = !busy, modifier = Modifier.testTag("idea-schedule-reminder")) { Text("安排为提醒") }
                                    TextButton(onClick = { model.perform { model.repository.saveIdea(idea.copy(status = "ARCHIVED"), idea.version) } }, enabled = !busy) { Text("归档") }
                                }
                            }
                        }
                    }
                }
            }
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("任务点提醒", Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = onNewReminder, enabled = !busy, modifier = Modifier.testTag("reminder-create")) { Icon(Icons.Rounded.Add, null); Text("提醒") }
                    }
                    Text("到点只发一次系统通知，不占用日历时段，也不会自动补出一小时。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (reminders.isEmpty()) Text("还没有任务点提醒。", style = MaterialTheme.typography.bodySmall)
                    reminders.forEach { reminder ->
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(reminder.title, fontWeight = FontWeight.SemiBold, color = if (reminder.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${Instant.ofEpochMilli(reminder.remindAt).label(zone)} · ${if (reminder.deliveredAt != null) "已提醒" else if (reminder.enabled) "待提醒" else "已关闭"}", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { onEditReminder(reminder) }, enabled = !busy) { Text("编辑") }
                                    TextButton(onClick = { model.perform { model.repository.saveReminder(reminder.copy(enabled = !reminder.enabled), reminder.version) } }, enabled = !busy) { Text(if (reminder.enabled) "关闭" else "开启") }
                                    TextButton(onClick = { deleteReminder = reminder }, enabled = !busy) { Text("删除") }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (creatingIdea || editingIdea != null) IdeaEditorDialog(
        model = model,
        idea = editingIdea,
        busy = busy,
        onClose = { creatingIdea = false; editingIdea = null },
    )
    deleteReminder?.let { reminder ->
        AlertDialog(
            onDismissRequest = { deleteReminder = null },
            title = { Text("删除这条提醒？") },
            text = { Text("删除后可以撤销恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteReminder = null
                    model.perform { model.repository.deleteReminder(reminder.id, reminder.version) }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteReminder = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun IdeaEditorDialog(model: TimeViewModel, idea: IdeaEntity?, busy: Boolean, onClose: () -> Unit) {
    var title by rememberSaveable(idea?.id) { mutableStateOf(idea?.title.orEmpty()) }
    var content by rememberSaveable(idea?.id) { mutableStateOf(idea?.content.orEmpty()) }
    var validation by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(if (idea == null) "记下想法" else "编辑想法") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("想法") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("idea-title"))
                OutlinedTextField(content, { content = it }, label = { Text("补充（可选）") }, minLines = 3, modifier = Modifier.fillMaxWidth().testTag("idea-content"))
                validation?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                modifier = Modifier.testTag("idea-save"),
                onClick = {
                    if (title.isBlank()) validation = "请填写想法"
                    else {
                        model.perform(onSuccess = onClose) {
                            model.repository.saveIdea(
                                (idea ?: IdeaEntity(UUID.randomUUID().toString(), title.trim())).copy(title = title.trim(), content = content),
                                idea?.version,
                            )
                        }
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderDetailSheet(
    reminder: ReminderEntity,
    zone: ZoneId,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("任务点提醒", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(reminder.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(Instant.ofEpochMilli(reminder.remindAt).label(zone))
            Text(
                when {
                    reminder.deliveredAt != null -> "已向系统提交通知；这不代表你已经读过。"
                    reminder.enabled -> "等待到点；声音、锁屏与省电策略由系统决定。"
                    else -> "提醒已关闭，不会提交系统通知。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onToggle) { Text(if (reminder.enabled) "关闭提醒" else "重新开启") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}
