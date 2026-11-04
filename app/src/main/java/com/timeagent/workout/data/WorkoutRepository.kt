package com.timeagent.workout.data

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.timeagent.core.workout.*
import com.timeagent.data.OperationEntity
import com.timeagent.data.StaleChange
import com.timeagent.data.TimeRecordEntity
import com.timeagent.data.TimeRepository
import com.timeagent.di.AppEntryPoint
import com.timeagent.domain.command.WriteWorkout
import com.timeagent.domain.command.WorkoutMutation
import com.timeagent.domain.command.throwIfRejected
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.util.Locale
import java.util.UUID

/**
 * Training storage lives in its own database, but every user write is executed through
 * [WriteWorkout] / CommandExecutor so request ids, version checks, journal groups and undo/redo
 * share the main database's single entry point. The two SQLite files cannot be committed
 * atomically. Touched rows are retained in a local recovery file before writing. A marker in the
 * main transaction decides whether startup keeps or restores those rows after process death.
 */
class WorkoutRepository internal constructor(
    private val db: WorkoutDatabase,
    private val timeRepository: TimeRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    constructor(context: Context, timeRepository: TimeRepository, clock: Clock = Clock.systemUTC()) :
        this(AppEntryPoint.from(context).workoutDatabase(), timeRepository, clock)

    private val gson = Gson()
    private val dao = db.dao()

    init {
        timeRepository.bindWorkoutDatabase(db)
    }

    fun observeSessions(): Flow<List<WorkoutSession>> = dao.observeSessions()
        .onStart { timeRepository.recoverWorkoutWrites() }
        .map { rows -> rows.map(::decode) }.distinctUntilChanged().flowOn(Dispatchers.IO)

    fun observeTemplates(): Flow<List<WorkoutTemplate>> = dao.observeTemplates()
        .onStart { timeRepository.recoverWorkoutWrites() }
        .map { rows -> rows.map(::decodeTemplate) }
        .distinctUntilChanged().flowOn(Dispatchers.IO)

    suspend fun getSession(id: String): WorkoutSession? = withContext(Dispatchers.IO) {
        timeRepository.recoverWorkoutWrites()
        dao.session(id)?.let(::decode)
    }
    suspend fun activeSession(): WorkoutSession? = withContext(Dispatchers.IO) {
        timeRepository.recoverWorkoutWrites()
        dao.activeSession()?.let(::decode)
    }

    suspend fun start(
        title: String = "自由训练",
        exercises: List<ExerciseDefinition> = emptyList(),
        switchTimer: TimeRecordEntity? = null,
        requestId: String = UUID.randomUUID().toString(),
    ): WorkoutSession = sessionResult(requestId, WorkoutMutation.Start(requestId, title.trim(), exercises, switchTimer))

    suspend fun startFromTemplate(
        templateId: String,
        switchTimer: TimeRecordEntity? = null,
        requestId: String = UUID.randomUUID().toString(),
    ): WorkoutSession = sessionResult(requestId, WorkoutMutation.StartFromTemplate(requestId, templateId, switchTimer))

    suspend fun repeatSession(
        sessionId: String,
        switchTimer: TimeRecordEntity? = null,
        requestId: String = UUID.randomUUID().toString(),
    ): WorkoutSession = sessionResult(requestId, WorkoutMutation.RepeatSession(requestId, sessionId, switchTimer))

    suspend fun mutate(
        id: String,
        expectedUpdatedAt: Long? = null,
        transform: (WorkoutSession) -> WorkoutSession,
    ): WorkoutSession {
        timeRepository.recoverWorkoutWrites()
        val before = requireSession(id)
        checkVersion(before, expectedUpdatedAt)
        require(before.status == WorkoutSessionStatus.ACTIVE || before.status == WorkoutSessionStatus.PAUSED) { "已结束训练不能再修改" }
        val candidate = transform(before)
        require(candidate.id == before.id && candidate.startedAt == before.startedAt && candidate.status == before.status &&
            candidate.endedAt == before.endedAt && candidate.pausedAt == before.pausedAt && candidate.totalPausedMillis == before.totalPausedMillis &&
            candidate.timelineRecordId == before.timelineRecordId && candidate.templateId == before.templateId) { "请使用训练暂停、恢复或结束操作来改变计时状态" }
        fun targetRetained(movementId: String, setId: String): Boolean {
            val oldMovement = before.movements.find { it.id == movementId }
            val newMovement = candidate.movements.find { it.id == movementId }
            return newMovement != null && newMovement.exercise == oldMovement?.exercise &&
                newMovement.sets.any { it.id == setId && !it.isCompleted }
        }
        val saved = candidate.copy(
            title = candidate.title.trim(),
            setTimer = candidate.setTimer?.takeIf { targetRetained(it.movementId, it.setId) },
            intervalTimer = candidate.intervalTimer?.takeIf { targetRetained(it.movementId, it.setId) },
        )
        if (saved == before) return before
        return sessionResult(saveRequestId("mutate", before, saved), WorkoutMutation.SaveSession(saved, before.updatedAt, "保存训练"))
    }

    suspend fun pause(id: String, expectedUpdatedAt: Long? = null): WorkoutSession =
        update(id, expectedUpdatedAt, "pause", "暂停训练") { WorkoutRules.pause(it, clock.millis()) }

    suspend fun resume(id: String, expectedUpdatedAt: Long? = null): WorkoutSession =
        update(id, expectedUpdatedAt, "resume", "恢复训练") { WorkoutRules.resume(it, clock.millis()) }

    suspend fun completeSet(sessionId: String, movementId: String, setId: String, expectedUpdatedAt: Long? = null): WorkoutSession =
        update(sessionId, expectedUpdatedAt, "complete-set", "完成训练组") { WorkoutRules.completeSet(it, movementId, setId, clock.millis()) }

    suspend fun startInterval(sessionId: String, movementId: String, setId: String): WorkoutSession =
        update(sessionId, null, "start-interval", "开始阶段计时") { WorkoutRules.startInterval(it, movementId, setId, clock.millis()) }

    suspend fun finishInterval(sessionId: String): WorkoutSession =
        update(sessionId, null, "finish-interval", "完成阶段计时") { WorkoutRules.finishInterval(it, clock.millis()) }

    suspend fun startSetTimer(sessionId: String, movementId: String, setId: String): WorkoutSession =
        update(sessionId, null, "start-set-timer", "开始单组计时") { WorkoutRules.startSetTimer(it, movementId, setId, clock.millis()) }

    suspend fun stopSetTimer(sessionId: String): WorkoutSession =
        update(sessionId, null, "stop-set-timer", "停止单组计时") { WorkoutRules.stopSetTimer(it, clock.millis()) }

    suspend fun finish(id: String, notes: String? = null, expectedUpdatedAt: Long? = null): WorkoutSession {
        timeRepository.recoverWorkoutWrites()
        val current = requireSession(id)
        checkVersion(current, expectedUpdatedAt)
        if (current.status == WorkoutSessionStatus.COMPLETED && current.timelineRecordId != null) return current
        val requestId = if (current.status == WorkoutSessionStatus.COMPLETED)
            "workout-finish:$id:repair:${UUID.randomUUID()}" else "workout-finish:$id:${current.updatedAt}"
        return sessionResult(requestId, WorkoutMutation.Finish(id, notes, current.updatedAt))
    }

    suspend fun switchToTimer(id: String, expectedUpdatedAt: Long, taskId: String?, expectedTaskVersion: Long?, requestId: String) {
        executeCommand(requestId, WorkoutMutation.SwitchToTimer(id, expectedUpdatedAt, taskId, expectedTaskVersion, requestId))
    }

    suspend fun discard(id: String, expectedUpdatedAt: Long? = null): WorkoutSession {
        timeRepository.recoverWorkoutWrites()
        val current = requireSession(id)
        checkVersion(current, expectedUpdatedAt)
        if (current.status == WorkoutSessionStatus.DISCARDED) return current
        require(current.status != WorkoutSessionStatus.COMPLETED) { "已结束训练不能放弃" }
        val now = clock.millis()
        require(now >= current.startedAt) { "当前时间早于训练开始" }
        val candidate = current.copy(
            status = WorkoutSessionStatus.DISCARDED, endedAt = now, updatedAt = now,
            totalPausedMillis = current.totalPausedMillis + (current.pausedAt?.let { (now - it).coerceAtLeast(0) } ?: 0),
            pausedAt = null, restEndsAt = null, pausedRestRemainingMillis = null, intervalTimer = null, setTimer = null,
        )
        return sessionResult(saveRequestId("discard", current, candidate), WorkoutMutation.SaveSession(candidate, current.updatedAt, "放弃训练"))
    }

    suspend fun saveTemplate(sessionId: String, title: String): WorkoutTemplate {
        timeRepository.recoverWorkoutWrites()
        val session = requireSession(sessionId)
        val template = WorkoutRules.templateFrom(session, title, clock.millis())
        val fixed = "workout-template:$sessionId:${session.updatedAt}:${title.trim().hashCode()}"
        // A saved template that was undone no longer exists; saving again must create it instead
        // of replaying a result that points at a deleted row.
        val replayed = timeRepository.commands.replay(fixed)?.payload
            ?.let { runCatching { gson.fromJson(it, WorkoutTemplate::class.java) }.getOrNull() }
        val requestId = if (replayed != null && dao.template(replayed.id) == null) "$fixed:${template.id}" else fixed
        return templateResult(requestId, WorkoutMutation.SaveTemplate(template))
    }

    suspend fun saveTemplateDefinition(template: WorkoutTemplate): WorkoutTemplate {
        timeRepository.recoverWorkoutWrites()
        val current = dao.template(template.id)?.let(::decodeTemplate)
        if (current != null && current.updatedAt != template.updatedAt) throw StaleChange()
        val contentHash = gson.toJson(template.copy(createdAt = 0, updatedAt = 0)).hashCode()
        val requestId = "workout-template:${template.id}:${template.updatedAt}:$contentHash"
        return templateResult(requestId, WorkoutMutation.SaveTemplate(template, template.updatedAt.takeIf { current != null }))
    }

    suspend fun deleteTemplate(id: String, expectedUpdatedAt: Long? = null) {
        timeRepository.recoverWorkoutWrites()
        val current = dao.template(id)?.let(::decodeTemplate)
        if (expectedUpdatedAt != null && current?.updatedAt != expectedUpdatedAt) throw StaleChange()
        // The version makes a retry idempotent while a restored template (undo) is deleted again.
        executeCommand("workout-delete-template:$id:${current?.updatedAt ?: "missing"}",
            WorkoutMutation.DeleteTemplate(id, current?.updatedAt))
    }

    suspend fun lastMovement(exerciseId: String, excludingSessionId: String? = null): WorkoutMovement? = withContext(Dispatchers.IO) {
        timeRepository.recoverWorkoutWrites()
        dao.sessions().map(::decode).filter { it.status == WorkoutSessionStatus.COMPLETED && it.id != excludingSessionId }
            .sortedByDescending { it.endedAt ?: it.startedAt }.firstNotNullOfOrNull { session ->
                val completedIds = WorkoutRules.completedSetIds(session)
                session.movements.lastOrNull { it.exercise.id == exerciseId && it.sets.any { set -> set.id in completedIds } }
                    ?.let { it.copy(sets = it.sets.filter { set -> set.id in completedIds }) }
            }
    }

    private data class JournalEntry(val type: String, val id: String, val before: Any?, val after: Any?, val label: String)
    private data class MutationOutcome(val value: Any?, val journal: List<JournalEntry> = emptyList())

    /**
     * Runs inside the main transaction opened by CommandExecutor/TimeRepository.applyHistory.
     * Every write is serialized through [mutationLock] and the workout database transaction.
     */
    internal suspend fun applyMutation(mutation: WorkoutMutation): Any? = mutationLock.withLock {
        timeRepository.beginWorkoutWrite()
        if (mutation is WorkoutMutation.Finish) return@withLock finishMutation(mutation)
        if (mutation is WorkoutMutation.SwitchToTimer) {
            val current = requireSession(mutation.sessionId)
            checkVersion(current, mutation.expectedUpdatedAt)
            require(current.status in setOf(WorkoutSessionStatus.ACTIVE, WorkoutSessionStatus.PAUSED)) { "当前训练已结束" }
            if (WorkoutRules.summary(current, clock.millis()).completedSets > 0) {
                finishMutation(WorkoutMutation.Finish(current.id, expectedUpdatedAt = current.updatedAt))
            } else {
                val end = clock.millis()
                val discarded = db.withTransaction {
                    persistSession(current, current.copy(status = WorkoutSessionStatus.DISCARDED, endedAt = end,
                        pausedAt = null, totalPausedMillis = current.totalPausedMillis + (current.pausedAt?.let { end - it } ?: 0),
                        restEndsAt = null, pausedRestRemainingMillis = null, intervalTimer = null, setTimer = null))
                }
                timeRepository.journal("WORKOUT_SESSION", current.id, current, discarded, "放弃空训练")
            }
            return@withLock timeRepository.startTimerRaw(mutation.taskId, mutation.recordId,
                expectedTaskVersion = mutation.expectedTaskVersion)
        }
        // The free timer is checked before anything is written, even on a retry after a partial start.
        if (mutation is WorkoutMutation.Start) switchTimerIfNeeded(mutation.switchTimer)
        if (mutation is WorkoutMutation.StartFromTemplate) switchTimerIfNeeded(mutation.switchTimer)
        if (mutation is WorkoutMutation.RepeatSession) switchTimerIfNeeded(mutation.switchTimer)
        // Only the workout database is written here. Main-database journal rows are written after
        // this transaction commits, while CommandExecutor's main transaction is still open; nesting
        // a main-database write inside a workout-database transaction would deadlock on the main lock.
        val outcome = db.withTransaction {
            when (mutation) {
                is WorkoutMutation.Start -> {
                    val existing = existingStart(mutation.sessionId)
                    if (existing != null) MutationOutcome(existing)
                    else {
                        val stored = insertStart(WorkoutSession(
                            id = mutation.sessionId,
                            title = mutation.title.trim(),
                            startedAt = clock.millis(),
                            movements = mutation.exercises.map { WorkoutMovement(exercise = it) },
                        ))
                        MutationOutcome(stored, listOf(JournalEntry("WORKOUT_SESSION", stored.id, null, stored, "开始训练")))
                    }
                }

                is WorkoutMutation.StartFromTemplate -> {
                    val existing = existingStart(mutation.sessionId)
                    if (existing != null) MutationOutcome(existing)
                    else {
                        val template = requireTemplate(mutation.templateId)
                        val stored = insertStart(WorkoutRules.startFromTemplate(template, clock.millis()).copy(id = mutation.sessionId))
                        MutationOutcome(stored, listOf(JournalEntry("WORKOUT_SESSION", stored.id, null, stored, "开始训练")))
                    }
                }

                is WorkoutMutation.RepeatSession -> {
                    val existing = existingStart(mutation.sessionId)
                    if (existing != null) MutationOutcome(existing)
                    else {
                        val previous = requireSession(mutation.previousSessionId)
                        require(previous.status == WorkoutSessionStatus.COMPLETED) { "只能复训已完成的训练" }
                        val now = clock.millis()
                        val stored = insertStart(WorkoutRules.startFromTemplate(WorkoutRules.templateFrom(previous, previous.title, now), now)
                            .copy(id = mutation.sessionId, templateId = previous.templateId))
                        MutationOutcome(stored, listOf(JournalEntry("WORKOUT_SESSION", stored.id, null, stored, "开始训练")))
                    }
                }

                is WorkoutMutation.SaveSession -> {
                    val current = requireSession(mutation.session.id)
                    checkVersion(current, mutation.expectedUpdatedAt)
                    val candidate = mutation.session
                    require(candidate.id == current.id && candidate.startedAt == current.startedAt) { "训练标识不一致" }
                    require(candidate.templateId == current.templateId && candidate.timelineRecordId == current.timelineRecordId) {
                        "请使用训练命令修改关联信息"
                    }
                    require(candidate.status != WorkoutSessionStatus.COMPLETED) { "请使用结束训练保存完成状态" }
                    if (candidate.status == WorkoutSessionStatus.ACTIVE || candidate.status == WorkoutSessionStatus.PAUSED) {
                        val active = dao.activeSession()
                        if (active != null && active.id != candidate.id) throw WorkoutAlreadyActive()
                    }
                    val stored = persistSession(current, candidate)
                    MutationOutcome(stored, listOf(JournalEntry("WORKOUT_SESSION", stored.id, current, stored, mutation.label)))
                }

                is WorkoutMutation.Finish, is WorkoutMutation.SwitchToTimer -> error("此命令在训练事务外执行")

                is WorkoutMutation.SaveTemplate -> {
                    val current = dao.template(mutation.template.id)?.let(::decodeTemplate)
                    if (mutation.expectedUpdatedAt != null && current?.updatedAt != mutation.expectedUpdatedAt) throw StaleChange()
                    val cleaned = mutation.template.copy(
                        title = mutation.template.title.trim(),
                        createdAt = current?.createdAt ?: mutation.template.createdAt.takeIf { it > 0 } ?: clock.millis(),
                        movements = mutation.template.movements.map { movement ->
                            movement.copy(sets = movement.sets.map { it.copy(completedAt = null) })
                        },
                    )
                    val stamped = cleaned.copy(updatedAt = nextStamp(cleaned.updatedAt, current?.updatedAt))
                    WorkoutRules.validateTemplate(stamped)
                    preserveTemplate(stamped.id)
                    dao.putTemplate(WorkoutTemplateRow(stamped.id, stamped.updatedAt, gson.toJson(stamped)))
                    MutationOutcome(stamped, listOf(JournalEntry("WORKOUT_TEMPLATE", stamped.id, current, stamped,
                        if (current == null) "新增训练模板" else "修改训练模板")))
                }

                is WorkoutMutation.DeleteTemplate -> {
                    val before = dao.template(mutation.templateId)?.let(::decodeTemplate)
                    if (before?.updatedAt != mutation.expectedUpdatedAt) throw StaleChange()
                    if (before == null) MutationOutcome(null)
                    else {
                        preserveTemplate(mutation.templateId)
                        dao.deleteTemplate(mutation.templateId)
                        MutationOutcome(null, listOf(JournalEntry("WORKOUT_TEMPLATE", mutation.templateId, before, null, "删除训练模板")))
                    }
                }
            }
        }
        outcome.journal.forEach { timeRepository.journal(it.type, it.id, it.before, it.after, it.label) }
        outcome.value
    }

    /**
     * Restores a session/template snapshot produced by the journal. Called by
     * TimeRepository.applyHistory inside the main transaction; revisions stay monotonic through
     * updatedAt, and an active session that would collide with another workout is rejected.
     */
    internal suspend fun applyHistory(action: OperationEntity, forward: Boolean) {
        mutationLock.withLock {
            timeRepository.beginWorkoutWrite()
            val target = if (forward) action.afterSnapshot else action.beforeSnapshot
            if (action.entityType == "WORKOUT_SESSION" && target != null) {
                val restored = gson.fromJson(target, WorkoutSession::class.java)
                if (restored.status in setOf(WorkoutSessionStatus.ACTIVE, WorkoutSessionStatus.PAUSED) &&
                    timeRepository.runningTimer() != null) throw StaleChange()
            }
            db.withTransaction {
                when (action.entityType) {
                    "WORKOUT_SESSION" -> restoreSession(action, forward)
                    "WORKOUT_TEMPLATE" -> restoreTemplate(action, forward)
                    else -> throw IllegalArgumentException("未知的训练操作：${action.entityType}")
                }
            }
        }
    }

    /**
     * Completion is committed to the workout database first. The actual record is then written in
     * the still-open main transaction. The row recovery file retains the pre-command snapshot;
     * if the main transaction does not commit, the next startup restores that snapshot.
     */
    private suspend fun finishMutation(mutation: WorkoutMutation.Finish): WorkoutSession {
        var completion: JournalEntry? = null
        val completed = db.withTransaction {
            val current = requireSession(mutation.sessionId)
            checkVersion(current, mutation.expectedUpdatedAt)
            if (current.status == WorkoutSessionStatus.COMPLETED) current
            else {
                require(current.status != WorkoutSessionStatus.DISCARDED) { "已放弃的训练不能结束" }
                val stored = persistSession(current, WorkoutRules.finish(current.copy(notes = mutation.notes ?: current.notes), clock.millis()))
                completion = JournalEntry("WORKOUT_SESSION", stored.id, current, stored, "结束训练")
                stored
            }
        }
        completion?.let { timeRepository.journal(it.type, it.id, it.before, it.after, it.label) }
        // The link also records that this session's one actual record was already produced; a later
        // finish never recreates a record the user deleted.
        if (completed.timelineRecordId != null) return completed
        val recordId = "workout:${completed.id}"
        val main = timeRepository.database.dao()
        if (main.record(recordId) == null && main.revision("RECORD:$recordId") == null) {
            val fitness = main.activity("fitness")
            val categoryId = fitness?.categoryId?.takeIf { id -> main.categories().any { it.id == id && !it.isArchived } }
                ?: main.categories().firstOrNull { it.name == "健身" && !it.isArchived }?.id
            timeRepository.saveRecordRaw(
                TimeRecordEntity(
                    id = recordId, taskId = null, startAt = completed.startedAt, endAt = requireNotNull(completed.endedAt),
                    categoryId = categoryId, notes = timelineNotes(completed), source = "WORKOUT", title = completed.title,
                ),
                allowFuture = false,
            )
        }
        val linked = completed.copy(timelineRecordId = recordId, updatedAt = nextStamp(completed.updatedAt, completed.updatedAt))
        val savedLink = db.withTransaction { persistSession(completed, linked) }
        timeRepository.journal("WORKOUT_SESSION", savedLink.id, completed, savedLink, "关联训练记录")
        return savedLink
    }

    private suspend fun restoreSession(action: OperationEntity, forward: Boolean) {
        val target = if (forward) action.afterSnapshot else action.beforeSnapshot
        val expected = if (forward) action.beforeSnapshot else action.afterSnapshot
        val current = dao.session(action.entityId)?.let(::decode)
        // Main commit may survive process death before its final workout link. Undo must still
        // accept that exact completed session; the remaining command group restores the record.
        val expectedSession = expected?.let { gson.fromJson(it, WorkoutSession::class.java) }
        val comparable = if (current?.status == WorkoutSessionStatus.COMPLETED &&
            current.timelineRecordId == null && expectedSession?.timelineRecordId == "workout:${current.id}")
            current.copy(timelineRecordId = expectedSession.timelineRecordId) else current
        matches(comparable, expected)
        if (target == null) {
            if (current != null) {
                if (current.timelineRecordId != null) throw StaleChange()
                preserveSession(action.entityId)
                dao.deleteSession(action.entityId)
            }
            return
        }
        val restored = gson.fromJson(target, WorkoutSession::class.java)
        require(restored.id == action.entityId) { "训练标识不一致" }
        if (restored.status == WorkoutSessionStatus.ACTIVE || restored.status == WorkoutSessionStatus.PAUSED) {
            val active = dao.activeSession()
            if (active != null && active.id != restored.id) throw StaleChange()
        }
        val stamped = restored.copy(updatedAt = nextStamp(restored.updatedAt, current?.updatedAt))
        WorkoutRules.validateSession(stamped)
        preserveSession(stamped.id)
        dao.putSession(WorkoutSessionRow.from(stamped, gson))
    }

    private suspend fun restoreTemplate(action: OperationEntity, forward: Boolean) {
        val target = if (forward) action.afterSnapshot else action.beforeSnapshot
        val expected = if (forward) action.beforeSnapshot else action.afterSnapshot
        val current = dao.template(action.entityId)?.let(::decodeTemplate)
        matches(current, expected)
        preserveTemplate(action.entityId)
        if (target == null) {
            if (current != null) dao.deleteTemplate(action.entityId)
            return
        }
        val restored = gson.fromJson(target, WorkoutTemplate::class.java)
        require(restored.id == action.entityId) { "训练模板标识不一致" }
        val stamped = restored.copy(updatedAt = nextStamp(restored.updatedAt, current?.updatedAt))
        WorkoutRules.validateTemplate(stamped)
        dao.putTemplate(WorkoutTemplateRow(stamped.id, stamped.updatedAt, gson.toJson(stamped)))
    }

    private fun matches(current: Any?, expected: String?) {
        fun normalized(raw: String?): JsonObject? =
            raw?.let { JsonParser.parseString(it).asJsonObject.apply { remove("updatedAt") } }
        if (normalized(current?.let { gson.toJson(it) }) != normalized(expected)) throw StaleChange()
    }

    private suspend fun switchTimerIfNeeded(confirmed: TimeRecordEntity?) {
        val timer = timeRepository.runningTimer()
        if (timer == null) {
            if (confirmed != null) throw StaleChange()
            return
        }
        if (confirmed == null) throw com.timeagent.domain.command.WorkoutTimerConflict(timer)
        if (confirmed.id != timer.id || confirmed.version != timer.version) throw StaleChange()
        // Explicit stop in the same command group: undo restarts the free timer.
        timeRepository.stopTimerRaw(timer.id, timer.version)
    }

    private suspend fun insertStart(session: WorkoutSession): WorkoutSession {
        dao.session(session.id)?.let { return decode(it) }
        if (dao.activeSession() != null) throw WorkoutAlreadyActive()
        return persistSession(null, session)
    }

    /** Workout-database write only; the caller journals after this transaction commits. */
    private suspend fun persistSession(before: WorkoutSession?, after: WorkoutSession): WorkoutSession {
        val stamped = after.copy(updatedAt = nextStamp(after.updatedAt, before?.updatedAt))
        WorkoutRules.validateSession(stamped)
        preserveSession(stamped.id)
        dao.putSession(WorkoutSessionRow.from(stamped, gson))
        return stamped
    }

    private suspend fun preserveSession(id: String) {
        val before = dao.session(id)
        timeRepository.preserveWorkoutSession(id, before)
        timeRepository.rollbackWorkoutOnFailure {
            db.withTransaction { if (before == null) dao.deleteSession(id) else dao.putSession(before) }
        }
    }

    private suspend fun preserveTemplate(id: String) {
        val before = dao.template(id)
        timeRepository.preserveWorkoutTemplate(id, before)
        timeRepository.rollbackWorkoutOnFailure {
            db.withTransaction { if (before == null) dao.deleteTemplate(id) else dao.putTemplate(before) }
        }
    }
    private suspend fun update(
        id: String,
        expectedUpdatedAt: Long?,
        operation: String,
        label: String,
        transform: (WorkoutSession) -> WorkoutSession,
    ): WorkoutSession {
        timeRepository.recoverWorkoutWrites()
        val before = requireSession(id)
        checkVersion(before, expectedUpdatedAt)
        val candidate = transform(before)
        if (candidate == before) return before
        return sessionResult(saveRequestId(operation, before, candidate), WorkoutMutation.SaveSession(candidate, before.updatedAt, label))
    }

    private fun checkVersion(current: WorkoutSession, expected: Long?) {
        if (expected != null && current.updatedAt != expected) throw StaleChange()
    }

    private fun saveRequestId(operation: String, before: WorkoutSession, after: WorkoutSession): String =
        "workout-$operation:${before.id}:${before.updatedAt}:${gson.toJson(after.copy(updatedAt = before.updatedAt)).hashCode()}"

    private fun nextStamp(proposed: Long, previous: Long?): Long =
        maxOf(proposed, clock.millis(), (previous ?: Long.MIN_VALUE) + 1)

    private suspend fun executeCommand(requestId: String, mutation: WorkoutMutation): String? {
        val result = timeRepository.commands.executeForRepository(WriteWorkout(requestId, mutation))
        result.throwIfRejected()
        return result.payload
    }

    private suspend fun sessionResult(requestId: String, mutation: WorkoutMutation): WorkoutSession =
        gson.fromJson(requireNotNull(executeCommand(requestId, mutation)) { "训练保存结果缺失" }, WorkoutSession::class.java)

    private suspend fun templateResult(requestId: String, mutation: WorkoutMutation): WorkoutTemplate =
        gson.fromJson(requireNotNull(executeCommand(requestId, mutation)) { "训练模板保存结果缺失" }, WorkoutTemplate::class.java)

    private suspend fun existingStart(id: String): WorkoutSession? = dao.session(id)?.let(::decode)

    private suspend fun requireTemplate(id: String): WorkoutTemplate =
        requireNotNull(dao.template(id)?.let(::decodeTemplate)) { "训练模板不存在" }

    private fun decode(row: WorkoutSessionRow): WorkoutSession = gson.fromJson(row.payload, WorkoutSession::class.java)
    private fun decodeTemplate(row: WorkoutTemplateRow): WorkoutTemplate = gson.fromJson(row.payload, WorkoutTemplate::class.java)
    private suspend fun requireSession(id: String): WorkoutSession = requireNotNull(dao.session(id)?.let(::decode)) { "训练不存在" }

    private fun timelineNotes(session: WorkoutSession): String = buildString {
        val summary = WorkoutRules.summary(session, session.endedAt ?: clock.millis())
        val completedSetIds = WorkoutRules.completedSetIds(session)
        appendLine("训练汇总：正式组 ${summary.workingSets}，热身组 ${summary.warmupSets}，共 ${summary.completedSets} 组；完整组/轮 ${summary.completedRounds}")
        appendLine("训练时长 ${formatDuration(summary.elapsedMillis)}；暂停 ${formatDuration(session.totalPausedMillis)}")
        appendLine("训练容量 ${number(summary.volumeKg)} kg（不含热身）")
        session.movements.filter { movement -> movement.sets.any { it.id in completedSetIds } }.forEach { movement ->
            appendLine(movement.exercise.name + (if (movement.oneSide) "（单侧次数，容量×2）" else "") +
                (if (movement.supersetId != null) "（超级组）" else ""))
            movement.sets.filter { it.id in completedSetIds }.forEachIndexed { index, set ->
                val kind = when (set.kind) {
                    WorkoutSetKind.NORMAL -> "普通"
                    WorkoutSetKind.WARMUP -> "热身"
                    WorkoutSetKind.DROP -> "递减"
                    WorkoutSetKind.FAILURE -> "力竭"
                }
                val weight = if (movement.singleSide) "右 ${number(set.weightKg ?: 0.0)} / 左 ${number(set.leftWeightKg ?: 0.0)} kg"
                    else "${number(set.weightKg ?: 0.0)} kg"
                val value = when (movement.exercise.metric) {
                    WorkoutMetric.WEIGHT_REPS -> "$weight × ${set.reps} 次"
                    WorkoutMetric.BODYWEIGHT_REPS -> "体重 ${number(session.bodyWeightKg ?: 0.0)} kg + 附加 ${number(set.weightKg ?: 0.0)} kg × ${set.reps} 次"
                    WorkoutMetric.ASSISTED_REPS -> "体重 ${number(session.bodyWeightKg ?: 0.0)} kg - 辅助 $weight × ${set.reps} 次"
                    WorkoutMetric.REPS -> "${set.reps} 次"
                    WorkoutMetric.DURATION -> "${set.durationSeconds} 秒"
                    WorkoutMetric.CARDIO -> buildList {
                        set.durationSeconds?.let { add("$it 秒") }
                        set.distanceMeters?.let { add("${number(it)} 米") }
                        set.caloriesKcal?.let { add("${number(it)} kcal") }
                        set.recordValues.filterValues { it.isNotBlank() }.forEach { (key, raw) ->
                            val field = cardioLabels[key]
                            add("${field?.first ?: key} $raw${field?.second?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""}")
                        }
                    }.joinToString("；")
                    WorkoutMetric.STRETCH -> set.durationSeconds?.let { "拉伸 $it 秒" } ?: "拉伸完成"
                    WorkoutMetric.TABATA -> (set.tabata ?: WorkoutTabata()).let {
                        "${set.durationSeconds} 秒；准备 ${it.prepareSeconds} 秒，训练 ${it.workSeconds} 秒 / 休息 ${it.restSeconds} 秒，${it.rounds}轮"
                    }
                }
                appendLine("  ${index + 1}. $kind $value")
                if (movement.exercise.metric in setOf(WorkoutMetric.WEIGHT_REPS, WorkoutMetric.BODYWEIGHT_REPS, WorkoutMetric.ASSISTED_REPS, WorkoutMetric.REPS))
                    set.durationSeconds?.let { appendLine("    计时 $it 秒") }
                set.rpe?.let { appendLine("    RPE ${number(it)}") }
                if (set.comment.isNotBlank()) appendLine("    组心得：${set.comment}")
                if (set.kind == WorkoutSetKind.DROP) set.dropSegments.forEachIndexed { dropIndex, segment ->
                    val dropWeight = if (movement.singleSide) "右 ${number(segment.weightKg ?: 0.0)} / 左 ${number(segment.leftWeightKg ?: 0.0)} kg"
                        else "${number(segment.weightKg ?: 0.0)} kg"
                    appendLine("    递减段 ${dropIndex + 1}：$dropWeight × ${segment.reps} 次")
                }
            }
            if (movement.notes.isNotBlank()) appendLine("  动作笔记：${movement.notes}")
        }
        if (session.notes.isNotBlank()) appendLine("心得：${session.notes}")
    }.trimEnd()

    private fun number(value: Double): String = String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")
    private fun formatDuration(millis: Long): String {
        val seconds = millis.coerceAtLeast(0) / 1000
        return "${seconds / 3600}小时${seconds / 60 % 60}分${seconds % 60}秒"
    }

    companion object {
        private val cardioLabels = mapOf(
            "distance" to ("距离" to "km"), "kcal" to ("消耗" to "kcal"), "bpm" to ("平均心率" to "bpm"),
            "count" to ("次数" to "次"), "steps" to ("步数" to "步"), "floors" to ("楼层" to "层"),
            "laps" to ("圈数" to "圈"), "poolLength" to ("泳池长度" to "米"), "rounds" to ("轮次" to "轮"),
            "workSeconds" to ("单轮训练" to "秒"), "restSeconds" to ("单轮休息" to "秒"),
            "cadence" to ("步频" to "次/分钟"), "strokeRate" to ("划频" to "次/分钟"),
            "avgSpeed" to ("均速" to "km/h"), "pace" to ("配速" to "分/公里"), "elevationGain" to ("累计爬升" to "米"),
        )
        // All repository instances share the lock; the unique activeSlot also protects the SQLite boundary.
        private val mutationLock = Mutex()
    }
}
