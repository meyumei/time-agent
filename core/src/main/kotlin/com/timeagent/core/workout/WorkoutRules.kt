package com.timeagent.core.workout

import java.util.UUID
import kotlin.math.floor

object WorkoutRules {
    fun elapsedMillis(session: WorkoutSession, now: Long): Long =
        ((session.endedAt ?: session.pausedAt ?: now) - session.startedAt - session.totalPausedMillis).coerceAtLeast(0)

    fun restRemainingMillis(session: WorkoutSession, now: Long): Long = when (session.status) {
        WorkoutSessionStatus.PAUSED -> session.pausedRestRemainingMillis ?: 0
        WorkoutSessionStatus.ACTIVE -> ((session.restEndsAt ?: now) - now).coerceAtLeast(0)
        else -> 0
    }

    fun startSetTimer(session: WorkoutSession, movementId: String, setId: String, now: Long): WorkoutSession {
        require(session.status == WorkoutSessionStatus.ACTIVE) { "请先恢复训练再开始单组计时" }
        require(now >= session.startedAt) { "当前时间早于训练开始" }
        require(session.intervalTimer == null) { "请先完成Tabata阶段计时" }
        session.setTimer?.let {
            require(it.movementId == movementId && it.setId == setId) { "请先停止当前单组计时" }
            return session
        }
        val movement = requireNotNull(session.movements.find { it.id == movementId }) { "动作不存在" }
        val set = requireNotNull(movement.sets.find { it.id == setId }) { "训练组不存在" }
        require(!set.isCompleted) { "此组已经完成" }
        return session.copy(updatedAt = now, restEndsAt = null, pausedRestRemainingMillis = null,
            setTimer = WorkoutSetTimer(movementId, setId, now, session.totalPausedMillis)).also(::validateSession)
    }

    fun stopSetTimer(session: WorkoutSession, now: Long): WorkoutSession {
        val timer = session.setTimer ?: return session
        require(session.status == WorkoutSessionStatus.ACTIVE || session.status == WorkoutSessionStatus.PAUSED) { "已结束训练不能继续计时" }
        require(now >= timer.startedAt) { "当前时间早于单组计时开始" }
        val seconds = (sessionSetTimerElapsedMillis(session, now) / 1000).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        return session.copy(updatedAt = now, setTimer = null, movements = session.movements.map { movement ->
            if (movement.id != timer.movementId) movement else movement.copy(sets = movement.sets.map { set ->
                if (set.id != timer.setId) set else set.copy(durationSeconds = seconds)
            })
        }).also(::validateSession)
    }

    fun sessionSetTimerElapsedMillis(session: WorkoutSession, now: Long): Long {
        val timer = session.setTimer ?: return 0
        return ((session.endedAt ?: session.pausedAt ?: now) - timer.startedAt -
            (session.totalPausedMillis - timer.pausedMillisAtStart)).coerceAtLeast(0)
    }

    fun summary(session: WorkoutSession, now: Long): WorkoutSummary {
        val completedIds = completedSetIds(session)
        val completed = session.movements.flatMap { movement -> movement.sets.filter { it.id in completedIds }.map { movement to it } }
        val rounds = session.movements.filter { it.supersetId == null }.sumOf { movement -> movement.sets.count { it.id in completedIds } } +
            session.movements.filter { it.supersetId != null }.groupBy { it.supersetId }.values.sumOf { group ->
                (0 until (group.maxOfOrNull { it.sets.size } ?: 0)).count { index ->
                    group.mapNotNull { it.sets.getOrNull(index) }.all { it.id in completedIds }
                }
            }
        return WorkoutSummary(
            elapsedMillis(session, now), completed.size,
            session.movements.count { movement -> movement.sets.any { it.id in completedIds } },
            completed.sumOf { (movement, set) -> setVolume(movement, set, session.bodyWeightKg) },
            workingSets = completed.count { it.second.kind != WorkoutSetKind.WARMUP },
            warmupSets = completed.count { it.second.kind == WorkoutSetKind.WARMUP },
            completedRounds = rounds,
        )
    }

