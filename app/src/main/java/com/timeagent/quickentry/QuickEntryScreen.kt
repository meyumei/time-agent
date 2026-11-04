package com.timeagent.quickentry

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timeagent.data.ActivityTemplateEntity
import com.timeagent.domain.command.QuickEntrySlot
import com.timeagent.ui.components.UndoMessage
import com.timeagent.ui.components.UndoSnackbar
import kotlinx.coroutines.launch
import java.util.UUID

private val QUICK_ENTRY_COLORS = listOf(
    0xFF425F76L, 0xFF1E8268L, 0xFFA7362AL, 0xFF785626L, 0xFF5756B7L, 0xFF8A4F7DL,
)

private fun quickActionLabel(action: String?): String = when (action) {
    QuickEntryAction.START_TIMER.name -> "开始计时"
    QuickEntryAction.START_FOCUS.name -> "开始专注"
    QuickEntryAction.CREATE_SCHEDULE.name -> "创建日程"
    QuickEntryAction.CREATE_ACTUAL.name -> "补录实际"
    else -> "未设置动作"
}

internal fun displayTitle(entry: QuickEntryEntity, state: QuickEntryUiState): String =
    entry.label?.takeIf { it.isNotBlank() } ?: state.activityLabel(entry.activityId)

private fun orderedEntries(state: QuickEntryUiState): List<QuickEntryEntity> =
    state.entries.sortedWith(compareByDescending<QuickEntryEntity> { it.pinned }.thenBy { it.sortOrder }.thenByDescending { it.lastUsedAt })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickEntryScreen(
    model: QuickEntryModel,
    onClose: () -> Unit,
    onOpenDraft: ((QuickEntryDraft) -> Unit)? = null,
    initialEntryId: String? = null,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var undoMessage by remember { mutableStateOf<UndoMessage?>(null) }
    var editing by remember { mutableStateOf<QuickEntryEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf<QuickEntryLaunch.NeedsConfirmation?>(null) }
    var initialConsumed by androidx.compose.runtime.saveable.rememberSaveable(initialEntryId) { mutableStateOf(false) }

    LaunchedEffect(model) { model.refresh() }
    BackHandler(onBack = onClose)
    UndoSnackbar(
        snackbar, undoMessage,
        onUndo = { token ->
            undoMessage = null
            scope.launch { model.undo(token) }
        },
        onConsumed = { id -> if (undoMessage?.id == id) undoMessage = null },
    )

    fun use(entry: QuickEntryEntity, seen: List<QuickEntrySlot> = emptyList()) {
        scope.launch {
            when (val result = model.activate(entry.id, seen)) {
                is QuickEntryLaunch.NeedsConfirmation -> conflict = result
                is QuickEntryLaunch.StartedTimer -> model.notify("已开始计时：${displayTitle(entry, state)}")
                is QuickEntryLaunch.StartedFocus -> model.notify("已开始专注：${displayTitle(entry, state)}")
                is QuickEntryLaunch.OpenEditor -> {
                    if (onOpenDraft != null) onOpenDraft(result.draft)
                    else model.notify("请在“今天”或编辑预览中确认时间后再创建")
                }
                is QuickEntryLaunch.Failed -> model.notify(result.message)
            }
        }
    }

    fun remove(entry: QuickEntryEntity) {
        scope.launch {
            val token = model.deleteEntry(entry.id, entry.version)
            if (token != null) {
                undoMessage = UndoMessage(UUID.randomUUID().toString(), "已删除快捷记录", token)
            }
        }
    }
    LaunchedEffect(initialEntryId) {
        if (initialEntryId != null && !initialConsumed) {
            initialConsumed = true
            model.refresh()
            model.state.value.entries.firstOrNull { it.id == initialEntryId }?.let { use(it) }
                ?: model.notify("快捷记录已删除，请重新选择")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_001), fontWeight = FontWeight.Bold) },
                navigationIcon = { TextButton(onClick = onClose) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_close)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_002)) },
                icon = { Text("+", style = MaterialTheme.typography.titleLarge) },
                onClick = { creating = true },
                modifier = Modifier.testTag("quick-entry-add"),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.error?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { model.clearMessages() }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_acknowledge)) }
                    }
                }
            }
            state.notice?.let { message ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { model.clearMessages() }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_acknowledge)) }
                    }
                }
            }
            if (state.loading && state.entries.isEmpty()) {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else {
                val pinned = orderedEntries(state).filter { it.pinned }
                val recent = orderedEntries(state).filter { !it.pinned && it.lastUsedAt != null }
                    .sortedByDescending { it.lastUsedAt }.take(3)
                val others = orderedEntries(state).filterNot { it.pinned || (it.id in recent.map { item -> item.id }) }
                LazyColumn(
                    contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (pinned.isNotEmpty()) {
                        item { SectionLabel("固定") }
                        items(pinned, key = { it.id }) { entry ->
                            QuickEntryRow(entry, state, orderedEntries(state), state.busy,
                                onUse = { use(entry) },
                                onEdit = { editing = entry },
                                onPin = { scope.launch { model.setPinned(entry.id, entry.version, !entry.pinned) } },
                                onMove = { delta -> scope.launch { model.moveEntry(entry.id, delta) } },
                                onDelete = { remove(entry) },
                            )
                        }
                    }
                    if (recent.isNotEmpty()) {
                        item { SectionLabel("最近常用（未固定，自动推荐 3 项）") }
                        items(recent, key = { it.id }) { entry ->
                            QuickEntryRow(entry, state, orderedEntries(state), state.busy,
                                onUse = { use(entry) },
                                onEdit = { editing = entry },
                                onPin = { scope.launch { model.setPinned(entry.id, entry.version, true) } },
                                onMove = { delta -> scope.launch { model.moveEntry(entry.id, delta) } },
                                onDelete = { remove(entry) },
                            )
                        }
                    }
                    if (others.isNotEmpty()) {
                        item { SectionLabel("全部") }
                        items(others, key = { it.id }) { entry ->
                            QuickEntryRow(entry, state, orderedEntries(state), state.busy,
                                onUse = { use(entry) },
                                onEdit = { editing = entry },
                                onPin = { scope.launch { model.setPinned(entry.id, entry.version, true) } },
                                onMove = { delta -> scope.launch { model.moveEntry(entry.id, delta) } },
                                onDelete = { remove(entry) },
                            )
                        }
                    }
                    if (state.entries.isEmpty()) {
                        item {
                            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_003), style = MaterialTheme.typography.titleMedium)
                                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_004),
                                        style = MaterialTheme.typography.bodyMedium)
                                    OutlinedButton(onClick = { creating = true }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_005)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        QuickEntryEditorDialog(
            model = model,
            state = state,
            existing = editing,
            onDismiss = { creating = false; editing = null },
        )
    }

    conflict?.let { pending ->
        QuickEntryConflictDialog(
            conflict = pending,
            onDismiss = { conflict = null },
            onConfirm = {
                val entry = pending.entry
                conflict = null
                use(entry, pending.slots)
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
}

@Composable
private fun QuickEntryRow(
    entry: QuickEntryEntity,
    state: QuickEntryUiState,
    ordered: List<QuickEntryEntity>,
    busy: Boolean,
    onUse: () -> Unit,
    onEdit: () -> Unit,
    onPin: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val project = state.projectLabel(entry.projectId)
    val task = state.taskLabel(entry.taskId)
    val index = ordered.indexOfFirst { it.id == entry.id }
    Surface(
        Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onEdit),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).background(
                        if (entry.color == 0L) MaterialTheme.colorScheme.primaryContainer else Color(entry.color.toInt()),
                        CircleShape,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(entry.icon.ifBlank { displayTitle(entry, state).take(1) },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (entry.color == 0L) MaterialTheme.colorScheme.onPrimaryContainer else Color.White)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(displayTitle(entry, state), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(
                        state.activityLabel(entry.activityId),
                        quickActionLabel(entry.action),
                        entry.defaultDurationMinutes?.let { "${it} 分钟" },
                        project?.let { "项目：$it" },
                        task?.let { "任务：$it" },
                    ).joinToString(" · ")
                    Text(meta, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (entry.pinned) Text("★", color = MaterialTheme.colorScheme.primary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已用 ${entry.usageCount} 次", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onUse, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_006)) }
                TextButton(onClick = onEdit, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_edit)) }
                TextButton(onClick = onPin, enabled = !busy) { Text(if (entry.pinned) "取消固定" else "固定") }
                TextButton(onClick = { onMove(-1) }, enabled = !busy && index > 0) { Text("↑") }
                TextButton(onClick = { onMove(1) }, enabled = !busy && index in 0 until ordered.lastIndex) { Text("↓") }
                TextButton(onClick = onDelete, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_delete)) }
            }
        }
    }
}

