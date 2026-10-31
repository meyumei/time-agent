package com.timeagent.suggestions

import com.timeagent.core.Scheduling
import com.timeagent.core.TimeSpan
import com.timeagent.core.SleepRecord
import com.timeagent.core.SleepSummary
import com.timeagent.core.SleepDaySummary
import com.timeagent.memory.PersonalMemoryRules
import com.timeagent.data.AgentSuggestionEntity
import com.timeagent.data.HealthSampleKind
import com.timeagent.data.LocalSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class SuggestionCandidate(
    val type: String, val title: String, val detail: String, val dedupKey: String,
    val taskId: String? = null, val taskVersion: Long? = null,
    val proposedStartAt: Long? = null, val proposedEndAt: Long? = null,
    val forDate: String? = null, val expiresAt: Long? = null,
)

/** Completed source records only. Missing dates deliberately have no entry. */
fun LocalSnapshot.sleepDays(now: Instant, zone: ZoneId): Map<LocalDate, SleepDaySummary> {
    val sourceByRecord = healthItems.filter { it.kind == "SLEEP" && !it.userDeleted && it.recordId != null }.associateBy { it.recordId }
    val sleepTasks = tasks.filter { task -> task.activityId == "sleep" || task.title in setOf("睡觉", "睡眠") }.map { it.id }.toSet()
    val spans = records.mapNotNull { record ->
        val source = sourceByRecord[record.id]
        val isSleep = source != null || record.id.startsWith("health:sleep:") || record.taskId in sleepTasks || record.title in setOf("睡眠", "睡觉")
        val end = record.endAt ?: return@mapNotNull null
        if (!isSleep || end > now.toEpochMilli() || end <= record.startAt) return@mapNotNull null
        SleepRecord(record.id, source?.originPackage ?: if (record.source == "HEALTH_SYNC") "健康同步（来源未保留）" else "手工记录", Instant.ofEpochMilli(record.startAt), Instant.ofEpochMilli(end))
    }
    return SleepSummary.aggregate(spans, now, zone).days
}

/** The same interval computation is used by cards, briefing and the commit-time check. */
fun suggestionFreeSlots(data: LocalSnapshot, zone: ZoneId, now: Instant, requiredMinutes: Int): List<TimeSpan> {
    val today = now.atZone(zone).toLocalDate()
    val end = today.atTime(22, 0).atZone(zone).toInstant()
    val nextMinute = Instant.ofEpochMilli((now.toEpochMilli() / 60_000 + 1) * 60_000)
    val start = maxOf(today.atTime(8, 0).atZone(zone).toInstant(), nextMinute)
    if (start >= end) return emptyList()
    val busy = data.plans.filter { it.status == "SCHEDULED" }.map { TimeSpan(Instant.ofEpochMilli(it.startAt), Instant.ofEpochMilli(it.endAt)) } +
        data.occurrences.filter { it.status != "CANCELLED" }.map { TimeSpan(Instant.ofEpochMilli(it.startAt), Instant.ofEpochMilli(it.endAt)) } +
        data.records.mapNotNull { record ->
            val recordEnd = record.endAt?.let(Instant::ofEpochMilli) ?: end
            if (recordEnd > Instant.ofEpochMilli(record.startAt)) TimeSpan(Instant.ofEpochMilli(record.startAt), recordEnd) else null
        }
    return Scheduling.findFreeSlots(TimeSpan(start, end), busy, requiredMinutes)
}

/**
 * Pure rule evaluation over a snapshot; no Android or database access so rules are directly
 * unit-testable. Rules produce candidates; persistence and dedup live in the repository.
 */
fun suggestionTaskSlots(data: LocalSnapshot, task: com.timeagent.data.TaskEntity, zone: ZoneId, now: Instant, minimumGap: Int = task.estimatedMinutes): List<TimeSpan> =
    suggestionFreeSlots(data, zone, now, maxOf(minimumGap, task.estimatedMinutes)).mapNotNull { gap ->
        var start = gap.start
        val duration = task.estimatedMinutes * 60L
        while (start.plusSeconds(duration) <= gap.end) {
            val end = start.plusSeconds(duration)
            if (PersonalMemoryRules.allows(data.memories, task, start, end, zone)) return@mapNotNull TimeSpan(start, end)
            start = start.plusSeconds(60)
        }
        null
    }

object SuggestionEngine {
    const val TYPE_OVERDUE = "OVERDUE_TASK"
    const val TYPE_FREE_SLOT = "FREE_SLOT"
    const val TYPE_SLEEP_SHORT = "SLEEP_SHORT"

