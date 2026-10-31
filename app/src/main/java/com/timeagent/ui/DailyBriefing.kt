package com.timeagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.timeagent.core.SleepDaySummary
import com.timeagent.core.SleepRecord
import com.timeagent.core.SleepSummary
import com.timeagent.core.TimeSpan
import com.timeagent.core.workout.WorkoutSession
import com.timeagent.core.workout.WorkoutSessionStatus
import com.timeagent.data.LocalSnapshot
import com.timeagent.data.TaskEntity
import com.timeagent.suggestions.suggestionFreeSlots
import com.timeagent.suggestions.sleepDays
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Source state of the locally synced health/briefing data. */
enum class BriefingSyncState { DISABLED, UNAUTHORIZED, FAILED, STALE, READY, NO_DATA, NOT_READ }

data class BriefingSyncStatus(
    val state: BriefingSyncState,
    val lastSyncAt: Long = 0,
    val latestSourceDateText: String = "",
    val detail: String = "",
)

fun briefingHealthStatus(enabled: Boolean, report: com.timeagent.health.HealthReadReport, zone: ZoneId, now: Instant): BriefingSyncStatus {
    val channels = report.channels
    val latest = channels.mapNotNull { it.latestAt }.maxOrNull()?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    val sleepChannels = channels.filter { it.channel.contains("睡眠") && it.state == com.timeagent.health.HealthReadState.READY }
    val sleepLatest = sleepChannels.mapNotNull { it.latestAt }.maxOrNull()?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    val freshnessDate = if (sleepChannels.isNotEmpty()) sleepLatest else latest
    val today = now.atZone(zone).toLocalDate()
    val detail = channels.joinToString("\n") { channel ->
        val state = when (channel.state) {
            com.timeagent.health.HealthReadState.DISABLED -> "已关闭"
            com.timeagent.health.HealthReadState.NOT_CONNECTED -> "未连接文件来源"
            com.timeagent.health.HealthReadState.UNAUTHORIZED -> "未授权"
            com.timeagent.health.HealthReadState.UNAVAILABLE -> "设备不可用"
            com.timeagent.health.HealthReadState.FAILED -> "读取失败：${channel.error}"
            com.timeagent.health.HealthReadState.READY -> "读取 ${channel.count} 条"
            com.timeagent.health.HealthReadState.NO_DATA -> "读取完成，未查到记录"
        }
        "${channel.channel}：$state" + (channel.latestAt?.let { "，数据至 ${Instant.ofEpochMilli(it).atZone(zone).toLocalDate()}" } ?: "")
    }
    val state = when {
        !enabled -> BriefingSyncState.DISABLED
        channels.isEmpty() -> BriefingSyncState.NOT_READ
        channels.any { it.state == com.timeagent.health.HealthReadState.FAILED } -> BriefingSyncState.FAILED
        channels.any { it.state == com.timeagent.health.HealthReadState.READY } -> if (freshnessDate == null || freshnessDate < today || Instant.ofEpochMilli(report.attemptedAt).atZone(zone).toLocalDate() < today) BriefingSyncState.STALE else BriefingSyncState.READY
        channels.any { it.state == com.timeagent.health.HealthReadState.NO_DATA } -> BriefingSyncState.NO_DATA
        else -> BriefingSyncState.UNAUTHORIZED
    }
    val dateText = if (sleepChannels.isNotEmpty()) sleepLatest?.let { "最近睡眠来源数据日期 $it" } else latest?.let { "最近来源数据日期 $it" }
    return BriefingSyncStatus(state, report.attemptedAt, dateText.orEmpty(), detail)
}

/**
 * One entry of the briefing. [type] is one of TASK, PLAN, OCCURRENCE, REMINDER, RECORD, WORKOUT
 * and is handed straight back to the UI callback together with [id].
 */
data class BriefingItem(
    val type: String,
    val id: String,
    val title: String,
    val startAt: Long,
    val endAt: Long? = null,
)

data class BriefingTask(
    val id: String,
    val title: String,
    val overdue: Boolean,
)

data class BriefingTraining(
    val type: String,
    val id: String,
    val title: String,
    val startAt: Long,
    val endAt: Long? = null,
)

data class BriefingSleep(
    val today: SleepDaySummary?,
    val latestPastDate: LocalDate?,
    val todayMessage: String,
    val latestPast: SleepDaySummary? = null,
)

