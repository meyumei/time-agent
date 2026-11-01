package com.timeagent.workout.data

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson
import com.timeagent.core.workout.*
import com.timeagent.data.TimeRecordEntity
import com.timeagent.data.TimeRepository
import com.timeagent.di.AppEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.util.Locale

class WorkoutRepository internal constructor(
    private val db: WorkoutDatabase,
    private val timeRepository: TimeRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    constructor(context: Context, timeRepository: TimeRepository, clock: Clock = Clock.systemUTC()) :
        this(AppEntryPoint.from(context).workoutDatabase(), timeRepository, clock)

    private val gson = Gson()
    private val dao = db.dao()

    fun observeSessions(): Flow<List<WorkoutSession>> = dao.observeSessions()
        .map { rows -> rows.map(::decode) }.distinctUntilChanged().flowOn(Dispatchers.IO)

    fun observeTemplates(): Flow<List<WorkoutTemplate>> = dao.observeTemplates()
        .map { rows -> rows.map { gson.fromJson(it.payload, WorkoutTemplate::class.java) } }
        .distinctUntilChanged().flowOn(Dispatchers.IO)

    suspend fun getSession(id: String): WorkoutSession? = withContext(Dispatchers.IO) { dao.session(id)?.let(::decode) }
    suspend fun activeSession(): WorkoutSession? = withContext(Dispatchers.IO) { dao.activeSession()?.let(::decode) }

    suspend fun start(title: String = "自由训练", exercises: List<ExerciseDefinition> = emptyList()): WorkoutSession = write {
        db.withTransaction {
            insertStart(WorkoutSession(title = title.trim(), startedAt = clock.millis(), movements = exercises.map { WorkoutMovement(exercise = it) }))
        }
    }

    suspend fun mutate(id: String, transform: (WorkoutSession) -> WorkoutSession): WorkoutSession = write {
        db.withTransaction {
            val before = requireSession(id)
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
            store(candidate.copy(title = candidate.title.trim(), updatedAt = clock.millis(),
                setTimer = candidate.setTimer?.takeIf { targetRetained(it.movementId, it.setId) },
                intervalTimer = candidate.intervalTimer?.takeIf { targetRetained(it.movementId, it.setId) },
            ))
        }
    }

    suspend fun pause(id: String): WorkoutSession = update(id) { WorkoutRules.pause(it, clock.millis()) }
    suspend fun resume(id: String): WorkoutSession = update(id) { WorkoutRules.resume(it, clock.millis()) }
    suspend fun completeSet(sessionId: String, movementId: String, setId: String): WorkoutSession =
        update(sessionId) { WorkoutRules.completeSet(it, movementId, setId, clock.millis()) }
    suspend fun startInterval(sessionId: String, movementId: String, setId: String): WorkoutSession =
        update(sessionId) { WorkoutRules.startInterval(it, movementId, setId, clock.millis()) }
    suspend fun finishInterval(sessionId: String): WorkoutSession =
        update(sessionId) { WorkoutRules.finishInterval(it, clock.millis()) }
    suspend fun startSetTimer(sessionId: String, movementId: String, setId: String): WorkoutSession =
        update(sessionId) { WorkoutRules.startSetTimer(it, movementId, setId, clock.millis()) }
    suspend fun stopSetTimer(sessionId: String): WorkoutSession =
        update(sessionId) { WorkoutRules.stopSetTimer(it, clock.millis()) }

    suspend fun finish(id: String, notes: String? = null): WorkoutSession = write {
        // Commit the complete workout first; a failed timeline write remains visible and retryable.
        val completed = db.withTransaction {
            val current = requireSession(id)
            if (current.status == WorkoutSessionStatus.COMPLETED) current
            else store(WorkoutRules.finish(current.copy(notes = notes ?: current.notes), clock.millis()))
        }
        if (completed.timelineRecordId != null) return@write completed
        val recordId = "workout:${completed.id}"
        // The main DB's durable request marker also survives user deletion/undo and a lost link commit.
        timeRepository.assistantTransaction("workout-timeline:${completed.id}") {
            val snapshot = timeRepository.snapshot()
            if (snapshot.records.none { it.id == recordId }) {
                val categoryId = snapshot.activities.find { it.id == "fitness" && it.archivedAt == null }?.categoryId
                    ?: snapshot.categories.find { it.name == "健身" && !it.isArchived }?.id
                timeRepository.saveRecord(TimeRecordEntity(
                    id = recordId, taskId = null, startAt = completed.startedAt, endAt = completed.endedAt,
                    categoryId = categoryId, notes = timelineNotes(completed), source = "WORKOUT", title = completed.title,
                ))
            }
        }
        db.withTransaction { store(requireSession(id).copy(timelineRecordId = recordId)) }
    }

    suspend fun discard(id: String): WorkoutSession = update(id) { current ->
        if (current.status == WorkoutSessionStatus.DISCARDED) current
        else {
            require(current.status != WorkoutSessionStatus.COMPLETED) { "已结束训练不能放弃" }
            val now = clock.millis()
            require(now >= current.startedAt) { "当前时间早于训练开始" }
            current.copy(status = WorkoutSessionStatus.DISCARDED, endedAt = now, updatedAt = now,
                totalPausedMillis = current.totalPausedMillis + (current.pausedAt?.let { (now - it).coerceAtLeast(0) } ?: 0),
                pausedAt = null, restEndsAt = null, pausedRestRemainingMillis = null, intervalTimer = null, setTimer = null)
        }
    }

    suspend fun saveTemplate(sessionId: String, title: String): WorkoutTemplate = write {
        db.withTransaction { storeTemplate(WorkoutRules.templateFrom(requireSession(sessionId), title, clock.millis())) }
    }

    suspend fun saveTemplateDefinition(template: WorkoutTemplate): WorkoutTemplate = write {
        db.withTransaction {
            val before = dao.template(template.id)?.let { gson.fromJson(it.payload, WorkoutTemplate::class.java) }
            storeTemplate(template.copy(title = template.title.trim(), createdAt = before?.createdAt ?: clock.millis(), updatedAt = clock.millis(),
                movements = template.movements.map { movement -> movement.copy(sets = movement.sets.map { it.copy(completedAt = null) }) }))
        }
    }

    suspend fun deleteTemplate(id: String): Unit = write { db.withTransaction { dao.deleteTemplate(id) } }

    suspend fun startFromTemplate(templateId: String): WorkoutSession = write {
        db.withTransaction {
            val template = requireNotNull(dao.template(templateId)) { "训练模板不存在" }
                .let { gson.fromJson(it.payload, WorkoutTemplate::class.java) }
            insertStart(WorkoutRules.startFromTemplate(template, clock.millis()))
        }
    }

    suspend fun repeatSession(sessionId: String): WorkoutSession = write {
        db.withTransaction {
            val previous = requireSession(sessionId)
            require(previous.status == WorkoutSessionStatus.COMPLETED) { "只能复训已完成的训练" }
            val now = clock.millis()
            insertStart(WorkoutRules.startFromTemplate(WorkoutRules.templateFrom(previous, previous.title, now), now).copy(templateId = previous.templateId))
        }
    }

    suspend fun lastMovement(exerciseId: String, excludingSessionId: String? = null): WorkoutMovement? = withContext(Dispatchers.IO) {
        dao.sessions().map(::decode).filter { it.status == WorkoutSessionStatus.COMPLETED && it.id != excludingSessionId }
            .sortedByDescending { it.endedAt ?: it.startedAt }.firstNotNullOfOrNull { session ->
                val completedIds = WorkoutRules.completedSetIds(session)
                session.movements.lastOrNull { it.exercise.id == exerciseId && it.sets.any { set -> set.id in completedIds } }
                    ?.let { it.copy(sets = it.sets.filter { set -> set.id in completedIds }) }
            }
    }

    private suspend fun insertStart(session: WorkoutSession): WorkoutSession {
        if (dao.activeSession() != null) throw WorkoutAlreadyActive()
        return store(session)
    }

    private suspend fun store(session: WorkoutSession): WorkoutSession {
        WorkoutRules.validateSession(session)
        dao.putSession(WorkoutSessionRow.from(session, gson))
        return session
    }

    private suspend fun storeTemplate(template: WorkoutTemplate): WorkoutTemplate {
        WorkoutRules.validateTemplate(template)
        dao.putTemplate(WorkoutTemplateRow(template.id, template.updatedAt, gson.toJson(template)))
        return template
    }

    private fun decode(row: WorkoutSessionRow): WorkoutSession = gson.fromJson(row.payload, WorkoutSession::class.java)
    private suspend fun requireSession(id: String): WorkoutSession = requireNotNull(dao.session(id)?.let(::decode)) { "训练不存在" }

    private suspend fun update(id: String, transform: (WorkoutSession) -> WorkoutSession): WorkoutSession = write {
        db.withTransaction { store(transform(requireSession(id))) }
    }

    private suspend fun <T> write(block: suspend () -> T): T = withContext(Dispatchers.IO) { com.timeagent.data.PersonalDataGate.access { mutationLock.withLock { block() } } }

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
