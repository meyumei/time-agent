package com.timeagent.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.timeagent.domain.command.CommandResult
import com.timeagent.domain.command.CommandStatus
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.Gson
import com.timeagent.core.*
import com.timeagent.data.*
import com.timeagent.recurring.*
import com.timeagent.reminders.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.UUID
import kotlinx.coroutines.launch

private val gson = Gson()
private val dateFmt = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)
private val stampFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private const val DEADLINE_DOMAIN = "DEADLINE"

private fun rulePattern(rule: RecurringRuleEntity): RecurringPattern? =
    runCatching { gson.fromJson(rule.patternJson, RecurringPattern::class.java) }.getOrNull()

private fun parseDate(text: String): LocalDate? =
    runCatching { LocalDate.parse(text.trim(), dateFmt) }.getOrNull()

private fun fmtAt(value: Long?, zone: ZoneId): String =
    value?.let { Instant.ofEpochMilli(it).atZone(zone).format(stampFmt) } ?: "—"

private fun freqLabel(f: String?): String = when (f) {
    "ONCE" -> "一次"
    "MONTHLY" -> "每月"
    "YEARLY" -> "每年"
    else -> "未设置"
}

private fun statusLabel(s: String): String = when (s) {
    "PENDING" -> "待处理"
    "DELETED" -> "原任务已删"
    "CANCELLED" -> "已取消"
    "COMPLETED" -> "已完成"
    "SKIPPED" -> "已跳过"
    "SUSPENDED" -> "已暂停"
    "SUPERSEDED" -> "已替代"
    else -> s
}


internal enum class DeadlineBucket { TODAY_OR_OVERDUE, WITHIN_7_DAYS, LATER }

private fun bucketLabel(bucket: DeadlineBucket): String = when (bucket) {
    DeadlineBucket.TODAY_OR_OVERDUE -> "今天与已到期"
    DeadlineBucket.WITHIN_7_DAYS -> "7天内"
    DeadlineBucket.LATER -> "以后"
}

// 分组看本次实例的有效到期日；改期后任务到期日已更新，不能用原始规则开始日代替。
private fun deadlineDueAt(instance: RecurringInstanceEntity, task: TaskEntity?, zone: ZoneId): Long? =
    task?.dueAt ?: instance.scheduledAt ?: runCatching {
        LocalDate.parse(instance.originalDate).atStartOfDay(zone).toInstant().toEpochMilli()
    }.getOrNull()

internal fun deadlineBucketFor(dueAt: Long?, nowMillis: Long, zone: ZoneId): DeadlineBucket {
    if (dueAt == null) return DeadlineBucket.LATER
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val dueDate = Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate()
    return when {
        !dueDate.isAfter(today) -> DeadlineBucket.TODAY_OR_OVERDUE
        !dueDate.isAfter(today.plusDays(7)) -> DeadlineBucket.WITHIN_7_DAYS
        else -> DeadlineBucket.LATER
    }
}

internal data class DeadlineNext(
    val rule: RecurringRuleEntity,
    val instance: RecurringInstanceEntity,
    val task: TaskEntity?,
    val dueAt: Long?,
)

internal fun nextPendingDeadlines(
    rules: List<RecurringRuleEntity>,
    instances: List<RecurringInstanceEntity>,
    tasks: Map<String, TaskEntity>,
    zone: ZoneId
): List<DeadlineNext> = rules.mapNotNull { rule ->
    val next = instances
        .filter { it.ruleId == rule.id && recurringState(it, tasks[it.taskId]) == "PENDING" }
        .minWithOrNull(
            compareBy<RecurringInstanceEntity>(
                { deadlineDueAt(it, tasks[it.taskId], zone) ?: Long.MAX_VALUE },
                { it.originalDate },
                { it.ordinal }
            )
        ) ?: return@mapNotNull null
    DeadlineNext(rule, next, tasks[next.taskId], deadlineDueAt(next, tasks[next.taskId], zone))
}