data class BriefingHealth(
    val state: BriefingSyncState,
    val title: String,
    val detail: String,
)

data class DailyBriefing(
    val date: LocalDate,
    val sync: BriefingSyncStatus,
    val nextItem: BriefingItem?,
    val dueTasks: List<BriefingTask>,
    val overdueCount: Int,
    val freeSlots: List<TimeSpan>,
    val sleep: BriefingSleep,
    val latestTraining: BriefingTraining?,
    val health: BriefingHealth,
    val focusTasks: List<BriefingTask> = emptyList(),
)

private data class RankedTask(val task: TaskEntity, val overdue: Boolean)

private val briefingDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)
private val briefingShortDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日", Locale.SIMPLIFIED_CHINESE)
private val briefingTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.SIMPLIFIED_CHINESE)

private val excludedBlockStatuses = setOf("CANCELLED", "CANCELED", "COMPLETED", "DONE", "DELETED", "SKIPPED")
private val openTaskStatuses = setOf("TODO", "IN_PROGRESS")

/**
 * Builds the whole daily briefing from the local snapshot. Pure function: no cloud access, no
 * example data — every value either comes from [data]/[workouts] or is an explicit "unknown" marker.
 */
fun buildDailyBriefing(
    data: LocalSnapshot,
    now: Instant,
    zone: ZoneId,
    sync: BriefingSyncStatus,
    workouts: List<WorkoutSession> = emptyList(),
): DailyBriefing {
    val nowMs = now.toEpochMilli()
    val today = now.atZone(zone).toLocalDate()
    val tomorrowStartMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    // ---- Due tasks: overdue first, then priority desc, then deadline asc ----
    val rankedTasks = data.tasks.asSequence()
        .filter { it.deletedAt == null }
        .filter { openTaskStatuses.contains(it.status.uppercase(Locale.ROOT)) }
        .mapNotNull { task ->
            val dueAt = task.dueAt ?: return@mapNotNull null
            if (dueAt >= tomorrowStartMs) return@mapNotNull null
            RankedTask(task, dueAt < nowMs)
        }
        .distinctBy { it.task.id }
        .sortedWith(
            compareByDescending<RankedTask> { it.overdue }
                .thenByDescending { it.task.priority }
                .thenBy { it.task.dueAt ?: Long.MAX_VALUE }
        )
        .toList()

    val dueTasks = rankedTasks.map { BriefingTask(it.task.id, it.task.title, it.overdue) }
    val overdueCount = rankedTasks.count { it.overdue }

    // ---- Next item: earliest actual instant across plans, course occurrences and reminders ----
    val tasksById = data.tasks.associateBy { it.id }
    val coursesById = data.courses.associateBy { it.id }
    val candidates = ArrayList<BriefingItem>()

    for (plan in data.plans) {
        if (excludedBlockStatuses.contains(plan.status.uppercase(Locale.ROOT))) continue
        if (plan.endAt <= nowMs) continue
        val task = tasksById[plan.taskId]
        if (task != null && (task.deletedAt != null || task.status !in openTaskStatuses)) continue
        val title = tasksById[plan.taskId]?.title?.takeIf { it.isNotBlank() } ?: "日程安排"
        candidates += BriefingItem("PLAN", plan.id, title, plan.startAt, plan.endAt)
    }
    for (occurrence in data.occurrences) {
        if (excludedBlockStatuses.contains(occurrence.status.uppercase(Locale.ROOT))) continue
        if (occurrence.endAt <= nowMs) continue
        val title = coursesById[occurrence.courseId]?.name?.takeIf { it.isNotBlank() } ?: "课程"
        candidates += BriefingItem("OCCURRENCE", occurrence.id, title, occurrence.startAt, occurrence.endAt)
    }
    for (reminder in data.reminders) {
        if (!reminder.enabled || reminder.deliveredAt != null) continue
        if (reminder.remindAt < nowMs) continue
        if (reminder.taskId != null && tasksById[reminder.taskId]?.let { it.deletedAt == null && it.status in openTaskStatuses } != true) continue
        candidates += BriefingItem("REMINDER", reminder.id, reminder.title, reminder.remindAt, null)
    }

    val nextItem = candidates.minWithOrNull(
        compareBy<BriefingItem> { it.startAt }
            .thenBy { it.endAt ?: Long.MAX_VALUE }
            .thenBy { it.id }
    )

    // ---- Free slots (full list, the UI only shows the first three) ----
    // Defensive: a suggestion failure must never break the briefing itself.
    val freeSlots: List<TimeSpan> =
        runCatching { suggestionFreeSlots(data, zone, now, 15) }.getOrDefault(emptyList())

    // ---- Sleep: main and nap kept separate, never guessed ----
    val sleepDays = data.sleepDays(now, zone)
    val todaySleep = sleepDays[today]
    val latestPastDate = sleepDays.keys.filter { it < today }.maxOrNull()
    val latestPast = latestPastDate?.let { sleepDays[it] }
    val todayMessage = if (todaySleep != null) {
        buildString {
            if (todaySleep.mainRecordIds.isEmpty()) append("主睡眠尚未查到记录")
            else { append("今天起床已记录主睡眠 "); append(minutesText(todaySleep.mainMinutes)) }
            if (todaySleep.napMinutes > 0) {
                append("，小睡 ")
                append(minutesText(todaySleep.napMinutes))
            }
            append("（来源 ")
            append(todaySleep.selectedSource)
            append("）")
        }
    } else {
        "未查到今天起床的睡眠记录"
    }
    val sleepBriefing = BriefingSleep(
        today = todaySleep,
        latestPastDate = latestPastDate,
        todayMessage = todayMessage,
        latestPast = latestPast,
    )

    // ---- Latest training: sessions, then records, then health items; the truly latest start wins ----
    val trainingCandidates = ArrayList<Pair<BriefingTraining, Int>>()
    for (session in workouts) {
        if (session.status == WorkoutSessionStatus.DISCARDED) continue
        if (session.startedAt > nowMs) continue
        trainingCandidates += BriefingTraining(
            type = "WORKOUT",
            id = session.id,
            title = session.title.ifBlank { "训练" },
            startAt = session.startedAt,
            endAt = session.endedAt,
        ) to 2
    }
    for (record in data.records) {
        if (!record.source.uppercase(Locale.ROOT).contains("WORKOUT")) continue
        if (record.startAt > nowMs) continue
        trainingCandidates += BriefingTraining(
            type = "RECORD",
            id = record.id,
            title = record.title.ifBlank { "训练" },
            startAt = record.startAt,
            endAt = record.endAt,
        ) to 1
    }
    for (item in data.healthItems) {
        if (item.userDeleted || item.recordId == null || data.records.none { it.id == item.recordId }) continue
        if (!item.kind.uppercase(Locale.ROOT).contains("EXERCISE")) continue
        if (item.startAt > nowMs) continue
        trainingCandidates += BriefingTraining(
            type = "RECORD",
            id = item.recordId!!,
            title = item.title.ifBlank { "训练" },
            startAt = item.startAt,
            endAt = item.endAt,
        ) to 0
    }
    val latestTraining = trainingCandidates
        .maxWithOrNull(compareBy<Pair<BriefingTraining, Int>> { it.first.startAt }.thenBy { it.second })
        ?.first

    return DailyBriefing(
        date = today,
        sync = sync,
        nextItem = nextItem,
        dueTasks = dueTasks,
        overdueCount = overdueCount,
        freeSlots = freeSlots,
        sleep = sleepBriefing,
        latestTraining = latestTraining,
        health = buildHealthBriefing(sync),
        focusTasks = data.tasks.filter { it.deletedAt == null && it.status in openTaskStatuses && it.priority == 2 && dueTasks.none { due -> due.id == it.id } }.map { BriefingTask(it.id, it.title, false) },
    )
}

