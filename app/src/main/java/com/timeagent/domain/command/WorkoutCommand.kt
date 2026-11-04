package com.timeagent.domain.command

import com.timeagent.core.workout.ExerciseDefinition
import com.timeagent.core.workout.WorkoutSession
import com.timeagent.core.workout.WorkoutTemplate
import com.timeagent.data.TimeRecordEntity

/**
 * Raised before a workout starts when the single main timer slot is occupied by a free timer.
 * The UI shows the running record and, after confirmation, passes it back as `switchTimer`.
 */
class WorkoutTimerConflict(val timer: TimeRecordEntity) :
    IllegalStateException("已有自由计时正在进行，请确认结束当前计时后再开始训练")

class WorkoutFocusConflict(val focus: com.timeagent.focus.FocusSessionEntity) :
    IllegalStateException("已有专注正在进行，请确认结束当前专注后再开始训练")

/**
 * Training mutations are applied by [com.timeagent.workout.data.WorkoutRepository.applyMutation].
 * Sessions and templates carry their own payloads so commands only ever persist what the user saw.
 */
sealed interface WorkoutMutation {
    data class Start(
        val sessionId: String,
        val title: String,
        val exercises: List<ExerciseDefinition> = emptyList(),
        val switchTimer: TimeRecordEntity? = null,
        val switchFocus: com.timeagent.focus.FocusSessionEntity? = null,
    ) : WorkoutMutation

    data class StartFromTemplate(
        val sessionId: String,
        val templateId: String,
        val switchTimer: TimeRecordEntity? = null,
        val switchFocus: com.timeagent.focus.FocusSessionEntity? = null,
    ) : WorkoutMutation

    data class RepeatSession(
        val sessionId: String,
        val previousSessionId: String,
        val switchTimer: TimeRecordEntity? = null,
        val switchFocus: com.timeagent.focus.FocusSessionEntity? = null,
    ) : WorkoutMutation

    data class SaveSession(
        val session: WorkoutSession,
        val expectedUpdatedAt: Long?,
        val label: String = "保存训练",
    ) : WorkoutMutation

    data class Finish(
        val sessionId: String,
        val notes: String? = null,
        val expectedUpdatedAt: Long? = null,
    ) : WorkoutMutation

    data class SaveTemplate(
        val template: WorkoutTemplate,
        val expectedUpdatedAt: Long? = null,
    ) : WorkoutMutation

    data class DeleteTemplate(val templateId: String, val expectedUpdatedAt: Long?) : WorkoutMutation

    data class SwitchToTimer(
        val sessionId: String,
        val expectedUpdatedAt: Long,
        val taskId: String?,
        val expectedTaskVersion: Long?,
        val recordId: String,
    ) : WorkoutMutation
}

/**
 * The only command entry for training writes. CommandExecutor supplies request-id idempotency,
 * the main-database journal group and the cross-database ordering; the result payload is the
 * saved session/template (or `null` after a deletion).
 */
data class WriteWorkout(
    override val requestId: String,
    val mutation: WorkoutMutation,
    override val origin: Origin = Origin.UI,
) : Command
