package com.timeagent.reminders

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock

/**
 * Keeps AlarmManager in sync with the persisted instance table.
 *
 * The persisted registry lets a later process cancel an alarm armed by an earlier one. Every still
 * future instance is (re)armed on each pass; setting the same PendingIntent and trigger time is
 * idempotent and keeps alarms alive across permission changes and process restarts.
 */
class ReminderInstanceScheduler(
    context: Context,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val context = context.applicationContext
    private val alarms = AndroidReminderScheduler(this.context)
    private val registry = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    suspend fun sync(instances: List<ReminderInstanceEntity>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = clock.millis()
            val onTime = instances.filter { it.state == REMINDER_STATE_SCHEDULED }
            val desired = onTime.filter { it.fireAt > now }.associateBy { it.id }
            // A broadcast that is a few seconds late may still be queued. Do not cancel it and do
            // not re-arm it; the Fire mutation and the next refresh will settle its state.
            val keep = onTime.filter { it.fireAt in (now - ReminderInstanceRepository.GRACE_MS + 1)..now }
            val previous = registry.all.mapNotNull { (key, value) ->
                if (!key.startsWith(KEY_PREFIX)) null
                else (value as? Long)?.let { key.removePrefix(KEY_PREFIX) to it }
            }.toMap()

            previous.keys.forEach { id ->
                if (id !in desired && keep.none { it.id == id }) {
                    alarms.cancelInstance(id)
                }
            }
            desired.values.forEach { instance -> alarms.schedule(instance) }

            val next = mutableMapOf<String, Long>()
            desired.forEach { (id, instance) -> next[id] = instance.fireAt }
            keep.forEach { instance ->
                if (instance.id in previous) next[instance.id] = previous.getValue(instance.id)
            }
            registry.edit().clear().apply {
                next.forEach { (id, fireAt) -> putLong(KEY_PREFIX + id, fireAt) }
            }.commit()
        }
    }

    private companion object {
        const val PREFS = "reminder_instance_alarms_v1"
        const val KEY_PREFIX = "instance:"
    }
}