@Composable
private fun QuickEntryEditorDialog(
    model: QuickEntryModel,
    state: QuickEntryUiState,
    existing: QuickEntryEntity?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val key = existing?.id ?: "new"
    val activeActivities = state.activities.filter { it.archivedAt == null || it.id == existing?.activityId }
    val activeProjects = state.projects.filter { it.deletedAt == null }
    val activeTasks = state.tasks.filter { it.deletedAt == null && it.status != "CANCELLED" }
    var activityId by remember(key) { mutableStateOf(existing?.activityId ?: activeActivities.firstOrNull()?.id.orEmpty()) }
    var label by remember(key) { mutableStateOf(existing?.label.orEmpty()) }
    var icon by remember(key) { mutableStateOf(existing?.icon.orEmpty()) }
    var color by remember(key) { mutableStateOf(existing?.color?.takeIf { it != 0L } ?: 0xFF5756B7L) }
    var actionName by remember(key) { mutableStateOf(existing?.action ?: QuickEntryAction.START_TIMER.name) }
    var duration by remember(key) { mutableStateOf(existing?.defaultDurationMinutes?.toString().orEmpty()) }
    var projectId by remember(key) { mutableStateOf(existing?.projectId) }
    var taskId by remember(key) { mutableStateOf(existing?.taskId) }
    var tags by remember(key) { mutableStateOf(existing?.tagIdList.orEmpty()) }
    var pinned by remember(key) { mutableStateOf(existing?.pinned ?: false) }
    var activityMenu by remember { mutableStateOf(false) }
    var projectMenu by remember { mutableStateOf(false) }
    var taskMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        if (activityId.isBlank()) {
            error = "请选择一个活动"
            return
        }
        val minutes = duration.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (duration.isNotBlank() && (minutes == null || minutes !in 1..10_080)) {
            error = "默认时长须为 1 至 10080 分钟"
            return
        }
        val item = QuickEntryEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            activityId = activityId,
            label = label.trim().takeIf { it.isNotEmpty() },
            icon = icon.trim(),
            color = color,
            projectId = projectId,
            taskId = taskId,
            tagIds = tags.joinToString(","),
            action = actionName,
            defaultDurationMinutes = minutes,
            sortOrder = existing?.sortOrder ?: 0,
            pinned = pinned,
            usageCount = existing?.usageCount ?: 0,
            lastUsedAt = existing?.lastUsedAt,
            createdAt = existing?.createdAt ?: 0,
            updatedAt = existing?.updatedAt ?: 0,
            version = existing?.version ?: 0,
        )
        scope.launch {
            if (model.saveEntry(item, existing?.version)) onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text(if (existing == null) "新建快捷记录" else "编辑快捷记录") },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_007), style = MaterialTheme.typography.labelLarge)
                Box {
                    OutlinedButton(onClick = { activityMenu = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (activityId.isBlank()) "选择活动" else state.activityLabel(activityId),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = activityMenu, onDismissRequest = { activityMenu = false }) {
                        activeActivities.forEach { activity ->
                            DropdownMenuItem(
                                text = { Text(activityLabelOf(activity, state.activities)) },
                                onClick = { activityId = activity.id; activityMenu = false },
                            )
                        }
                    }
                }
                OutlinedTextField(value = label, onValueChange = { label = it.take(80) },
                    label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_008)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = icon, onValueChange = { icon = it.take(16) },
                    label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_009)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_organization_activity_039))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QUICK_ENTRY_COLORS.forEach { value ->
                        Box(
                            Modifier.size(32.dp).background(Color(value.toInt()), CircleShape)
                                .clickable { color = value }
                                .testTag("quick-color-${value}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (color == value) Text("✓", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_010), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickEntryAction.entries.forEach { action ->
                        FilterChip(
                            selected = actionName == action.name,
                            onClick = { actionName = action.name },
                            label = { Text(quickActionLabel(action.name)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = duration, onValueChange = { duration = it.filter(Char::isDigit).take(5) },
                    label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_011)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_012), style = MaterialTheme.typography.labelLarge)
                Box {
                    OutlinedButton(onClick = { projectMenu = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(state.projectLabel(projectId)?.let { "项目：$it" } ?: "不关联项目")
                    }
                    DropdownMenu(expanded = projectMenu, onDismissRequest = { projectMenu = false }) {
                        DropdownMenuItem(text = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_013)) }, onClick = { projectId = null; projectMenu = false })
                        activeProjects.forEach { project ->
                            DropdownMenuItem(text = { Text(project.name) }, onClick = { projectId = project.id; projectMenu = false })
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { taskMenu = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(state.taskLabel(taskId)?.let { "任务：$it" } ?: "不关联任务")
                    }
                    DropdownMenu(expanded = taskMenu, onDismissRequest = { taskMenu = false }) {
                        DropdownMenuItem(text = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_014)) }, onClick = { taskId = null; taskMenu = false })
                        activeTasks.take(100).forEach { task ->
                            DropdownMenuItem(text = { Text(task.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                onClick = { taskId = task.id; taskMenu = false })
                        }
                    }
                }
                if (state.tags.isNotEmpty()) {
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_015), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.tags.forEach { tag ->
                            FilterChip(
                                selected = tag.id in tags,
                                onClick = { tags = if (tag.id in tags) tags - tag.id else tags + tag.id },
                                label = { Text(tag.name) },
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_016), Modifier.weight(1f))
                    Switch(checked = pinned, onCheckedChange = { pinned = it }, enabled = !state.busy)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }, enabled = !state.busy) { Text(if (state.busy) "保存中…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } },
    )
}

