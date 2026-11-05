package com.timeagent.reports

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import com.timeagent.core.TimeSpan
import com.timeagent.core.TimeStatistics
import com.timeagent.core.workout.WorkoutSession
import com.timeagent.core.workout.WorkoutSessionStatus
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.focus.FocusSegments
import com.timeagent.habits.HabitDayState
import com.timeagent.habits.HabitEntity
import com.timeagent.habits.HabitEvent
import com.timeagent.habits.HabitMetric
import com.timeagent.habits.HabitPeriod
import com.timeagent.habits.HabitSource
import com.timeagent.habits.HabitStatistics
import com.timeagent.habits.formatMinuteOfDay
import com.timeagent.ledger.LedgerRepository
import com.timeagent.workout.data.WorkoutDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

enum class ReportKind(val label: String) {
    WEEK("周报"),
    MONTH("月报"),
}

enum class ReportSection(val label: String) {
    OVERVIEW("总览"),
    PROJECTS("项目分布"),
    TAGS("标签分布"),
    DEVIATIONS("计划偏差"),
    DEADLINES("截止"),
    HABITS("习惯"),
    WEEKLY_GOALS("周目标"),
    WORKOUTS("训练"),
    LEDGER("记账"),
}

data class ReportWindow(val start: LocalDate, val end: LocalDate) {
    val days: Int get() = (end.toEpochDay() - start.toEpochDay()).toInt() + 1
}

data class OverviewReport(
    val plannedMinutes: Long?,
    val actualMinutes: Long?,
    val coveredMinutes: Long?,
    val timeCoverageRate: Int?,
    val planCount: Int,
    val coveredPlans: Int,
    val planCompletionRate: Int?,
    val actualRecordCount: Int,
    val focusMinutes: Long?,
    val focusSessions: Int,
    val sleepMinutes: Long?,
    val sleepNights: Int,
)

data class DistributionSlice(
    val name: String,
    val minutes: Long,
    val sharePercent: Int,
    val count: Int,
)

data class DeviationReport(
    val label: String,
    val plannedMinutes: Long,
    val actualMinutes: Long,
) {
    val deltaMinutes: Long get() = actualMinutes - plannedMinutes
}

data class DeadlineReport(
    val total: Int,
    val completed: Int,
    val pending: Int,
    val overdue: Int,
    val completionRate: Int,
)

data class HabitReportLine(
    val id: String,
    val name: String,
    val achieved: Int,
    val evaluated: Int,
    val rate: Int?,
    val targetText: String,
    val noData: Boolean,
)

data class HabitReportSection(
    val lines: List<HabitReportLine>,
    val achieved: Int,
    val evaluated: Int,
    val unavailable: Boolean = false,
)

data class WeeklyGoalReportLine(
    val title: String,
    val weekStart: LocalDate,
    val targetMinutes: Int,
    val actualMinutes: Long,
    val source: String,
)

data class WorkoutReport(
    val sessions: Int,
    val minutes: Long,
    val completedSets: Int,
    val volumeKg: Double,
    val unavailable: Boolean = false,
)

data class LedgerSlice(val name: String, val amountMinor: Long, val sharePercent: Int)

data class LedgerReport(
    val expenseMinor: Long,
    val incomeMinor: Long,
    val refundMinor: Long,
    val netExpenseMinor: Long,
    val count: Int,
    val topCategories: List<LedgerSlice>,
)

data class ReportSnapshot(
    val kind: ReportKind,
    val window: ReportWindow,
    val generatedAt: Long,
    val zoneId: String,
    val overview: OverviewReport,
    val projects: List<DistributionSlice>,
    val tags: List<DistributionSlice>,
    val deviations: List<DeviationReport>,
    val deadlines: DeadlineReport?,
    val habits: HabitReportSection?,
    val weeklyGoals: List<WeeklyGoalReportLine>,
    val workout: WorkoutReport?,
    val ledger: LedgerReport?,
    val notes: List<String> = emptyList(),
)

data class ReportUiState(
    val kind: ReportKind = ReportKind.WEEK,
    val anchor: LocalDate = LocalDate.now(),
    val snapshot: ReportSnapshot? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val visibleSections: Set<ReportSection> = ReportSection.entries.toSet(),
    val fishCommentary: String? = null,
    val fishLoading: Boolean = false,
    val fishError: String? = null,
)

/**
 * Builds the weekly/monthly report entirely from local rows. A source that is not available
 * (pre-v19 focus/habits tables, no sleep sync, no training database) yields `null`/an explicit
 * unavailable marker instead of a fabricated zero. Every write-free query is read-only.
 */
