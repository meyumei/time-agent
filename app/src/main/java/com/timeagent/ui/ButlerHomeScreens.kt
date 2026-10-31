package com.timeagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.timeagent.core.RecurringPattern
import com.timeagent.data.LocalSnapshot
import com.timeagent.data.RecurringInstanceEntity
import com.timeagent.data.RecurringRuleEntity
import com.timeagent.data.TaskEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val planGson = Gson()
private val planDateTimeFmt = DateTimeFormatter.ofPattern("M月d日 HH:mm")

/**
 * 计划首页：只读取 LocalSnapshot 中已有的项目、任务、周期规则、期限实例、周目标、
 * 想法与附件，不新增存储、不计算未经记录支持的统计。
 */
@Composable
fun PlanHubScreen(
    data: LocalSnapshot,
    zone: ZoneId,
    onSelect: (TaskEntity) -> Unit,
    onCreate: () -> Unit,
    onStart: (String?) -> Unit,
    onOpen: (String) -> Unit,
) {
    val today = LocalDate.now(zone)
    val tasksById = remember(data.tasks) { data.tasks.associateBy { it.id } }
    val placements = remember(data.placements) { data.placements.associateBy { it.taskId } }

    val projects = remember(data.projects) {
        data.projects.filter { it.deletedAt == null && it.status == "ACTIVE" }
            .sortedBy { it.dueAt ?: Long.MAX_VALUE }
    }
    val liveTasks = remember(data.tasks) {
        data.tasks.filter { it.deletedAt == null && (it.status == "TODO" || it.status == "IN_PROGRESS") }
            .sortedWith(compareBy<TaskEntity> { it.dueAt ?: Long.MAX_VALUE }.thenByDescending { it.priority })
    }
    val habits = remember(data.recurringRules) {
        data.recurringRules.filter { it.domain == "HABIT" && it.deletedAt == null }.sortedBy { it.title }
    }
    val habitsWithTodayInstance = remember(data.recurringInstances, tasksById, today) {
        data.recurringInstances
            .filter { it.originalDate == today.toString() && it.status == "PENDING" }
            .filter { instance ->
                val task = tasksById[instance.taskId]
                task == null || (task.deletedAt == null && task.status != "COMPLETED" && task.status != "CANCELLED")
            }
            .map { it.ruleId }
            .toSet()
    }
    val deadlines = remember(data.recurringRules, data.recurringInstances, tasksById, zone) {
        data.recurringRules.filter { it.domain == "DEADLINE" && it.deletedAt == null }
            .mapNotNull { rule ->
                val nextDate = data.recurringInstances
                    .filter { it.ruleId == rule.id && it.status == "PENDING" }
                    .filter { instance ->
                        val task = tasksById[instance.taskId]
                        task == null || (task.deletedAt == null && task.status != "COMPLETED" && task.status != "CANCELLED")
                    }
                    .mapNotNull { planInstanceDate(it, zone) }
                    .minOrNull()
                nextDate?.let { date -> rule to date }
            }
            .sortedBy { it.second }
    }
    val weekStart = remember(today) { today.minusDays((today.dayOfWeek.value - 1).toLong()).toString() }
    val weeklyGoals = remember(data.weeklyGoals, data.projects, data.activities, weekStart) {
        data.weeklyGoals.filter { it.weekStart == weekStart }.mapNotNull { goal ->
            val name = goal.projectId
                ?.let { id -> data.projects.firstOrNull { it.id == id && it.deletedAt == null }?.name }
                ?: goal.activityId?.let { id -> data.activities.firstOrNull { it.id == id }?.name }
            name?.let { it to goal.targetMinutes }
        }
    }
    val unscheduledIdeas = remember(data.ideas) {
        data.ideas.filter { it.deletedAt == null && it.status == "UNSCHEDULED" }.sortedByDescending { it.updatedAt }
    }
    val attachmentCount = remember(data.attachments) {
        data.attachments.count { it.deletedAt == null && it.purgedAt == null }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("plan-hub"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "把未来的事放回项目、习惯、期限与本周目标里。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (data.loading) {
            Text("正在读取本地数据…", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth().testTag("plan-create").semantics { contentDescription = "新建" }) {
            Text("新建任务")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onOpen("inbox") },modifier=Modifier.testTag("task-inbox-entry")) { Text("收集想法") }
            TextButton(onClick = { onOpen("attachments") }) { Text("附件") }
            TextButton(onClick = { onOpen("workout") },modifier=Modifier.testTag("task-workout-entry")) { Text("开始训练") }
        }

        PlanSection(
            title = "项目",
            subtitle = "项目下的任务与截止日期来自本地记录。",
            actionLabel = "查看全部",
            actionDescription = "打开项目页面",
            onAction = { onOpen("projects") },
            isEmpty = projects.isEmpty(),
            emptyText = "还没有进行中的项目。",
            testTag = "plan-projects",
        ) {
            projects.take(3).forEach { project ->
                val ownedCount = data.tasks.count { task ->
                    task.deletedAt == null && placements[task.id]?.projectId == project.id
                }
                val meta = buildList {
                    project.dueAt?.let { add("截止 ${planDateTime(it, zone)}") }
                    add("关联任务 $ownedCount 个")
                }.joinToString(" · ")
                PlanEntry(
                    title = project.name,
                    meta = meta,
                    onClickLabel = "打开项目页面，查看项目 ${project.name}",
                ) { onOpen("project:${project.id}") }
            }
        }

        PlanSection(
            title = "任务",
            subtitle = "点任务查看详情，右侧按钮直接开始计时。",
            actionLabel = "查看全部",
            actionDescription = "打开全部任务",
            onAction = { onOpen("tasks") },
            isEmpty = liveTasks.isEmpty(),
            emptyText = "还没有待办任务，先写下一件要做的事。",
            testTag = "plan-tasks",
        ) {
            liveTasks.take(6).forEach { task ->
                PlanTaskRow(task = task, zone = zone, onSelect = onSelect, onStart = onStart)
            }
            if (liveTasks.size > 6) {
                Text(
                    text = "另有 ${liveTasks.size - 6} 个未完成的任务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        PlanSection(
            title = "周期习惯",
            subtitle = "规则和今天是否有待完成实例都按本地记录显示。",
            actionLabel = "查看全部",
            actionDescription = "打开周期任务与习惯页面",
            onAction = { onOpen("recurring") },
            isEmpty = habits.isEmpty(),
            emptyText = "还没有周期习惯或周期任务。",
            testTag = "plan-habits",
        ) {
            habits.take(4).forEach { rule ->
                val meta = buildList {
                    add(planPatternText(planPattern(rule)))
                    if (rule.id in habitsWithTodayInstance) add("今天有一项待完成")
                }.joinToString(" · ")
                PlanEntry(
                    title = rule.title,
                    meta = meta,
                    onClickLabel = "打开周期任务与习惯页面，查看 ${rule.title}",
                ) { onOpen("recurring") }
            }
        }

        PlanSection(
            title = "生活期限",
            subtitle = "只列出仍待处理的期限，按最近日期排序。",
            actionLabel = "查看全部",
            actionDescription = "打开生活期限页面",
            onAction = { onOpen("deadlines") },
            isEmpty = deadlines.isEmpty(),
            emptyText = "没有待处理的期限。",
            testTag = "plan-deadlines",
        ) {
            deadlines.take(4).forEach { (rule, date) ->
                val paused = planPattern(rule)?.paused == true
                val meta = planDateLabel(date, today) + " · " + if (paused) "规则已暂停" else "待处理"
                PlanEntry(
                    title = rule.title,
                    meta = meta,
                    onClickLabel = "打开生活期限页面，查看 ${rule.title}",
                ) { onOpen("deadlines") }
            }
        }

        PlanSection(
            title = "本周目标",
            subtitle = "目标分钟数按记录原值显示，不推算完成度。",
            actionLabel = "查看周目标",
            actionDescription = "打开周目标页面",
            onAction = { onOpen("goals") },
            isEmpty = weeklyGoals.isEmpty(),
            emptyText = "这一周还没有设置目标。",
            testTag = "plan-goals",
        ) {
            weeklyGoals.take(4).forEach { (name, minutes) ->
                PlanEntry(
                    title = name,
                    meta = "本周目标 $minutes 分钟",
                    onClickLabel = "打开周目标页面，查看 $name",
                ) { onOpen("goals") }
            }
        }

        PlanSection(
            title = "收件箱",
            subtitle = "未安排的想法与附件都先放在这里。",
            actionLabel = "打开收件箱",
            actionDescription = "打开想法收件箱",
            onAction = { onOpen("inbox") },
            isEmpty = unscheduledIdeas.isEmpty() && attachmentCount == 0,
            emptyText = "收件箱是空的。",
            testTag = "plan-inbox",
        ) {
            PlanEntry(
                title = "未安排的想法 ${unscheduledIdeas.size} 个",
                meta = unscheduledIdeas.take(2).joinToString(" · ") { it.title }.ifBlank { "暂无内容" },
                onClickLabel = "打开收件箱，查看未安排的想法",
            ) { onOpen("inbox") }
            if (attachmentCount > 0) {
                HorizontalDivider()
            }
            PlanEntry(
                title = "附件收件箱 $attachmentCount 个",
                meta = if (attachmentCount == 0) "暂无附件" else "查看文件与文字草稿",
                onClickLabel = "打开附件收件箱",
            ) { onOpen("attachments") }
        }

    }
}

/**
 * 管家页顶部角色区。形象、口吻与动画素材尚未提供，这里只做明确占位，
 * 不显示运行状态、平台连接或在线情况。
 */
@Composable
fun ButlerHomeHeader(
    onChat: () -> Unit,
    onSettings: () -> Unit,
    onMemory: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().testTag("butler-header"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .semantics { contentDescription = "大肥鱼，与你使用共同记忆的管家" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("鱼", style = MaterialTheme.typography.titleLarge)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("大肥鱼", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        text = "形象、声音与角色档案可自定义",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onChat,
                    modifier = Modifier.weight(1f).testTag("butler-chat"),
                ) { Text("和大肥鱼聊聊") }
                OutlinedButton(onClick = onSettings, modifier = Modifier.testTag("butler-settings")) {
                    Text("设置")
                }
            }
            TextButton(onClick = onMemory, modifier = Modifier.testTag("butler-memory")) {
                Text("共同记忆与角色约定")
            }
        }
    }
}

