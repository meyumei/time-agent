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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.Gson
import com.timeagent.data.ActivityTemplateEntity
import com.timeagent.R
import com.timeagent.domain.command.QuickEntrySlot
import com.timeagent.ui.components.UndoMessage
import com.timeagent.ui.components.UndoSnackbar
import com.timeagent.ui.rememberDraftExit
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
    val context = LocalContext.current
    val draftStore = remember(context) { QuickEntryEditorDraftStore(context) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val drafts = remember(editingId, state.entries) { draftStore.all() }
    fun edit(entry: QuickEntryEntity?) {
        val draft = entry?.let { draftStore.read(it.id) } ?: QuickEntryEditorDraft.create(
            entry, state.activities.firstOrNull { it.archivedAt == null }?.id.orEmpty())
        if (draftStore.save(draft)) editingId = draft.entry.id
        else model.notify(context.getString(R.string.quick_draft_write_failed))
    }
    val confirmationSaver = remember {
        Saver<QuickEntryLaunch.NeedsConfirmation?, String>(
            save = { it?.let { value -> Gson().toJson(value) } },
            restore = { Gson().fromJson(it, QuickEntryLaunch.NeedsConfirmation::class.java) },
        )
    }
    var conflict by rememberSaveable(stateSaver = confirmationSaver) {
        mutableStateOf<QuickEntryLaunch.NeedsConfirmation?>(null)
    }
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
            when (val result = model.activate(entry.id, seen, expectedVersion = entry.version)) {
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
            when (val result = model.previewActivation(initialEntryId)) {
                is QuickEntryLaunch.NeedsConfirmation -> conflict = result
                is QuickEntryLaunch.OpenEditor -> if (onOpenDraft != null) onOpenDraft(result.draft)
                is QuickEntryLaunch.Failed -> model.notify(result.message)
                else -> Unit
            }
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
                onClick = { edit(null) },
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
                    items(drafts, key = { "draft:${it.entry.id}" }) { draft ->
                        OutlinedButton(onClick = { editingId = draft.entry.id }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.quick_draft_resume,
                                draft.entry.label?.takeIf(String::isNotBlank)
                                    ?: stringResource(R.string.quick_draft_untitled)))
                        }
                    }
                    if (pinned.isNotEmpty()) {
                        item { SectionLabel("固定") }
                        items(pinned, key = { it.id }) { entry ->
                            QuickEntryRow(entry, state, orderedEntries(state), state.busy,
                                onUse = { use(entry) },
                                onEdit = { edit(entry) },
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
                                onEdit = { edit(entry) },
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
                                onEdit = { edit(entry) },
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
                                    OutlinedButton(onClick = { edit(null) }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_005)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editingId?.let { id ->
        val draft = remember(id) { draftStore.read(id) }
        if (draft != null) QuickEntryEditorDialog(
            model = model, state = state, initial = draft,
            onDraftChange = draftStore::save,
            onRemoveDraft = { draftStore.remove(id) },
            onDismiss = { editingId = null },
        ) else LaunchedEffect(id) { editingId = null }
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
    initial: QuickEntryEditorDraft,
    onDraftChange: (QuickEntryEditorDraft) -> Boolean,
    onRemoveDraft: () -> Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val key = initial.entry.id
    val existing = initial.original
    var draft by remember(key) { mutableStateOf(initial) }
    var completed by remember(key) { mutableStateOf(initial.saved) }
    var submitting by remember { mutableStateOf(false) }
    val busy = state.busy || submitting || completed
    val activeActivities = state.activities.filter { it.archivedAt == null || it.id == existing?.activityId }
    val activeProjects = state.projects.filter { it.deletedAt == null }
    val activeTasks = state.tasks.filter { it.deletedAt == null && it.status != "CANCELLED" }
    val activityId = draft.entry.activityId
    val label = draft.entry.label.orEmpty()
    val icon = draft.entry.icon
    val color = draft.entry.color
    val actionName = draft.entry.action
    val duration = draft.durationText
    val projectId = draft.entry.projectId
    val taskId = draft.entry.taskId
    val tags = draft.entry.tagIdList
    val pinned = draft.entry.pinned
    var activityMenu by remember { mutableStateOf(false) }
    var projectMenu by remember { mutableStateOf(false) }
    var taskMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun update(next: QuickEntryEditorDraft) {
        if (state.busy || submitting || completed || next == draft) return
        draft = next.copy(requestId = UUID.randomUUID().toString())
        error = if (onDraftChange(draft)) null else context.getString(R.string.quick_draft_write_failed)
    }
    fun cleanup() {
        if (onRemoveDraft()) onDismiss()
        else error = context.getString(if (completed) R.string.quick_draft_cleanup_failed else R.string.quick_draft_write_failed)
    }
    val dismiss = rememberDraftExit(key, listOf(draft.entry, duration), busy,
        onDiscard = ::cleanup,
        onKeep = {
            if (onDraftChange(draft)) onDismiss()
            else error = context.getString(R.string.quick_draft_write_failed)
        }, initialFields = listOf(initial.baselineEntry, initial.baselineEntry.defaultDurationMinutes?.toString().orEmpty()))

    fun save() {
        if (state.busy || submitting || completed) return
        val pending = draft
        val entry = pending.entry
        if (entry.activityId.isBlank()) {
            error = "请选择一个活动"
            return
        }
        val minutes = pending.durationText.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (pending.durationText.isNotBlank() && (minutes == null || minutes !in 1..10_080)) {
            error = "默认时长须为 1 至 10080 分钟"
            return
        }
        val item = entry.copy(
            label = entry.label?.trim()?.takeIf { it.isNotEmpty() },
            icon = entry.icon.trim(),
            tagIds = entry.tagIdList.joinToString(","),
            defaultDurationMinutes = minutes,
        )
        submitting = true
        scope.launch {
            try {
                // Persist before submission so a process restart can replay the same request.
                if (!onDraftChange(pending)) {
                    error = context.getString(R.string.quick_draft_write_failed)
                } else if (model.saveEntry(item, pending.original?.version, pending.requestId)) {
                    completed = true
                    draft = pending.copy(saved = true)
                    onDraftChange(draft)
                    cleanup()
                }
            } finally {
                submitting = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (completed) cleanup() else dismiss() },
        title = { Text(if (existing == null) "新建快捷记录" else "编辑快捷记录") },
        text = {
            if (completed) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.quick_draft_saved))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } else Column(
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
                                onClick = { update(draft.copy(entry = draft.entry.copy(activityId = activity.id))); activityMenu = false },
                            )
                        }
                    }
                }
                OutlinedTextField(value = label, onValueChange = { update(draft.copy(entry = draft.entry.copy(label = it.take(80)))) },
                    label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_008)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = icon, onValueChange = { update(draft.copy(entry = draft.entry.copy(icon = it.take(16)))) },
                    label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_009)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_organization_activity_039))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QUICK_ENTRY_COLORS.forEach { value ->
                        Box(
                            Modifier.size(32.dp).background(Color(value.toInt()), CircleShape)
                                .clickable { update(draft.copy(entry = draft.entry.copy(color = value))) }
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
                            onClick = { update(draft.copy(entry = draft.entry.copy(action = action.name))) },
                            label = { Text(quickActionLabel(action.name)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = duration, onValueChange = { update(draft.copy(durationText = it.filter(Char::isDigit).take(5))) },
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
                        DropdownMenuItem(text = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_013)) }, onClick = { update(draft.copy(entry = draft.entry.copy(projectId = null))); projectMenu = false })
                        activeProjects.forEach { project ->
                            DropdownMenuItem(text = { Text(project.name) }, onClick = { update(draft.copy(entry = draft.entry.copy(projectId = project.id))); projectMenu = false })
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { taskMenu = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(state.taskLabel(taskId)?.let { "任务：$it" } ?: "不关联任务")
                    }
                    DropdownMenu(expanded = taskMenu, onDismissRequest = { taskMenu = false }) {
                        DropdownMenuItem(text = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_014)) }, onClick = { update(draft.copy(entry = draft.entry.copy(taskId = null))); taskMenu = false })
                        activeTasks.take(100).forEach { task ->
                            DropdownMenuItem(text = { Text(task.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                onClick = { update(draft.copy(entry = draft.entry.copy(taskId = task.id))); taskMenu = false })
                        }
                    }
                }
                if (state.tags.isNotEmpty()) {
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_015), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.tags.forEach { tag ->
                            FilterChip(
                                selected = tag.id in tags,
                                onClick = {
                                    val currentTags = draft.entry.tagIdList
                                    update(draft.copy(entry = draft.entry.copy(tagIds =
                                        (if (tag.id in currentTags) currentTags - tag.id else currentTags + tag.id).joinToString(","))))
                                },
                                label = { Text(tag.name) },
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_016), Modifier.weight(1f))
                    Switch(checked = pinned, onCheckedChange = { update(draft.copy(entry = draft.entry.copy(pinned = it))) }, enabled = !busy)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (completed) cleanup() else save() }, enabled = !state.busy && !submitting) {
                Text(if (completed) stringResource(R.string.action_close) else if (submitting) "保存中…" else "保存")
            }
        },
        dismissButton = { if (!completed) TextButton(onClick = dismiss, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } },
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
        title = { Text(androidx.compose.ui.res.stringResource(
            if (conflict.slots.isEmpty()) com.timeagent.R.string.quick_entry_confirm_start
            else com.timeagent.R.string.text_quick_entry_screen_017)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val title = conflict.entry.label ?: androidx.compose.ui.res.stringResource(com.timeagent.R.string.quick_entry_confirm_fallback)
                Text(androidx.compose.ui.res.stringResource(
                    if (conflict.slots.isEmpty()) com.timeagent.R.string.quick_entry_confirm_idle
                    else com.timeagent.R.string.quick_entry_confirm_switch, title))
                conflict.slots.forEach { slot ->
                    Text("· ${slotName(slot)}（版本 ${slot.version}）", style = MaterialTheme.typography.bodyMedium)
                }
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_quick_entry_screen_018), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(androidx.compose.ui.res.stringResource(
            if (conflict.slots.isEmpty()) com.timeagent.R.string.action_confirm
            else com.timeagent.R.string.text_focus_screen_007)) } },
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
            when (val result = model.activate(entry.id, seen, expectedVersion = entry.version)) {
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