internal fun handledDeadlines(
    rules: List<RecurringRuleEntity>,
    instances: List<RecurringInstanceEntity>,
    tasks: Map<String, TaskEntity>,
    zone: ZoneId
): List<DeadlineNext> {
    val ruleById = rules.associateBy { it.id }
    return instances
        .mapNotNull { instance ->
            val rule = ruleById[instance.ruleId] ?: return@mapNotNull null
            val task = tasks[instance.taskId]
            if (recurringState(instance, task) == "PENDING") return@mapNotNull null
            DeadlineNext(rule, instance, task, deadlineDueAt(instance, task, zone))
        }
        .sortedByDescending { it.task?.completedAt ?: it.dueAt ?: 0L }
}

@Composable
fun DeadlineScreen(
    data: LocalSnapshot,
    model: TimeViewModel,
    onTask: (TaskEntity) -> Unit,
    onClose: () -> Unit,
    onSchedule: (TaskEntity) -> Unit = onTask,
    onToggleTask: (TaskEntity) -> Unit = onTask,
) {
    val context = LocalContext.current
    val now by model.now.collectAsStateWithLifecycle()
    val zone = model.repository.zone
    val today=now.atZone(zone).toLocalDate()
    val service = remember(model) { RecurringService(model.repository) }
    val scope = rememberCoroutineScope()

    var access by remember { mutableStateOf(reminderAccessState(context)) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { access=reminderAccessState(context) }
    var accessOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    val draftStore = remember(context) { DeadlineEditorDraftStore(context) }
    var editingDraftId by rememberSaveable { mutableStateOf<String?>(null) }
    val drafts = remember(editingDraftId, data.recurringRules) { draftStore.all() }
    val snackbar = remember { SnackbarHostState() }
    var undoMessage by remember { mutableStateOf<com.timeagent.ui.components.UndoMessage?>(null) }
    com.timeagent.ui.components.UndoSnackbar(snackbar, undoMessage,
        onUndo = { token -> model.perform {
            val result = model.repository.commands.execute(com.timeagent.domain.command.UndoCommand(UUID.randomUUID().toString(), token))
            check(result.status in setOf(CommandStatus.APPLIED, CommandStatus.DUPLICATE)) { result.message }
        } },
        onConsumed = { if (undoMessage?.id == it) undoMessage = null })
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var handledShown by remember { mutableStateOf(setOf<String>()) }
    var showHandledAll by remember { mutableStateOf(false) }
    var delayChoice by remember { mutableStateOf<RecurringInstanceEntity?>(null) }
    val moveStore = remember(context) { RecurringMoveDraftStore(context) }
    var editingMoveId by rememberSaveable { mutableStateOf<String?>(null) }
    val moveDrafts = remember(editingMoveId, data.recurringInstances) { moveStore.all().filter { it.domain == DEADLINE_DOMAIN } }

    val rules = data.recurringRules.filter { it.domain == DEADLINE_DOMAIN && it.deletedAt == null }
    val taskById = data.tasks.associateBy { it.id }
    val nextItems = nextPendingDeadlines(rules, data.recurringInstances, taskById, zone)
    val handledItems = handledDeadlines(rules, data.recurringInstances, taskById, zone)

    fun editRule(rule: RecurringRuleEntity?, from: LocalDate? = null) {
        val draft = rule?.let { draftStore.read(it.id) } ?: DeadlineEditorDraft.create(rule, from, today, zone,
            context.getString(com.timeagent.R.string.deadline_draft_default_category))
        if (draftStore.save(draft)) editingDraftId = draft.id
        else model.report(context.getString(com.timeagent.R.string.deadline_draft_write_failed))
    }

    fun openMove(instance: RecurringInstanceEntity) {
        val draft = moveStore.read(instance.id) ?: RecurringMoveDraft.create(instance, taskById[instance.taskId], zone)
        if (moveStore.save(draft)) editingMoveId = draft.id
        else model.report(context.getString(com.timeagent.R.string.move_draft_write_failed))
    }

    fun act(instance: RecurringInstanceEntity, action: String) {
        model.perform(action = {
            service.act(instance.id, instance.version, action, UUID.randomUUID().toString(),
                taskById[instance.taskId]?.version, data.plans.filter { it.taskId == instance.taskId && it.status == "SCHEDULED" && it.deletedAt == null }.associate { it.id to it.version })
        })
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.navigationBarsPadding()) },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding).statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClose) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_back)) }
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_001), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                TextButton(
                    enabled = data.history.any { !it.undone },
                    onClick = { scope.launch { model.perform(action = { model.repository.undoLastAction() }) } },
                ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_undo)) }
                TextButton(
                    onClick = { scope.launch { model.perform(action = { model.repository.redoLastAction() }) } },
                ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_redo)) }
            }

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { editRule(null) },
                        modifier = Modifier.testTag("deadline-create"),
                    ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_002)) }
                    OutlinedButton(
                        onClick = { scope.launch { model.perform(action = { service.refresh() }) } },
                    ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_003)) }
                }
                drafts.forEach { draft ->
                    OutlinedButton(onClick = { editingDraftId = draft.id }, modifier = Modifier.fillMaxWidth()) {
                        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.deadline_draft_resume,
                            draft.fields.title.ifBlank { context.getString(com.timeagent.R.string.deadline_draft_untitled) }))
                    }
                }
                moveDrafts.forEach { draft ->
                    OutlinedButton(onClick = { editingMoveId = draft.id }, modifier = Modifier.fillMaxWidth()) {
                        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.move_draft_resume, draft.title))
                    }
                }
                Text(
                    androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_004),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 6.dp),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { accessOpen = !accessOpen }) {
                        Text(if (accessOpen) "收起通知设置" else "通知设置")
                    }
                    Text(
                        buildString {
                            append("通知")
                            append(if (access.notificationsAllowed) "可用" else "未授权")
                            append(" / 精确闹钟")
                            append(if (access.exactAlarmsAllowed) "可用" else "受限")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (accessOpen) {
                    NotificationAccess(
                        modifier = Modifier.fillMaxWidth(),
                        onAccessChanged = { access = reminderAccessState(context) },
                    )
                    Text(
                        androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_005),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                TextButton(onClick = { infoOpen = !infoOpen }) {
                    Text(if (infoOpen) "收起说明" else "说明", style = MaterialTheme.typography.bodySmall)
                }
                if (infoOpen) {
                    Text(
                        androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_006) +
                            "过期提醒不补发，重启后只重新登记未来提醒。重新打开不会补打卡或恢复计划。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }

                DeadlineOverview(data, nextItems, now, zone, onTask, onSchedule, onToggleTask,
                    onHandleRecurring = { act(it.instance, "COMPLETE") },
                    onDelayRecurring = { delayChoice = it.instance })

                if (handledItems.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        Text(
                            "已处理 · ${handledItems.size}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showHandledAll = !showHandledAll }) {
                            Text(if (showHandledAll) "收起" else "展开", fontSize = 12.sp)
                        }
                    }
                    if (showHandledAll) {
                        handledItems.take(5).forEach { item ->
                            DeadlineFocusRow(
                                item = item,
                                zone = zone,
                                onTask = onTask,
                                onHandle = {},
                                onReopen = { act(item.instance, "REOPEN") },
                                onDelay = { delayChoice = item.instance },
                            )
                        }
                        if (handledItems.size > 5) {
                            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_008), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Text(
                    androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_009),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_010), style = MaterialTheme.typography.bodySmall)

                if (rules.isEmpty()) {
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_011), modifier = Modifier.padding(vertical = 12.dp))
                }

                rules.forEach { rule ->
                    val pattern = rulePattern(rule)
                    val paused = pattern?.paused == true
                    val instances = data.recurringInstances.filter { it.ruleId == rule.id }
                    val active = instances.filter { recurringState(it,data.tasks.find{t->t.id==it.taskId}) == "PENDING" }
                        .sortedBy { data.tasks.find{t->t.id==it.taskId}?.dueAt ?: Long.MAX_VALUE }
                    val handled = instances.filter { recurringState(it,data.tasks.find{t->t.id==it.taskId}) != "PENDING" }
                        .sortedByDescending { it.scheduledAt ?: 0L }
                    val showAll = rule.id in expanded
                    val showHandled = rule.id in handledShown

                    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(rule.title, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        listOf(rule.category.ifBlank { "其他" }, freqLabel(pattern?.frequency), if (paused) "已暂停" else "进行中").joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                TextButton(
                                    onClick = { editRule(rule) },
                                    modifier = Modifier.testTag("deadline-rule-${rule.id}"),
                                ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_012)) }
                                TextButton(onClick = {
                                    if (pattern == null) return@TextButton
                                    scope.launch {
                                        model.perform(action = {
                                            service.saveRule(
                                                rule.copy(patternJson = gson.toJson(pattern.copy(paused = !paused))),
                                                rule.version,
                                                today,
                                                UUID.randomUUID().toString(),
                                            )
                                        })
                                    }
                                }) { Text(if (paused) "恢复" else "暂停") }
                            }

                            if (rule.reference.isNotBlank()) {
                                Text(
                                    rule.reference,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }

                            if (active.isEmpty() && handled.isEmpty()) {
                                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_013), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                            }

                            (if (showAll) active else active.take(3)).forEach { instance ->
                                val task = data.tasks.firstOrNull { it.id == instance.taskId }
                                val reminder = data.reminders.firstOrNull { it.id == "recurring-reminder:${instance.id}" }
                                InstanceRow(
                                    instance = instance,
                                    task = task,
                                    reminder = reminder,
                                    leadMinutes = runCatching { rulePattern(gson.fromJson(instance.ruleSnapshot,RecurringRuleEntity::class.java))?.leadMinutes }.getOrNull(),
                                    now = now,
                                    zone = zone,
                                    access = access,
                                    onTask = onTask,
                                    onComplete = { act(instance, "COMPLETE") },
                                    onReopen = { act(instance, "REOPEN") },
                                    onDelay = { delayChoice = instance },
                                )
                            }

                            if (active.size > 3) {
                                TextButton(onClick = {
                                    expanded = if (showAll) expanded - rule.id else expanded + rule.id
                                }) { Text(if (showAll) "收起" else "展开全部（${active.size}）") }
                            }

                            if (handled.isNotEmpty()) {
                                TextButton(onClick = {
                                    handledShown = if (showHandled) handledShown - rule.id else handledShown + rule.id
                                }) { Text(if (showHandled) "隐藏已处理" else "显示已处理（${handled.size}）") }
                            }

                            if (showHandled) {
                                handled.forEach { instance ->
                                    val task = data.tasks.firstOrNull { it.id == instance.taskId }
                                    val reminder = data.reminders.firstOrNull { it.id == "recurring-reminder:${instance.id}" }
                                    InstanceRow(
                                        instance = instance,
                                        task = task,
                                        reminder = reminder,
                                        leadMinutes = runCatching { rulePattern(gson.fromJson(instance.ruleSnapshot,RecurringRuleEntity::class.java))?.leadMinutes }.getOrNull(),
                                        now = now,
                                        zone = zone,
                                        access = access,
                                        onTask = onTask,
                                        onComplete = {},
                                        onReopen = { act(instance, "REOPEN") },
                                        onDelay = { delayChoice = instance },
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    editingDraftId?.let { id ->
        val draft = remember(id) { draftStore.read(id) }
        val busy by model.busy.collectAsState()
        if (draft != null) key(id) {
            RuleDialog(
                initial = draft,
                projects = data.projects.filter { it.deletedAt == null },
                busy = busy,
                onDraftChange = draftStore::save,
                onRemoveDraft = { draftStore.remove(id) },
                onDismiss = { editingDraftId = null },
                onSave = { item, version, from, requestId ->
                    val result = model.repository.commands.execute(com.timeagent.domain.command.SaveRecurringRule(
                        requestId, item, version, from, RuleChangeScope.INCLUDING_PENDING))
                    if (result.status in setOf(CommandStatus.APPLIED, CommandStatus.DUPLICATE)) {
                        result.undoToken?.let { token ->
                            undoMessage = com.timeagent.ui.components.UndoMessage(result.requestId,
                                context.getString(com.timeagent.R.string.deadline_draft_saved), token)
                        }
                    }
                    result
                },
            )
        } else LaunchedEffect(id) { editingDraftId = null }
    }

    delayChoice?.let { instance ->
        val rule = data.recurringRules.firstOrNull { it.id == instance.ruleId }
        AlertDialog(
            onDismissRequest = { delayChoice = null },
            title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_014)) },
            text = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_015)) },
            confirmButton = {
                TextButton(onClick = { delayChoice = null; openMove(instance) }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_016)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    delayChoice = null
                    if (rule != null) {
                        editRule(rule, parseDate(instance.originalDate))
                    }
                }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_017)) }
            },
        )
    }

    editingMoveId?.let { id ->
        val draft = remember(id) { moveStore.read(id) }
        if (draft != null) key(id) {
            RecurringMoveEditDialog(draft, model,
                onDraftChange = moveStore::save,
                onRemoveDraft = { moveStore.remove(id) },
                onDismiss = { editingMoveId = null },
                onSaved = { result -> result.undoToken?.let { token ->
                    undoMessage = com.timeagent.ui.components.UndoMessage(result.requestId,
                        context.getString(com.timeagent.R.string.move_draft_saved), token)
                } })
        } else LaunchedEffect(id) { editingMoveId = null }
    }
}

