package com.timeagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.timeagent.R
import com.timeagent.domain.command.CommandStatus
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.Gson
import com.timeagent.core.*
import com.timeagent.data.*
import com.timeagent.recurring.RecurringService
import com.timeagent.recurring.recurringState
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

private enum class InstState { PENDING, DONE, DELETED, SKIPPED, SUSPENDED, SUPERSEDED, CANCELLED }
private fun stateOf(inst:RecurringInstanceEntity,task:TaskEntity?):InstState = when(val state=recurringState(inst,task)) {
    "COMPLETED"->InstState.DONE
    else->InstState.valueOf(state)
}

private fun stateLabel(state: InstState): String = when (state) {
    InstState.DONE -> "已完成"
    InstState.DELETED -> "原任务已删除"
    InstState.SKIPPED -> "已跳过"
    InstState.SUSPENDED -> "已暂停"
    InstState.SUPERSEDED -> "已替换"
    InstState.PENDING -> "待办"
    InstState.CANCELLED -> "已取消"
}

private fun wdName(d: Int): String = when (d) {
    1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"; 6 -> "六"; else -> "日"
}

private fun hhmm(minute: Int?): String =
    if (minute == null) "无固定时刻" else "%02d:%02d".format(minute / 60, minute % 60)

private fun freqLabel(p: RecurringPattern): String = when (p.frequency) {
    "WEEKDAYS" -> "每周" + p.weekdays.orEmpty().sorted().joinToString("") { wdName(it) }
    "WEEKLY_COUNT" -> "每周 ${p.weeklyCount} 次"
    "ONCE" -> "仅一次"
    "MONTHLY" -> "每月"
    "YEARLY" -> "每年"
    else -> "每日"
}

private fun patternOrNull(gson: Gson, rule: RecurringRuleEntity): RecurringPattern? = runCatching {
    val p = gson.fromJson(rule.patternJson, RecurringPattern::class.java)
    if (p == null || p.frequency.isBlank() || p.startDate.isBlank()) null else p
}.getOrNull()

private fun snapshotTitle(gson: Gson, inst: RecurringInstanceEntity): String? = runCatching {
    gson.fromJson(inst.ruleSnapshot, RecurringRuleEntity::class.java)?.title?.takeIf { it.isNotBlank() }
}.getOrNull()

private fun <T> toggle(set: Set<T>, value: T): Set<T> =
    if (set.contains(value)) set - value else set + value

// 本次实例的有效日期：改期写回任务到期日，未改期才回退到计划/原始日期。
private fun recurringDueAt(inst: RecurringInstanceEntity, task: TaskEntity?, zone: ZoneId): Long? =
    task?.dueAt ?: inst.scheduledAt ?: runCatching {
        LocalDate.parse(inst.originalDate).atStartOfDay(zone).toInstant().toEpochMilli()
    }.getOrNull()

internal fun recurringFocusInstances(
    instances: List<RecurringInstanceEntity>,
    tasks: Map<String, TaskEntity>,
    nowMillis: Long,
    zone: ZoneId,
    overdueDays: Long = 3,
    upcomingDays: Long = 3
): List<RecurringInstanceEntity> {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val from = today.minusDays(overdueDays)
    val to = today.plusDays(upcomingDays)
    return instances
        .filter { recurringState(it, tasks[it.taskId]) == "PENDING" }
        .filter { inst ->
            val date = recurringDueAt(inst, tasks[inst.taskId], zone)
                ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: return@filter false
            !date.isBefore(from) && !date.isAfter(to)
        }
        .sortedWith(compareBy<RecurringInstanceEntity>({ recurringDueAt(it, tasks[it.taskId], zone) ?: Long.MAX_VALUE }, { it.ordinal }))
}