private fun buildHealthBriefing(sync: BriefingSyncStatus): BriefingHealth = when (sync.state) {
    BriefingSyncState.NOT_READ -> BriefingHealth(sync.state, "尚未读取健康来源", "授权状态尚未检查；可打开健康同步读取。")
    BriefingSyncState.DISABLED -> BriefingHealth(
        state = sync.state,
        title = "自动读取已关闭",
        detail = "历史记录仍可查看，可在健康同步中手动读取。",
    )
    BriefingSyncState.UNAUTHORIZED -> BriefingHealth(
        state = sync.state,
        title = "未授权读取健康数据",
        detail = sync.detail.ifBlank { "请在同步设置中连接来源或授予读取权限。" },
    )
    BriefingSyncState.FAILED -> BriefingHealth(
        state = sync.state,
        title = "健康数据同步失败",
        detail = sync.detail.ifBlank { "读取失败，请打开同步设置查看来源。" },
    )
    BriefingSyncState.STALE -> BriefingHealth(
        state = sync.state,
        title = "健康数据来源可能过期",
        detail = listOf(sync.latestSourceDateText, sync.detail).filter { it.isNotBlank() }.joinToString("\n").ifBlank { "最近一次同步较早，请检查数据来源。" },
    )
    BriefingSyncState.READY -> BriefingHealth(
        state = sync.state,
        title = "健康数据已同步",
        detail = listOf(sync.latestSourceDateText, sync.detail).filter { it.isNotBlank() }.joinToString("\n"),
    )
    BriefingSyncState.NO_DATA -> BriefingHealth(
        state = sync.state,
        title = "暂无健康记录",
        detail = sync.detail.ifBlank { "未查到所选来源的可用记录；这不表示没有睡眠或训练。" },
    )
}