    fun completedSetIds(session: WorkoutSession): Set<String> {
        val groups = session.movements.filter { it.supersetId != null }.groupBy { it.supersetId }
        return session.movements.flatMap { movement ->
            movement.sets.filterIndexed { index, set ->
                set.isCompleted && (movement.supersetId == null || groups.getValue(movement.supersetId)
                    .all { it.sets.getOrNull(index)?.isCompleted != false })
            }.map { it.id }
        }.toSet()
    }

    fun validateSession(session: WorkoutSession) {
        require(session.id.isNotBlank()) { "训练标识不能为空" }
        require(session.bodyWeightKg == null || session.bodyWeightKg.isFinite() && session.bodyWeightKg > 0) { "体重必须是有效的正数" }
        validateContent(session.title, session.movements, session.notes, session.bodyWeightKg)
        require(session.totalPausedMillis >= 0) { "暂停时长无效" }
        require(session.updatedAt >= session.startedAt) { "训练更新时间无效" }
        require(session.movements.flatMap { it.sets }.all { it.completedAt == null || it.completedAt >= session.startedAt }) { "完成时间早于训练开始" }
        require(session.endedAt == null || session.endedAt >= session.startedAt) { "训练结束时间无效" }
        when (session.status) {
            WorkoutSessionStatus.ACTIVE -> require(session.pausedAt == null && session.endedAt == null) { "进行中训练状态无效" }
            WorkoutSessionStatus.PAUSED -> require(session.pausedAt != null && session.pausedAt >= session.startedAt && session.endedAt == null && session.restEndsAt == null) { "暂停训练状态无效" }
            else -> require(session.endedAt != null && session.pausedAt == null && session.restEndsAt == null) { "已结束训练状态无效" }
        }
        require(session.pausedRestRemainingMillis == null || session.pausedRestRemainingMillis >= 0) { "休息时长无效" }
        session.setTimer?.let { timer ->
            val set = session.movements.find { it.id == timer.movementId }?.sets?.find { it.id == timer.setId }
            require(set != null && !set.isCompleted) { "单组计时所属训练组不存在或已完成" }
            require(session.status == WorkoutSessionStatus.ACTIVE || session.status == WorkoutSessionStatus.PAUSED) { "已结束训练不能保留单组计时" }
            require(session.intervalTimer == null) { "单组秒表和Tabata不能同时计时" }
            require(timer.startedAt in session.startedAt..session.updatedAt && timer.pausedMillisAtStart in 0..session.totalPausedMillis) { "单组计时开始时间无效" }
        }
        session.intervalTimer?.let { timer ->
            val movement = session.movements.find { it.id == timer.movementId }
            val set = movement?.sets?.find { it.id == timer.setId }
            require(movement?.exercise?.metric == WorkoutMetric.TABATA && set != null && !set.isCompleted) { "阶段计时所属训练组不存在或已完成" }
            require(timer.startedAt >= session.startedAt && timer.pausedMillisAtStart in 0..session.totalPausedMillis) { "阶段计时开始时间无效" }
            validateTabata(timer.config)
        }
        require(summary(session, session.updatedAt).volumeKg.isFinite()) { "训练容量过大，请检查重量和次数" }
    }

    fun validateTemplate(template: WorkoutTemplate) {
        require(template.id.isNotBlank()) { "模板标识不能为空" }
        validateContent(template.title, template.movements, template.notes)
    }

    private fun validateContent(title: String, movements: List<WorkoutMovement>, notes: String, bodyWeightKg: Double? = null) {
        require(title.trim().length in 1..200) { "训练名称不能为空，最多200字" }
        require(notes.length <= 20_000) { "训练心得最多20000字" }
        require(movements.all { it.id.isNotBlank() } && movements.map { it.id }.toSet().size == movements.size) { "动作标识重复或为空" }
        val sets = movements.flatMap { it.sets }
        require(sets.all { it.id.isNotBlank() } && sets.map { it.id }.toSet().size == sets.size) { "训练组标识重复或为空" }
        movements.forEach { movement ->
            require(movement.exercise.id.isNotBlank() && movement.exercise.name.trim().length in 1..200) { "请填写有效的动作名称" }
            require(movement.restSeconds in 0..86_400) { "组间休息应为0至86400秒" }
            require(movement.warmupRestSeconds in 0..86_400) { "热身组休息应为0至86400秒" }
            require(movement.notes.length <= 20_000) { "动作笔记最多20000字" }
            require(movement.supersetId == null || movement.supersetId.isNotBlank()) { "超级组标识不能为空" }
            movement.sets.forEach { validateSet(it, movement, it.isCompleted, bodyWeightKg) }
        }
    }

