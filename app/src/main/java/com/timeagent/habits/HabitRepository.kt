package com.timeagent.habits

import android.content.Context
import com.google.gson.Gson
import com.timeagent.core.workout.WorkoutSession
import com.timeagent.core.workout.WorkoutSessionStatus
import com.timeagent.data.ReminderEntity
import com.timeagent.data.StaleChange
import com.timeagent.data.TimeRepository
import com.timeagent.domain.command.CommandResult
import com.timeagent.domain.command.HabitMutation
import com.timeagent.domain.command.WriteHabit
import com.timeagent.domain.command.throwIfRejected
import com.timeagent.focus.FocusSegment
import com.timeagent.focus.FocusSegments
import com.timeagent.reminders.ReminderInstanceRepository
import com.timeagent.workout.data.WorkoutDao
import com.timeagent.workout.data.WorkoutDatabase
import com.timeagent.workout.data.WorkoutSessionRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

data class HabitOption(val id: String, val label: String)

data class HabitOptions(
    val projects: List<HabitOption> = emptyList(),
    val tags: List<HabitOption> = emptyList(),
    val activities: List<HabitOption> = emptyList(),
    val categories: List<HabitOption> = emptyList(),
) {
    fun forSource(source: HabitSource): List<HabitOption> = when (source) {
        HabitSource.ACTUAL_BY_PROJECT -> projects
        HabitSource.ACTUAL_BY_TAG -> tags
        HabitSource.ACTUAL_BY_ACTIVITY -> activities
        HabitSource.ACTUAL_BY_CATEGORY -> categories
        else -> emptyList()
    }

    fun label(source: HabitSource, id: String?): String? =
        forSource(source).firstOrNull { it.id == id }?.label
}

data class HabitSummary(
    val habit: HabitEntity,
    val weekDays: List<HabitDay>,
    val week: HabitWeek,
    val streak: Int,
    val rate: Int,
    val todayState: HabitDayState,
    val weekLabel: String,
)

data class HabitBoard(
    val today: LocalDate,
    val weekStart: LocalDate,
    val active: List<HabitSummary> = emptyList(),
    val archived: List<HabitSummary> = emptyList(),
    val options: HabitOptions = HabitOptions(),
)

data class HabitDetail(
    val habit: HabitEntity,
    val evaluation: HabitEvaluation,
    val summary: HabitSummary,
    val checkins: List<HabitCheckinEntity>,
    val todayCheckin: HabitCheckinEntity?,
)

/**
 * Reads habits and derives every automatic result from the source rows on demand.
 *
 * [applyMutation] is called by CommandExecutor inside `repository.transaction` +
 * `commandWrite`, so request ids, versions, command_log and undo groups stay on the single write
 * path. Save/Archive journal `HABIT`; check-ins journal `HABIT_CHECKIN`. Reminder work is ordered
 * with [TimeRepository.afterCommit] so an undoable write never nests a second command.
 *
 * Optional 21:00 reminders are refreshed on source changes and at midnight. The alarm receiver
 * evaluates the source data again before delivery, so a completed goal never uses a stale result.
 */