private fun minutesText(minutes: Long): String {
    if (minutes <= 0) return "0 分钟"
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours > 0 && rest > 0 -> "$hours 小时 $rest 分钟"
        hours > 0 -> "$hours 小时"
        else -> "$rest 分钟"
    }
}

/** Material3 daily briefing card: collapsed by default, bounded and scrollable when expanded. */
@Composable
fun DailyBriefingCard(
    data: LocalSnapshot,
    now: Instant,
    zone: ZoneId,
    sync: BriefingSyncStatus,
    workouts: List<WorkoutSession> = emptyList(),
    onOpen: (String, String) -> Unit,
    onHealth: () -> Unit,
) {
    if (data.loading) {
        Text("今日管家简报 · 正在读取本机记录", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        return
    }
    val briefing = remember(data, now.epochSecond / 60, zone, sync, workouts) {
        buildDailyBriefing(data, now, zone, sync, workouts)
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "今日管家简报",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = collapsedSummary(briefing, zone),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.testTag("briefing-toggle"),
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起今日简报" else "展开今日简报",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = briefing.date.format(briefingDateFormatter),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val dueProjects = data.projects.filter { it.deletedAt == null && it.status == "ACTIVE" && it.dueAt != null && Instant.ofEpochMilli(it.dueAt).atZone(zone).toLocalDate() <= now.atZone(zone).toLocalDate().plusDays(7) }.sortedBy { it.dueAt }
                    if (dueProjects.isNotEmpty()) Text("临近截止项目（含逾期）", style=MaterialTheme.typography.labelLarge)
                    dueProjects.forEach { project ->
                        TextButton(onClick={onOpen("PROJECT",project.id)},modifier=Modifier.testTag("briefing-project-${project.id}")) { Text("${project.name} · 截止 ${Instant.ofEpochMilli(project.dueAt!!).atZone(zone).toLocalDate()} · ${data.tasks.count { t -> t.deletedAt == null && t.status in setOf("TODO","IN_PROGRESS") && data.placements.any { it.taskId == t.id && it.projectId == project.id } }} 项待办") }
                    }
                    ExpandedBriefing(briefing = briefing, zone = zone, onOpen = onOpen, onHealth = onHealth)
                }
            }
        }
    }
}

