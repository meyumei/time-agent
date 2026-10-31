package com.timeagent.core.workout

import java.util.UUID

enum class WorkoutMetric { WEIGHT_REPS, REPS, DURATION, BODYWEIGHT_REPS, ASSISTED_REPS, CARDIO, STRETCH, TABATA }
enum class WorkoutSetKind { NORMAL, WARMUP, DROP, FAILURE }
enum class WorkoutSessionStatus { ACTIVE, PAUSED, COMPLETED, DISCARDED }

data class ExerciseDefinition(
    val id: String,
    val name: String,
    val primaryMuscles: List<String>,
    val secondaryMuscles: List<String> = emptyList(),
    val equipment: String = "",
    val metric: WorkoutMetric = WorkoutMetric.WEIGHT_REPS,
    val instructions: String = "",
    val animationAsset: String? = null,
    val sourceType: String = "weight",
    val recordFieldKeys: List<String> = emptyList(),
)

data class WorkoutSet(
    val id: String = UUID.randomUUID().toString(),
    val kind: WorkoutSetKind = WorkoutSetKind.NORMAL,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val durationSeconds: Int? = null,
    val completedAt: Long? = null,
    val leftWeightKg: Double? = null,
    val distanceMeters: Double? = null,
    val caloriesKcal: Double? = null,
    val recordValues: Map<String, String> = emptyMap(),
    val dropSegments: List<WorkoutDropSegment> = emptyList(),
    val tabata: WorkoutTabata? = null,
    val rpe: Double? = null,
    val comment: String = "",
) {
    val isCompleted: Boolean get() = completedAt != null
}

data class WorkoutMovement(
    val id: String = UUID.randomUUID().toString(),
    val exercise: ExerciseDefinition,
    val sets: List<WorkoutSet> = listOf(WorkoutSet(tabata = if (exercise.metric == WorkoutMetric.TABATA) WorkoutTabata() else null)),
    val restSeconds: Int = 90,
    val supersetId: String? = null,
    val notes: String = "",
    val oneSide: Boolean = false,
    val singleSide: Boolean = false,
    val warmupRestSeconds: Int = 60,
)

data class WorkoutSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val startedAt: Long,
    val updatedAt: Long = startedAt,
    val status: WorkoutSessionStatus = WorkoutSessionStatus.ACTIVE,
    val movements: List<WorkoutMovement> = emptyList(),
    val notes: String = "",
    val endedAt: Long? = null,
    val pausedAt: Long? = null,
    val totalPausedMillis: Long = 0,
    val restEndsAt: Long? = null,
    val pausedRestRemainingMillis: Long? = null,
    val timelineRecordId: String? = null,
    val templateId: String? = null,
    val bodyWeightKg: Double? = null,
    val intervalTimer: WorkoutIntervalTimer? = null,
    val setTimer: WorkoutSetTimer? = null,
)

data class WorkoutDropSegment(val weightKg: Double? = null, val reps: Int? = null, val leftWeightKg: Double? = null)

data class WorkoutTabata(val prepareSeconds: Int = 5, val workSeconds: Int = 20, val restSeconds: Int = 10, val rounds: Int = 8)
enum class WorkoutIntervalPhase { PREPARE, WORK, REST, FINISHED }

data class WorkoutIntervalTimer(
    val movementId: String,
    val setId: String,
    val startedAt: Long,
    val pausedMillisAtStart: Long,
    val config: WorkoutTabata,
)

data class WorkoutSetTimer(
    val movementId: String,
    val setId: String,
    val startedAt: Long,
    val pausedMillisAtStart: Long,
)

data class WorkoutIntervalState(
    val phase: WorkoutIntervalPhase,
    val round: Int,
    val remainingMillis: Long,
    val totalRemainingMillis: Long = remainingMillis,
)

data class WorkoutTemplate(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val movements: List<WorkoutMovement>,
    val notes: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = createdAt,
)

data class WorkoutSummary(
    val elapsedMillis: Long,
    val completedSets: Int,
    val completedMovements: Int,
    val volumeKg: Double,
    val workingSets: Int = completedSets,
    val warmupSets: Int = 0,
    val completedRounds: Int = completedSets,
)

class NoCompletedWorkoutSets : IllegalArgumentException("请先完成至少一组训练，再结束训练")
class WorkoutAlreadyActive : IllegalStateException("已有训练正在进行，请先恢复或结束当前训练")
