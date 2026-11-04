package com.timeagent.domain.command

import com.timeagent.data.ReminderEntity

/**
 * Reminder writes are applied by [com.timeagent.reminders.ReminderInstanceRepository.applyMutation].
 * Save/Delete keep the existing reminder row semantics (version check, journal, undo); Snooze and
 * Act only move derived instances so the original `remindAt` is never rewritten.
 */
sealed interface ReminderMutation {
    data class Save(
        val item: ReminderEntity,
        val expectedVersion: Long? = null,
        val automatic: Boolean = false,
    ) : ReminderMutation

    data class Delete(val id: String, val expectedVersion: Long? = null) : ReminderMutation

    data class Snooze(val id: String, val expectedVersion: Long? = null, val fireAt: Long) : ReminderMutation

    data class Act(
        val id: String,
        val expectedVersion: Long? = null,
        val completeTask: Boolean = false,
    ) : ReminderMutation

    data class Fire(val instanceId: String) : ReminderMutation

    data object Reconcile : ReminderMutation
}

data class WriteReminder(
    override val requestId: String,
    val mutation: ReminderMutation,
    override val origin: Origin = Origin.UI,
) : Command
