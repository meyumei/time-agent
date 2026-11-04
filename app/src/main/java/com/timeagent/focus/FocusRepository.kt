package com.timeagent.focus

import android.content.Context
import android.os.SystemClock
import com.google.gson.Gson
import com.timeagent.core.workout.WorkoutSession
import com.timeagent.data.StaleChange
import com.timeagent.data.TimeRecordEntity
import com.timeagent.data.TimeRepository
import com.timeagent.domain.command.FocusMode
import com.timeagent.domain.command.FocusMutation
import com.timeagent.domain.command.Origin
import com.timeagent.domain.command.WriteFocus
import com.timeagent.domain.command.WorkoutMutation
import com.timeagent.domain.command.throwIfRejected
import com.timeagent.workout.data.WorkoutRepository
import com.timeagent.workout.data.WorkoutSessionRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import java.time.ZoneId
import java.util.UUID

/** A visible tenant of the single main-timer slot, used by the conflict confirmation sheet. */
data class FocusSlot(val kind: String, val id: String, val version: Long, val label: String)

/**
 * Applies focus mutations inside CommandExecutor's existing main transaction and commandWrite
 * block. Public methods execute WriteFocus so request ids, command_log, versions and journal
 * groups are supplied by the one shared write path.
 *
 * `context` is used for bootCount and for creating a WorkoutRepository when a confirmed switch
 * must end training. CommandExecutor should construct this with the application context.
 */
