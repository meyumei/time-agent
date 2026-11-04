package com.timeagent.habits

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * One already-attributed slice of source data. Adapters (main records, sleep, training, focus)
 * build these; [HabitStatistics] only compares numbers. Keeping this pure makes every metric /
 * source combination deterministic and lets the UI show the exact rows behind a day.
 */
data class HabitEvent(
    val date: LocalDate,
    /** RECORD | SLEEP | FOCUS | WORKOUT | CHECKIN */
    val kind: String,
    val id: String,
    val title: String,
    val minutes: Int = 0,
    val count: Int = 1,
    /** Start minute-of-day (0..1439); NULL when the source has no clock time. */
    val minuteOfDay: Int? = null,
    /** End minute-of-day for AFTER_TIME (wake-up, finish). */
    val endMinuteOfDay: Int? = null,
    val startAt: Long? = null,
    val endAt: Long? = null,
    val projectId: String? = null,
    val activityId: String? = null,
    val categoryId: String? = null,
    val tagIds: Set<String> = emptySet(),
    val note: String = "",
    /** Set for manual check-ins so rows of different habits never mix. */
    val habitId: String? = null,
)

data class HabitDay(
    val date: LocalDate,
    val state: HabitDayState,
    /** Minutes, count or minute-of-day depending on the metric; null means no data. */
    val value: Long?,
    val events: List<HabitEvent> = emptyList(),
)

data class HabitWeek(
    val weekStart: LocalDate,
    /** Sum of minutes/count, or met days for BEFORE_TIME / AFTER_TIME. */
    val value: Long?,
    val state: HabitDayState,
    /** Days inside the week that carried data (used for time metrics). */
    val dataDays: Int = 0,
    val metDays: Int = 0,
)

data class HabitEvaluation(
    val habit: HabitEntity,
    val days: List<HabitDay>,
    val weekStart: LocalDate,
    val weekDays: List<HabitDay>,
    val week: HabitWeek,
    val weeks: List<HabitWeek>,
    val streak: Int,
    val achieved: Int,
    val evaluated: Int,
) {
    /** Percent over evaluated days (or weeks for WEEKLY); 0 when nothing can be judged yet. */
    val rate: Int get() = if (evaluated == 0) 0 else achieved * 100 / evaluated
    val todayState: HabitDayState get() = days.lastOrNull()?.state ?: HabitDayState.NO_DATA

    /** Human readable weekly progress, e.g. "3/5 次" or "无数据". */
    val weekLabel: String
        get() {
            val metric = habit.metricKind
            val value = week.value ?: return "无数据"
            return when (metric) {
                HabitMetric.DURATION -> "${value / 60}h${value % 60}m / ${minutesLabel(habit.target)}"
                HabitMetric.COUNT -> "$value/${habit.target} 次"
                HabitMetric.BEFORE_TIME, HabitMetric.AFTER_TIME -> when {
                    week.state == HabitDayState.NO_DATA -> "无数据"
                    week.dataDays == 0 -> "本周未达成"
                    else -> "${week.metDays}/${week.dataDays} 天达标"
                }
            }
        }
}

object HabitStatistics {
    /**
     * Evaluates one habit over `[from, to]`. Days before `startDate`, days outside
     * `activeWeekdays`, and days without source data never count as missed.
     */
    fun evaluate(habit: HabitEntity, events: List<HabitEvent>, from: LocalDate, to: LocalDate): HabitEvaluation {
        val end = maxOf(from, to)
        val begin = minOf(from, to)
        val sourceEvents = events
            .filter { matches(habit, it) }
            // Keep the whole first week so a weekly target is not judged on a partial week.
            .filter { !it.date.isBefore(begin.with(DayOfWeek.MONDAY)) && !it.date.isAfter(end) }
            .groupBy { it.date }

        val days = mutableListOf<HabitDay>()
        var cursor = begin
        while (!cursor.isAfter(end)) {
            days += day(habit, cursor, today = to, byDate = sourceEvents)
            cursor = cursor.plusDays(1)
        }

        // 12-week (or caller window) trend; used by the detail page and by weekly streaks.
        val weeks = weeksOf(begin, end).map { weekOf(habit, it, to, sourceEvents) }
        val weekStart = to.with(DayOfWeek.MONDAY)
        val weekDays = (0L..6L).map { offset ->
            val date = weekStart.plusDays(offset)
            when {
                date.isAfter(to) -> HabitDay(date, HabitDayState.UPCOMING, null)
                else -> day(habit, date, to, sourceEvents)
            }
        }
        val currentWeek = weeks.firstOrNull { it.weekStart == weekStart }
            ?: weekOf(habit, weekStart, to, sourceEvents)

        val judged = days.filter { it.state == HabitDayState.ACHIEVED || it.state == HabitDayState.MISSED }
        val achieved = judged.count { it.state == HabitDayState.ACHIEVED }
        val streak = if (habit.periodKind == HabitPeriod.WEEKLY) {
            weeklyStreak(weeks, today = to)
        } else {
            dailyStreak(days, today = to)
        }
        // A weekly habit is judged per week; averaging its days would inflate the rate.
        val judgedWeeks = weeks.filter { it.state == HabitDayState.ACHIEVED || it.state == HabitDayState.MISSED }
        return HabitEvaluation(
            habit = habit,
            days = days,
            weekStart = weekStart,
            weekDays = weekDays,
            week = currentWeek,
            weeks = weeks,
            streak = streak,
            achieved = if (habit.periodKind == HabitPeriod.WEEKLY) judgedWeeks.count { it.state == HabitDayState.ACHIEVED } else achieved,
            evaluated = if (habit.periodKind == HabitPeriod.WEEKLY) judgedWeeks.size else judged.size,
        )
    }