    private fun validateSet(set: WorkoutSet, movement: WorkoutMovement, completing: Boolean, bodyWeightKg: Double?) {
        val metric = movement.exercise.metric
        require(set.rpe == null || set.rpe.isFinite() && set.rpe in 0.0..10.0) { "RPE应为0至10的有效数字" }
        require(set.comment.length <= 20_000) { "每组心得最多20000字" }
        require(set.weightKg == null || set.weightKg.isFinite() && set.weightKg >= 0) { "重量必须是有效的非负数字" }
        require(set.leftWeightKg == null || set.leftWeightKg.isFinite() && set.leftWeightKg >= 0) { "左侧重量必须是有效的非负数字" }
        require(set.reps == null || set.reps > 0) { "次数必须大于0" }
        require(set.durationSeconds == null || set.durationSeconds > 0) { "时长必须大于0秒" }
        require(set.distanceMeters == null || set.distanceMeters.isFinite() && set.distanceMeters >= 0) { "距离必须是有效的非负数字" }
        require(set.caloriesKcal == null || set.caloriesKcal.isFinite() && set.caloriesKcal >= 0) { "热量必须是有效的非负数字" }
        set.recordValues.forEach { (key, value) -> validateRecordValue(key, value) }
        set.tabata?.let(::validateTabata)
        set.dropSegments.forEach { segment ->
            require(segment.weightKg == null || segment.weightKg.isFinite() && segment.weightKg >= 0) { "递减段重量必须是有效的非负数字" }
            require(segment.leftWeightKg == null || segment.leftWeightKg.isFinite() && segment.leftWeightKg >= 0) { "递减段左侧重量无效" }
            require(segment.reps == null || segment.reps > 0) { "递减段次数必须大于0" }
        }
        if (!completing) return
        when (metric) {
            WorkoutMetric.WEIGHT_REPS, WorkoutMetric.BODYWEIGHT_REPS, WorkoutMetric.ASSISTED_REPS -> {
                require(set.weightKg != null && set.reps != null && (set.weightKg * set.reps).isFinite()) { "请填写有效重量和次数，自重动作可填0kg" }
                if (metric == WorkoutMetric.BODYWEIGHT_REPS || metric == WorkoutMetric.ASSISTED_REPS)
                    require(bodyWeightKg != null && bodyWeightKg.isFinite() && bodyWeightKg > 0) { "请先填写本次训练体重" }
                if (metric == WorkoutMetric.ASSISTED_REPS) require(set.weightKg <= bodyWeightKg!!) { "辅助重量不能大于体重" }
                if (movement.singleSide && metric != WorkoutMetric.BODYWEIGHT_REPS) require(set.leftWeightKg != null) { "请填写左侧重量" }
                if (set.kind == WorkoutSetKind.DROP) set.dropSegments.forEach { segment ->
                    require(segment.weightKg != null && segment.reps != null && (!movement.singleSide || segment.leftWeightKg != null)) { "请补全每个递减段的重量与次数" }
                }
            }
            WorkoutMetric.REPS -> require(set.reps != null) { "请填写完成次数" }
            WorkoutMetric.DURATION -> require(set.durationSeconds != null) { "请填写训练时长" }
            WorkoutMetric.CARDIO -> require(set.durationSeconds != null || set.distanceMeters != null || set.caloriesKcal != null ||
                set.recordValues.any { (key, value) -> value.isNotBlank() && (movement.exercise.recordFieldKeys.isEmpty() || key in movement.exercise.recordFieldKeys) }) { "请填写时长或至少一项有氧记录" }
            WorkoutMetric.STRETCH -> Unit
            WorkoutMetric.TABATA -> { validateTabata(set.tabata ?: WorkoutTabata()); require(set.durationSeconds != null) { "请完成阶段计时或填写实际训练时长" } }
        }
    }