private fun focusWhen(dueAt: Long?, hasTime: Boolean, today: LocalDate, zone: ZoneId): String {
    if (dueAt == null) return "日期未定"
    val at = Instant.ofEpochMilli(dueAt).atZone(zone)
    val date = at.toLocalDate()
    val time = if (hasTime) " %02d:%02d".format(at.hour, at.minute) else ""
    return when {
        date.isBefore(today) -> "${at.monthValue}-${at.dayOfMonth}$time 已过期"
        date.isEqual(today) -> "今天$time"
        date.isEqual(today.plusDays(1)) -> "明天$time"
        else -> "${at.monthValue}-${at.dayOfMonth}$time"
    }
}

private fun ruleSubtitle(rule: RecurringRuleEntity, p: RecurringPattern?, projectName: String?): String {
    val parts = mutableListOf<String>()
    parts += if (p == null) "规则无法解析" else freqLabel(p)
    p?.minuteOfDay?.let { parts += hhmm(it) }
    parts += if (projectName != null) "项目：$projectName" else if(rule.projectId!=null) "原项目已删除，请重新选择归属" else "未关联项目"
    if (p?.paused == true) parts += "已暂停"
    return parts.joinToString(" · ")
}

@Composable
fun RecurringScreen(
    data: LocalSnapshot,
    model: TimeViewModel,
    onTask: (TaskEntity) -> Unit,
    onClose: () -> Unit
) {
    val gson = remember { Gson() }
    val service = remember { RecurringService(model.repository) }
    val zone = remember { model.repository.zone }
    val now by model.now.collectAsStateWithLifecycle()
    val today = now.atZone(zone).toLocalDate()
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var history by remember { mutableStateOf<Set<String>>(emptySet()) }
    val context = LocalContext.current
    val draftStore = remember(context) { RecurringEditorDraftStore(context) }
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
    fun editRule(rule: RecurringRuleEntity?, from: String = today.toString()) {
        val draft = rule?.let { draftStore.read(it.id) } ?: RecurringEditorDraft.create(
            rule, today, zone, model.repository.now().toEpochMilli(), from)
        if (draftStore.save(draft)) editingDraftId = draft.id
        else model.report(context.getString(R.string.recurring_draft_write_failed))
    }
    val pauseStore = remember(context) { RecurringPauseDraftStore(context) }
    var editingPauseId by rememberSaveable { mutableStateOf<String?>(null) }
    val pauseDrafts = remember(editingPauseId, data.recurringRules) { pauseStore.all().filter { it.original.domain == "HABIT" } }
    var choice by remember { mutableStateOf<RecurringInstanceEntity?>(null) }
    val moveStore = remember(context) { RecurringMoveDraftStore(context) }
    var editingMoveId by rememberSaveable { mutableStateOf<String?>(null) }
    val moveDrafts = remember(editingMoveId, data.recurringInstances) { moveStore.all().filter { it.domain == "HABIT" } }

    val rules = data.recurringRules.filter { it.domain == "HABIT" && it.deletedAt == null }
        .sortedByDescending { it.createdAt }
    val taskById = data.tasks.associateBy { it.id }
    val projects = data.projects.filter { it.deletedAt == null }
    val projectName = projects.associate { it.id to it.name }
    val instancesByRule = data.recurringInstances.groupBy { it.ruleId }
    val habitRuleIds = rules.map { it.id }.toSet()
    val focus = recurringFocusInstances(
        data.recurringInstances.filter { it.ruleId in habitRuleIds },
        taskById,
        now.toEpochMilli(),
        zone
    )

    fun inWindow(date: String): Boolean = try {
        val d = LocalDate.parse(date)
        !d.isBefore(today.minusDays(7)) && !d.isAfter(today.plusDays(28))
    } catch (e: Exception) {
        false
    }

    fun togglePause(rule: RecurringRuleEntity, p: RecurringPattern?) {
        if (p == null) { model.report(context.getString(R.string.pause_draft_invalid_pattern)); return }
        val draft = pauseStore.read(rule.id) ?: RecurringPauseDraft.create(rule, p, today)
        if (pauseStore.save(draft)) editingPauseId = draft.id
        else model.report(context.getString(R.string.pause_draft_write_failed))
    }

    fun openMove(instance: RecurringInstanceEntity) {
        val draft = moveStore.read(instance.id) ?: RecurringMoveDraft.create(instance, taskById[instance.taskId], zone)
        if (moveStore.save(draft)) editingMoveId = draft.id
        else model.report(context.getString(R.string.move_draft_write_failed))
    }

    fun act(instance: RecurringInstanceEntity, action: String) {
        val taskVersion = taskById[instance.taskId]?.version
        val plans = data.plans.filter { it.taskId == instance.taskId && it.status == "SCHEDULED" && it.deletedAt == null }
            .associate { it.id to it.version }
        val requestId = UUID.randomUUID().toString()
        model.perform(action = {
            val result = service.act(instance.id, instance.version, action, requestId, taskVersion, plans)
            recurringInstanceUndo(context, result, action)?.let { undoMessage = it }
        })
    }

    Scaffold(modifier = Modifier.fillMaxSize(), contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.navigationBarsPadding()) }) { innerPadding ->
    Column(Modifier.fillMaxSize().padding(innerPadding).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClose) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_back), fontSize = 13.sp) }
            Column(Modifier.weight(1f)) {
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurring_screen_001), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurring_screen_002),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            TextButton(
                enabled = data.history.any { !it.undone },
                onClick = { model.perform(action = { model.repository.undoLastAction() }) }
            ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_undo), fontSize = 13.sp) }
            TextButton(
                enabled = data.history.any { it.undone && it.redoable },
                onClick = { model.perform(action = { model.repository.redoLastAction() }) }
            ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_redo), fontSize = 13.sp) }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { editRule(null) },
                modifier = Modifier.weight(1f).testTag("recurring-create")
            ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurring_screen_003), fontSize = 13.sp) }
            OutlinedButton(
                onClick = { model.perform(action = { service.refresh() }) },
                modifier = Modifier.weight(1f)
            ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurring_screen_004), fontSize = 13.sp) }
        }
        if (pauseDrafts.isNotEmpty()) Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
            pauseDrafts.forEach { draft ->
                OutlinedButton(onClick = { editingPauseId = draft.id }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.pause_draft_pending, draft.original.title))
                }
            }
        }
        if (drafts.isNotEmpty()) Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
            drafts.forEach { draft ->
                OutlinedButton(onClick = { editingDraftId = draft.id }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.recurring_draft_resume,
                        draft.fields.title.ifBlank { context.getString(R.string.recurring_draft_untitled) }))
                }
            }
        }
        if (moveDrafts.isNotEmpty()) Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
            moveDrafts.forEach { draft ->
                OutlinedButton(onClick = { editingMoveId = draft.id }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.move_draft_resume, draft.title))
                }
            }
        }
        if (rules.isEmpty() && focus.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurring_screen_005), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                item(key = "focus-header") {
                    SectionHeader(
                        "今天与近期",
                        if (focus.isEmpty()) "暂无待处理的本次事项" else "只调整这一次，不改规则；改规则在下方"
                    )
                }
                items(focus, key = { "focus-${it.id}" }) { inst ->
                    val task = taskById[inst.taskId]
                    val rule = rules.firstOrNull { it.id == inst.ruleId }
                    val hasTime = rule?.let { patternOrNull(gson, it)?.minuteOfDay } != null
                    FocusRow(
                        inst = inst,
                        task = task,
                        ruleTitle = rule?.title ?: snapshotTitle(gson, inst) ?: "规则不可用",
                        whenText = focusWhen(
                            recurringDueAt(inst, task, zone),
                            hasTime,
                            today,
                            zone
                        ),
                        onOpen = { task?.let(onTask) },
                        onAct = { action -> act(inst, action) },
                        onMove = { choice = inst },
                        onRule = { rule?.let { expanded = toggle(expanded, it.id) } }
                    )
                }
                item(key = "rules-header") {
                    SectionHeader("规则与以后安排", "从选定日期起生效；已处理或手改的本次保持原样")
                }
                items(rules, key = { it.id }) { rule ->
                    val p = patternOrNull(gson, rule)
                    val open = expanded.contains(rule.id)
                    val ruleInstances = (instancesByRule[rule.id] ?: emptyList())
                        .filter { inWindow(it.originalDate) }
                        .sortedWith(compareBy({ it.originalDate }, { it.ordinal }))
                    val doneCount = ruleInstances.count { taskById[it.taskId]?.status == "COMPLETED" }
                    val weekStart = today.with(DayOfWeek.MONDAY)
                    val weekDone = ruleInstances.count {
                        taskById[it.taskId]?.status == "COMPLETED" && runCatching {
                            taskById[it.taskId]?.completedAt?.let { at -> Instant.ofEpochMilli(at).atZone(zone).toLocalDate() }
                        }.getOrNull()?.let { d -> !d.isBefore(weekStart) && d.isBefore(weekStart.plusDays(7)) } == true
                    }
                    val showHistory = history.contains(rule.id)
                    val shown = ruleInstances.filter {
                        showHistory || stateOf(it, taskById[it.taskId]) == InstState.PENDING
                    }
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).testTag("recurring-toggle-${rule.id}").clickable { expanded = toggle(expanded, rule.id) }) {
                                    Text(rule.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(
                                        ruleSubtitle(rule, p, projectName[rule.projectId]),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(
                                    onClick = { editRule(rule) },
                                    modifier = Modifier.testTag("recurring-rule-${rule.id}")
                                ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_012), fontSize = 12.sp) }
                                TextButton(onClick = { togglePause(rule, p) }) {
                                    Text(stringResource(if (p?.paused == true) R.string.pause_draft_resume else R.string.pause_draft_pause), fontSize = 12.sp)
                                }
                            }
                            if (open) {
                                Text(
                                    androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurring_screen_006),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text("固定时段：${hhmm(p?.minuteOfDay)}", fontSize = 12.sp)
                                Text("目标：${if (p == null) "未知" else freqLabel(p)}", fontSize = 12.sp)
                                Text(
                                    if (p?.frequency == "WEEKLY_COUNT")
                                        "实际完成：本周 $weekDone 次（按完成日期；目标 ${p.weeklyCount} 次）"
                                    else "实际完成：窗口内 $doneCount 次",
                                    fontSize = 12.sp
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (showHistory) "当前窗口全部事项" else "当前待办（含逾期）",
                                        fontSize = 12.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { history = toggle(history, rule.id) }) {
                                        Text(if (showHistory) "仅看待办" else "显示已处理", fontSize = 12.sp)
                                    }
                                }
                                if (shown.isEmpty()) {
                                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurring_screen_007), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                shown.take(50).forEach { inst ->
                                    InstanceRow(
                                        inst = inst,
                                        task = taskById[inst.taskId],
                                        snapshotTitle = snapshotTitle(gson, inst),
                                        onOpen = { taskById[inst.taskId]?.let(onTask) },
                                        onAct = { action -> act(inst, action) },
                                        onMove = { choice = inst }
                                    )
                                }
                                if (shown.size > 50) {
                                    Text(
                                        androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurring_screen_008),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    }
    editingDraftId?.let { id ->
        val draft = remember(id) { draftStore.read(id) }
        if (draft != null) key(id) {
            RecurringRuleEditDialog(draft, projects, model,
                onDraftChange = draftStore::save,
                onRemoveDraft = { draftStore.remove(id) },
                onDismiss = { editingDraftId = null },
                onSaved = { result -> result.undoToken?.let { token ->
                    undoMessage = com.timeagent.ui.components.UndoMessage(result.requestId,
                        context.getString(R.string.recurring_draft_saved), token)
                } })
        } else LaunchedEffect(id) { editingDraftId = null }
    }

    editingPauseId?.let { id ->
        val draft = remember(id) { pauseStore.read(id) }
        if (draft != null) key(id) {
            RecurringPauseDialog(draft, model,
                onDraftChange = pauseStore::save,
                onRemoveDraft = { pauseStore.remove(id) },
                onDismiss = { editingPauseId = null },
                onSaved = { result -> result.undoToken?.let { token ->
                    undoMessage = com.timeagent.ui.components.UndoMessage(result.requestId,
                        context.getString(R.string.pause_draft_saved), token)
                } })
        } else LaunchedEffect(id) { editingPauseId = null }
    }

    choice?.let { inst ->
        val rule = rules.firstOrNull { it.id == inst.ruleId }
        AlertDialog(
            onDismissRequest = { choice = null },
            title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurring_screen_014), fontSize = 15.sp) },
            text = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurring_screen_015), fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    choice = null
                    openMove(inst)
                }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurring_screen_016)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    choice = null
                    if (rule != null) editRule(rule, inst.originalDate)
                }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_017)) }
            }
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
                        context.getString(R.string.move_draft_saved), token)
                } })
        } else LaunchedEffect(id) { editingMoveId = null }
    }
}