class HabitRepository(
    private val repository: TimeRepository,
    private val context: Context? = null,
) {
    private val dao get() = repository.database.habitDao()
    private val gson = Gson()

    companion object {
        const val REMINDER_HOUR = 21
        val TABLES = arrayOf(
            "habits", "habit_checkins", "time_records", "record_tags", "tasks", "task_placements",
            "health_sync_items", "focus_sessions", "activity_templates", "projects", "tags", "categories", "entry_contexts", "sample_records",
        )
    }

    // ---- Observation -----------------------------------------------------------------------------

    fun observeBoard(): Flow<HabitBoard> {
        val changes = repository.database.invalidationTracker.createFlow(*TABLES)
        val workouts = workoutDao()?.observeSessions()
            ?.map { rows -> rows.mapNotNull(::decodeWorkout) }
            ?: flowOf(emptyList<WorkoutSession>())
        return combine(changes, workouts) { _, sessions -> loadBoard(sessions) }.flowOn(Dispatchers.IO)
    }

    suspend fun board(): HabitBoard = loadBoard(workoutRows())

    suspend fun detail(id: String, weeks: Int = 12): HabitDetail? {
        val sessions = workoutRows()
        return repository.transaction {
            val habit = dao.habit(id) ?: return@transaction null
            val today = todayDate()
            val events = loadEvents(sessions, minOf(habit.start, today.minusDays(weeks * 7L + 6)))
            val summary = summarize(habit, events, today)
            val evaluation = HabitStatistics.evaluate(habit, events, today.minusDays(weeks * 7L - 1), today)
                .copy(streak = summary.streak)
            HabitDetail(
                habit = habit,
                evaluation = evaluation,
                summary = summary,
                checkins = dao.checkinsFor(id),
                todayCheckin = dao.checkin(id, today.toString()),
            )
        }
    }

    suspend fun options(): HabitOptions = repository.transaction { loadOptions() }

    private suspend fun loadBoard(sessions: List<WorkoutSession>): HabitBoard = repository.transaction {
        val today = todayDate()
        val habits = dao.habits()
        val events = loadEvents(sessions, minOf(habits.minOfOrNull { it.start } ?: today, today.minusDays(36)))
        val summaries = habits.map { summarize(it, events, today) }
        HabitBoard(
            today = today,
            weekStart = today.with(DayOfWeek.MONDAY),
            active = summaries.filterNot { it.habit.archived }.sortedWith(
                compareBy({ it.habit.sortOrder }, { it.habit.createdAt }),
            ),
            archived = summaries.filter { it.habit.archived }.sortedByDescending { it.habit.archivedAt },
            options = loadOptions(),
        )
    }

    private suspend fun summarize(habit: HabitEntity, events: List<HabitEvent>, today: LocalDate): HabitSummary {
        val evaluation = HabitStatistics.evaluate(habit, events, today.minusDays(29), today)
        return HabitSummary(
            habit = habit,
            weekDays = evaluation.weekDays,
            week = evaluation.week,
            streak = HabitStatistics.evaluate(habit, events, minOf(habit.start, today), today).streak,
            rate = evaluation.rate,
            todayState = evaluation.todayState,
            weekLabel = evaluation.weekLabel,
        )
    }

    private suspend fun loadOptions(): HabitOptions = HabitOptions(
        projects = dao.projects().map { HabitOption(it.id, it.name) },
        tags = dao.tags().map { HabitOption(it.id, it.name) },
        activities = dao.activities().map { HabitOption(it.id, it.name) },
        categories = dao.categories().map { HabitOption(it.id, it.name) },
    )

    // ---- Command entry point ---------------------------------------------------------------------

    suspend fun applyMutation(mutation: HabitMutation): Any? = when (mutation) {
        is HabitMutation.Save -> saveRaw(mutation.habit, mutation.expectedVersion)
        is HabitMutation.Archive -> archiveRaw(mutation.id, mutation.archived, mutation.expectedVersion)
        is HabitMutation.Checkin -> checkinRaw(
            mutation.habitId, mutation.date, mutation.value, mutation.note, mutation.expectedVersion,
        )
        is HabitMutation.ClearCheckin -> { clearCheckinRaw(mutation.habitId, mutation.date, mutation.expectedVersion); null }
    }

    // ---- Public convenience API ------------------------------------------------------------------

    suspend fun save(
        habit: HabitEntity,
        expectedVersion: Long? = null,
        requestId: String = "habit-save:${habit.id}:${UUID.randomUUID()}",
    ): HabitEntity = gson.fromJson(
        requireNotNull(apply(WriteHabit(requestId, HabitMutation.Save(habit, expectedVersion))).payload) {
            "习惯已保存，但返回内容为空"
        },
        HabitEntity::class.java,
    )

    suspend fun archive(
        id: String,
        archived: Boolean,
        expectedVersion: Long? = null,
        requestId: String = "habit-archive:$id:$archived:${UUID.randomUUID()}",
    ): HabitEntity = gson.fromJson(
        requireNotNull(apply(WriteHabit(requestId, HabitMutation.Archive(id, archived, expectedVersion))).payload) {
            "习惯已归档，但返回内容为空"
        },
        HabitEntity::class.java,
    )

    suspend fun checkin(
        habitId: String,
        date: LocalDate,
        value: Int,
        note: String = "",
        expectedVersion: Long? = null,
        requestId: String = "habit-checkin:$habitId:$date:$value:${UUID.randomUUID()}",
    ): HabitCheckinEntity = gson.fromJson(
        requireNotNull(apply(WriteHabit(requestId, HabitMutation.Checkin(habitId, date.toString(), value, note, expectedVersion))).payload) {
            "打卡已保存，但返回内容为空"
        },
        HabitCheckinEntity::class.java,
    )

    suspend fun clearCheckin(
        habitId: String,
        date: LocalDate,
        requestId: String = "habit-uncheck:$habitId:$date:${UUID.randomUUID()}",
        expectedVersion: Long? = null,
    ) {
        apply(WriteHabit(requestId, HabitMutation.ClearCheckin(habitId, date.toString(), expectedVersion)))
    }

    /** Refreshes the next eligible evening without storing derived habit results. */
    suspend fun syncReminderForToday(habitId: String) {
        val habit = repository.transaction { dao.habit(habitId) } ?: return
        val today = todayDate()
        val sessions = workoutRows()
        val events = repository.transaction { loadEvents(sessions, today.with(DayOfWeek.MONDAY)) }
        val evaluation = HabitStatistics.evaluate(habit, events, today, today)
        val achieved = if (habit.periodKind == HabitPeriod.WEEKLY) evaluation.week.state == HabitDayState.ACHIEVED
            else evaluation.todayState == HabitDayState.ACHIEVED
        applyReminder(habit, achieved)
    }

    suspend fun shouldDeliverReminder(habitId: String, fireAt: Long): Boolean {
        val habit = dao.habit(habitId) ?: return false
        val date = Instant.ofEpochMilli(fireAt).atZone(repository.zone).toLocalDate()
        if (habit.archived || !habit.reminderEnabled || date != todayDate() ||
            !HabitStatistics.isEvaluated(habit, date)) return false
        val events = loadEvents(workoutRows(), date.with(DayOfWeek.MONDAY))
        val evaluation = HabitStatistics.evaluate(habit, events, date, date)
        return if (habit.periodKind == HabitPeriod.WEEKLY) evaluation.week.state != HabitDayState.ACHIEVED
            else evaluation.todayState != HabitDayState.ACHIEVED
    }

    // ---- Raw writes (inside CommandExecutor's transaction) ---------------------------------------

    private suspend fun saveRaw(habit: HabitEntity, expectedVersion: Long?): HabitEntity {
        val before = dao.habit(habit.id)
        requireVersion(before?.version, expectedVersion)
        if (before?.archivedAt != null) throw IllegalArgumentException("习惯已归档，请先恢复后再编辑")
        val normalized = normalize(habit)
        val stamp = repository.now().toEpochMilli()
        val saved = normalized.copy(
            archivedAt = before?.archivedAt,
            sortOrder = before?.sortOrder ?: normalized.sortOrder,
            createdAt = before?.createdAt ?: stamp,
            updatedAt = stamp,
            version = repository.nextVersion("HABIT", habit.id, before?.version),
        )
        dao.putHabit(saved)
        repository.journal("HABIT", saved.id, before, saved, if (before == null) "新增习惯" else "编辑习惯")
        requestReminderRefresh()
        return saved
    }

    private suspend fun archiveRaw(id: String, archived: Boolean, expectedVersion: Long?): HabitEntity {
        val before = requireNotNull(dao.habit(id)) { "习惯不存在" }
        requireVersion(before.version, expectedVersion)
        if (before.archived == archived) return before
        val stamp = repository.now().toEpochMilli()
        val saved = before.copy(
            archivedAt = if (archived) stamp else null,
            updatedAt = stamp,
            version = repository.nextVersion("HABIT", id, before.version),
        )
        dao.putHabit(saved)
        repository.journal("HABIT", id, before, saved, if (archived) "归档习惯" else "恢复习惯")
        requestReminderRefresh()
        return saved
    }

    private suspend fun checkinRaw(
        habitId: String,
        dateText: String,
        value: Int,
        note: String,
        expectedVersion: Long?,
    ): HabitCheckinEntity {
        val habit = requireNotNull(dao.habit(habitId)) { "习惯不存在" }
        require(!habit.archived) { "习惯已归档，请先恢复后再打卡" }
        require(habit.sourceKind == HabitSource.MANUAL_CHECKIN) { "此习惯按实际数据自动统计，不能手动打卡" }
        val date = runCatching { LocalDate.parse(dateText) }.getOrElse { throw IllegalArgumentException("打卡日期无效") }
        val today = todayDate()
        require(!date.isAfter(today)) { "不能为未来日期打卡" }
        require(!date.isBefore(habit.start)) { "习惯开始前的日期不统计" }
        require(habit.isActive(date)) { "该习惯在所选星期不统计" }
        require(note.length <= 500) { "备注最多500字" }
        validateTargetValue(habit.metricKind, value)
        val before = dao.checkin(habitId, date.toString())
        requireVersion(before?.version, expectedVersion)
        val stamp = repository.now().toEpochMilli()
        val saved = HabitCheckinEntity(
            habitId = habitId,
            date = date.toString(),
            value = value,
            note = note.trim(),
            createdAt = before?.createdAt ?: stamp,
            updatedAt = stamp,
            version = repository.nextVersion("HABIT_CHECKIN", "$habitId:$date", before?.version),
        )
        dao.putCheckin(saved)
        repository.journal("HABIT_CHECKIN", "$habitId:$date", before, saved, "习惯打卡")
        requestReminderRefresh()
        return saved
    }

    private suspend fun clearCheckinRaw(habitId: String, dateText: String, expectedVersion: Long?) {
        val habit = requireNotNull(dao.habit(habitId)) { "习惯不存在" }
        val before = dao.checkin(habitId, dateText) ?: return
        requireVersion(before.version, expectedVersion)
        repository.nextVersion("HABIT_CHECKIN", "$habitId:$dateText", before.version)
        dao.removeCheckin(habitId, dateText)
        repository.journal("HABIT_CHECKIN", "$habitId:$dateText", before, null, "取消习惯打卡")
        requestReminderRefresh()
    }

    // ---- Validation ------------------------------------------------------------------------------

    private suspend fun normalize(habit: HabitEntity): HabitEntity {
        require(habit.id.isNotBlank()) { "习惯标识不能为空" }
        val name = habit.name.trim()
        require(name.length in 1..60) { "请填写习惯名称，最多60字" }
        val metric = HabitMetric.values().firstOrNull { it.name == habit.metric }
            ?: throw IllegalArgumentException("习惯统计方式无效")
        val source = HabitSource.values().firstOrNull { it.name == habit.source }
            ?: throw IllegalArgumentException("习惯数据来源无效")
        val period = HabitPeriod.values().firstOrNull { it.name == habit.period }
            ?: throw IllegalArgumentException("习惯周期无效")
        when (metric) {
            HabitMetric.DURATION -> require(habit.target > 0) { "目标分钟数须大于零" }
            HabitMetric.COUNT -> require(habit.target > 0) { "目标次数须大于零" }
            HabitMetric.BEFORE_TIME, HabitMetric.AFTER_TIME -> require(habit.target in 0..1439) { "目标时刻无效" }
        }
        val startDate = runCatching { LocalDate.parse(habit.startDate) }.getOrElse {
            throw IllegalArgumentException("开始日期无效")
        }
        val tokens = habit.activeWeekdays.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        require(tokens.all { token -> token.toIntOrNull()?.let { it in 1..7 } == true }) { "请选择有效的重复星期" }
        val weekdays = tokens.map { it.toInt() }.distinct().sorted().joinToString(",")
            .ifBlank { HabitEntity.ALL_WEEKDAYS }
        val ref = if (source.needsSourceRef) {
            val id = habit.sourceRef?.trim().orEmpty()
            require(id.isNotEmpty()) { "请选择习惯统计的${sourceLabel(source)}" }
            val exists = when (source) {
                HabitSource.ACTUAL_BY_PROJECT -> dao.projects().any { it.id == id }
                HabitSource.ACTUAL_BY_TAG -> dao.tags().any { it.id == id }
                HabitSource.ACTUAL_BY_ACTIVITY -> dao.activities().any { it.id == id }
                HabitSource.ACTUAL_BY_CATEGORY -> dao.categories().any { it.id == id }
                else -> false
            }
            require(exists) { "所选${sourceLabel(source)}不存在、已归档或已删除" }
            id
        } else null
        return habit.copy(
            name = name,
            metric = metric.name,
            source = source.name,
            sourceRef = ref,
            period = period.name,
            activeWeekdays = weekdays,
            startDate = startDate.toString(),
            icon = habit.icon.trim().ifBlank { "check" }.take(24),
        )
    }

    private fun sourceLabel(source: HabitSource): String = when (source) {
        HabitSource.ACTUAL_BY_PROJECT -> "项目"
        HabitSource.ACTUAL_BY_TAG -> "标签"
        HabitSource.ACTUAL_BY_ACTIVITY -> "活动"
        HabitSource.ACTUAL_BY_CATEGORY -> "分类"
        HabitSource.WORKOUT -> "训练"
        HabitSource.SLEEP -> "睡眠"
        HabitSource.FOCUS -> "专注"
        HabitSource.MANUAL_CHECKIN -> "手动打卡"
    }

    private fun validateTargetValue(metric: HabitMetric, value: Int) {
        when (metric) {
            HabitMetric.DURATION -> require(value in 1..1440) { "今天完成的分钟数须为1至1440" }
            HabitMetric.COUNT -> require(value > 0) { "今天完成的次数须大于零" }
            HabitMetric.BEFORE_TIME, HabitMetric.AFTER_TIME -> require(value in 0..1439) { "请填写有效时刻" }
        }
    }

    private fun reachedTarget(metric: HabitMetric, value: Int, target: Int): Boolean = when (metric) {
        HabitMetric.DURATION, HabitMetric.COUNT -> value >= target
        HabitMetric.BEFORE_TIME -> value <= target
        HabitMetric.AFTER_TIME -> value >= target
    }

    private fun requireVersion(current: Long?, expected: Long?) {
        if (current != expected) throw StaleChange()
    }

    // ---- Reminder (N07, ownerType=HABIT) ---------------------------------------------------------

    private suspend fun requestReminderRefresh() {
        context?.let { app -> repository.afterCommit { HabitReminderRefresh.request(app) } }
    }

    private suspend fun applyReminder(habit: HabitEntity, achievedToday: Boolean) {
        val id = reminderId(habit.id)
        val existing = repository.database.dao().reminder(id)
        if (!habit.reminderEnabled || habit.archived) {
            removeReminder(habit.id)
            return
        }
        if (existing == null && repository.database.dao().revision("REMINDER:$id") != null) return
        if (!achievedToday && existing?.deliveredAt != null &&
            Instant.ofEpochMilli(existing.remindAt).atZone(repository.zone).toLocalDate() == todayDate()) return
        val fireAt = nextReminderAt(habit, achievedToday)
        if (existing?.remindAt == fireAt && existing.title == "习惯「${habit.name}」今天还没达成".take(200) &&
            existing.enabled && existing.ownerType == "HABIT" && existing.ownerId == habit.id) return
        val stamp = repository.now().toEpochMilli()
        val item = ReminderEntity(
            id = id,
            title = "习惯「${habit.name}」今天还没达成".take(200),
            remindAt = fireAt,
            taskId = null,
            enabled = true,
            ownerType = "HABIT",
            ownerId = habit.id,
            createdAt = existing?.createdAt ?: stamp,
            updatedAt = stamp,
        )
        ReminderInstanceRepository(repository).save(item, existing?.version, automatic = true)
    }

    private suspend fun removeReminder(habitId: String) {
        val daos = repository.database.dao()
        val existing = daos.reminder(reminderId(habitId)) ?: return
        if (existing.enabled) ReminderInstanceRepository(repository)
            .save(existing.copy(enabled = false), existing.version, automatic = true)
    }

    private fun reminderId(habitId: String) = "habit-reminder:$habitId"

    private fun nextReminderAt(habit: HabitEntity, achievedToday: Boolean): Long {
        val now = repository.now().atZone(repository.zone)
        var date = maxOf(now.toLocalDate(), habit.start)
        if (date == now.toLocalDate() &&
            (achievedToday || !now.toLocalTime().isBefore(LocalTime.of(REMINDER_HOUR, 0)))) {
            date = date.plusDays(1)
        }
        while (!habit.isActive(date)) date = date.plusDays(1)
        return date.atTime(REMINDER_HOUR, 0).atZone(repository.zone).toInstant().toEpochMilli()
    }

    // ---- Derived events --------------------------------------------------------------------------

    private suspend fun loadEvents(sessions: List<WorkoutSession>, from: LocalDate): List<HabitEvent> {
        val zone = repository.zone
        val tasks = dao.tasks().associateBy { it.id }
        val placements = dao.placements().associateBy { it.taskId }
        val tags = dao.recordTags().groupBy({ it.recordId }, { it.tagId })
        val sleepIds = dao.sleepLinks().mapNotNull { it.recordId }.toSet()
        val contexts = repository.database.entryContextDao().all().filter { it.entityType == "RECORD" }.associateBy { it.entityId }
        val fromMillis = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val events = mutableListOf<HabitEvent>()

        val focusRecords = dao.focusSessions().associateBy { "focus:${it.id}" }
        val sampleIds = repository.database.sampleDataDao().all().map { it.entityId }.toSet()
        dao.finishedRecords().filterNot { it.id in sampleIds }.flatMap { record ->
            val session = focusRecords[record.id]
            if (session == null) listOf(record) else FocusSegments.decode(session.segmentsJson).map { segment ->
                record.copy(startAt = segment.startAt, endAt = segment.startAt + segment.elapsedMs)
            }
        }.forEach { record ->
            val end = record.endAt ?: return@forEach
            if (end < fromMillis) return@forEach
            val title = record.title.ifBlank { tasks[record.taskId]?.title ?: "实际记录" }
            if (isSleep(record.id, record.title, record.notes, sleepIds)) {
                // A night belongs to the evening it started; 00:30 still counts for yesterday.
                events += HabitEvent(
                    date = nightOf(record.startAt, zone),
                    kind = HabitStatistics.KIND_SLEEP,
                    id = record.id,
                    title = title,
                    minutes = minutesBetween(record.startAt, end),
                    count = 1,
                    minuteOfDay = minuteOfDay(record.startAt, zone),
                    endMinuteOfDay = minuteOfDay(end, zone),
                    startAt = record.startAt,
                    endAt = end,
                )
                return@forEach
            }
            val slices = splitByDay(record.startAt, end, zone)
            slices.forEachIndexed { index, slice ->
                events += HabitEvent(
                    date = slice.date,
                    kind = HabitStatistics.KIND_RECORD,
                    id = record.id,
                    title = title,
                    minutes = minutesBetween(slice.startAt, slice.endAt),
                    count = if (index == 0) 1 else 0,
                    minuteOfDay = if (index == 0) minuteOfDay(record.startAt, zone) else null,
                    endMinuteOfDay = if (index == slices.lastIndex) minuteOfDay(end, zone) else null,
                    startAt = slice.startAt,
                    endAt = slice.endAt,
                    projectId = contexts[record.id]?.projectId ?: record.taskId?.let { placements[it]?.projectId },
                    activityId = contexts[record.id]?.activityId ?: record.taskId?.let { tasks[it]?.activityId },
                    categoryId = record.categoryId,
                    tagIds = (tags[record.id].orEmpty() + contexts[record.id]?.tagIds.orEmpty().split(',').filter(String::isNotBlank)).toSet(),
                )
            }
        }

        dao.focusSessions().forEach { session ->
            val segments = FocusSegments.decode(session.segmentsJson).ifEmpty {
                if (session.focusedElapsedMs > 0) {
                    listOf(FocusSegment(session.startedAt, session.startedAt + session.focusedElapsedMs, session.focusedElapsedMs, session.roundIndex))
                } else emptyList()
            }
            val counted = mutableSetOf<LocalDate>()
            segments.forEach { segment ->
                val slices = splitByDay(segment.startAt, maxOf(segment.startAt + 1, segment.endAt), zone)
                val wall = (segment.endAt - segment.startAt).coerceAtLeast(1L)
                slices.forEach { slice ->
                    // Only focused segments exist here; rest and pause are already excluded.
                    val share = segment.elapsedMs * (slice.endAt - slice.startAt) / wall
                    val count = if (counted.add(slice.date)) 1 else 0
                    events += HabitEvent(
                        date = slice.date,
                        kind = HabitStatistics.KIND_FOCUS,
                        id = session.id,
                        title = session.label.ifBlank { "专注" },
                        minutes = (share / 60_000L).toInt(),
                        count = count,
                        minuteOfDay = if (count == 1) minuteOfDay(segment.startAt, zone) else null,
                        endMinuteOfDay = minuteOfDay(segment.endAt, zone),
                        startAt = segment.startAt,
                        endAt = segment.endAt,
                    )
                }
            }
        }

        sessions.filter { it.status == WorkoutSessionStatus.COMPLETED && it.endedAt != null }.forEach { session ->
            val end = requireNotNull(session.endedAt)
            if (end < fromMillis) return@forEach
            val active = (end - session.startedAt - session.totalPausedMillis).coerceAtLeast(0L)
            val slices = splitByDay(session.startedAt, maxOf(session.startedAt + 1, end), zone)
            val wall = (end - session.startedAt).coerceAtLeast(1L)
            slices.forEachIndexed { index, slice ->
                val share = if (slices.size == 1) active else active * (slice.endAt - slice.startAt) / wall
                events += HabitEvent(
                    date = slice.date,
                    kind = HabitStatistics.KIND_WORKOUT,
                    id = session.id,
                    title = session.title.ifBlank { "训练" },
                    minutes = (share / 60_000L).toInt(),
                    count = if (index == 0) 1 else 0,
                    minuteOfDay = if (index == 0) minuteOfDay(session.startedAt, zone) else null,
                    endMinuteOfDay = if (index == slices.lastIndex) minuteOfDay(end, zone) else null,
                    startAt = session.startedAt,
                    endAt = end,
                )
            }
        }

        dao.checkins().forEach { checkin ->
            events += HabitEvent(
                date = checkin.day,
                kind = HabitStatistics.KIND_CHECKIN,
                id = "${checkin.habitId}:${checkin.date}",
                title = "手动打卡",
                minutes = checkin.value,
                count = checkin.value,
                minuteOfDay = checkin.value,
                endMinuteOfDay = checkin.value,
                note = checkin.note,
                habitId = checkin.habitId,
            )
        }
        return events
    }

    private fun isSleep(id: String, title: String, notes: String, links: Set<String>): Boolean {
        if (id in links) return true
        val text = "$title $notes"
        return text.contains("睡眠") || text.contains("睡觉") || text.contains("sleep", ignoreCase = true)
    }

    private fun nightOf(startAt: Long, zone: ZoneId): LocalDate {
        val local = Instant.ofEpochMilli(startAt).atZone(zone)
        return if (local.toLocalTime().isBefore(LocalTime.NOON)) local.toLocalDate().minusDays(1) else local.toLocalDate()
    }

    private fun minuteOfDay(at: Long, zone: ZoneId): Int =
        Instant.ofEpochMilli(at).atZone(zone).toLocalTime().toSecondOfDay() / 60

    private fun minutesBetween(start: Long, end: Long): Int = ((end - start).coerceAtLeast(0L) / 60_000L).toInt()

    private data class DaySlice(val date: LocalDate, val startAt: Long, val endAt: Long)

    private fun splitByDay(startAt: Long, endAt: Long, zone: ZoneId): List<DaySlice> {
        if (endAt <= startAt) return emptyList()
        val out = mutableListOf<DaySlice>()
        var cursor = startAt
        while (cursor < endAt) {
            val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val sliceEnd = minOf(dayEnd, endAt)
            if (sliceEnd <= cursor) break
            out += DaySlice(date, cursor, sliceEnd)
            cursor = sliceEnd
        }
        return out
    }

    // ---- Workout access --------------------------------------------------------------------------

    private fun workoutDao(): WorkoutDao? =
        repository.workoutDatabase?.dao() ?: context?.let { WorkoutDatabase.open(it).dao() }

    private suspend fun workoutRows(): List<WorkoutSession> =
        workoutDao()?.sessions().orEmpty().map(::decodeWorkout)

    private fun decodeWorkout(row: WorkoutSessionRow): WorkoutSession =
        gson.fromJson(row.payload, WorkoutSession::class.java)

    private fun todayDate(): LocalDate = habitToday(repository.zone, repository.now().toEpochMilli())

    internal suspend fun apply(command: WriteHabit): CommandResult {
        val result = repository.commands.executeForRepository(command)
        result.throwIfRejected()
        return result
    }

    internal suspend fun undo(token: String) {
        repository.commands.executeForRepository(com.timeagent.domain.command.UndoCommand(
            "habit-undo:${UUID.randomUUID()}", token)).throwIfRejected()
        requestReminderRefresh()
    }
}