    private fun setVolume(movement: WorkoutMovement, set: WorkoutSet, bodyWeightKg: Double?): Double {
        if (set.kind == WorkoutSetKind.WARMUP) return 0.0
        val load = when (movement.exercise.metric) {
            WorkoutMetric.WEIGHT_REPS -> (set.weightKg ?: 0.0) + if (movement.singleSide) set.leftWeightKg ?: 0.0 else 0.0
            WorkoutMetric.BODYWEIGHT_REPS -> (bodyWeightKg ?: 0.0) + (set.weightKg ?: 0.0)
            WorkoutMetric.ASSISTED_REPS -> (bodyWeightKg ?: 0.0) - (set.weightKg ?: 0.0) + if (movement.singleSide) set.leftWeightKg ?: 0.0 else 0.0
            else -> return 0.0
        }
        val drops = if (set.kind == WorkoutSetKind.DROP) set.dropSegments.sumOf { segment ->
            roundCapacity(((segment.weightKg ?: 0.0) + if (movement.singleSide) segment.leftWeightKg ?: 0.0 else 0.0) * (segment.reps ?: 0))
        } else 0.0
        // Canonical kg avoids the source app's bodyweight/lb unit-mixing bug.
        return roundCapacity(roundCapacity(load * (set.reps ?: 0)) + drops) * if (movement.oneSide) 2 else 1
    }

    private fun roundCapacity(value: Double): Double = floor(value * 10 + 0.5) / 10

    private val integerRecordFields = setOf("count", "steps", "floors", "laps", "poolLength", "rounds", "workSeconds", "restSeconds", "cadence", "strokeRate", "elevationGain")
    private val decimalRecordFields = setOf("distance", "kcal", "avgSpeed", "bpm")
    private fun validateRecordValue(key: String, raw: String) {
        require(key in integerRecordFields || key in decimalRecordFields || key == "pace") { "不支持的有氧记录字段：$key" }
        if (raw.isBlank()) return
        val value = raw.trim()
        if (key == "pace") require(Regex("\\d+:[0-5]\\d").matches(value)) { "配速请按分:秒填写，如5:30" }
        else {
            val numeric = value.toDoubleOrNull()
            require(numeric != null && numeric.isFinite() && numeric >= 0 &&
                (if (key in integerRecordFields) Regex("\\d+").matches(value) else Regex("(?:\\d+(?:\\.\\d*)?|\\.\\d+)").matches(value))) { "有氧记录必须是有效的非负数字" }
        }
    }

    private fun validateTabata(config: WorkoutTabata) {
        require(config.prepareSeconds in 0..86_400 && config.workSeconds in 1..86_400 && config.restSeconds in 0..86_400 && config.rounds in 1..1000) { "请填写有效的准备、训练、休息秒数和轮数" }
    }

    private fun intervalTotalMillis(config: WorkoutTabata): Long =
        (config.prepareSeconds.toLong() + config.workSeconds.toLong() * config.rounds + config.restSeconds.toLong() * (config.rounds - 1)) * 1000

    fun startInterval(session: WorkoutSession, movementId: String, setId: String, now: Long): WorkoutSession {
        require(session.status == WorkoutSessionStatus.ACTIVE) { "请先恢复训练再开始阶段计时" }
        require(session.setTimer == null) { "请先停止当前单组计时" }
        require(now >= session.startedAt) { "当前时间早于训练开始" }
        session.intervalTimer?.let {
            require(it.movementId == movementId && it.setId == setId) { "请先完成或取消当前阶段计时" }
            return session
        }
        val movement = requireNotNull(session.movements.find { it.id == movementId }) { "动作不存在" }
        require(movement.exercise.metric == WorkoutMetric.TABATA) { "此动作不支持Tabata阶段计时" }
        val set = requireNotNull(movement.sets.find { it.id == setId }) { "训练组不存在" }
        require(!set.isCompleted) { "此组已经完成" }
        val config = set.tabata ?: WorkoutTabata()
        validateTabata(config)
        return session.copy(updatedAt = now, restEndsAt = null, pausedRestRemainingMillis = null,
            movements = session.movements.map { item -> if (item.id != movementId) item else item.copy(sets = item.sets.map { if (it.id != setId) it else it.copy(tabata = config) }) },
            intervalTimer = WorkoutIntervalTimer(movementId, setId, now, session.totalPausedMillis, config)).also(::validateSession)
    }