@Composable
private fun SectionHeader(title: String, hint: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (hint.isNotBlank()) {
            Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FocusRow(
    inst: RecurringInstanceEntity,
    task: TaskEntity?,
    ruleTitle: String,
    whenText: String,
    onOpen: () -> Unit,
    onAct: (String) -> Unit,
    onMove: () -> Unit,
    onRule: () -> Unit
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task?.title ?: "任务不可用",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text(whenText, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                buildString {
                    append("规则：")
                    append(ruleTitle)
                    if (inst.manualOverride) append(" · 已手改本次")
                    if (task == null || task.deletedAt != null) append(" · 原任务已删")
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (task != null && task.deletedAt == null) {
                    TextButton(onClick = onOpen) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_018), fontSize = 12.sp) }
                }
                TextButton(onClick = onRule) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurring_screen_022), fontSize = 12.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(
                    onClick = { onAct("COMPLETE") },
                    modifier = Modifier.testTag("recurring-focus-complete-${inst.id}")
                ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurring_screen_023), fontSize = 12.sp) }
                TextButton(
                    onClick = { onAct("SKIP") },
                    modifier = Modifier.testTag("recurring-focus-skip-${inst.id}")
                ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_002), fontSize = 12.sp) }
                TextButton(
                    onClick = onMove,
                    modifier = Modifier.testTag("recurring-focus-move-${inst.id}")
                ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurring_screen_016), fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun InstanceRow(
    inst: RecurringInstanceEntity,
    task: TaskEntity?,
    snapshotTitle: String?,
    onOpen: () -> Unit,
    onAct: (String) -> Unit,
    onMove: () -> Unit
) {
    val state = stateOf(inst, task)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                task?.title ?: "任务不可用",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                stateLabel(state),
                fontSize = 11.sp,
                color = if (state == InstState.PENDING || state == InstState.DONE)
                    MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
            )
        }
        Text(
            buildString {
                append(if(inst.id.contains(":w:")) "所属周 " else "原始日期 ")
                append(inst.originalDate)
                append(" 第")
                append(inst.ordinal)
                append("次")
                if (inst.scheduleNote.isNotBlank()) {
                    append(" · ")
                    append(inst.scheduleNote)
                }
            },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (snapshotTitle != null) {
            Text(
                "依据：$snapshotTitle（生成时规则）",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (task != null && task.deletedAt == null) {
                TextButton(onClick = onOpen) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_018), fontSize = 12.sp) }
            }
            if (state == InstState.PENDING) {
                TextButton(
                    onClick = { onAct("COMPLETE") },
                    modifier = Modifier.testTag("recurring-complete-${inst.id}")
                ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurring_screen_023), fontSize = 12.sp) }
                TextButton(
                    onClick = { onAct("SKIP") },
                    modifier = Modifier.testTag("recurring-skip-${inst.id}")
                ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_002), fontSize = 12.sp) }
                TextButton(onClick = onMove) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurring_screen_016), fontSize = 12.sp) }
            } else if (state in setOf(InstState.DONE,InstState.SKIPPED,InstState.CANCELLED,InstState.SUSPENDED,InstState.SUPERSEDED)) {
                TextButton(onClick = { onAct("REOPEN") }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_020), fontSize = 12.sp) }
                TextButton(onClick = onMove) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurring_screen_016), fontSize = 12.sp) }
            }
        }
    }
}