private fun activityLabelOf(activity: ActivityTemplateEntity, activities: List<ActivityTemplateEntity>): String {
    val parent = activity.parentId?.let { id -> activities.firstOrNull { it.id == id } }
    return listOfNotNull(parent?.name?.takeIf { it.isNotBlank() }, activity.name).joinToString(" / ")
}

@Composable
internal fun QuickEntryConflictDialog(
    conflict: QuickEntryLaunch.NeedsConfirmation,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_017)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("同一时间只允许一个主计时。下面这些正在运行；继续会先结束它们，再开始“${conflict.entry.label ?: "快捷记录"}”。")
                conflict.slots.forEach { slot ->
                    Text("· ${slotName(slot)}（版本 ${slot.version}）", style = MaterialTheme.typography.bodyMedium)
                }
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_018), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_focus_screen_007)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } },
    )
}

internal fun slotName(slot: QuickEntrySlot): String {
    val prefix = when (slot.kind) {
        "TIMER" -> "自由计时"
        "WORKOUT" -> "训练"
        "FOCUS" -> "专注"
        else -> "计时"
    }
    return "$prefix：${slot.label}"
}

/**
 * Today-page chips. Pinned entries come first, followed by up to three recently used unpinned
 * entries. Creation actions return an editor draft; the host decides whether to open the shared
 * schedule/actual editor preview.
 */