class ReportRepository(
    private val db: TimeAgentDatabase,
    private val workoutDb: WorkoutDatabase? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    constructor(context: Context, clock: Clock = Clock.systemDefaultZone()) : this(
        TimeAgentDatabase.open(context.applicationContext),
        WorkoutDatabase.open(context.applicationContext),
        clock,
    )

    private val gson = Gson()
    private val ledgerRepository by lazy { LedgerRepository(db, clock) }
    private val tableCache = mutableMapOf<String, Boolean>()

    val zone: ZoneId get() = clock.zone

    fun today(): LocalDate = Instant.ofEpochMilli(clock.millis()).atZone(zone).toLocalDate()

    fun window(kind: ReportKind, anchor: LocalDate): ReportWindow = when (kind) {
        ReportKind.WEEK -> {
            val monday = anchor.with(DayOfWeek.MONDAY)
            ReportWindow(monday, monday.plusDays(6))
        }
        ReportKind.MONTH -> {
            val month = YearMonth.from(anchor)
            ReportWindow(month.atDay(1), month.atEndOfMonth())
        }
    }

    suspend fun load(kind: ReportKind, anchor: LocalDate): ReportSnapshot = withContext(Dispatchers.IO) {
        val window = window(kind, anchor)
        val startAt = window.start.atStartOfDay(zone).toInstant().toEpochMilli()
        val endAt = window.end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = clock.millis()

        val tasks = readable.rows(
            "SELECT id, title, status, dueAt, completedAt, activityId, deletedAt FROM tasks",
        ).associateBy { it.str("id") }
        val projects = readable.rows("SELECT id, name, deletedAt FROM projects").associateBy { it.str("id") }
        val placements = readable.rows("SELECT taskId, projectId FROM task_placements")
            .associate { it.str("taskId") to it.str("projectId").ifBlank { null } }
        val categories = readable.rows("SELECT id, name FROM categories")
            .associate { it.str("id") to it.str("name") }
        val tags = readable.rows("SELECT id, name FROM tags").associate { it.str("id") to it.str("name") }
        val recordTags = readable.rows("SELECT recordId, tagId FROM record_tags")
            .groupBy({ it.str("recordId") }, { it.str("tagId") })

        val plans = loadPlans(startAt, endAt)
        val records = loadRecords(startAt, endAt)
        val planSpans = plans.mapNotNull { clipSpan(it.startAt, it.endAt, startAt, endAt) }
        val actualSpans = records.mapNotNull { clipSpan(it.startAt, it.endAt ?: return@mapNotNull null, startAt, endAt) }
        val plannedStats = TimeStatistics.calculate(planSpans)
        val actualStats = TimeStatistics.calculate(actualSpans)
        val plannedMinutes = planSpans.takeIf { it.isNotEmpty() }?.let { plannedStats.elapsedMinutes }
        val actualMinutes = actualSpans.takeIf { it.isNotEmpty() }?.let { actualStats.elapsedMinutes }
        val coveredMinutes = if (planSpans.isEmpty()) null else intersectMinutes(planSpans, actualSpans)
        val coveredPlans = plans.count { plan ->
            val span = clipSpan(plan.startAt, plan.endAt, startAt, endAt) ?: return@count false
            actualSpans.any { it.overlaps(span) }
        }
        val planCompletionRate = if (plans.isEmpty()) null else coveredPlans * 100 / plans.size
        val timeCoverageRate = if (planSpans.isEmpty()) null else ((coveredMinutes ?: 0L) * 100 / plannedStats.elapsedMinutes.coerceAtLeast(1)).toInt()

        val focus = loadFocus(startAt, endAt)
        val sleep = loadSleep(startAt, endAt)
        val totalRecordMinutes = records.sumOf { recordMinutes(it, startAt, endAt) }

        val projectSlices = distribution(
            records = records,
            startAt = startAt,
            endAt = endAt,
            keyOf = { record ->
                val projectId = record.projectId ?: record.taskId?.let(placements::get)
                projectId to (projectId?.let { projects[it]?.str("name") } ?: "未归属项目")
            },
            totalMinutes = totalRecordMinutes,
        )
        val tagSlices = tagDistribution(records, recordTags, tags, startAt, endAt, totalRecordMinutes)

        val deadlineRows = tasks.values.filter { task ->
            task.str("deletedAt").isBlank() && task.longOrNull("dueAt")?.let { it >= startAt && it < endAt } == true
        }
        val deadlines = if (deadlineRows.isEmpty()) null else {
            val completed = deadlineRows.count { it.str("status") == "COMPLETED" && it.longOrNull("completedAt") != null }
            val overdue = deadlineRows.count {
                !(it.str("status") == "COMPLETED" && it.longOrNull("completedAt") != null) &&
                    (it.longOrNull("dueAt") ?: Long.MAX_VALUE) < now
            }
            DeadlineReport(
                total = deadlineRows.size,
                completed = completed,
                pending = deadlineRows.size - completed,
                overdue = overdue,
                completionRate = completed * 100 / deadlineRows.size,
            )
        }

        val habits = loadHabits(window, tasks, placements, recordTags, startAt, endAt)
        val weeklyGoals = loadWeeklyGoals(window, tasks, placements, records, startAt, endAt)
        val workout = loadWorkout(startAt, endAt)
        val ledger = loadLedger(startAt, endAt)

        val notes = buildList {
            if (!hasTable("focus_sessions")) add("专注表尚未注册（v19 前），专注显示为无数据。")
            if (focus == null && hasTable("focus_sessions")) add("所选区间没有已完成的专注记录。")
            if (sleep == null) add("所选区间没有睡眠数据；睡眠缺失不按 0 计算。")
            if (habits?.unavailable == true) add("习惯表尚未注册（v19 前），习惯显示为无数据。")
            if (workout == null) add("训练数据库不可用，训练显示为无数据。")
            if (ledger == null) add("所选区间没有已确认的记账。")
        }

        ReportSnapshot(
            kind = kind,
            window = window,
            generatedAt = now,
            zoneId = zone.id,
            overview = OverviewReport(
                plannedMinutes = plannedMinutes,
                actualMinutes = actualMinutes,
                coveredMinutes = coveredMinutes,
                timeCoverageRate = timeCoverageRate,
                planCount = plans.size,
                coveredPlans = coveredPlans,
                planCompletionRate = planCompletionRate,
                actualRecordCount = records.map { it.id }.distinct().size,
                focusMinutes = focus?.first,
                focusSessions = focus?.second ?: 0,
                sleepMinutes = sleep?.first,
                sleepNights = sleep?.second ?: 0,
            ),
            projects = projectSlices,
            tags = tagSlices,
            deviations = deviations(plans, records, tasks, startAt, endAt),
            deadlines = deadlines,
            habits = habits,
            weeklyGoals = weeklyGoals,
            workout = workout,
            ledger = ledger,
            notes = notes,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Raw read helpers
    // ---------------------------------------------------------------------------------------------

    private val readable: SupportSQLiteDatabase get() = db.openHelper.readableDatabase

    private fun hasTable(name: String): Boolean = tableCache.getOrPut(name) {
        runCatching {
            readable.query(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                arrayOf(name),
            ).use { it.moveToFirst() }
        }.getOrDefault(false)
    }

    private data class PlanRow(val id: String, val taskId: String?, val title: String, val startAt: Long, val endAt: Long)

    private data class RecordRow(
        val id: String,
        val taskId: String?,
        val title: String,
        val startAt: Long,
        val endAt: Long?,
        val categoryId: String?,
        val projectId: String?,
        val activityId: String?,
        val notes: String,
    )

    private fun loadPlans(startAt: Long, endAt: Long): List<PlanRow> = readable.rows(
        "SELECT id, taskId, title, startAt, endAt FROM schedule_blocks " +
            "WHERE deletedAt IS NULL AND status='SCHEDULED' AND endAt > startAt " +
            "AND startAt < ? AND endAt > ? ORDER BY startAt",
        endAt, startAt,
    ).map {
        PlanRow(it.str("id"), it.str("taskId").ifBlank { null }, it.str("title"), it.long("startAt"), it.long("endAt"))
    }

    private fun loadRecords(startAt: Long, endAt: Long): List<RecordRow> {
        val focus = readable.rows("SELECT id, segmentsJson FROM focus_sessions").associateBy { "focus:${it.str("id")}" }
        return readable.rows(
            "SELECT r.id, r.taskId, r.title, r.startAt, r.endAt, r.categoryId, r.notes, c.projectId, c.activityId FROM time_records r " +
                "LEFT JOIN entry_contexts c ON c.entityType='RECORD' AND c.entityId=r.id " +
                "WHERE r.endAt IS NOT NULL AND r.endAt > r.startAt AND r.startAt < ? AND r.endAt > ? ORDER BY r.startAt", endAt, startAt,
        ).flatMap { row ->
            val record = RecordRow(row.str("id"), row.str("taskId").ifBlank { null }, row.str("title"),
                row.long("startAt"), row.long("endAt"), row.str("categoryId").ifBlank { null },
                row.str("projectId").ifBlank { null }, row.str("activityId").ifBlank { null }, row.str("notes"))
            val session = focus[record.id]
            if (session == null) listOf(record) else FocusSegments.decode(session.str("segmentsJson")).mapNotNull { segment ->
                val start = maxOf(segment.startAt, startAt)
                val end = minOf(segment.startAt + segment.elapsedMs, endAt)
                if (end > start) record.copy(startAt = start, endAt = end) else null
            }
        }
    }

    private fun loadFocus(startAt: Long, endAt: Long): Pair<Long, Int>? {
        val rows = readable.rows("SELECT id, segmentsJson FROM focus_sessions WHERE focusedElapsedMs > 0")
        var count = 0
        val millis = rows.sumOf { row ->
            val duration = FocusSegments.decode(row.str("segmentsJson")).sumOf { segment ->
                (minOf(segment.startAt + segment.elapsedMs, endAt) - maxOf(segment.startAt, startAt)).coerceAtLeast(0)
            }
            if (duration > 0) count++
            duration
        }
        return millis / 60_000L to count
    }

    private fun loadSleep(startAt: Long, endAt: Long): Pair<Long, Int>? {
        if (!hasTable("health_sync_items")) return null
        val rows = readable.rows(
            "SELECT startAt, endAt FROM health_sync_items " +
                "WHERE kind='SLEEP' AND userDeleted=0 AND endAt > startAt " +
                "AND startAt < ? AND endAt > ? ORDER BY startAt",
            endAt, startAt,
        )
        if (rows.isEmpty()) return null
        val spans = rows.mapNotNull { clipSpan(it.long("startAt"), it.long("endAt"), startAt, endAt) }
        if (spans.isEmpty()) return null
        val nights = rows.map { Instant.ofEpochMilli(it.long("endAt")).atZone(zone).toLocalDate() }.distinct().size
        return TimeStatistics.calculate(spans).elapsedMinutes to nights
    }

    // ---------------------------------------------------------------------------------------------
    // Distributions & deviations
    // ---------------------------------------------------------------------------------------------

    private fun distribution(
        records: List<RecordRow>,
        startAt: Long,
        endAt: Long,
        keyOf: (RecordRow) -> Pair<String?, String>,
        totalMinutes: Long,
    ): List<DistributionSlice> {
        if (records.isEmpty()) return emptyList()
        val grouped = linkedMapOf<String, Pair<String, Long>>()
        records.forEach { record ->
            val (key, label) = keyOf(record)
            val mapKey = key ?: "unassigned:$label"
            val minutes = recordMinutes(record, startAt, endAt)
            val current = grouped[mapKey]
            grouped[mapKey] = (current?.first ?: label) to ((current?.second ?: 0L) + minutes)
        }
        return grouped.values
            .sortedByDescending { it.second }
            .map { (name, minutes) ->
                DistributionSlice(name, minutes, if (totalMinutes <= 0) 0 else (minutes * 100 / totalMinutes).toInt(), 0)
            }
    }

    private fun tagDistribution(
        records: List<RecordRow>,
        recordTags: Map<String, List<String>>,
        tagNames: Map<String, String>,
        startAt: Long,
        endAt: Long,
        totalMinutes: Long,
    ): List<DistributionSlice> {
        if (records.isEmpty()) return emptyList()
        val grouped = linkedMapOf<String, Pair<String, Long>>()
        records.forEach { record ->
            val minutes = recordMinutes(record, startAt, endAt)
            val ids = recordTags[record.id].orEmpty()
            if (ids.isEmpty()) {
                val current = grouped["unlabelled"]
                grouped["unlabelled"] = "未加标签" to ((current?.second ?: 0L) + minutes)
            } else {
                ids.distinct().forEach { tagId ->
                    val current = grouped[tagId]
                    grouped[tagId] = (tagNames[tagId] ?: "已删除标签") to ((current?.second ?: 0L) + minutes)
                }
            }
        }
        return grouped.values
            .sortedByDescending { it.second }
            .map { (name, minutes) ->
                DistributionSlice(name, minutes, if (totalMinutes <= 0) 0 else (minutes * 100 / totalMinutes).toInt(), 0)
            }
    }

    private fun deviations(
        plans: List<PlanRow>,
        records: List<RecordRow>,
        tasks: Map<String, Map<String, Any?>>,
        startAt: Long,
        endAt: Long,
    ): List<DeviationReport> {
        if (plans.isEmpty()) return emptyList()
        val planned = linkedMapOf<String, Pair<String, Long>>()
        plans.forEach { plan ->
            val label = taskLabel(plan.taskId, plan.title, tasks)
            val key = plan.taskId ?: "title:${label.trim()}"
            val current = planned[key]
            planned[key] = label to ((current?.second ?: 0L) + recordMinutes(plan.startAt, plan.endAt, startAt, endAt))
        }
        val actual = linkedMapOf<String, Pair<String, Long>>()
        var unplannedMinutes = 0L
        records.forEach { record ->
            val label = taskLabel(record.taskId, record.title, tasks)
            val key = record.taskId ?: "title:${label.trim()}"
            val minutes = recordMinutes(record, startAt, endAt)
            if (key in planned) {
                val current = actual[key]
                actual[key] = label to ((current?.second ?: 0L) + minutes)
            } else {
                unplannedMinutes += minutes
            }
        }
        val rows = planned.map { (key, value) ->
            val actualMinutes = actual[key]?.second ?: 0L
            DeviationReport(value.first, value.second, actualMinutes)
        }.toMutableList()
        if (unplannedMinutes > 0) {
            rows += DeviationReport("计划外实际（未匹配计划）", 0, unplannedMinutes)
        }
        return rows.sortedByDescending { abs(it.deltaMinutes) }.take(5)
    }

    private fun taskLabel(taskId: String?, title: String, tasks: Map<String, Map<String, Any?>>): String =
        title.ifBlank { taskId?.let { tasks[it]?.str("title") } ?: "未命名事项" }

    // ---------------------------------------------------------------------------------------------
    // Habits
    // ---------------------------------------------------------------------------------------------

    private suspend fun loadHabits(
        window: ReportWindow,
        tasks: Map<String, Map<String, Any?>>,
        placements: Map<String, String?>,
        recordTags: Map<String, List<String>>,
        startAt: Long,
        endAt: Long,
    ): HabitReportSection? {
        if (!hasTable("habits")) return HabitReportSection(emptyList(), 0, 0, unavailable = true)
        val habits = readable.rows(
            "SELECT * FROM habits WHERE archivedAt IS NULL AND startDate <= ? ORDER BY sortOrder, createdAt",
            window.end.toString(),
        ).map(::habitFrom)
        if (habits.isEmpty()) return HabitReportSection(emptyList(), 0, 0)

        val eventStart = window.start.with(DayOfWeek.MONDAY)
        val eventStartAt = eventStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val events = loadHabitEvents(eventStart, window.end, eventStartAt, endAt, tasks, placements, recordTags)
        val evaluationToday = minOf(window.end, today())
        var achievedTotal = 0
        var evaluatedTotal = 0
        val lines = habits.map { habit ->
            val evaluation = HabitStatistics.evaluate(habit, events, window.start, window.end, today = evaluationToday)
            achievedTotal += evaluation.achieved
            evaluatedTotal += evaluation.evaluated
            HabitReportLine(
                id = habit.id,
                name = habit.name,
                achieved = evaluation.achieved,
                evaluated = evaluation.evaluated,
                rate = if (evaluation.evaluated == 0) null else evaluation.rate,
                targetText = targetText(habit),
                noData = evaluation.evaluated == 0 || evaluation.days.all { it.state == HabitDayState.NO_DATA },
            )
        }
        return HabitReportSection(lines, achievedTotal, evaluatedTotal)
    }

    private suspend fun loadHabitEvents(
        eventStart: LocalDate,
        eventEnd: LocalDate,
        startAt: Long,
        endAt: Long,
        tasks: Map<String, Map<String, Any?>>,
        placements: Map<String, String?>,
        recordTags: Map<String, List<String>>,
    ): List<HabitEvent> {
        val events = mutableListOf<HabitEvent>()
        val records = loadRecords(startAt, endAt)
        val sleepIds = if (hasTable("health_sync_items")) {
            readable.rows("SELECT h.recordId FROM health_sync_items h JOIN time_records r ON r.id=h.recordId " +
                "WHERE h.kind='SLEEP' AND h.recordId IS NOT NULL")
                .map { it.str("recordId") }.toSet()
        } else emptySet()

        records.forEach { record ->
            val end = record.endAt ?: return@forEach
            val title = taskLabel(record.taskId, record.title, tasks)
            val sleepText = "$title ${record.notes}"
            val isSleep = record.id in sleepIds || sleepText.contains("睡眠") || sleepText.contains("睡觉") ||
                sleepText.contains("sleep", ignoreCase = true)
            val projectId = record.projectId ?: record.taskId?.let(placements::get)
            val activityId = record.activityId ?: record.taskId?.let { tasks[it]?.str("activityId")?.ifBlank { null } }
            if (isSleep) {
                events += HabitEvent(
                    date = nightOf(record.startAt),
                    kind = HabitStatistics.KIND_SLEEP,
                    id = record.id,
                    title = title,
                    minutes = ((end - record.startAt).coerceAtLeast(0L) / 60_000L).toInt(),
                    count = 1,
                    minuteOfDay = minuteOfDay(record.startAt),
                    endMinuteOfDay = minuteOfDay(end),
                    startAt = record.startAt,
                    endAt = end,
                )
                return@forEach
            }
            val slices = splitByDay(record.startAt, end)
            slices.forEachIndexed { index, slice ->
                events += HabitEvent(
                    date = slice.first,
                    kind = HabitStatistics.KIND_RECORD,
                    id = record.id,
                    title = title,
                    minutes = ((slice.third - slice.second).coerceAtLeast(0L) / 60_000L).toInt(),
                    count = if (index == 0) 1 else 0,
                    minuteOfDay = if (index == 0) minuteOfDay(record.startAt) else null,
                    endMinuteOfDay = if (index == slices.lastIndex) minuteOfDay(end) else null,
                    startAt = slice.second,
                    endAt = slice.third,
                    projectId = projectId,
                    activityId = activityId,
                    categoryId = record.categoryId,
                    tagIds = recordTags[record.id].orEmpty().toSet(),
                )
            }
        }

        if (hasTable("focus_sessions")) {
            readable.rows(
                "SELECT id, label, startedAt, focusedElapsedMs, roundIndex, segmentsJson FROM focus_sessions " +
                    "WHERE focusedElapsedMs > 0 AND startedAt < ? AND (endedAt IS NULL OR endedAt > ?)",
                endAt, startAt,
            ).forEach { session ->
                val id = session.str("id")
                val label = session.str("label").ifBlank { "专注" }
                val segments = FocusSegments.decode(session.str("segmentsJson")).ifEmpty {
                    val start = session.long("startedAt")
                    val elapsed = session.long("focusedElapsedMs")
                    listOf(com.timeagent.focus.FocusSegment(start, start + elapsed, elapsed, session.int("roundIndex")))
                }
                val counted = mutableSetOf<LocalDate>()
                segments.forEach { segment ->
                    if (segment.endAt <= segment.startAt || segment.elapsedMs <= 0) return@forEach
                    splitByDay(segment.startAt, segment.endAt).forEach { slice ->
                        val wall = (segment.endAt - segment.startAt).coerceAtLeast(1L)
                        val share = segment.elapsedMs * (slice.third - slice.second) / wall
                        val count = if (counted.add(slice.first)) 1 else 0
                        events += HabitEvent(
                            date = slice.first,
                            kind = HabitStatistics.KIND_FOCUS,
                            id = id,
                            title = label,
                            minutes = (share / 60_000L).toInt(),
                            count = count,
                            minuteOfDay = if (count == 1) minuteOfDay(segment.startAt) else null,
                            endMinuteOfDay = minuteOfDay(segment.endAt),
                            startAt = slice.second,
                            endAt = slice.third,
                        )
                    }
                }
            }
        }

        decodeWorkouts().filter {
            it.status == WorkoutSessionStatus.COMPLETED && it.endedAt != null &&
                it.startedAt < endAt && (it.endedAt ?: 0L) > startAt
        }.forEach { session ->
            val end = session.endedAt ?: return@forEach
            val active = (end - session.startedAt - session.totalPausedMillis).coerceAtLeast(0L)
            val wall = (end - session.startedAt).coerceAtLeast(1L)
            val slices = splitByDay(session.startedAt, end)
            slices.forEachIndexed { index, slice ->
                val share = active * (slice.third - slice.second) / wall
                events += HabitEvent(
                    date = slice.first,
                    kind = HabitStatistics.KIND_WORKOUT,
                    id = session.id,
                    title = session.title.ifBlank { "训练" },
                    minutes = (share / 60_000L).toInt(),
                    count = if (index == 0) 1 else 0,
                    minuteOfDay = if (index == 0) minuteOfDay(session.startedAt) else null,
                    endMinuteOfDay = if (index == slices.lastIndex) minuteOfDay(end) else null,
                    startAt = slice.second,
                    endAt = slice.third,
                )
            }
        }

        if (hasTable("health_sync_items")) {
            readable.rows(
                "SELECT recordId, startAt, endAt, title FROM health_sync_items " +
                    "WHERE kind='SLEEP' AND userDeleted=0 AND endAt > startAt AND startAt < ? AND endAt > ?",
                endAt, startAt,
            ).forEach { item ->
                // A linked record is authoritative even when edited outside this report window.
                val linkedRecordId = item.str("recordId").ifBlank { null }
                if (linkedRecordId != null && linkedRecordId in sleepIds) return@forEach
                val start = maxOf(item.long("startAt"), startAt)
                val end = minOf(item.long("endAt"), endAt)
                if (end <= start) return@forEach
                events += HabitEvent(
                    date = nightOf(item.long("startAt")),
                    kind = HabitStatistics.KIND_SLEEP,
                    id = "health:${item.long("startAt")}:${item.long("endAt")}",
                    title = item.str("title").ifBlank { "睡眠" },
                    minutes = ((end - start) / 60_000L).toInt(),
                    count = 1,
                    minuteOfDay = minuteOfDay(item.long("startAt")),
                    endMinuteOfDay = minuteOfDay(item.long("endAt")),
                    startAt = start,
                    endAt = end,
                )
            }
        }

        if (hasTable("habit_checkins")) {
            readable.rows(
                "SELECT habitId, date, value, note FROM habit_checkins WHERE date >= ? AND date <= ?",
                eventStart.toString(), eventEnd.toString(),
            ).forEach { checkin ->
                val value = checkin.int("value")
                val date = runCatching { LocalDate.parse(checkin.str("date")) }.getOrNull() ?: return@forEach
                events += HabitEvent(
                    date = date,
                    kind = HabitStatistics.KIND_CHECKIN,
                    id = "${checkin.str("habitId")}:$date",
                    title = "手动打卡",
                    minutes = value,
                    count = value,
                    minuteOfDay = value,
                    endMinuteOfDay = value,
                    note = checkin.str("note"),
                    habitId = checkin.str("habitId"),
                )
            }
        }
        return events
    }

    private fun habitFrom(row: Map<String, Any?>): HabitEntity = HabitEntity(
        id = row.str("id"),
        name = row.str("name"),
        icon = row.str("icon").ifBlank { "check" },
        color = row.long("color"),
        metric = row.str("metric").ifBlank { HabitMetric.DURATION.name },
        source = row.str("source").ifBlank { HabitSource.MANUAL_CHECKIN.name },
        sourceRef = row.str("sourceRef").ifBlank { null },
        target = row.int("target"),
        period = row.str("period").ifBlank { HabitPeriod.DAILY.name },
        activeWeekdays = row.str("activeWeekdays").ifBlank { HabitEntity.ALL_WEEKDAYS },
        startDate = row.str("startDate"),
        archivedAt = row.longOrNull("archivedAt"),
        reminderEnabled = row.int("reminderEnabled") == 1,
        sortOrder = row.int("sortOrder"),
        createdAt = row.long("createdAt"),
        updatedAt = row.long("updatedAt"),
        version = row.long("version"),
    )

    private fun targetText(habit: HabitEntity): String = when (habit.metricKind) {
        HabitMetric.DURATION -> if (habit.periodKind == HabitPeriod.WEEKLY) "每周 ${habit.target} 分钟" else "每天 ${habit.target} 分钟"
        HabitMetric.COUNT -> if (habit.periodKind == HabitPeriod.WEEKLY) "每周 ${habit.target} 次" else "每天 ${habit.target} 次"
        HabitMetric.BEFORE_TIME -> "每天 ${formatMinuteOfDay(habit.target)} 前"
        HabitMetric.AFTER_TIME -> "每天 ${formatMinuteOfDay(habit.target)} 后"
    }

    // ---------------------------------------------------------------------------------------------
    // Weekly goals, training, ledger
    // ---------------------------------------------------------------------------------------------

    private fun loadWeeklyGoals(
        window: ReportWindow,
        tasks: Map<String, Map<String, Any?>>,
        placements: Map<String, String?>,
        records: List<RecordRow>,
        startAt: Long,
        endAt: Long,
    ): List<WeeklyGoalReportLine> {
        if (!hasTable("weekly_goals")) return emptyList()
        val projects = readable.rows("SELECT id, name FROM projects").associate { it.str("id") to it.str("name") }
        val activities = readable.rows("SELECT id, name, parentId FROM activity_templates")
        val activityNames = activities.associate { it.str("id") to it.str("name") }
        val activityParents = activities.associate { it.str("id") to it.str("parentId").ifBlank { null } }
        val goals = readable.rows(
            "SELECT id, projectId, activityId, weekStart, targetMinutes FROM weekly_goals " +
                "WHERE weekStart >= ? AND weekStart <= ? ORDER BY weekStart, id",
            window.start.with(DayOfWeek.MONDAY).toString(), window.end.toString(),
        )
        return goals.mapNotNull { goal ->
            val weekStart = runCatching { LocalDate.parse(goal.str("weekStart")) }.getOrNull() ?: return@mapNotNull null
            val weekStartAt = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
            val weekEndAt = weekStart.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val projectId = goal.str("projectId").ifBlank { null }
            val activityId = goal.str("activityId").ifBlank { null }
            val activityIds = activityId?.let { descendantActivities(it, activityParents) }.orEmpty()
            val actual = TimeStatistics.calculate(
                records.filter { record ->
                    when {
                        projectId != null -> (record.projectId ?: record.taskId?.let { placements[it] }) == projectId
                        activityId != null -> (record.activityId
                            ?: record.taskId?.let { tasks[it]?.str("activityId") }) in activityIds
                        else -> false
                    }
                }
                    .mapNotNull { clipSpan(it.startAt, it.endAt ?: return@mapNotNull null, weekStartAt, weekEndAt) },
            ).recordedMinutes
            val title = projectId?.let { projects[it] } ?: activityId?.let { activityNames[it] } ?: "原对象已不可用"
            WeeklyGoalReportLine(
                title = title,
                weekStart = weekStart,
                targetMinutes = goal.int("targetMinutes"),
                actualMinutes = actual,
                source = if (projectId != null) "项目" else "活动",
            )
        }
    }

    private fun descendantActivities(id: String, parents: Map<String, String?>): Set<String> {
        val out = mutableSetOf(id)
        var changed = true
        while (changed) {
            changed = false
            parents.forEach { (child, parent) ->
                if (parent in out && child !in out) {
                    out += child
                    changed = true
                }
            }
        }
        return out
    }

    private suspend fun loadWorkout(startAt: Long, endAt: Long): WorkoutReport? {
        val database = workoutDb ?: return null
        val rows = database.dao().sessions()
        val sessions = rows.map { row ->
            gson.fromJson(row.payload, WorkoutSession::class.java)
        }.filter {
            it.status == WorkoutSessionStatus.COMPLETED && it.endedAt != null &&
                it.startedAt < endAt && (it.endedAt ?: 0L) > startAt
        }
        var minutes = 0L
        var sets = 0
        var volume = 0.0
        sessions.forEach { session ->
            val end = session.endedAt ?: return@forEach
            val wall = (end - session.startedAt).coerceAtLeast(1L)
            val overlap = (minOf(end, endAt) - maxOf(session.startedAt, startAt)).coerceAtLeast(0L)
            val active = (end - session.startedAt - session.totalPausedMillis).coerceAtLeast(0L)
            minutes += active * overlap / wall / 60_000L
            session.movements.forEach { movement ->
                movement.sets.filter { it.isCompleted }.forEach { set ->
                    sets++
                    val weight = set.weightKg
                    val reps = set.reps
                    if (weight != null && reps != null) volume += weight * reps
                    set.dropSegments.forEach { drop ->
                        if (drop.weightKg != null && drop.reps != null) volume += requireNotNull(drop.weightKg) * requireNotNull(drop.reps)
                    }
                }
            }
        }
        return WorkoutReport(sessions.size, minutes, sets, volume)
    }

    private suspend fun loadLedger(startAt: Long, endAt: Long): LedgerReport? {
        val dao = db.ledgerDao()
        val transactions = dao.transactions()
        val rows = transactions.filter { it.deletedAt == null && it.occurredAt in startAt until endAt }
        if (rows.isEmpty()) return null
        val totals = ledgerRepository.totals(rows)
        val categoryNames = dao.categories()
            .associate { it.id to it.name }
        val byCategory = rows.filter { it.direction == "EXPENSE" }
            .groupBy { categoryNames[it.categoryId] ?: "已删除分类" }
            .mapValues { (_, values) -> values.sumOf { it.amountMinor } }
        val expense = totals.expenseMinor.coerceAtLeast(0L)
        val slices = byCategory.entries.sortedByDescending { it.value }.take(5).map {
            LedgerSlice(it.key, it.value, if (expense <= 0) 0 else (it.value * 100 / expense).toInt())
        }
        return LedgerReport(
            expenseMinor = totals.expenseMinor,
            incomeMinor = totals.incomeMinor,
            refundMinor = totals.refundMinor,
            netExpenseMinor = totals.netExpenseMinor,
            count = totals.count,
            topCategories = slices,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------------------------------

    private suspend fun decodeWorkouts(): List<WorkoutSession> {
        val database = workoutDb ?: return emptyList()
        return database.dao().sessions().map {
            gson.fromJson(it.payload, WorkoutSession::class.java)
        }
    }

    private fun recordMinutes(record: RecordRow, startAt: Long, endAt: Long): Long {
        val end = record.endAt ?: return 0
        return recordMinutes(record.startAt, end, startAt, endAt)
    }

    private fun recordMinutes(start: Long, end: Long, startAt: Long, endAt: Long): Long {
        val clipped = clipSpan(start, end, startAt, endAt) ?: return 0
        return Duration.between(clipped.start, clipped.end).seconds / 60
    }

    private fun clipSpan(start: Long, end: Long, startAt: Long, endAt: Long): TimeSpan? {
        val clippedStart = maxOf(start, startAt)
        val clippedEnd = minOf(end, endAt)
        return if (clippedEnd > clippedStart) {
            TimeSpan(Instant.ofEpochMilli(clippedStart), Instant.ofEpochMilli(clippedEnd))
        } else null
    }

    private fun intersectMinutes(plans: List<TimeSpan>, actuals: List<TimeSpan>): Long {
        if (plans.isEmpty() || actuals.isEmpty()) return 0
        val left = merge(plans)
        val right = merge(actuals)
        var i = 0
        var j = 0
        var seconds = 0L
        while (i < left.size && j < right.size) {
            val start = maxOf(left[i].start, right[j].start)
            val end = minOf(left[i].end, right[j].end)
            if (start < end) seconds += Duration.between(start, end).seconds
            if (left[i].end <= right[j].end) i++ else j++
        }
        return seconds / 60
    }

    private fun merge(spans: List<TimeSpan>): List<TimeSpan> {
        val out = mutableListOf<TimeSpan>()
        spans.sortedBy { it.start }.forEach { span ->
            val last = out.lastOrNull()
            if (last != null && span.start <= last.end) {
                out[out.lastIndex] = TimeSpan(last.start, maxOf(last.end, span.end))
            } else {
                out += span
            }
        }
        return out
    }

    private fun splitByDay(startAt: Long, endAt: Long): List<Triple<LocalDate, Long, Long>> {
        if (endAt <= startAt) return emptyList()
        val out = mutableListOf<Triple<LocalDate, Long, Long>>()
        var cursor = startAt
        var guard = 0
        while (cursor < endAt && guard++ < 400) {
            val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val sliceEnd = minOf(dayEnd, endAt)
            if (sliceEnd <= cursor) break
            out += Triple(date, cursor, sliceEnd)
            cursor = sliceEnd
        }
        return out
    }

    private fun minuteOfDay(at: Long): Int =
        Instant.ofEpochMilli(at).atZone(zone).toLocalTime().toSecondOfDay() / 60

    private fun nightOf(startAt: Long): LocalDate {
        val local = Instant.ofEpochMilli(startAt).atZone(zone)
        return if (local.toLocalTime().isBefore(LocalTime.NOON)) local.toLocalDate().minusDays(1) else local.toLocalDate()
    }
}

class ReportModel(
    private val repository: ReportRepository,
    private val preferences: SharedPreferences,
    initialKind: ReportKind = ReportKind.WEEK,
) : ViewModel() {
    private val _state = MutableStateFlow(
        ReportUiState(
            kind = runCatching { ReportKind.valueOf(preferences.getString(KEY_KIND, null) ?: initialKind.name) }
                .getOrDefault(initialKind),
            anchor = repository.today(),
            visibleSections = readSections(),
        ),
    )
    val state = _state.asStateFlow()
    private var loadJob: Job? = null

    init { load() }

    fun selectKind(kind: ReportKind) {
        if (_state.value.kind == kind) return
        preferences.edit().putString(KEY_KIND, kind.name).apply()
        _state.update { it.copy(kind = kind, anchor = repository.today(), fishCommentary = null, fishError = null) }
        load()
    }

    fun showPeriod(kind: ReportKind, anchor: LocalDate) {
        _state.update { it.copy(kind = kind, anchor = anchor, fishCommentary = null, fishError = null) }
        load()
    }

    fun move(delta: Int) {
        if (delta > 0 && !canGoNext()) return
        val current = _state.value
        val anchor = when (current.kind) {
            ReportKind.WEEK -> current.anchor.plusWeeks(delta.toLong())
            ReportKind.MONTH -> current.anchor.plusMonths(delta.toLong())
        }
        _state.update { it.copy(anchor = anchor, fishCommentary = null, fishError = null) }
        load()
    }

    fun canGoNext(): Boolean {
        val current = _state.value
        return repository.window(current.kind, current.anchor).end < repository.today()
    }

    fun refresh() = load()

    fun toggleSection(section: ReportSection) {
        val current = _state.value.visibleSections
        val next = if (section in current) current - section else current + section
        if (next.isEmpty()) return
        preferences.edit().putString(KEY_SECTIONS, next.joinToString(",") { it.name }).apply()
        _state.update { it.copy(visibleSections = next) }
    }

    fun beginFishCommentary() {
        _state.update { it.copy(fishLoading = true, fishError = null) }
    }

    fun setFishCommentary(text: String?) {
        _state.update {
            it.copy(
                fishLoading = false,
                fishCommentary = text?.trim()?.takeIf(String::isNotEmpty),
                fishError = if (text.isNullOrBlank()) "大肥鱼没有返回点评内容，数字仍以上方本地统计为准。" else null,
            )
        }
    }

    fun failFishCommentary(message: String) {
        _state.update { it.copy(fishLoading = false, fishError = message) }
    }

    private fun load() {
        loadJob?.cancel()
        val current = _state.value
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            runCatching { repository.load(current.kind, current.anchor) }
                .onSuccess { snapshot ->
                    _state.update { it.copy(snapshot = snapshot, loading = false, error = null) }
                }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    _state.update {
                        it.copy(
                            loading = false,
                            error = error.message?.takeIf(String::isNotBlank)
                                ?: "报表生成失败，数据未修改。请重试。",
                        )
                    }
                }
        }
    }

    private fun readSections(): Set<ReportSection> {
        val raw = preferences.getString(KEY_SECTIONS, null) ?: return ReportSection.entries.toSet()
        val parsed = raw.split(',').mapNotNull { runCatching { ReportSection.valueOf(it) }.getOrNull() }.toSet()
        return parsed.ifEmpty { ReportSection.entries.toSet() }
    }

    companion object {
        private const val KEY_KIND = "report_kind"
        private const val KEY_SECTIONS = "report_sections"
    }
}

// -------------------------------------------------------------------------------------------------
// Formatting shared by the screen and the document renderers.
// -------------------------------------------------------------------------------------------------

object ReportFormat {
    private val dateTime = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINA)
    private val date = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)
    private val shortDate = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
    private val isoDate = DateTimeFormatter.ISO_LOCAL_DATE

    fun windowTitle(window: ReportWindow): String {
        val sameYear = window.start.year == window.end.year
        val left = if (sameYear) shortDate.format(window.start) else date.format(window.start)
        return if (window.start == window.end) date.format(window.start) else "$left – ${shortDate.format(window.end)}"
    }

    fun generatedAt(at: Long, zone: ZoneId): String = dateTime.format(Instant.ofEpochMilli(at).atZone(zone))

    fun minutes(value: Long?): String {
        if (value == null) return "无数据"
        val sign = if (value < 0) "-" else ""
        val total = abs(value)
        val hours = total / 60
        val mins = total % 60
        return when {
            hours > 0 && mins > 0 -> "$sign${hours} 小时 ${mins} 分"
            hours > 0 -> "$sign${hours} 小时"
            else -> "$sign${mins} 分钟"
        }
    }

    fun compactMinutes(value: Long?): String {
        if (value == null) return "—"
        val total = abs(value)
        return if (total >= 60) "${total / 60}h${total % 60}m" else "${total}m"
    }

    fun rate(value: Int?): String = if (value == null) "无数据" else "$value%"

    fun money(minor: Long): String {
        val negative = minor < 0
        val absolute = abs(minor)
        val text = String.format(Locale.ROOT, "%d.%02d", absolute / 100, absolute % 100)
        return if (negative) "−¥$text" else "¥$text"
    }

    fun localDate(value: LocalDate?): String = value?.let(isoDate::format) ?: "—"
}

