package com.timeagent.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
private val deadlineFmt = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
private const val DEADLINE_DOMAIN = "DEADLINE"

private fun rulePattern(rule: RecurringRuleEntity): RecurringPattern? =
    runCatching { gson.fromJson(rule.patternJson, RecurringPattern::class.java) }.getOrNull()

private fun parseDate(text: String): LocalDate? =
    runCatching { LocalDate.parse(text.trim(), dateFmt) }.getOrNull()

private fun parseMillis(text: String, zone: ZoneId): Long? = runCatching {
    java.time.LocalDateTime.parse(text.trim(), deadlineFmt).atZone(zone).toInstant().toEpochMilli()
}.getOrNull()

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

private data class DelayCheck(val target: Long, val conflicts: List<Conflict>)

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
    var formOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<RecurringRuleEntity?>(null) }
    var editingFrom by remember { mutableStateOf<LocalDate?>(null) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var handledShown by remember { mutableStateOf(setOf<String>()) }
    var showHandledAll by remember { mutableStateOf(false) }
    var delayChoice by remember { mutableStateOf<RecurringInstanceEntity?>(null) }
    var delayInput by remember { mutableStateOf<RecurringInstanceEntity?>(null) }

    val rules = data.recurringRules.filter { it.domain == DEADLINE_DOMAIN && it.deletedAt == null }
    val taskById = data.tasks.associateBy { it.id }
    val nextItems = nextPendingDeadlines(rules, data.recurringInstances, taskById, zone)
    val handledItems = handledDeadlines(rules, data.recurringInstances, taskById, zone)

    fun editRule(rule: RecurringRuleEntity, from: LocalDate? = null) {
        editing = rule
        editingFrom = from
        formOpen = true
    }

    fun act(instance: RecurringInstanceEntity, action: String) {
        model.perform(action = {
            service.act(instance.id, instance.version, action, UUID.randomUUID().toString(),
                taskById[instance.taskId]?.version, data.plans.filter { it.taskId == instance.taskId && it.status == "SCHEDULED" && it.deletedAt == null }.associate { it.id to it.version })
        })
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
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
                        onClick = { editing = null; editingFrom = null; formOpen = true },
                        modifier = Modifier.testTag("deadline-create"),
                    ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_002)) }
                    OutlinedButton(
                        onClick = { scope.launch { model.perform(action = { service.refresh() }) } },
                    ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_003)) }
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

    if (formOpen) {
        RuleDialog(
            initial = editing,
            fromDefault = editingFrom,
            projects = data.projects.filter { it.deletedAt == null },
            zone = zone,
            today=today,
            onDismiss = { formOpen = false },
            onSave = { item, version, from ->
                scope.launch {
                    model.perform(
                        onSuccess = { formOpen = false; editing = null; editingFrom = null },
                        action = { service.saveRule(item, version, from, UUID.randomUUID().toString()) },
                    )
                }
            },
        )
    }

    delayChoice?.let { instance ->
        val rule = data.recurringRules.firstOrNull { it.id == instance.ruleId }
        AlertDialog(
            onDismissRequest = { delayChoice = null },
            title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_014)) },
            text = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_015)) },
            confirmButton = {
                TextButton(onClick = { delayChoice = null; delayInput = instance }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_016)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    delayChoice = null
                    if (rule != null) {
                        editing = rule
                        editingFrom = parseDate(instance.originalDate)
                        formOpen = true
                    }
                }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_017)) }
            },
        )
    }

    delayInput?.let { instance ->
        DelayInputDialog(
            instance = instance,
            service = service,
            model = model,
            zone = zone,
            onDismiss = { delayInput = null },
        )
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
private fun DelayInputDialog(
    instance: RecurringInstanceEntity,
    service: RecurringService,
    model: TimeViewModel,
    zone: ZoneId,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var text by remember(instance.id) { mutableStateOf("") }
    var checked by remember(instance.id) { mutableStateOf<DelayCheck?>(null) }
    var error by remember(instance.id) { mutableStateOf<String?>(null) }
    val busy by model.busy.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_016)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; checked = null; error = null },
                    label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_022)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                val c = checked
                if (c != null) {
                    Text("检查时间：${fmtAt(c.target, zone)}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_023),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_024), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            if (checked == null) {
                TextButton(onClick = {
                    val target = parseMillis(text, zone)
                    if (target == null) {
                        error = "请输入有效的 yyyy-MM-dd HH:mm"
                    } else {
                        scope.launch {
                            model.perform(action = {
                                checked = DelayCheck(target, service.previewMove(instance.id, instance.version, target, false))
                            })
                        }
                    }
                }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_025)) }
            } else {
                TextButton(enabled = !busy, onClick = {
                    val c = checked ?: return@TextButton
                    scope.launch {
                        model.perform(
                            onSuccess = { onDismiss() },
                            action = {
                                service.move(
                                    instance.id,
                                    instance.version,
                                    c.target,
                                    false,
                                    c.conflicts,
                                    UUID.randomUUID().toString(),
                                )
                            },
                        )
                    }
                }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_026)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } },
    )
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
    initial: RecurringRuleEntity?,
    fromDefault: LocalDate?,
    projects: List<ProjectEntity>,
    zone: ZoneId,
    today:LocalDate,
    onDismiss: () -> Unit,
    onSave: (RecurringRuleEntity, Long?, LocalDate) -> Unit,
) {
    val context = LocalContext.current
    val old = remember(initial) { initial?.let { rulePattern(it) } }
    val ruleId=remember(initial?.id){initial?.id?:UUID.randomUUID().toString()}
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var category by remember { mutableStateOf(initial?.category?.ifBlank { "其他" } ?: "其他") }
    var dateText by remember { mutableStateOf(old?.startDate ?: today.plusDays(1).toString()) }
    var timeText by remember {
        mutableStateOf(old?.minuteOfDay?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "09:00")
    }
    var frequency by remember { mutableStateOf(old?.frequency ?: "ONCE") }
    var leadText by remember { mutableStateOf(old?.leadMinutes?.let { (it / 1440).toString() }.orEmpty()) }
    var projectId by remember { mutableStateOf(initial?.projectId) }
    var reference by remember { mutableStateOf(initial?.reference.orEmpty()) }
    var fromText by remember {
        mutableStateOf((fromDefault ?: today).toString())
    }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(if (initial == null) com.timeagent.R.string.deadline_picker_create else com.timeagent.R.string.deadline_picker_edit)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_template_editor_006)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("deadline-title"),
                )
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_027), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("续费", "归还", "报销", "生日", "其他").forEach { item ->
                        FilterChip(
                            selected = category == item,
                            onClick = { category = item },
                            label = { Text(item) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                DeadlineDateField(
                    label = context.getString(com.timeagent.R.string.deadline_picker_date),
                    text = dateText,
                    fallback = today.plusDays(1),
                    onChange = { dateText = it; error = null },
                    modifier = Modifier.testTag("deadline-date"),
                )
                DeadlineTimeField(
                    label = context.getString(com.timeagent.R.string.deadline_picker_time),
                    text = timeText,
                    onChange = { timeText = it; error = null },
                    modifier = Modifier.testTag("deadline-time"),
                )
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("ONCE" to "一次", "MONTHLY" to "每月", "YEARLY" to "每年").forEach { (key, label) ->
                        FilterChip(
                            selected = frequency == key,
                            onClick = { frequency = key },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = leadText,
                    onValueChange = { leadText = it.filter { ch -> ch.isDigit() } },
                    label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_030)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("deadline-lead"),
                )
                if (initial != null) {
                    DeadlineDateField(
                        label = context.getString(com.timeagent.R.string.deadline_picker_effective),
                        text = fromText,
                        fallback = fromDefault ?: today,
                        onChange = { fromText = it; error = null },
                    )
                }
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_template_editor_009), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(
                        selected = projectId == null,
                        onClick = { projectId = null },
                        label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_032)) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    projects.forEach { project ->
                        FilterChip(
                            selected = projectId == project.id,
                            onClick = { projectId = project.id },
                            label = { Text(project.name) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = reference,
                    onValueChange = { if (it.length <= 20000) reference = it },
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
            TextButton(modifier = Modifier.testTag("deadline-save"), onClick = {
                val date = parseDate(dateText)
                val time = runCatching { LocalTime.parse(timeText.trim(), timeFmt) }.getOrNull()
                val days = if (leadText.isBlank()) null else leadText.toIntOrNull()
                val from = if (initial == null) {
                    if (frequency == "ONCE") date else today
                } else parseDate(fromText)
                when {
                    title.isBlank() -> error = context.getString(com.timeagent.R.string.deadline_picker_error_title)
                    date == null -> error = context.getString(com.timeagent.R.string.deadline_picker_error_date)
                    time == null -> error = context.getString(com.timeagent.R.string.deadline_picker_error_time)
                    leadText.isNotBlank() && (days == null || days !in 0..366) -> error = context.getString(com.timeagent.R.string.deadline_picker_error_lead)
                    from == null -> error = context.getString(com.timeagent.R.string.deadline_picker_error_effective)
                    else -> {
                        val pattern = RecurringPattern(
                            id = ruleId,
                            frequency = frequency,
                            startDate = date.toString(),
                            minuteOfDay = time.hour * 60 + time.minute,
                            durationMinutes = old?.durationMinutes?:30,
                            untilDate=old?.untilDate,
                            zoneId = old?.zoneId ?: zone.id,
                            leadMinutes = days?.times(1440),
                            paused = old?.paused ?: false,
                            effectiveFrom = from.toString(),
                        )
                        val item = if (initial == null) {
                            RecurringRuleEntity(
                                id = ruleId,
                                title = title.trim(),
                                patternJson = gson.toJson(pattern),
                                domain = DEADLINE_DOMAIN,
                                category = category,
                                reference = reference,
                                projectId = projectId,
                            )
                        } else {
                            initial.copy(
                                title = title.trim(),
                                patternJson = gson.toJson(pattern),
                                category = category,
                                reference = reference,
                                projectId = projectId,
                            )
                        }
                        onSave(item, initial?.version, from)
                    }
                }
            }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } },
    )
}