@Composable
private fun ExpandedBriefing(
    briefing: DailyBriefing,
    zone: ZoneId,
    onOpen: (String, String) -> Unit,
    onHealth: () -> Unit,
) {
    val nextItem = briefing.nextItem
    if (nextItem != null) {
        SectionTitle("下一项")
        TextButton(
            onClick = { onOpen(nextItem.type, nextItem.id) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "${typeLabel(nextItem.type)} ${timeText(nextItem.startAt, zone)} · ${nextItem.title}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    SectionTitle("今日任务")
    Text(
        text = "待处理截止 ${briefing.dueTasks.size} 项 · 逾期 ${briefing.overdueCount} 项",
        style = MaterialTheme.typography.bodySmall,
    )
    if (briefing.dueTasks.isEmpty()) {
        Text(text = "今天没有到期任务", style = MaterialTheme.typography.bodySmall)
    } else {
        briefing.dueTasks.forEach { task ->
            TextButton(
                onClick = { onOpen("TASK", task.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (task.overdue) "逾期 · ${task.title}" else task.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    if (briefing.focusTasks.isNotEmpty()) {
        SectionTitle("重点 · ${briefing.focusTasks.size} 项")
        briefing.focusTasks.forEach { task -> TextButton(onClick = { onOpen("TASK", task.id) }) { Text(task.title) } }
    }

    SectionTitle("空闲时段")
    Text("今天 08:00–22:00 内，扣除安排与实际记录，保留至少 15 分钟的空档。", style = MaterialTheme.typography.bodySmall)
    Text(
        text = "共 ${briefing.freeSlots.size} 段，展示前 ${minOf(3, briefing.freeSlots.size)} 段",
        style = MaterialTheme.typography.bodySmall,
    )
    if (briefing.freeSlots.isEmpty()) {
        Text(text = "暂无可用的空闲时段", style = MaterialTheme.typography.bodySmall)
    } else {
        for (index in 0 until minOf(3, briefing.freeSlots.size)) {
            TextButton(
                onClick = { onOpen("SUGGESTIONS", briefing.date.toString()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                val span = briefing.freeSlots[index]
                Text("${timeText(span.start.toEpochMilli(), zone)}–${timeText(span.end.toEpochMilli(), zone)} · ${span.seconds / 60} 分钟")
            }
        }
    }

    SectionTitle("睡眠")
    Text(text = briefing.sleep.todayMessage, style = MaterialTheme.typography.bodySmall)
    val todaySleep = briefing.sleep.today
    if (todaySleep != null) {
        if (todaySleep.alternatives.isNotEmpty()) Text("其他来源（未相加）：" + todaySleep.alternatives.joinToString("；") { "${com.timeagent.data.healthOriginLabel(it.source)} 主睡眠 ${minutesText(it.mainMinutes)}" }, style = MaterialTheme.typography.bodySmall)
        (todaySleep.mainRecordIds + todaySleep.napRecordIds).distinct().forEach { recordId ->
            TextButton(onClick = { onOpen("RECORD", recordId) }) {
                Text("打开睡眠记录")
            }
        }
    }
    val pastDate = briefing.sleep.latestPastDate
    if (pastDate != null) {
        val past = briefing.sleep.latestPast
        Text(
            text = "最近一次：${pastDate.format(briefingShortDateFormatter)}" +
                (past?.let { " · 主睡眠 ${minutesText(it.mainMinutes)} · 午睡 ${minutesText(it.napMinutes)} · 来源 ${com.timeagent.data.healthOriginLabel(it.selectedSource)}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
        past?.mainRecordIds?.forEach { recordId ->
            TextButton(onClick = { onOpen("RECORD", recordId) }) {
                Text("打开这段睡眠记录")
            }
        }
    }

    val training = briefing.latestTraining
    if (training != null) {
        SectionTitle("最近训练")
        TextButton(
            onClick = { onOpen(training.type, training.id) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "${training.title} · ${Instant.ofEpochMilli(training.startAt).atZone(zone).toLocalDate()} ${timeText(training.startAt, zone)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    SectionTitle("健康数据")
    Text(
        text = briefing.health.title,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
    )
    Text(text = briefing.health.detail, style = MaterialTheme.typography.bodySmall)
    if (briefing.sync.lastSyncAt > 0) Text("上次读取 ${Instant.ofEpochMilli(briefing.sync.lastSyncAt).label(zone)}", style = MaterialTheme.typography.labelSmall)
    TextButton(onClick = onHealth, modifier = Modifier.testTag("briefing-health")) {
        Text("查看健康同步")
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

fun collapsedSummary(briefing: DailyBriefing, zone: ZoneId): String {
    val next = briefing.nextItem ?: return "今天暂无即将开始的安排"
    val date = Instant.ofEpochMilli(next.startAt).atZone(zone).toLocalDate()
    return "下一项 ${if (date != briefing.date) date.format(briefingShortDateFormatter) + " " else ""}${timeText(next.startAt, zone)} · ${typeLabel(next.type)} ${next.title}"
}

private fun typeLabel(type: String): String = when (type) {
    "TASK" -> "任务"
    "PLAN" -> "日程"
    "OCCURRENCE" -> "课程"
    "REMINDER" -> "提醒"
    "RECORD" -> "记录"
    "WORKOUT" -> "训练"
    "SUGGESTIONS" -> "建议"
    else -> "安排"
}

private fun timeText(epochMillis: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalTime().format(briefingTimeFormatter)