@Composable
private fun DeadlineFocusRow(
    item: DeadlineNext,
    zone: ZoneId,
    onTask: (TaskEntity) -> Unit,
    onHandle: () -> Unit,
    onReopen: () -> Unit,
    onDelay: () -> Unit,
) {
    val instance = item.instance
    val task = item.task
    val state = recurringState(instance, task)
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task?.title ?: "任务不存在",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(statusLabel(state), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "期限 ${fmtAt(item.dueAt, zone)} · ${item.rule.title}",
                style = MaterialTheme.typography.bodySmall,
            )
            val usable = task != null && task.deletedAt == null
            Row {
                TextButton(enabled = usable, onClick = { task?.let(onTask) }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_018), fontSize = 12.sp) }
                if (usable && state == "PENDING" && (task?.status == "TODO" || task?.status == "IN_PROGRESS")) {
                    TextButton(
                        onClick = onHandle,
                        modifier = Modifier.testTag("deadline-focus-handle-${instance.id}"),
                    ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_019), fontSize = 12.sp) }
                } else if (usable && state != "PENDING") {
                    TextButton(
                        onClick = onReopen,
                        modifier = Modifier.testTag("deadline-focus-handle-${instance.id}"),
                    ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_020), fontSize = 12.sp) }
                }
                if (state == "PENDING" && usable) {
                    TextButton(
                        onClick = onDelay,
                        modifier = Modifier.testTag("deadline-focus-delay-${instance.id}"),
                    ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_014), fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun InstanceRow(
    instance: RecurringInstanceEntity,
    task: TaskEntity?,
    reminder: ReminderEntity?,
    leadMinutes: Int?,
    now: Instant,
    zone: ZoneId,
    access: ReminderAccessState,
    onTask: (TaskEntity) -> Unit,
    onComplete: () -> Unit,
    onReopen: () -> Unit,
    onDelay: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(task?.title ?: "任务不存在", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(statusLabel(recurringState(instance,task)), style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "期限 ${fmtAt(task?.dueAt, zone)} · 原始日期 ${instance.originalDate}",
            style = MaterialTheme.typography.bodySmall,
        )
        if (task == null || task.deletedAt != null) {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_021), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                deadlineReminderText(
                    instance = instance,
                    task = task,
                    reminder = reminder,
                    leadMinutes = leadMinutes,
                    now = now,
                    notificationsAllowed = access.notificationsAllowed,
                    exactAllowed = access.exactAlarmsAllowed,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (task.status == "COMPLETED") {
                Text("完成于 ${fmtAt(task.completedAt, zone)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row {
            TextButton(enabled = task != null, onClick = { task?.let(onTask) }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_018)) }
            if (task != null && task.deletedAt == null) {
                if (task.status == "TODO" || task.status == "IN_PROGRESS") {
                    TextButton(
                        onClick = onComplete,
                        modifier = Modifier.testTag("deadline-handle-${instance.id}"),
                    ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_019)) }
                } else if (task.status in setOf("COMPLETED","CANCELLED")) {
                    TextButton(
                        onClick = onReopen,
                        modifier = Modifier.testTag("deadline-handle-${instance.id}"),
                    ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_020)) }
                }
            }
            if (recurringState(instance,task) == "PENDING") {
                TextButton(
                    onClick = onDelay,
                    modifier = Modifier.testTag("deadline-delay-${instance.id}"),
                ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_014)) }
            }
        }
    }
}