    fun finishInterval(session: WorkoutSession, now: Long): WorkoutSession {
        val timer = session.intervalTimer ?: return session
        require(session.status == WorkoutSessionStatus.ACTIVE) { "请先恢复训练再完成阶段计时" }
        check(intervalState(session, now)?.phase == WorkoutIntervalPhase.FINISHED) { "阶段计时尚未完成" }
        val prepared = session.copy(intervalTimer = null, movements = session.movements.map { movement ->
            if (movement.id != timer.movementId) movement else movement.copy(sets = movement.sets.map { set ->
                if (set.id != timer.setId) set else set.copy(tabata = timer.config, durationSeconds = (intervalTotalMillis(timer.config) / 1000).toInt())
            })
        })
        return completeSet(prepared, timer.movementId, timer.setId, now)
    }

    fun intervalState(session: WorkoutSession, now: Long): WorkoutIntervalState? {
        val timer = session.intervalTimer ?: return null
        val config = timer.config
        val elapsed = ((session.endedAt ?: session.pausedAt ?: now) - timer.startedAt - (session.totalPausedMillis - timer.pausedMillisAtStart)).coerceAtLeast(0)
        val totalRemaining = (intervalTotalMillis(config) - elapsed).coerceAtLeast(0)
        val prepare = config.prepareSeconds * 1000L
        if (elapsed < prepare) return WorkoutIntervalState(WorkoutIntervalPhase.PREPARE, 1, prepare - elapsed, totalRemaining)
        if (totalRemaining == 0L) return WorkoutIntervalState(WorkoutIntervalPhase.FINISHED, config.rounds, 0, 0)
        val work = config.workSeconds * 1000L
        val rest = config.restSeconds * 1000L
        val position = elapsed - prepare
        val round = (position / (work + rest)).toInt() + 1
        val within = position % (work + rest)
        return if (within < work) WorkoutIntervalState(WorkoutIntervalPhase.WORK, round, work - within, totalRemaining)
        else WorkoutIntervalState(WorkoutIntervalPhase.REST, round, work + rest - within, totalRemaining)
    }

    fun pause(session: WorkoutSession, now: Long): WorkoutSession {
        if (session.status == WorkoutSessionStatus.PAUSED) return session
        require(session.status == WorkoutSessionStatus.ACTIVE) { "只有进行中的训练可以暂停" }
        require(now >= session.startedAt) { "当前时间早于训练开始" }
        return session.copy(status = WorkoutSessionStatus.PAUSED, updatedAt = now, pausedAt = now,
            pausedRestRemainingMillis = session.restEndsAt?.let { restRemainingMillis(session, now) }, restEndsAt = null)
    }

    fun resume(session: WorkoutSession, now: Long): WorkoutSession {
        if (session.status == WorkoutSessionStatus.ACTIVE) return session
        require(session.status == WorkoutSessionStatus.PAUSED) { "只有暂停的训练可以恢复" }
        val pausedAt = requireNotNull(session.pausedAt) { "暂停时间丢失" }
        require(now >= pausedAt) { "当前时间早于暂停时间" }
        return session.copy(status = WorkoutSessionStatus.ACTIVE, updatedAt = now, pausedAt = null,
            totalPausedMillis = session.totalPausedMillis + now - pausedAt,
            restEndsAt = session.pausedRestRemainingMillis?.takeIf { it > 0 }?.let { now + it }, pausedRestRemainingMillis = null)
    }