    /** True when a day is eligible for evaluation at all (created and in the active weekday set). */
    fun isEvaluated(habit: HabitEntity, date: LocalDate): Boolean =
        !date.isBefore(habit.start) && habit.isActive(date)

    private fun matches(habit: HabitEntity, event: HabitEvent): Boolean = when (habit.sourceKind) {
        HabitSource.ACTUAL_BY_PROJECT -> event.kind == KIND_RECORD && habit.sourceRef != null && event.projectId == habit.sourceRef
        HabitSource.ACTUAL_BY_TAG -> event.kind == KIND_RECORD && habit.sourceRef != null && habit.sourceRef in event.tagIds
        HabitSource.ACTUAL_BY_ACTIVITY -> event.kind == KIND_RECORD && habit.sourceRef != null && event.activityId == habit.sourceRef
        HabitSource.ACTUAL_BY_CATEGORY -> event.kind == KIND_RECORD && habit.sourceRef != null && event.categoryId == habit.sourceRef
        HabitSource.WORKOUT -> event.kind == KIND_WORKOUT
        HabitSource.SLEEP -> event.kind == KIND_SLEEP
        HabitSource.FOCUS -> event.kind == KIND_FOCUS
        HabitSource.MANUAL_CHECKIN -> event.kind == KIND_CHECKIN && event.habitId == habit.id
    }

    private fun day(
        habit: HabitEntity,
        date: LocalDate,
        today: LocalDate,
        byDate: Map<LocalDate, List<HabitEvent>>,
    ): HabitDay {
        if (date.isBefore(habit.start)) return HabitDay(date, HabitDayState.BEFORE_START, null)
        if (!habit.isActive(date)) return HabitDay(date, HabitDayState.INACTIVE, null)
        val events = byDate[date].orEmpty()
        if (events.isEmpty()) {
            // A manual habit is explicit: no check-in is a real "not done". Derived sources may
            // simply not have synced yet, so they report 无数据 instead of a failure.
            return if (habit.sourceKind == HabitSource.MANUAL_CHECKIN) {
                HabitDay(date, if (date.isAfter(today)) HabitDayState.UPCOMING else HabitDayState.MISSED, 0L)
            } else {
                HabitDay(date, if (date.isAfter(today)) HabitDayState.UPCOMING else HabitDayState.NO_DATA, null)
            }
        }
        val value = valueOf(habit.metricKind, events) ?: return HabitDay(date, HabitDayState.NO_DATA, null, events)
        val state = if (reached(habit.metricKind, value, habit.target)) HabitDayState.ACHIEVED else HabitDayState.MISSED
        return HabitDay(date, state, value, events)
    }

    private fun valueOf(metric: HabitMetric, events: List<HabitEvent>): Long? = when (metric) {
        HabitMetric.DURATION -> events.sumOf { it.minutes.toLong() }
        HabitMetric.COUNT -> events.groupBy { it.id }.values.sumOf { slices -> slices.maxOf { it.count }.toLong() }
        HabitMetric.BEFORE_TIME -> events.mapNotNull { it.minuteOfDay }.minOrNull()?.toLong()
        HabitMetric.AFTER_TIME -> events.mapNotNull { it.endMinuteOfDay ?: it.minuteOfDay }.maxOrNull()?.toLong()
    }