@Composable
private fun PlanSection(
    title: String,
    subtitle: String,
    actionLabel: String?,
    actionDescription: String?,
    onAction: (() -> Unit)?,
    isEmpty: Boolean,
    emptyText: String,
    testTag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().testTag(testTag),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Box(Modifier.weight(1f))
                if (actionLabel != null && onAction != null) {
                    TextButton(
                        onClick = onAction,
                        modifier = Modifier.testTag("$testTag-action").semantics {
                            contentDescription = actionDescription ?: actionLabel
                        },
                    ) { Text(actionLabel) }
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isEmpty) {
                Text(text = emptyText, style = MaterialTheme.typography.bodySmall)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
            }
        }
    }
}

@Composable
private fun PlanTaskRow(
    task: TaskEntity,
    zone: ZoneId,
    onSelect: (TaskEntity) -> Unit,
    onStart: (String?) -> Unit,
) {
    val meta = buildList {
        add("预计 ${task.estimatedMinutes} 分钟")
        add("${planPriorityLabel(task.priority)}优先级")
        if (task.status == "IN_PROGRESS") add("进行中")
        task.dueAt?.let { add("截止 ${planDateTime(it, zone)}") }
    }.joinToString(" · ")

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.Button,
                    onClickLabel = "查看任务 ${task.title}",
                ) { onSelect(task) }
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { onStart(task.id) },
                modifier = Modifier.testTag("plan-start-${task.id}"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayCircleOutline,
                    contentDescription = "开始计时：${task.title}",
                )
            }
        }
    }
}