    private val dayWindowStartHour = 8L
    private val dayWindowEndHour = 22L
    private val minFreeSlotMinutes = 60L
    private val minSleepHours = 6L

    fun generate(data: LocalSnapshot, zone: ZoneId, now: Instant): List<SuggestionCandidate> {
        val today = now.atZone(zone).toLocalDate()
        val candidates = overdueTasks(data, zone, now).toMutableList()
        freeSlotToday(data, zone, now, today)?.let { candidates += it }
        shortSleep(data, zone, now)?.let { candidates += it }
        return candidates
    }

    private fun overdueTasks(data: LocalSnapshot, zone: ZoneId, now: Instant): List<SuggestionCandidate> =
        data.tasks.filter { it.deletedAt == null && it.status == "TODO" && it.dueAt != null && it.dueAt < now.toEpochMilli() - 30 * 60_000L }
            .map { task ->
                val overdueMinutes = (now.toEpochMilli() - task.dueAt!!) / 60_000
                val overdueText = if (overdueMinutes >= 1440) "已超 ${overdueMinutes / 1440} 天" else "已超 ${overdueMinutes / 60} 小时${overdueMinutes % 60} 分"
                SuggestionCandidate(
                    TYPE_OVERDUE,
                    "任务已截止：${task.title}",
                    "截止 ${Instant.ofEpochMilli(task.dueAt).atZone(zone).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))}，$overdueText。可以重新安排时间或调整截止。",
                    "overdue:${task.id}:${task.dueAt}:v${task.version}",
                    taskId = task.id, taskVersion = task.version,
                    forDate = now.atZone(zone).toLocalDate().toString(),
                    expiresAt = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                )
            }

    private fun freeSlotToday(data: LocalSnapshot, zone: ZoneId, now: Instant, today: LocalDate): SuggestionCandidate? {
        val plannedTaskIds = data.plans.map { it.taskId }.toSet()
        val pair = data.tasks
            .filter { it.deletedAt == null && it.status == "TODO" && it.id !in plannedTaskIds }
            .sortedWith(compareByDescending<com.timeagent.data.TaskEntity> { it.priority }.thenBy { it.dueAt ?: Long.MAX_VALUE })
            .firstNotNullOfOrNull { task -> suggestionTaskSlots(data, task, zone, now, minFreeSlotMinutes.toInt()).firstOrNull()?.let { task to it } } ?: return null
        val (candidateTask, slot) = pair
        val slotMinutes = slot.seconds / 60
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        return SuggestionCandidate(
            TYPE_FREE_SLOT,
            "今天 ${slot.start.atZone(zone).format(formatter)}–${slot.end.atZone(zone).format(formatter)} 有 $slotMinutes 分钟空闲",
            "可以安排《${candidateTask.title}》（预计 ${candidateTask.estimatedMinutes} 分钟），或留给自己。",
            "free_slot:$today:${candidateTask.id}:v${candidateTask.version}",
            taskId = candidateTask.id, taskVersion = candidateTask.version,
            proposedStartAt = slot.start.toEpochMilli(), proposedEndAt = slot.start.plusSeconds(candidateTask.estimatedMinutes * 60L).toEpochMilli(),
            forDate = today.toString(), expiresAt = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )
    }

    private fun shortSleep(data: LocalSnapshot, zone: ZoneId, now: Instant): SuggestionCandidate? {
        val healthData = data.copy(records = data.records.filter { it.source == "HEALTH_SYNC" })
        val today = now.atZone(zone).toLocalDate()
        val sleep = healthData.sleepDays(now, zone)[today]?.takeIf { it.mainRecordIds.isNotEmpty() } ?: return null
        val minutes = sleep.mainMinutes
        if (minutes >= minSleepHours * 60) return null
        return SuggestionCandidate(
            TYPE_SLEEP_SHORT,
            "$today 起床的主睡眠 ${minutes / 60} 小时${minutes % 60} 分",
            "已合并同来源分段并去除重叠，午睡单列；这只是已记录的时长。来源：${sleep.selectedSource}。",
            "sleep_short:${sleep.mainRecordIds.joinToString(",")}",
            forDate = today.toString(), expiresAt = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )
    }
}

/** Converts engine candidates into storable entities; keeps entity construction out of the engine. */
fun SuggestionCandidate.toEntity(id: String, createdAt: Long): AgentSuggestionEntity =
    AgentSuggestionEntity(id = id, type = type, title = title, detail = detail, dedupKey = dedupKey, createdAt = createdAt,
        taskId = taskId, taskVersion = taskVersion, proposedStartAt = proposedStartAt, proposedEndAt = proposedEndAt, forDate = forDate, expiresAt = expiresAt)