@Composable
fun QuickEntryChips(
    model: QuickEntryModel,
    onOpenManager: () -> Unit,
    onOpenFocus: (() -> Unit)? = null,
    onOpenDraft: ((QuickEntryDraft) -> Unit)? = null,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var conflict by remember { mutableStateOf<QuickEntryLaunch.NeedsConfirmation?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(model) { model.refresh() }

    fun launch(entry: QuickEntryEntity, seen: List<QuickEntrySlot> = emptyList()) {
        scope.launch {
            message = null
            when (val result = model.activate(entry.id, seen)) {
                is QuickEntryLaunch.NeedsConfirmation -> conflict = result
                is QuickEntryLaunch.StartedTimer -> message = "已开始计时：${displayTitle(entry, state)}"
                is QuickEntryLaunch.StartedFocus -> {
                    message = "已开始专注：${displayTitle(entry, state)}"
                    onOpenFocus?.invoke()
                }
                is QuickEntryLaunch.OpenEditor -> {
                    if (onOpenDraft != null) onOpenDraft(result.draft)
                    else message = "请在编辑预览中确认后再创建"
                }
                is QuickEntryLaunch.Failed -> message = result.message
            }
        }
    }

    val pinned = state.entries.filter { it.pinned }.sortedBy { it.sortOrder }
    val recent = state.entries.filter { !it.pinned && it.lastUsedAt != null }
        .sortedByDescending { it.lastUsedAt }.take(3)
    val chips = (pinned + recent).distinctBy { it.id }

    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_019), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                if (state.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                TextButton(onClick = onOpenManager, modifier = Modifier.testTag("quick-entry-manage")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_020)) }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (chips.isEmpty()) {
                    OutlinedButton(onClick = onOpenManager) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_021)) }
                } else {
                    chips.forEach { entry ->
                        Surface(
                            Modifier.clickable(enabled = !state.busy) { launch(entry) },
                            shape = RoundedCornerShape(50),
                            color = if (entry.color == 0L) MaterialTheme.colorScheme.secondaryContainer else Color(entry.color.toInt()),
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(entry.icon.ifBlank { "▶" }, color = if (entry.color == 0L) MaterialTheme.colorScheme.onSecondaryContainer else Color.White)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    displayTitle(entry, state),
                                    color = if (entry.color == 0L) MaterialTheme.colorScheme.onSecondaryContainer else Color.White,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
            message?.let {
                Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    conflict?.let { pending ->
        QuickEntryConflictDialog(
            conflict = pending,
            onDismiss = { conflict = null },
            onConfirm = {
                val entry = pending.entry
                conflict = null
                launch(entry, pending.slots)
            },
        )
    }
}