    fun completeSet(session: WorkoutSession, movementId: String, setId: String, now: Long): WorkoutSession {
        require(session.status == WorkoutSessionStatus.ACTIVE) { "请先恢复训练再完成组" }
        require(now >= session.startedAt) { "当前时间早于训练开始" }
        val ready = if (session.setTimer?.let { it.movementId == movementId && it.setId == setId } == true) stopSetTimer(session, now) else session
        val movement = requireNotNull(ready.movements.find { it.id == movementId }) { "动作不存在" }
        val index = movement.sets.indexOfFirst { it.id == setId }
        require(index >= 0) { "训练组不存在" }
        val set = movement.sets[index]
        if (set.isCompleted) return session
        validateSet(set, movement, true, ready.bodyWeightKg)
        val updated = ready.copy(updatedAt = now, movements = ready.movements.map { item ->
            if (item.id == movementId) item.copy(sets = item.sets.map { if (it.id == setId) it.copy(completedAt = now) else it }) else item
        })
        val roundDone = set.id in completedSetIds(updated)
        val roundSets = if (movement.supersetId == null) listOf(set) else updated.movements
            .filter { it.supersetId == movement.supersetId }.mapNotNull { it.sets.getOrNull(index) }
        val restSeconds = if (roundSets.all { it.kind == WorkoutSetKind.WARMUP }) movement.warmupRestSeconds else movement.restSeconds
        return updated.copy(restEndsAt = if (roundDone && restSeconds > 0) now + restSeconds * 1000L else null,
            intervalTimer = session.intervalTimer?.takeUnless { it.movementId == movementId && it.setId == setId })
            .also(::validateSession)
    }

    fun finish(session: WorkoutSession, now: Long): WorkoutSession {
        if (session.status == WorkoutSessionStatus.COMPLETED) return session
        require(session.status != WorkoutSessionStatus.DISCARDED) { "已放弃的训练不能结束" }
        validateSession(session)
        if (summary(session, now).completedSets == 0) throw NoCompletedWorkoutSets()
        require(now > session.startedAt) { "训练结束时间必须晚于开始时间" }
        val paused = session.pausedAt?.let { (now - it).coerceAtLeast(0) } ?: 0
        return session.copy(status = WorkoutSessionStatus.COMPLETED, endedAt = now, updatedAt = now,
            pausedAt = null, totalPausedMillis = session.totalPausedMillis + paused,
            restEndsAt = null, pausedRestRemainingMillis = null, intervalTimer = null, setTimer = null).also(::validateSession)
    }

    fun templateFrom(session: WorkoutSession, title: String, now: Long): WorkoutTemplate =
        WorkoutTemplate(title = title.trim(), movements = freshMovements(session.movements, clearResults = true), createdAt = now)
            .also(::validateTemplate)

    fun startFromTemplate(template: WorkoutTemplate, now: Long): WorkoutSession {
        validateTemplate(template)
        return WorkoutSession(title = template.title.trim(), startedAt = now, movements = freshMovements(template.movements), templateId = template.id)
            .also(::validateSession)
    }

    private fun freshMovements(movements: List<WorkoutMovement>, clearResults: Boolean = false): List<WorkoutMovement> {
        val groups = movements.mapNotNull { it.supersetId }.distinct().associateWith { UUID.randomUUID().toString() }
        return movements.map { movement ->
            movement.copy(id = UUID.randomUUID().toString(), supersetId = movement.supersetId?.let { groups[it] },
                sets = movement.sets.map { set -> set.copy(id = UUID.randomUUID().toString(), completedAt = null,
                    durationSeconds = if (clearResults || movement.exercise.metric == WorkoutMetric.TABATA) null else set.durationSeconds,
                    distanceMeters = if (clearResults) null else set.distanceMeters,
                    caloriesKcal = if (clearResults) null else set.caloriesKcal,
                    recordValues = if (clearResults) emptyMap() else set.recordValues,
                    rpe = if (clearResults) null else set.rpe, comment = if (clearResults) "" else set.comment,
                ) })
        }
    }
}