@Composable
private fun DeadlineDateField(
    label: String,
    text: String,
    fallback: LocalDate,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val parsed = parseDate(text)
    com.timeagent.ui.components.DateField(label, parsed ?: fallback, { onChange(it.toString()) }, modifier,
        error = if (parsed == null) androidx.compose.ui.res.stringResource(com.timeagent.R.string.deadline_picker_error_date) else null)
}

@Composable
private fun DeadlineTimeField(label: String, text: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val parsed = runCatching { LocalTime.parse(text.trim(), timeFmt) }.getOrNull()
    com.timeagent.ui.components.TimeField(label, parsed ?: LocalTime.of(9, 0), { onChange(it.format(timeFmt)) }, modifier,
        error = if (parsed == null) androidx.compose.ui.res.stringResource(com.timeagent.R.string.deadline_picker_error_time) else null)
}

@Composable
private fun RuleDialog(
    initial: DeadlineEditorDraft,
    projects: List<ProjectEntity>,
    busy: Boolean,
    onDraftChange: (DeadlineEditorDraft) -> Boolean,
    onRemoveDraft: () -> Boolean,
    onDismiss: () -> Unit,
    onSave: suspend (RecurringRuleEntity, Long?, LocalDate, String) -> CommandResult,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var completed by remember(initial.id) { mutableStateOf(initial.saved) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val original = initial.original
    val old = remember(initial.id) { original?.let { rulePattern(it) } }
    val today = remember(initial.id) { LocalDate.parse(initial.baseDate) }
    val title = draft.fields.title
    val category = draft.fields.category
    val dateText = draft.fields.dateText
    val timeText = draft.fields.timeText
    val frequency = draft.fields.frequency
    val leadText = draft.fields.leadText
    val projectId = draft.fields.projectId
    val reference = draft.fields.reference
    val fromText = draft.fields.fromText
    val editing = !busy && !submitting && !completed
    fun update(fields: DeadlineFields) {
        if (busy || submitting || completed || fields == draft.fields) return
        draft = draft.copy(fields = fields, requestId = UUID.randomUUID().toString())
        error = if (onDraftChange(draft)) null else context.getString(com.timeagent.R.string.deadline_draft_write_failed)
    }
    fun cleanup() {
        if (onRemoveDraft()) onDismiss()
        else error = context.getString(if (completed) com.timeagent.R.string.deadline_draft_cleanup_failed else com.timeagent.R.string.deadline_draft_write_failed)
    }
    val dismiss = rememberDraftExit(initial.id, draft.fields.values(), !editing,
        onDiscard = ::cleanup,
        onKeep = {
            if (onDraftChange(draft)) onDismiss()
            else error = context.getString(com.timeagent.R.string.deadline_draft_write_failed)
        }, initialFields = initial.baseline.values())

    AlertDialog(
        onDismissRequest = { if (completed) cleanup() else dismiss() },
        title = { Text(context.getString(if (original == null) com.timeagent.R.string.deadline_picker_create else com.timeagent.R.string.deadline_picker_edit)) },
        text = {
            if (completed) Column {
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.deadline_draft_saved))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } else Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { update(draft.fields.copy(title = it)) }, enabled = editing,
                    label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_template_editor_006)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("deadline-title"),
                )
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_027), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("续费", "归还", "报销", "生日", "其他").forEach { item ->
                        FilterChip(
                            selected = category == item,
                            onClick = { update(draft.fields.copy(category = item)) }, enabled = editing,
                            label = { Text(item) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                DeadlineDateField(
                    label = context.getString(com.timeagent.R.string.deadline_picker_date),
                    text = dateText,
                    fallback = today.plusDays(1),
                    onChange = { update(draft.fields.copy(dateText = it)) },
                    modifier = Modifier.testTag("deadline-date"),
                )
                DeadlineTimeField(
                    label = context.getString(com.timeagent.R.string.deadline_picker_time),
                    text = timeText,
                    onChange = { update(draft.fields.copy(timeText = it)) },
                    modifier = Modifier.testTag("deadline-time"),
                )
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("ONCE" to "一次", "MONTHLY" to "每月", "YEARLY" to "每年").forEach { (key, label) ->
                        FilterChip(
                            selected = frequency == key,
                            onClick = { update(draft.fields.copy(frequency = key)) }, enabled = editing,
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = leadText,
                    onValueChange = { update(draft.fields.copy(leadText = it.filter { ch -> ch.isDigit() })) }, enabled = editing,
                    label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_030)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("deadline-lead"),
                )
                if (original != null) {
                    DeadlineDateField(
                        label = context.getString(com.timeagent.R.string.deadline_picker_effective),
                        text = fromText,
                        fallback = today,
                        onChange = { update(draft.fields.copy(fromText = it)) },
                    )
                }
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_template_editor_009), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(
                        selected = projectId == null,
                        onClick = { update(draft.fields.copy(projectId = null)) }, enabled = editing,
                        label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_032)) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    projects.forEach { project ->
                        FilterChip(
                            selected = projectId == project.id,
                            onClick = { update(draft.fields.copy(projectId = project.id)) }, enabled = editing,
                            label = { Text(project.name) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                if (projectId != null && projects.none { it.id == projectId }) {
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.deadline_draft_project_missing), color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = reference,
                    onValueChange = { if (it.length <= 20000) update(draft.fields.copy(reference = it)) }, enabled = editing,
                    label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_033)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_034),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.testTag("deadline-save"), enabled = !busy && !submitting, onClick = {
                if (completed) { cleanup(); return@TextButton }
                if (busy || submitting) return@TextButton
                val pending = draft
                val fields = pending.fields
                val date = parseDate(fields.dateText)
                val time = runCatching { LocalTime.parse(fields.timeText.trim(), timeFmt) }.getOrNull()
                val days = if (fields.leadText.isBlank()) null else fields.leadText.toIntOrNull()
                val from = if (pending.original == null) {
                    if (fields.frequency == "ONCE") date else LocalDate.parse(pending.baseDate)
                } else parseDate(fields.fromText)
                when {
                    fields.title.isBlank() -> error = context.getString(com.timeagent.R.string.deadline_picker_error_title)
                    date == null -> error = context.getString(com.timeagent.R.string.deadline_picker_error_date)
                    time == null -> error = context.getString(com.timeagent.R.string.deadline_picker_error_time)
                    fields.leadText.isNotBlank() && (days == null || days !in 0..366) -> error = context.getString(com.timeagent.R.string.deadline_picker_error_lead)
                    from == null -> error = context.getString(com.timeagent.R.string.deadline_picker_error_effective)
                    else -> {
                        val pattern = RecurringPattern(
                            id = pending.id,
                            frequency = fields.frequency,
                            startDate = date.toString(),
                            minuteOfDay = time.hour * 60 + time.minute,
                            durationMinutes = old?.durationMinutes?:30,
                            untilDate=old?.untilDate,
                            zoneId = old?.zoneId ?: pending.zoneId,
                            leadMinutes = days?.times(1440),
                            paused = old?.paused ?: false,
                            effectiveFrom = from.toString(),
                        )
                        val item = if (pending.original == null) {
                            RecurringRuleEntity(
                                id = pending.id,
                                title = fields.title.trim(),
                                patternJson = gson.toJson(pattern),
                                domain = DEADLINE_DOMAIN,
                                category = fields.category,
                                reference = fields.reference,
                                projectId = fields.projectId,
                            )
                        } else {
                            pending.original.copy(
                                title = fields.title.trim(),
                                patternJson = gson.toJson(pattern),
                                category = fields.category,
                                reference = fields.reference,
                                projectId = fields.projectId,
                            )
                        }
                        if (!onDraftChange(pending)) {
                            error = context.getString(com.timeagent.R.string.deadline_draft_write_failed)
                            return@TextButton
                        }
                        submitting = true
                        scope.launch {
                            try {
                                val result = onSave(item, pending.original?.version, from, pending.requestId)
                                if (result.status in setOf(CommandStatus.APPLIED, CommandStatus.DUPLICATE)) {
                                    completed = true
                                    draft = pending.copy(saved = true)
                                    onDraftChange(draft)
                                    cleanup()
                                } else error = result.message
                            } catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                error = failure.message ?: context.getString(com.timeagent.R.string.deadline_draft_save_failed)
                            } finally {
                                submitting = false
                            }
                        }
                    }
                }
            }) { Text(androidx.compose.ui.res.stringResource(if (completed) com.timeagent.R.string.action_close else com.timeagent.R.string.action_save)) }
        },
        dismissButton = { if (!completed) TextButton(onClick = dismiss, enabled = editing) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } },
    )
}