@Composable
private fun PlanEntry(
    title: String,
    meta: String,
    onClickLabel: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun planPattern(rule: RecurringRuleEntity): RecurringPattern? = runCatching {
    planGson.fromJson(rule.patternJson, RecurringPattern::class.java)
}.getOrNull()

private fun planPatternText(pattern: RecurringPattern?): String {
    if (pattern == null || pattern.frequency.isBlank()) return "规则待完善"
    val frequency = when (pattern.frequency) {
        "WEEKDAYS" -> "每周" + pattern.weekdays.sorted().joinToString("") { planWeekday(it) }
        "WEEKLY_COUNT" -> "每周 ${pattern.weeklyCount} 次"
        else -> "每天"
    }
    val time = pattern.minuteOfDay?.let { " · %02d:%02d".format(it / 60, it % 60) }.orEmpty()
    val paused = if (pattern.paused) " · 已暂停" else ""
    return frequency + time + paused
}

private fun planWeekday(day: Int): String = when (day) {
    1 -> "一"
    2 -> "二"
    3 -> "三"
    4 -> "四"
    5 -> "五"
    6 -> "六"
    7 -> "日"
    else -> "?"
}

private fun planPriorityLabel(priority: Int): String =
    listOf("低", "中", "高").getOrElse(priority) { "中" }

private fun planInstanceDate(instance: RecurringInstanceEntity, zone: ZoneId): LocalDate? {
    val scheduled = instance.scheduledAt
    if (scheduled != null) return Instant.ofEpochMilli(scheduled).atZone(zone).toLocalDate()
    return runCatching { LocalDate.parse(instance.originalDate) }.getOrNull()
}

private fun planDateTime(epochMillis: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).format(planDateTimeFmt)

private fun planDateLabel(date: LocalDate, today: LocalDate): String {
    val base = "${date.monthValue}月${date.dayOfMonth}日"
    return when (ChronoUnit.DAYS.between(today, date)) {
        0L -> "今天 · $base"
        1L -> "明天 · $base"
        -1L -> "昨天 · $base"
        else -> base
    }
}
