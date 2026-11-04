package com.timeagent.domain.command

import com.timeagent.habits.HabitEntity

/**
 * Habit writes are applied by [com.timeagent.habits.HabitRepository.applyMutation] inside
 * CommandExecutor's transaction + commandWrite block, so request ids, version checks, the undo
 * journal (`HABIT` / `HABIT_CHECKIN`) and the DataChanged event all come from the one write path.
 *
 * Only [Checkin] / [ClearCheckin] create manual rows. Derived sources never write statistics back.
 */
sealed interface HabitMutation {
    data class Save(
        val habit: HabitEntity,
        val expectedVersion: Long? = null,
    ) : HabitMutation

    /** Archives or restores a habit. History and check-ins stay readable either way. */
    data class Archive(
        val id: String,
        val archived: Boolean,
        val expectedVersion: Long? = null,
    ) : HabitMutation

    data class Checkin(
        val habitId: String,
        /** ISO local date of the habit day. */
        val date: String,
        val value: Int,
        val note: String = "",
        val expectedVersion: Long? = null,
    ) : HabitMutation

    data class ClearCheckin(
        val habitId: String,
        val date: String,
        val expectedVersion: Long? = null,
    ) : HabitMutation
}

data class WriteHabit(
    override val requestId: String,
    val mutation: HabitMutation,
    override val origin: Origin = Origin.UI,
) : Command
