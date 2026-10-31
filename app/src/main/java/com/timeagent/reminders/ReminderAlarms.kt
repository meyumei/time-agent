package com.timeagent.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import com.timeagent.data.ReminderEntity
import com.timeagent.data.TaskEntity
import com.timeagent.data.TimeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock

enum class AlarmPrecision { EXACT, INEXACT }

internal fun exactAlarmsAllowed(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

/** One immutable identity per id and time lets a later edit cancel the old operation. */
class AndroidReminderScheduler(context: Context) {
    private val context = context.applicationContext
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun schedule(id: String, remindAt: Long): AlarmPrecision {
        val operation = requireNotNull(PendingIntent.getBroadcast(
            context, 0, ReminderIntents.alarm(context, id, remindAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ))
        if (exactAlarmsAllowed(context)) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAt, operation)
                return AlarmPrecision.EXACT
            } catch (_: SecurityException) {
                // Permission can change between the capability check and this call.
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAt, operation)
        return AlarmPrecision.INEXACT
    }

    fun cancel(id: String, remindAt: Long) {
        val operation = PendingIntent.getBroadcast(
            context, 0, ReminderIntents.alarm(context, id, remindAt),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarms.cancel(operation)
        operation.cancel()
    }
}

class ReminderCoordinator(
    context: Context,
    private val repository: TimeRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val context = context.applicationContext
    private val scheduler = AndroidReminderScheduler(this.context)
    private val registry = this.context.getSharedPreferences("reminder_alarm_registry_v1", Context.MODE_PRIVATE)
    private var observing: Job? = null

    /** Start once in an app-owned scope; the same instance returns its existing active Job. */
    @Synchronized
    fun start(scope: CoroutineScope): Job {
        observing?.takeIf { it.isActive }?.let { return it }
        ReminderRuntime.attach(repository)
        return scope.launch(Dispatchers.IO) {
            repository.observe()
                .map { it.reminders to it.tasks }
                .distinctUntilChanged()
                .catch {
                    // A database closing with the UI must not crash the app scope; existing alarms stay.
                }
                .collect { (reminders, tasks) -> reconcile(reminders, tasks) }
        }.also { observing = it }
    }

    /** Also used after boot, a package update, or permission changes. No missed-time catch-up. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        com.timeagent.recurring.RecurringService(repository).refresh()
        val snapshot = repository.snapshot()
        reconcile(snapshot.reminders, snapshot.tasks)
    }

    private fun activeTask(reminder: ReminderEntity, tasks: Map<String, TaskEntity>): Boolean =
        reminder.taskId?.let { id ->
            tasks[id]?.let { it.deletedAt == null && it.status !in setOf("CANCELLED", "COMPLETED") } == true
        } ?: true

    private suspend fun reconcile(reminders: List<ReminderEntity>, tasks: List<TaskEntity>) = withContext(Dispatchers.IO) {
        schedulingMutex.withLock {
            val tasksById = tasks.associateBy { it.id }
            val current = reminders.associateBy { it.id }
            val previous = registry.all.mapNotNull { (id, value) -> (value as? Long)?.let { id to it } }.toMap()
            val stamp = clock.millis()
            previous.forEach { (id, oldTime) ->
                val item = current[id]
                val invalidTask = item != null && !activeTask(item, tasksById)
                if (item == null || !item.enabled || item.remindAt != oldTime || invalidTask) {
                    scheduler.cancel(id, oldTime)
                    ReminderNotifications.cancel(context, id)
                } else if (item.deliveredAt != null || item.remindAt <= stamp) {
                    // A delivered point keeps its status-bar notification until it is edited or removed.
                    scheduler.cancel(id, oldTime)
                }
            }
            // Retain a delivered reminder's registration to dismiss its notification if later removed.
            val next = previous.filter { (id, time) ->
                current[id]?.let { it.enabled && it.remindAt == time && activeTask(it, tasksById) } == true
            }.toMutableMap()
            reminders.filter { it.enabled && it.deliveredAt == null && it.remindAt > stamp && activeTask(it, tasksById) }.forEach {
                scheduler.schedule(it.id, it.remindAt)
                next[it.id] = it.remindAt
            }
            // A disabled reminder or an invalid parent task dismisses any posted notification, even
            // when this process never registered the alarm that delivered it.
            reminders.filter { !it.enabled || !activeTask(it, tasksById) }.forEach {
                ReminderNotifications.cancel(context, it.id)
            }
            val visibleTimes = reminders.filter { it.enabled && activeTask(it, tasksById) }
                .associate { it.id to it.remindAt }
            ReminderNotifications.cancelOrphans(context, visibleTimes)
            if (next != previous) {
                registry.edit().clear().also { editor -> next.forEach { (id, time) -> editor.putLong(id, time) } }.commit()
            }
        }
    }

    private companion object {
        // A foreground coordinator and a restore broadcast may both reconcile in the same process.
        val schedulingMutex = Mutex()
    }
}
