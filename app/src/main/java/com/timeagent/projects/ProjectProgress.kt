package com.timeagent.projects

import com.timeagent.data.*
import com.timeagent.core.*
import java.time.*

/** Source entities retain their original intervals; minute totals are clipped to the week (actuals also to now). */
data class ProjectProgress(val completedTasks: List<TaskEntity>, val pendingTasks: List<TaskEntity>, val plans: List<ScheduleBlockEntity>, val records: List<TimeRecordEntity>, val plannedMinutes: Long, val recordedMinutes: Long, val elapsedMinutes: Long)
fun projectProgress(data: LocalSnapshot, projectId: String, weekStart: LocalDate, now: Instant, zone: ZoneId): ProjectProgress =
    progress(data, data.placements.filter { it.projectId == projectId }.map { it.taskId }.toSet(), weekStart, now, zone,
        data.entryContexts.filter { it.projectId == projectId }, projectContext = true)

fun goalProgress(data: LocalSnapshot, goal: WeeklyGoalEntity, now: Instant, zone: ZoneId): ProjectProgress {
    if (goal.projectId != null) return projectProgress(data, goal.projectId, LocalDate.parse(goal.weekStart), now, zone)
    val activities = data.activities.filter { it.id == goal.activityId || it.parentId == goal.activityId }.map { it.id }.toSet()
    return progress(data, data.tasks.filter { it.activityId in activities }.map { it.id }.toSet(), LocalDate.parse(goal.weekStart), now, zone,
        data.entryContexts.filter { it.activityId in activities })
}

private fun progress(data: LocalSnapshot, ids: Set<String>, week: LocalDate, now: Instant, zone: ZoneId,
    contexts: List<com.timeagent.quickentry.EntryContextEntity>, projectContext: Boolean = false): ProjectProgress {
    val window = TimeSpan(week.atStartOfDay(zone).toInstant(), week.plusWeeks(1).atStartOfDay(zone).toInstant())
    val tasks = data.tasks.filter { it.id in ids }
    val planIds = contexts.filter { it.entityType == "PLAN" }.map { it.entityId }.toSet()
    val recordIds = contexts.filter { it.entityType == "RECORD" }.map { it.entityId }.toSet()
    val overrides = data.entryContexts.filter { if (projectContext) it.projectId != null else it.activityId != null }
    val overriddenPlans = overrides.filter { it.entityType == "PLAN" }.map { it.entityId }.toSet()
    val overriddenRecords = overrides.filter { it.entityType == "RECORD" }.map { it.entityId }.toSet()
    val plans = data.plans.filter { (it.id in planIds || (it.id !in overriddenPlans && it.taskId in ids)) && it.status == "SCHEDULED" && it.deletedAt == null && it.kind != "POINT" && it.endAt > it.startAt && TimeSpan(Instant.ofEpochMilli(it.startAt), Instant.ofEpochMilli(it.endAt)).clip(window) != null }
    fun actualSpan(r: TimeRecordEntity): TimeSpan? {
        val start = Instant.ofEpochMilli(r.startAt)
        val end = minOf(r.endAt?.let(Instant::ofEpochMilli) ?: now, now)
        return if (end > start) TimeSpan(start,end).clip(window) else null
    }
    val records = data.records.filter { (it.id in recordIds || (it.id !in overriddenRecords && it.taskId in ids)) && actualSpan(it) != null }
    val actual = TimeStatistics.calculate(records.flatMap { data.actualSpans(it, now) }, window)
    return ProjectProgress(
        tasks.filter { it.status == "COMPLETED" && it.completedAt != null && it.completedAt >= window.start.toEpochMilli() && it.completedAt < minOf(window.end.toEpochMilli(), now.toEpochMilli()+1) },
        tasks.filter { it.deletedAt == null && it.status in setOf("TODO","IN_PROGRESS") }, plans, records,
        plans.sumOf { TimeSpan(Instant.ofEpochMilli(it.startAt),Instant.ofEpochMilli(it.endAt)).clip(window)?.seconds ?: 0 } / 60,
        actual.recordedMinutes, actual.elapsedMinutes,
    )
}