// -------------------------------------------------------------------------------------------------
// Read-only SQL helpers. They are internal so ExportWorker can stream the same columns.
// -------------------------------------------------------------------------------------------------

internal fun SupportSQLiteDatabase.rows(sql: String, vararg args: Any?): List<Map<String, Any?>> =
    query(sql, args).use { cursor ->
        val out = ArrayList<Map<String, Any?>>(cursor.count.coerceAtLeast(0))
        while (cursor.moveToNext()) out += cursor.toRow()
        out
    }

internal fun Cursor.toRow(): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>(columnCount)
    for (index in 0 until columnCount) {
        val value: Any? = when (getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> getLong(index)
            Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
            Cursor.FIELD_TYPE_BLOB -> getBlob(index)
            else -> getString(index)
        }
        out[getColumnName(index)] = value
    }
    return out
}

internal fun Map<String, Any?>.str(key: String): String = this[key]?.toString().orEmpty()

internal fun Map<String, Any?>.long(key: String): Long = (this[key] as? Number)?.toLong() ?: 0L

internal fun Map<String, Any?>.longOrNull(key: String): Long? = (this[key] as? Number)?.toLong()

internal fun Map<String, Any?>.int(key: String): Int = (this[key] as? Number)?.toInt() ?: 0

internal fun Map<String, Any?>.double(key: String): Double = (this[key] as? Number)?.toDouble() ?: 0.0