class FocusRepository(
    private val repository: TimeRepository,
    private val context: Context? = null,
) {
    private val dao = repository.database.focusDao()
    private val gson = Gson()

    fun observeActive(): Flow<FocusSessionEntity?> = dao.observeActive()
    fun observeLatest(): Flow<FocusSessionEntity?> = dao.observeLatest()

    suspend fun active(): FocusSessionEntity? = dao.active()

    suspend fun recent(limit: Int = 20): List<FocusSessionEntity> = dao.recent(limit)

    suspend fun slots(): List<FocusSlot> = buildList {
        repository.database.dao().runningTimer()?.let {
            add(FocusSlot("TIMER", it.id, it.version, it.title.ifBlank { "自由计时" }))
        }
        repository.workoutDatabase?.dao()?.activeSession()?.let { row ->
            val session = decodeWorkout(row)
            add(FocusSlot("WORKOUT", row.id, session?.updatedAt ?: 0L, session?.title ?: "训练"))
        }
        dao.active()?.let {
            add(FocusSlot("FOCUS", it.id, it.version, it.label))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Public command API
    // ---------------------------------------------------------------------------------------------

    suspend fun start(
        label: String,
        id: String = UUID.randomUUID().toString(),
        mode: FocusMode = FocusMode.POMODORO,
        focusMinutes: Int = 25,
        breakMinutes: Int = 5,
        longBreakMinutes: Int = 15,
        rounds: Int = 4,
        taskId: String? = null,
        scheduleBlockId: String? = null,
        switchTimerId: String? = null,
        expectedTimerVersion: Long? = null,
        switchWorkoutId: String? = null,
        expectedWorkoutVersion: Long? = null,
        requestId: String = UUID.randomUUID().toString(),
        origin: Origin = Origin.UI,
        longBreakEvery: Int = 4,
    ): FocusSessionEntity = execute(
        WriteFocus(
            requestId,
            FocusMutation.Start(
                id = id, label = label, mode = mode, focusMinutes = focusMinutes,
                breakMinutes = breakMinutes, longBreakMinutes = longBreakMinutes, rounds = rounds,
                taskId = taskId, scheduleBlockId = scheduleBlockId,
                switchTimerId = switchTimerId, expectedTimerVersion = expectedTimerVersion,
                switchWorkoutId = switchWorkoutId, expectedWorkoutVersion = expectedWorkoutVersion,
                longBreakEvery = longBreakEvery,
            ),
            origin,
        )
    )

    suspend fun pause(
        id: String,
        expectedVersion: Long,
        requestId: String = UUID.randomUUID().toString(),
        origin: Origin = Origin.UI,
    ): FocusSessionEntity = execute(WriteFocus(requestId, FocusMutation.Pause(id, expectedVersion), origin))

    suspend fun resume(
        id: String,
        expectedVersion: Long,
        requestId: String = UUID.randomUUID().toString(),
        origin: Origin = Origin.UI,
    ): FocusSessionEntity = execute(WriteFocus(requestId, FocusMutation.Resume(id, expectedVersion), origin))

    suspend fun finish(
        id: String,
        expectedVersion: Long,
        save: Boolean = true,
        requestId: String = UUID.randomUUID().toString(),
        origin: Origin = Origin.UI,
    ): FocusSessionEntity = execute(WriteFocus(requestId, FocusMutation.Finish(id, expectedVersion, save), origin))

    suspend fun skipBreak(
        id: String,
        expectedVersion: Long,
        requestId: String = UUID.randomUUID().toString(),
        origin: Origin = Origin.UI,
    ): FocusSessionEntity = execute(WriteFocus(requestId, FocusMutation.SkipBreak(id, expectedVersion), origin))

    suspend fun tick(
        id: String,
        expectedVersion: Long,
        requestId: String = "focus-tick:$id:$expectedVersion:${SystemClock.elapsedRealtime()}",
        origin: Origin = Origin.SYSTEM,
    ): FocusSessionEntity = execute(WriteFocus(requestId, FocusMutation.Tick(id, expectedVersion), origin))

    /** Reconciles boot/time changes and pause timeout without creating an undo entry. */
    suspend fun reconcile(): FocusSessionEntity? {
        val session = dao.active() ?: return null
        return try {
            tick(session.id, session.version, requestId = "focus-reconcile:${session.id}:${session.version}:${SystemClock.elapsedRealtime()}")
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: StaleChange) {
            dao.active()
        }
    }

    private suspend fun execute(command: WriteFocus): FocusSessionEntity {
        val result = repository.commands.executeForRepository(command)
        result.throwIfRejected()
        return gson.fromJson(requireNotNull(result.payload) { "专注结果缺失" }, FocusSessionEntity::class.java)
    }

    // ---------------------------------------------------------------------------------------------
    // CommandExecutor callback
    // ---------------------------------------------------------------------------------------------

    internal suspend fun applyMutation(
        mutation: FocusMutation,
        requestId: String = mutation.id,
    ): FocusSessionEntity = when (mutation) {
        is FocusMutation.Start -> startMutation(mutation, requestId)
        is FocusMutation.Pause -> pauseMutation(mutation)
        is FocusMutation.Resume -> resumeMutation(mutation)
        is FocusMutation.Finish -> finishMutation(mutation)
        is FocusMutation.SkipBreak -> skipBreakMutation(mutation)
        is FocusMutation.Tick -> tickMutation(mutation)
        is FocusMutation.AdjustSegment -> adjustSegment(mutation)
    }

    private suspend fun adjustSegment(mutation: FocusMutation.AdjustSegment): FocusSessionEntity {
        val before = requireActive(mutation.id, mutation.expectedVersion)
        require(!before.isActive && before.actualRecordId != null) { "请先结束并保存专注，再调整实际记录" }
        require(mutation.endAt > mutation.startAt && mutation.endAt <= wallNow()) { "实际片段须有正时长，不能延伸到未来" }
        val segments = FocusSegments.decode(before.segmentsJson).toMutableList()
        val segment = segments.getOrNull(mutation.segmentIndex) ?: throw StaleChange()
        require(segments.withIndex().none { (index, other) ->
            index != mutation.segmentIndex && mutation.startAt < other.startAt + other.elapsedMs && mutation.endAt > other.startAt
        }) { "同一次专注的片段不能互相重叠，请调整边界" }
        segments[mutation.segmentIndex] = segment.copy(startAt = mutation.startAt, endAt = mutation.endAt,
            elapsedMs = mutation.endAt - mutation.startAt)
        val next = segments.sortedBy { it.startAt }
        val recordDao = repository.database.dao()
        val record = requireNotNull(recordDao.record(requireNotNull(before.actualRecordId))) { "专注实际记录已删除" }
        repository.saveRecordRaw(record.copy(startAt = next.first().startAt, endAt = next.last().endAt,
            title = mutation.title ?: record.title, notes = mutation.notes ?: record.notes, isManuallyEdited = true),
            expectedVersion = record.version)
        return persist(before, before.copy(segmentsJson = FocusSegments.encode(next),
            focusedElapsedMs = next.sumOf { it.elapsedMs }, label = mutation.title ?: before.label),
            wallNow(), FocusClock.nowElapsed(), "调整专注实际片段", true)
    }

    // ---------------------------------------------------------------------------------------------
    // Start and the single main-timer slot
    // ---------------------------------------------------------------------------------------------

    private suspend fun startMutation(mutation: FocusMutation.Start, requestId: String): FocusSessionEntity {
        require(requestId.isNotBlank() && mutation.id.isNotBlank()) { "专注标识不能为空" }
        val label = mutation.label.trim()
        require(label.isNotEmpty() && label.length <= 200) { "请填写专注名称，最多200字" }
        require(mutation.focusMinutes > 0) { "专注时长须大于零" }
        require(mutation.breakMinutes >= 0 && mutation.longBreakMinutes >= 0) { "休息时长不能为负数" }
        require(mutation.rounds > 0 && mutation.longBreakEvery > 0) { "轮数与长休息间隔须大于零" }

        val existing = dao.session(mutation.id)
        if (existing != null) {
            if (existing.isActive && sameStart(existing, mutation, label)) return existing
            throw StaleChange()
        }
        dao.byRequestId(requestId)?.let { if (it.id != mutation.id) throw StaleChange() }
        dao.active()?.let { throw StaleChange() } // An existing focus is never silently replaced.

        val task = mutation.taskId?.let { repository.database.dao().task(it) }
        if (mutation.taskId != null) require(task != null && task.deletedAt == null) { "关联任务不存在或已删除" }
        mutation.scheduleBlockId?.let {
            val plan = repository.database.dao().plan(it)
            require(plan != null && plan.deletedAt == null) { "关联日程不存在或已删除" }
        }

        val running = repository.database.dao().runningTimer()
        val workoutRow = repository.workoutDatabase?.dao()?.activeSession()
        val workout = workoutRow?.let(::decodeWorkout)
        if (workoutRow != null && workout == null) throw StaleChange()
        if (running != null && (mutation.switchTimerId != running.id || mutation.expectedTimerVersion != running.version)) {
            throw StaleChange()
        }
        if (running == null && mutation.switchTimerId != null) throw StaleChange()
        if (workout != null && (mutation.switchWorkoutId != workout.id || mutation.expectedWorkoutVersion != workout.updatedAt)) {
            throw StaleChange()
        }
        if (workout == null && mutation.switchWorkoutId != null) throw StaleChange()

        // All confirmations are checked before the first write, so a stale confirmation leaves
        // both the running timer and the workout untouched.
        running?.let { repository.stopTimerRaw(it.id, it.version) }
        workout?.let { finishSwitchedWorkout(it) }

        val pomodoro = mutation.mode == FocusMode.POMODORO
        val nowWall = wallNow()
        val nowElapsed = FocusClock.nowElapsed()
        val boot = FocusClock.bootCount(context)
        val session = FocusSessionEntity(
            id = mutation.id,
            requestId = requestId,
            taskId = mutation.taskId,
            scheduleBlockId = mutation.scheduleBlockId,
            label = label,
            mode = mutation.mode.name,
            plannedFocusMinutes = mutation.focusMinutes,
            plannedBreakMinutes = if (pomodoro) mutation.breakMinutes else 0,
            longBreakMinutes = if (pomodoro) mutation.longBreakMinutes else 0,
            roundsPlanned = if (pomodoro) mutation.rounds else 1,
            roundIndex = 1,
            state = FocusSessionEntity.STATE_FOCUSING,
            startedAt = nowWall,
            pausedAccumulatedMs = 0,
            lastResumedAt = null,
            endedAt = null,
            actualRecordId = null,
            timezone = repository.zone.id,
            version = repository.nextVersion("FOCUS", mutation.id),
            focusedElapsedMs = 0,
            roundElapsedMs = 0,
            phaseStartedAt = nowWall,
            phaseAnchorElapsedMs = nowElapsed,
            pausedAtElapsedMs = null,
            pausedFromState = null,
            longBreakEvery = mutation.longBreakEvery,
            bootCount = boot,
            timeChanged = false,
            needsReview = false,
            finishReason = null,
            segmentsJson = "[]",
            lastWallAt = nowWall,
            lastElapsedAt = nowElapsed,
            createdAt = nowWall,
            updatedAt = nowWall,
        )
        dao.put(session)
        repository.journal("FOCUS", session.id, null, session, "开始专注")
        return session
    }

    private fun sameStart(existing: FocusSessionEntity, mutation: FocusMutation.Start, label: String): Boolean {
        val pomodoro = mutation.mode == FocusMode.POMODORO
        return existing.label == label &&
            existing.mode == mutation.mode.name &&
            existing.plannedFocusMinutes == mutation.focusMinutes &&
            existing.plannedBreakMinutes == (if (pomodoro) mutation.breakMinutes else 0) &&
            existing.longBreakMinutes == (if (pomodoro) mutation.longBreakMinutes else 0) &&
            existing.longBreakEvery == mutation.longBreakEvery &&
            existing.roundsPlanned == (if (pomodoro) mutation.rounds else 1) &&
            existing.taskId == mutation.taskId && existing.scheduleBlockId == mutation.scheduleBlockId
    }

    private fun decodeWorkout(row: WorkoutSessionRow): WorkoutSession? =
        runCatching { gson.fromJson(row.payload, WorkoutSession::class.java) }.getOrNull()

    private suspend fun finishSwitchedWorkout(session: WorkoutSession) {
        val db = requireNotNull(repository.workoutDatabase) { "训练数据库尚未就绪" }
        WorkoutRepository(db, repository, repository.commandClock).applyMutation(
            WorkoutMutation.Finish(session.id, notes = null, expectedUpdatedAt = session.updatedAt)
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Pause / resume / finish / skip
    // ---------------------------------------------------------------------------------------------

    private suspend fun pauseMutation(mutation: FocusMutation.Pause): FocusSessionEntity {
        val before = requireActive(mutation.id, mutation.expectedVersion)
        if (before.state == FocusSessionEntity.STATE_PAUSED) return before
        require(before.state == FocusSessionEntity.STATE_FOCUSING) { "休息中无需暂停" }
        val nowWall = wallNow()
        val nowElapsed = FocusClock.nowElapsed()
        val boot = bootNow(before)
        if (boot != before.bootCount) {
            return persist(before, recoverFromBoot(before, nowWall, nowElapsed, boot), nowWall, nowElapsed, "暂停专注", true)
        }
        val accumulated = accumulate(before, nowWall, nowElapsed).first
        val candidate = withTimeFlags(accumulated, nowWall, nowElapsed, boot).copy(
            state = FocusSessionEntity.STATE_PAUSED,
            pausedFromState = FocusSessionEntity.STATE_FOCUSING,
            pausedAtElapsedMs = nowElapsed,
            lastResumedAt = nowWall,
            phaseStartedAt = nowWall,
            phaseAnchorElapsedMs = nowElapsed,
        )
        return persist(before, candidate, nowWall, nowElapsed, "暂停专注", true)
    }

    private suspend fun resumeMutation(mutation: FocusMutation.Resume): FocusSessionEntity {
        val before = requireActive(mutation.id, mutation.expectedVersion)
        if (before.state == FocusSessionEntity.STATE_FOCUSING) return before
        require(before.state == FocusSessionEntity.STATE_PAUSED) { "当前专注不能继续" }
        val nowWall = wallNow()
        val nowElapsed = FocusClock.nowElapsed()
        val boot = bootNow(before)
        val session = if (boot != before.bootCount) recoverFromBoot(before, nowWall, nowElapsed, boot) else before
        if (session.pausedFromState == FocusSessionEntity.STATE_BREAK) {
            return beginNextRound(session, nowWall, nowElapsed, journalWrites = true, action = "开始下一轮")
        }
        val pauseMs = if (session.bootCount == boot && session.pausedAtElapsedMs != null) {
            (nowElapsed - session.pausedAtElapsedMs).coerceAtLeast(0L)
        } else 0L
        if (pauseMs >= FocusClock.PAUSE_LIMIT_MS) {
            return complete(
                session.copy(pausedAccumulatedMs = session.pausedAccumulatedMs + pauseMs,
                    pausedAtElapsedMs = null, pausedFromState = null),
                save = true, reason = "PAUSE_TIMEOUT", journalWrites = true, nowWall = nowWall, nowElapsed = nowElapsed,
            )
        }
        val candidate = withTimeFlags(session, nowWall, nowElapsed, boot).copy(
            state = FocusSessionEntity.STATE_FOCUSING,
            pausedAccumulatedMs = session.pausedAccumulatedMs + pauseMs,
            pausedAtElapsedMs = null,
            pausedFromState = null,
            lastResumedAt = nowWall,
            phaseStartedAt = nowWall,
            phaseAnchorElapsedMs = nowElapsed,
        )
        return persist(before, candidate, nowWall, nowElapsed, "继续专注", true)
    }

    private suspend fun finishMutation(mutation: FocusMutation.Finish): FocusSessionEntity {
        val before = requireActive(mutation.id, mutation.expectedVersion)
        if (!before.isActive) return before
        val nowWall = wallNow()
        val nowElapsed = FocusClock.nowElapsed()
        val boot = bootNow(before)
        val session = if (boot != before.bootCount) recoverFromBoot(before, nowWall, nowElapsed, boot) else before
        var candidate = session
        if (session.state == FocusSessionEntity.STATE_FOCUSING) {
            candidate = accumulate(session, nowWall, nowElapsed).first
        }
        if (session.state == FocusSessionEntity.STATE_PAUSED && session.pausedAtElapsedMs != null && session.bootCount == boot) {
            val paused = (nowElapsed - session.pausedAtElapsedMs).coerceAtLeast(0L)
            candidate = candidate.copy(pausedAccumulatedMs = candidate.pausedAccumulatedMs + paused)
        }
        candidate = withTimeFlags(candidate, nowWall, nowElapsed, boot)
        return complete(
            candidate, save = mutation.save,
            reason = if (mutation.save) "MANUAL_SAVE" else "MANUAL_DISCARD",
            journalWrites = true, nowWall = nowWall, nowElapsed = nowElapsed,
        )
    }

    private suspend fun skipBreakMutation(mutation: FocusMutation.SkipBreak): FocusSessionEntity {
        val before = requireActive(mutation.id, mutation.expectedVersion)
        val nowWall = wallNow()
        val nowElapsed = FocusClock.nowElapsed()
        val boot = bootNow(before)
        val session = if (boot != before.bootCount) recoverFromBoot(before, nowWall, nowElapsed, boot) else before
        return when (session.state) {
            FocusSessionEntity.STATE_BREAK -> beginNextRound(session, nowWall, nowElapsed, true, "跳过休息")
            FocusSessionEntity.STATE_PAUSED ->
                if (session.pausedFromState == FocusSessionEntity.STATE_BREAK) {
                    beginNextRound(session.copy(pausedAtElapsedMs = null, pausedFromState = null), nowWall, nowElapsed, true, "跳过休息")
                } else throw IllegalArgumentException("当前不是休息阶段")
            FocusSessionEntity.STATE_FOCUSING -> session
            else -> session
        }
    }

    private suspend fun beginNextRound(
        session: FocusSessionEntity,
        nowWall: Long,
        nowElapsed: Long,
        journalWrites: Boolean,
        action: String,
    ): FocusSessionEntity {
        if (session.roundIndex >= session.roundsPlanned) {
            return complete(session, true, "ROUNDS_DONE", journalWrites, nowWall, nowElapsed)
        }
        val candidate = session.copy(
            roundIndex = session.roundIndex + 1,
            roundElapsedMs = 0,
            state = FocusSessionEntity.STATE_FOCUSING,
            phaseStartedAt = nowWall,
            phaseAnchorElapsedMs = nowElapsed,
            pausedAtElapsedMs = null,
            pausedFromState = null,
            lastResumedAt = nowWall,
            finishReason = null,
        )
        return persist(session, candidate, nowWall, nowElapsed, action, journalWrites)
    }

    // ---------------------------------------------------------------------------------------------
    // Automatic tick: phase transitions only, never one row per displayed second
    // ---------------------------------------------------------------------------------------------

    private suspend fun tickMutation(mutation: FocusMutation.Tick): FocusSessionEntity {
        val before = requireActive(mutation.id, mutation.expectedVersion)
        if (!before.isActive) return before
        val nowWall = wallNow()
        val nowElapsed = FocusClock.nowElapsed()
        val boot = bootNow(before)
        if (boot != before.bootCount) {
            return persist(before, recoverFromBoot(before, nowWall, nowElapsed, boot), nowWall, nowElapsed, "", false)
        }

        val drifted = FocusClock.wallDriftMs(before, nowWall, nowElapsed, boot) > FocusClock.WALL_DRIFT_LIMIT_MS
        val session = if (drifted) withTimeFlags(before, nowWall, nowElapsed, boot) else before

        when (session.state) {
            FocusSessionEntity.STATE_PAUSED -> {
                val pauseMs = session.pausedAtElapsedMs?.let { (nowElapsed - it).coerceAtLeast(0L) } ?: 0L
                if (pauseMs >= FocusClock.PAUSE_LIMIT_MS) {
                    return complete(
                        session.copy(pausedAccumulatedMs = session.pausedAccumulatedMs + pauseMs,
                            pausedAtElapsedMs = null, pausedFromState = null),
                        true, "PAUSE_TIMEOUT", false, nowWall, nowElapsed,
                    )
                }
                if (drifted) return persist(before, session, nowWall, nowElapsed, "", false)
                return before
            }

            FocusSessionEntity.STATE_FOCUSING -> {
                if (FocusClock.isTimeLimited(session) &&
                    FocusClock.remainingRoundMs(session, nowElapsed, boot) <= 0L
                ) {
                    val accumulated = accumulate(session, nowWall, nowElapsed).first
                    val pomodoro = session.mode == FocusMode.POMODORO.name
                    if (pomodoro && session.roundIndex < session.roundsPlanned) {
                        val candidate = withTimeFlags(accumulated, nowWall, nowElapsed, boot).copy(
                            state = FocusSessionEntity.STATE_BREAK,
                            phaseStartedAt = nowWall,
                            phaseAnchorElapsedMs = nowElapsed,
                            lastResumedAt = nowWall,
                            finishReason = null,
                        )
                        return persist(before, candidate, nowWall, nowElapsed, "", false)
                    }
                    return complete(
                        accumulated, true,
                        if (pomodoro) "ROUNDS_DONE" else "COUNTDOWN_DONE",
                        false, nowWall, nowElapsed,
                    )
                }
                if (drifted) return persist(before, session, nowWall, nowElapsed, "", false)
                return before
            }

            FocusSessionEntity.STATE_BREAK -> {
                if (FocusClock.breakRemainingMs(session, nowElapsed, boot) <= 0L) {
                    return beginNextRound(session, nowWall, nowElapsed, false, "休息结束")
                }
                if (drifted) return persist(before, session, nowWall, nowElapsed, "", false)
                return before
            }

            else -> return before
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Persistence and recovery
    // ---------------------------------------------------------------------------------------------

    private suspend fun requireActive(id: String, expectedVersion: Long): FocusSessionEntity {
        val session = dao.session(id) ?: throw StaleChange()
        if (session.version != expectedVersion) throw StaleChange()
        return session
    }

    private fun accumulate(
        session: FocusSessionEntity,
        nowWall: Long,
        nowElapsed: Long,
    ): Pair<FocusSessionEntity, Long> {
        val boot = bootNow(session)
        val raw = FocusClock.phaseElapsedMs(session, nowElapsed, boot)
        val add = if (FocusClock.isTimeLimited(session)) {
            minOf(raw, (FocusClock.plannedFocusMs(session) - session.roundElapsedMs).coerceAtLeast(0L))
        } else raw
        if (add <= 0L) return session to 0L
        val start = session.phaseStartedAt.coerceAtMost(nowWall)
        val end = start + add
        val segment = FocusSegment(start, end, add, session.roundIndex)
        return session.copy(
            focusedElapsedMs = session.focusedElapsedMs + add,
            roundElapsedMs = session.roundElapsedMs + add,
            segmentsJson = FocusSegments.append(session.segmentsJson, segment),
        ) to add
    }

    private suspend fun persist(
        before: FocusSessionEntity,
        candidate: FocusSessionEntity,
        nowWall: Long,
        nowElapsed: Long,
        action: String,
        journal: Boolean,
    ): FocusSessionEntity {
        val saved = candidate.copy(
            updatedAt = nowWall,
            lastWallAt = nowWall,
            lastElapsedAt = nowElapsed,
            version = repository.nextVersion("FOCUS", before.id, before.version),
        )
        dao.put(saved)
        if (journal && action.isNotBlank()) {
            val frozenBefore = if (before.state == FocusSessionEntity.STATE_FOCUSING && bootNow(before) == before.bootCount)
                accumulate(before, nowWall, nowElapsed).first else before
            repository.journal("FOCUS", saved.id, frozenBefore, saved, action)
        }
        return saved
    }

    private suspend fun complete(
        session: FocusSessionEntity,
        save: Boolean,
        reason: String,
        journalWrites: Boolean,
        nowWall: Long,
        nowElapsed: Long,
    ): FocusSessionEntity {
        val recordId = if (save && session.focusedElapsedMs > 0L) {
            writeActual(session, journalWrites, nowWall)
        } else null
        val final = session.copy(
            state = if (save) FocusSessionEntity.STATE_COMPLETED else FocusSessionEntity.STATE_ABANDONED,
            endedAt = nowWall,
            actualRecordId = recordId,
            finishReason = reason,
            pausedAtElapsedMs = null,
            pausedFromState = null,
            phaseStartedAt = nowWall,
            phaseAnchorElapsedMs = nowElapsed,
            lastWallAt = nowWall,
            lastElapsedAt = nowElapsed,
            version = repository.nextVersion("FOCUS", session.id, session.version),
        )
        dao.put(final)
        if (journalWrites) {
            repository.journal("FOCUS", final.id, session, final, if (save) "结束专注" else "放弃专注")
        }
        return final
    }

    /**
     * One deterministic record per session (`focus:<sessionId>`). A manually finished session
     * journals the record so undo can remove it; an automatic tick must not pollute undo and
     * therefore writes the same row without a journal entry.
     */
    private suspend fun writeActual(
        session: FocusSessionEntity,
        journal: Boolean,
        nowWall: Long,
    ): String? {
        val timeDao = repository.database.dao()
        val id = "focus:${session.id}"
        timeDao.record(id)?.let { return it.id }
        // A deleted record is never recreated; the session still proves it existed once.
        if (timeDao.revision("RECORD:$id") != null) return null

        val taskId = session.taskId?.takeIf { id -> timeDao.task(id)?.let { it.deletedAt == null } == true }
        val task = taskId?.let { timeDao.task(it) }
        val preset = repository.database.entryContextDao().find("RECORD", id)
        val activity = preset?.activityId?.let { timeDao.activity(it) }
        val segments = FocusClock.decodeSegments(session)
        val start = segments.firstOrNull()?.startAt ?: session.startedAt
        val end = segments.lastOrNull()?.endAt ?: (start + session.focusedElapsedMs)
        val record = TimeRecordEntity(
            id = id,
            taskId = taskId,
            startAt = start,
            endAt = end,
            categoryId = activity?.categoryId ?: task?.categoryId,
            notes = actualNotes(session, segments),
            source = "FOCUS",
            createdAt = nowWall,
            updatedAt = nowWall,
            isManuallyEdited = false,
            title = session.label,
        )
        if (journal) {
            repository.saveRecordRaw(record, allowFuture = true)
        } else {
            timeDao.putRecord(record.copy(version = repository.nextVersion("RECORD", id)))
        }
        val org = repository.database.organizationDao()
        val tags = preset?.tagIds.orEmpty().split(',').filter { it.isNotBlank() && org.tag(it) != null }.distinct().sorted()
        tags.forEach { org.addRecordTag(com.timeagent.data.RecordTagEntity(id, it)) }
        if (journal && tags.isNotEmpty()) repository.journal("ORG_RECORD_TAGS", id,
            com.timeagent.organization.OrgTagLinks(), com.timeagent.organization.OrgTagLinks(tags), "保存专注标签")
        return id
    }

    private fun actualNotes(session: FocusSessionEntity, segments: List<FocusSegment>): String {
        val zone = runCatching { ZoneId.of(session.timezone) }.getOrDefault(repository.zone)
        val shown = segments.take(10).joinToString("；") {
            "${FocusClock.formatWall(it.startAt, zone)}–${FocusClock.formatWall(it.endAt, zone)}（${FocusClock.formatDuration(it.elapsedMs)}）"
        }
        val more = if (segments.size > 10) "；另有 ${segments.size - 10} 段" else ""
        val multi = if (segments.size > 1) {
            "实际专注被暂停或休息分成 ${segments.size} 段；时间轴与统计按专注片段计算，休息和暂停不计入。"
        } else ""
        val crossDay = if (FocusClock.segmentsByDay(session, zone).size > 1) "跨天专注，统计请按片段分配到各天。" else ""
        val changed = if (session.needsReview)
            "设备重启后已暂停；只保留重启前已核实的专注时长，请核对，缺失时段可手动补录。"
        else if (session.timeChanged) "检测到系统时间变化，时长按 elapsedRealtime 计算，请核对展示时间。" else ""
        return listOf(
            "专注 ${FocusClock.formatDuration(session.focusedElapsedMs)}",
            if (shown.isNotBlank()) "片段：$shown$more" else "",
            multi, crossDay, changed,
        ).filter { it.isNotBlank() }.joinToString("；")
    }

    private fun recoverFromBoot(
        session: FocusSessionEntity,
        nowWall: Long,
        nowElapsed: Long,
        boot: Int,
    ): FocusSessionEntity {
        var recovered = session
        if (session.state == FocusSessionEntity.STATE_FOCUSING) {
            // A new boot has no monotonic evidence for time after the last persisted checkpoint.
            val knownElapsed = (session.lastElapsedAt - session.phaseAnchorElapsedMs).coerceAtLeast(0L)
            val cap = if (FocusClock.isTimeLimited(session)) {
                (FocusClock.plannedFocusMs(session) - session.roundElapsedMs).coerceAtLeast(0L)
            } else knownElapsed
            val add = minOf(knownElapsed, cap)
            if (add > 0L) {
                val start = session.phaseStartedAt
                recovered = recovered.copy(
                    focusedElapsedMs = recovered.focusedElapsedMs + add,
                    roundElapsedMs = recovered.roundElapsedMs + add,
                    segmentsJson = FocusSegments.append(recovered.segmentsJson,
                        FocusSegment(start, start + add, add, session.roundIndex)),
                )
            }
            recovered = recovered.copy(
                state = FocusSessionEntity.STATE_PAUSED,
                pausedFromState = FocusSessionEntity.STATE_FOCUSING,
                pausedAtElapsedMs = nowElapsed,
                lastResumedAt = nowWall,
            )
        } else if (session.state == FocusSessionEntity.STATE_BREAK) {
            recovered = recovered.copy(
                state = FocusSessionEntity.STATE_PAUSED,
                pausedFromState = FocusSessionEntity.STATE_BREAK,
                pausedAtElapsedMs = nowElapsed,
                lastResumedAt = nowWall,
            )
        } else if (session.state == FocusSessionEntity.STATE_PAUSED) {
            recovered = recovered.copy(pausedAtElapsedMs = nowElapsed, lastResumedAt = nowWall)
        }
        return recovered.copy(
            bootCount = boot,
            timeChanged = true,
            needsReview = true,
            phaseStartedAt = nowWall,
            phaseAnchorElapsedMs = nowElapsed,
            lastWallAt = nowWall,
            lastElapsedAt = nowElapsed,
        )
    }

    private fun withTimeFlags(
        session: FocusSessionEntity,
        nowWall: Long,
        nowElapsed: Long,
        boot: Int,
    ): FocusSessionEntity {
        if (FocusClock.wallDriftMs(session, nowWall, nowElapsed, boot) <= FocusClock.WALL_DRIFT_LIMIT_MS) return session
        return session.copy(timeChanged = true, needsReview = true, lastWallAt = nowWall, lastElapsedAt = nowElapsed)
    }

    private fun wallNow(): Long = repository.now().toEpochMilli()

    /** Without an application context, preserve the session's boot id instead of treating -1 as a reboot. */
    private fun bootNow(session: FocusSessionEntity): Int {
        if (session.bootCount < 0) return session.bootCount
        return FocusClock.bootCount(context).takeIf { it >= 0 } ?: session.bootCount
    }
}
