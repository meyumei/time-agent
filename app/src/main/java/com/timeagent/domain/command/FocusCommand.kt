package com.timeagent.domain.command

/** The three supported focus modes; the database stores the enum name. */
enum class FocusMode { POMODORO, COUNT_UP, COUNT_DOWN }

/**
 * All focus writes share [WriteFocus] so CommandExecutor supplies request-id idempotency,
 * version checks, one journal group and one transaction. `Tick` is deliberately a mutation:
 * it is executed by the alarm receiver/UI boundary watcher, never once per displayed second.
 */
sealed interface FocusMutation {
    val id: String

    data class Start(
        override val id: String,
        val label: String,
        val mode: FocusMode = FocusMode.POMODORO,
        val focusMinutes: Int = 25,
        val breakMinutes: Int = 5,
        val longBreakMinutes: Int = 15,
        val rounds: Int = 4,
        val taskId: String? = null,
        val scheduleBlockId: String? = null,
        val switchTimerId: String? = null,
        val expectedTimerVersion: Long? = null,
        val switchWorkoutId: String? = null,
        val expectedWorkoutVersion: Long? = null,
        val longBreakEvery: Int = 4,
    ) : FocusMutation

    data class Pause(override val id: String, val expectedVersion: Long) : FocusMutation

    data class Resume(override val id: String, val expectedVersion: Long) : FocusMutation

    data class Finish(
        override val id: String,
        val expectedVersion: Long,
        val save: Boolean = true,
    ) : FocusMutation

    data class SkipBreak(override val id: String, val expectedVersion: Long) : FocusMutation

    data class Tick(override val id: String, val expectedVersion: Long) : FocusMutation

    data class AdjustSegment(
        override val id: String,
        val expectedVersion: Long,
        val segmentIndex: Int,
        val startAt: Long,
        val endAt: Long,
        val title: String? = null,
        val notes: String? = null,
    ) : FocusMutation
}

/**
 * Main-database command for the focus module. CommandExecutor dispatches it to
 * `FocusRepository.applyMutation` inside the existing transaction + commandWrite block and
 * serializes the returned FocusSessionEntity as the result payload.
 */
data class WriteFocus(
    override val requestId: String,
    val mutation: FocusMutation,
    override val origin: Origin = Origin.UI,
) : Command

/** Ends the exact focus the user confirmed and starts a free timer in one undo group. */
data class SwitchFocusToTimer(
    override val requestId: String,
    val focusId: String,
    val expectedVersion: Long,
    val taskId: String?,
    val expectedTaskVersion: Long?,
    override val origin: Origin = Origin.UI,
) : Command
