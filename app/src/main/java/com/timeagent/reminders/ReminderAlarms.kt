package com.timeagent.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import com.timeagent.data.TimeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock

enum class AlarmPrecision { EXACT, INEXACT }

internal fun exactAlarmsAllowed(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

/**
 * AlarmManager adapter. Reminder instances use a stable request code (instance-id hash) plus an
 * instance-specific URI; URI identity prevents two colliding hashes from replacing each other's
 * alarms. The legacy reminder-id/time methods remain for old callers and tests.
 */
class AndroidReminderScheduler(context: Context) {
    private val context = context.applicationContext
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun schedule(instance: ReminderInstanceEntity): AlarmPrecision = schedule(
        ReminderIntents.alarmInstance(context, instance.id, instance.reminderId, instance.fireAt),
        reminderInstanceRequestCode(instance.id),
        instance.fireAt,
    )

    fun cancelInstance(instanceId: String) {
        val operation = PendingIntent.getBroadcast(
            context,
            reminderInstanceRequestCode(instanceId),
            ReminderIntents.alarmInstance(context, instanceId, "", 0),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarms.cancel(operation)
        operation.cancel()
    }

    fun schedule(id: String, remindAt: Long): AlarmPrecision =
        schedule(ReminderIntents.alarm(context, id, remindAt), 0, remindAt)

    fun cancel(id: String, remindAt: Long) {
        val operation = PendingIntent.getBroadcast(
            context,
            0,
            ReminderIntents.alarm(context, id, remindAt),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarms.cancel(operation)
        operation.cancel()
    }

    private fun schedule(intent: android.content.Intent, requestCode: Int, triggerAt: Long): AlarmPrecision {
        val operation = requireNotNull(
            PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        if (exactAlarmsAllowed(context)) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
                return AlarmPrecision.EXACT
            } catch (_: SecurityException) {
                // Permission can change between the capability check and this call.
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        return AlarmPrecision.INEXACT
    }
}

class ReminderCoordinator(
    context: Context,
    private val repository: TimeRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val context = context.applicationContext
    private val scheduler = ReminderInstanceScheduler(this.context, clock)
    private val legacyRegistry = this.context.getSharedPreferences("reminder_alarm_registry_v1", Context.MODE_PRIVATE)
    private var observing: Job? = null

    /** Start once in an app-owned scope; the same instance returns its existing active Job. */
    @Synchronized
    fun start(scope: CoroutineScope): Job {
        observing?.takeIf { it.isActive }?.let { return it }
        ReminderRuntime.attach(repository)
        return scope.launch(Dispatchers.IO) {
            // createFlow may not emit until the first invalidation; arm existing rows on launch.
            refreshQuietly()
            repository.database.invalidationTracker.createFlow(
                "reminders",
                "tasks",
                "schedule_blocks",
                "recurring_instances",
                "reminder_instances",
                "ideas",
                "projects",
                "activity_templates",
                "course_occurrences",
            )
                .conflate()
                .catch {
                    // A database closing with the UI must not crash the app scope; existing alarms stay.
                }
                .collect { refreshQuietly() }
        }.also { observing = it }
    }

    /** Also used after boot, a package update, timezone changes or explicit command refresh. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        com.timeagent.recurring.RecurringService(repository).refresh()
        schedulingMutex.withLock {
            val instances = ReminderInstanceRepository(repository)
            cancelLegacyAlarms()
            instances.reconcile()
            scheduler.sync(instances.scheduledSnapshot())
            ReminderNotifications.cancelOrphans(context, instances.notificationTimes())
        }
    }

    private suspend fun refreshQuietly() {
        try {
            refresh()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A closed database or a transient command failure must not kill the observer.
        }
    }

    private fun cancelLegacyAlarms() {
        val previous = legacyRegistry.all.mapNotNull { (id, value) -> (value as? Long)?.let { id to it } }
        if (previous.isEmpty()) return
        val legacy = AndroidReminderScheduler(context)
        previous.forEach { (id, oldTime) -> legacy.cancel(id, oldTime) }
        legacyRegistry.edit().clear().commit()
    }

    private companion object {
        // A foreground coordinator and a restore broadcast may both reconcile in the same process.
        val schedulingMutex = Mutex()
    }
}