    private fun reached(metric: HabitMetric, value: Long, target: Int): Boolean = when (metric) {
        HabitMetric.DURATION, HabitMetric.COUNT -> value >= target
        HabitMetric.BEFORE_TIME -> value <= target
        HabitMetric.AFTER_TIME -> value >= target
    }

    private fun weeksOf(from: LocalDate, to: LocalDate): List<LocalDate> {
        if (to.isBefore(from)) return emptyList()
        val first = from.with(DayOfWeek.MONDAY)
        val last = to.with(DayOfWeek.MONDAY)
        val count = ChronoUnit.WEEKS.between(first, last).toInt()
        return (0..count).map { first.plusWeeks(it.toLong()) }
    }

    private fun weekOf(
        habit: HabitEntity,
        weekStart: LocalDate,
        today: LocalDate,
        byDate: Map<LocalDate, List<HabitEvent>>,
    ): HabitWeek {
        val days = (0L..6L).map { weekStart.plusDays(it) }
        val evaluated = days.filter { isEvaluated(habit, it) }
        val judged = evaluated.filter { !it.isAfter(today) }
        val events = judged.flatMap { byDate[it].orEmpty() }
        val dataDays = judged.count { byDate[it].orEmpty().isNotEmpty() }
        val closed = weekStart.plusDays(6).isBefore(today)
        if (evaluated.isEmpty()) return HabitWeek(weekStart, null, HabitDayState.INACTIVE)
        if (judged.isEmpty()) return HabitWeek(weekStart, null, HabitDayState.UPCOMING)
        if (dataDays == 0 && habit.sourceKind != HabitSource.MANUAL_CHECKIN) {
            return HabitWeek(weekStart, null, if (closed) HabitDayState.NO_DATA else HabitDayState.UPCOMING)
        }
        return when (habit.metricKind) {
            HabitMetric.DURATION, HabitMetric.COUNT -> {
                val value = valueOf(habit.metricKind, events) ?: 0L
                val state = when {
                    value >= habit.target -> HabitDayState.ACHIEVED
                    closed -> HabitDayState.MISSED
                    else -> HabitDayState.UPCOMING
                }
                HabitWeek(weekStart, value, state, dataDays)
            }
            HabitMetric.BEFORE_TIME, HabitMetric.AFTER_TIME -> {
                val met = judged.count { date ->
                    val dayEvents = byDate[date].orEmpty()
                    val value = valueOf(habit.metricKind, dayEvents) ?: return@count false
                    reached(habit.metricKind, value, habit.target)
                }
                val state = when {
                    dataDays == 0 && habit.sourceKind == HabitSource.MANUAL_CHECKIN -> HabitDayState.MISSED
                    dataDays == 0 -> HabitDayState.NO_DATA
                    met == dataDays -> HabitDayState.ACHIEVED
                    closed -> HabitDayState.MISSED
                    else -> HabitDayState.UPCOMING
                }
                HabitWeek(weekStart, met.toLong(), state, dataDays, met)
            }
        }
    }

    private fun dailyStreak(days: List<HabitDay>, today: LocalDate): Int {
        var streak = 0
        for (day in days.asReversed()) {
            when (day.state) {
                HabitDayState.INACTIVE, HabitDayState.BEFORE_START, HabitDayState.UPCOMING -> continue
                HabitDayState.ACHIEVED -> streak++
                HabitDayState.MISSED -> return streak
                // Today may simply not be recorded yet; a past blank day cannot prove a streak.
                HabitDayState.NO_DATA -> if (day.date == today) continue else return streak
            }
        }
        return streak
    }

    private fun weeklyStreak(weeks: List<HabitWeek>, today: LocalDate): Int {
        var streak = 0
        for (week in weeks.asReversed()) {
            val inProgress = !week.weekStart.plusDays(6).isBefore(today)
            when (week.state) {
                HabitDayState.INACTIVE, HabitDayState.BEFORE_START -> continue
                HabitDayState.ACHIEVED -> streak++
                HabitDayState.UPCOMING -> continue
                HabitDayState.NO_DATA -> if (inProgress) continue else return streak
                HabitDayState.MISSED -> return streak
            }
        }
        return streak
    }

    const val KIND_RECORD = "RECORD"
    const val KIND_SLEEP = "SLEEP"
    const val KIND_FOCUS = "FOCUS"
    const val KIND_WORKOUT = "WORKOUT"
    const val KIND_CHECKIN = "CHECKIN"
}

private fun minutesLabel(minutes: Int): String = if (minutes % 60 == 0) "${minutes / 60}h" else "${minutes / 60}h${minutes % 60}m"
