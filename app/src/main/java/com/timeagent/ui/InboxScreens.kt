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

/** Stable conversion markers shared with the host shell. Additions must not write business data here. */
object IdeaConversionTarget {
    const val TASK = "TASK"
    const val SCHEDULE = "SCHEDULE"
    const val LEDGER = "LEDGER"
    const val MEMORY = "MEMORY"
}

private val ideaConversions = listOf(
    IdeaConversionTarget.TASK to "任务",
    IdeaConversionTarget.SCHEDULE to "日程",
    IdeaConversionTarget.LEDGER to "记账",
    IdeaConversionTarget.MEMORY to "记忆规则",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(model: TimeViewModel, data: LocalSnapshot, target: NoteTarget, onClose: () -> Unit, onKeepDraft: (() -> Unit)? = null) {
    val busy by model.busy.collectAsState()
    val existing = data.notes.firstOrNull { it.targetType == target.type && it.targetId == target.id }
    var title by rememberSaveable(target.type, target.id) { mutableStateOf(existing?.title.orEmpty()) }
    var body by rememberSaveable(target.type, target.id) { mutableStateOf(existing?.body.orEmpty()) }
    val close = rememberDraftExit("${target.type}:${target.id}", listOf(title, body), busy, onClose, onKeepDraft)
    BackHandler(onBack = close)

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("记笔记 · ${target.title}", maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = close, enabled = !busy, modifier = Modifier.testTag("note-back")) {
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
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_inbox_screens_001), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(title, { title = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_inbox_screens_002)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                body, { body = it },
                label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_inbox_screens_003)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp).testTag("note-body"),
                minLines = 8,
            )
            if (existing != null) Text("已有笔记 · 版本 ${existing.version}；其他窗口的新修改不会被覆盖。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }


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
    onConvertIdea: (IdeaEntity, String) -> Unit = { _, _ -> },
) {
    val ideas = data.ideas.filter { it.deletedAt == null && it.status != "ARCHIVED" }.sortedByDescending { it.updatedAt }
    val unscheduled = ideas.filter { it.status == "UNSCHEDULED" }
    val reminders = data.reminders.sortedBy { it.remindAt }
    var editingIdea by remember { mutableStateOf<IdeaEntity?>(null) }
    var creatingIdea by remember { mutableStateOf(false) }
    var deleteReminder by remember { mutableStateOf<ReminderEntity?>(null) }
    var convertMenuIdea by remember { mutableStateOf<String?>(null) }
    var deleteIdea by remember { mutableStateOf<IdeaEntity?>(null) }
    BackHandler { if (!busy) onClose() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_inbox_screens_004)) },
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
                        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_inbox_screens_005), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_inbox_screens_006), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_inbox_screens_007), Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = { creatingIdea = true }, enabled = !busy, modifier = Modifier.testTag("idea-create")) { Icon(Icons.Rounded.Add, null); Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_inbox_screens_008)) }
                    }
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_inbox_screens_009), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (unscheduled.isEmpty()) Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_inbox_screens_010), style = MaterialTheme.typography.bodySmall)
                    unscheduled.forEach { idea ->
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(idea.title, fontWeight = FontWeight.SemiBold)
                                if (idea.content.isNotBlank()) Text(idea.content, style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { editingIdea = idea }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_edit)) }
                                    TextButton(onClick = { onScheduleIdea(idea) }, enabled = !busy, modifier = Modifier.testTag("idea-schedule-reminder")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_inbox_screens_011)) }
                                    Box {
                                        TextButton(
                                            onClick = { convertMenuIdea = idea.id },
                                            enabled = !busy,
                                            modifier = Modifier.testTag("idea-convert"),
                                        ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_inbox_screens_012)) }
                                        DropdownMenu(
                                            expanded = convertMenuIdea == idea.id,
                                            onDismissRequest = { convertMenuIdea = null },
                                        ) {
                                            ideaConversions.forEach { (target, label) ->
                                                DropdownMenuItem(
                                                    text = { Text(label) },
                                                    onClick = {
                                                        convertMenuIdea = null
                                                        // Only opens the matching editor with prefilled values; no business row is written here.
                                                        onConvertIdea(idea, target)
                                                    },
                                                    modifier = Modifier.testTag("idea-convert-${target.lowercase()}"),
                                                )
                                            }
                                            HorizontalDivider()
                                            DropdownMenuItem(
                                                text = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                                                onClick = { convertMenuIdea = null; deleteIdea = idea },
                                                modifier = Modifier.testTag("idea-convert-delete"),
                                            )
                                        }
                                    }
                                    TextButton(onClick = { model.perform { model.repository.saveIdea(idea.copy(status = "ARCHIVED"), idea.version) } }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_archive)) }
                                }
                            }
                        }
                    }
                }
            }
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_inbox_screens_013), Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = onNewReminder, enabled = !busy, modifier = Modifier.testTag("reminder-create")) { Icon(Icons.Rounded.Add, null); Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_inbox_screens_014)) }
                    }
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_inbox_screens_015), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (reminders.isEmpty()) Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_inbox_screens_016), style = MaterialTheme.typography.bodySmall)
                    reminders.forEach { reminder ->
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(reminder.title, fontWeight = FontWeight.SemiBold, color = if (reminder.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${Instant.ofEpochMilli(reminder.remindAt).label(zone)} · ${if (reminder.deliveredAt != null) "已提醒" else if (reminder.enabled) "待提醒" else "已关闭"}", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { onEditReminder(reminder) }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_edit)) }
                                    TextButton(onClick = { model.perform { model.repository.saveReminder(reminder.copy(enabled = !reminder.enabled), reminder.version) } }, enabled = !busy) { Text(if (reminder.enabled) "关闭" else "开启") }
                                    TextButton(onClick = { deleteReminder = reminder }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_delete)) }
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
            title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_inbox_screens_017)) },
            text = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_inbox_screens_018)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteReminder = null
                    model.perform { model.repository.deleteReminder(reminder.id, reminder.version) }
                }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteReminder = null }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } },
        )
    }
    deleteIdea?.let { idea ->
        AlertDialog(
            onDismissRequest = { if (!busy) deleteIdea = null },
            title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_inbox_screens_019)) },
            text = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_inbox_screens_020)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteIdea = null
                        // No dedicated deleteIdea API exists yet: write the tombstone through saveIdea so the
                        // seen version is checked, the batch is idempotent, and delete stays one undo action.
                        model.perform {
                            model.repository.assistantTransaction("delete-idea:${idea.id}:${idea.version}", "删除想法") {
                                model.repository.saveIdea(
                                    idea.copy(status = "ARCHIVED", deletedAt = model.repository.now().toEpochMilli()),
                                    idea.version,
                                )
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.testTag("idea-delete-confirm"),
                ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteIdea = null }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } },
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
                OutlinedTextField(title, { title = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_inbox_screens_008)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("idea-title"))
                OutlinedTextField(content, { content = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_inbox_screens_021)) }, minLines = 3, modifier = Modifier.fillMaxWidth().testTag("idea-content"))
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
            ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } },
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
    onSnooze: () -> Unit = {},
    onRepeat: () -> Unit = {},
) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_inbox_screens_013), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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
                TextButton(onClick = onEdit) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_edit)) }
                TextButton(onClick = onToggle) { Text(if (reminder.enabled) "关闭提醒" else "重新开启") }
                TextButton(onClick = onDelete) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_delete)) }
            }
            Row {
                TextButton(onClick = onSnooze) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reminder_snooze_sheet_001)) }
                TextButton(onClick = onRepeat) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_inbox_screens_022)) }
            }
        }
    }
}
