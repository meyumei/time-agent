package com.timeagent.reminders

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.timeagent.data.TimeRepository
import com.timeagent.di.AppEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Clock

internal object ReminderRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var attachedRepository: TimeRepository? = null

    fun attach(repository: TimeRepository) { attachedRepository = repository }

    @Synchronized
    fun repository(context: Context): TimeRepository =
        attachedRepository ?: AppEntryPoint.from(context).timeRepository()
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ReminderIntents.ACTION_OPEN -> handleOpen(context.applicationContext, intent)
            ReminderIntents.ACTION_NOTIFICATION -> handleNotificationAction(context.applicationContext, intent)
            ReminderIntents.ACTION_FIRE -> handleFire(context.applicationContext, intent)
        }
    }

    private fun handleFire(context: Context, intent: Intent) {
        val pending = goAsync()
        val payload = Intent(intent)
        ReminderRuntime.scope.launch {
            try {
                withTimeout(8_000L) {
                    val repository = ReminderRuntime.repository(context)
                    if (!reminderAccessState(context).notificationsAllowed) {
                        // Do not claim an instance we cannot surface. The next reconcile discards it.
                        ReminderCoordinator(context, repository).refresh()
                        return@withTimeout
                    }
                    val instanceId = payload.getStringExtra(ReminderIntents.EXTRA_INSTANCE_ID)
                    if (!instanceId.isNullOrBlank()) {
                        ReminderInstanceRepository(repository).fire(instanceId)?.let { event ->
                            ReminderNotifications.post(context, event)
                        }
                    } else {
                        // Bridge an alarm armed by the pre-v16 coordinator: if its time matches a
                        // persisted SCHEDULED instance, deliver through the instance path. When the
                        // new alarm already fired it, fire() returns null and no second notification
                        // can appear. The old path remains only for databases without instances.
                        val legacyId = payload.getStringExtra(ReminderIntents.EXTRA_REMINDER_ID)
                        val legacyTime = payload.getLongExtra(ReminderIntents.EXTRA_REMIND_AT, Long.MIN_VALUE)
                        val migrated = legacyId?.let { id ->
                            repository.database.reminderInstanceDao().forReminder(id).firstOrNull {
                                it.state == REMINDER_STATE_SCHEDULED && it.fireAt == legacyTime
                            }
                        }
                        if (migrated != null) {
                            ReminderInstanceRepository(repository).fire(migrated.id)?.let { event ->
                                ReminderNotifications.post(context, event)
                            }
                        } else {
                            deliver(context, repository, payload)
                        }
                    }
                    ReminderCoordinator(context, repository).refresh()
                }
            } catch (error: TimeoutCancellationException) {
                Log.w("TimeAgentReminder", "Local reminder processing timed out", error)
            } catch (error: Exception) {
                Log.e("TimeAgentReminder", "Could not process a local reminder", error)
            } finally {
                pending?.finish()
            }
        }
    }

    /**
     * Notification content click: mark the reminder handled (so repeats stop) through the same
     * command path as the detail sheet, then open the real detail screen.
     */
    private fun handleOpen(context: Context, intent: Intent) {
        val id = intent.getStringExtra(ReminderIntents.EXTRA_REMINDER_ID)?.takeIf { it.isNotBlank() } ?: return
        val instanceId = intent.getStringExtra(ReminderIntents.EXTRA_INSTANCE_ID)
        val pending = goAsync()
        ReminderRuntime.scope.launch {
            var repository: TimeRepository? = null
            try {
                withTimeout(4_000L) {
                    val repo = ReminderRuntime.repository(context)
                    repository = repo
                    val reminder = repo.database.dao().reminder(id) ?: return@withTimeout
                    ReminderInstanceRepository(repo).act(
                        id = id,
                        expectedVersion = reminder.version,
                        completeTask = false,
                        requestId = "reminder-open:$id:${instanceId ?: reminder.version}",
                    )
                }
            } catch (error: TimeoutCancellationException) {
                Log.w("TimeAgentReminder", "Opening a reminder was slow; opening the detail anyway", error)
            } catch (error: Exception) {
                Log.e("TimeAgentReminder", "Could not mark a reminder as opened", error)
            } finally {
                ReminderNotifications.cancel(context, id)
                runCatching { context.startActivity(ReminderIntents.open(context, id)) }
                    .onFailure { Log.e("TimeAgentReminder", "Could not open the reminder detail", it) }
                repository?.let { repo ->
                    ReminderRuntime.scope.launch { runCatching { ReminderCoordinator(context, repo).refresh() } }
                }
                pending?.finish()
            }
        }
    }

    private fun handleNotificationAction(context: Context, intent: Intent) {
        val pending = goAsync()
        val id = intent.getStringExtra(ReminderIntents.EXTRA_REMINDER_ID)
        val action = intent.getStringExtra(ReminderIntents.EXTRA_NOTIFICATION_ACTION)
        val instanceId = intent.getStringExtra(ReminderIntents.EXTRA_INSTANCE_ID)
        if (id.isNullOrBlank() || action.isNullOrBlank()) {
            pending.finish()
            return
        }
        ReminderRuntime.scope.launch {
            try {
                val repository = ReminderRuntime.repository(context)
                val reminder = repository.database.dao().reminder(id)
                if (reminder != null) {
                    when (action) {
                        ReminderIntents.ACTION_DONE -> {
                            ReminderInstanceRepository(repository).act(
                                id = id,
                                expectedVersion = reminder.version,
                                completeTask = true,
                                requestId = "reminder-done:$id:${instanceId ?: reminder.version}",
                            )
                            ReminderNotifications.cancel(context, id)
                        }
                        ReminderIntents.ACTION_SNOOZE -> {
                            // Old notifications (posted before v16) only had a fixed snooze action.
                            // New notifications open the chooser directly; this keeps the old tap
                            // useful without silently rewriting remindAt.
                            runCatching {
                                context.startActivity(ReminderIntents.open(context, id, snooze = true, instanceId = instanceId))
                            }
                        }
                        ReminderIntents.ACTION_START -> {
                            ReminderInstanceRepository(repository).act(
                                id = id,
                                expectedVersion = reminder.version,
                                completeTask = false,
                                requestId = "reminder-start-act:$id:${instanceId ?: reminder.version}",
                            )
                            if (reminder.taskId != null) {
                                repository.startTimer(
                                    reminder.taskId,
                                    requestId = "reminder-start:${reminder.id}:${reminder.version}",
                                    switchExisting = false,
                                )
                                ReminderNotifications.cancel(context, id)
                            } else {
                                ReminderNotifications.cancel(context, id)
                                runCatching { context.startActivity(ReminderIntents.open(context, id)) }
                            }
                        }
                    }
                    ReminderCoordinator(context, repository).refresh()
                } else {
                    ReminderNotifications.cancel(context, id)
                }
            } catch (error: Exception) {
                Log.e("TimeAgentReminder", "Could not process notification action", error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        internal suspend fun deliver(context: Context, repository: TimeRepository, intent: Intent, clock: Clock = Clock.systemUTC()): Boolean {
            if (intent.action != ReminderIntents.ACTION_FIRE) return false
            val id = intent.getStringExtra(ReminderIntents.EXTRA_REMINDER_ID)?.takeIf { it.isNotBlank() } ?: return false
            if (!intent.hasExtra(ReminderIntents.EXTRA_REMIND_AT)) return false
            // v16 alarms carry an instance id. Anything without one is a pre-upgrade alarm; if the
            // reminder already has instances, the new path owns delivery and this must stay silent.
            if (repository.database.reminderInstanceDao().forReminder(id).isNotEmpty()) return false
            val expectedTime = intent.getLongExtra(ReminderIntents.EXTRA_REMIND_AT, Long.MIN_VALUE)
            val item = repository.snapshot().reminders.firstOrNull { it.id == id } ?: return false
            if (!item.enabled || item.deliveredAt != null || item.remindAt != expectedTime || item.remindAt > clock.millis()) return false
            if (!reminderAccessState(context).notificationsAllowed) return false
            // The database claim is atomic: stale and concurrent broadcasts cannot both submit.
            if (!repository.markReminderDelivered(id, expectedTime)) return false
            return ReminderNotifications.post(context, item)
        }
    }
}

class ReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in actions) return
        val pending = goAsync()
        val appContext = context.applicationContext
        ReminderRuntime.scope.launch {
            try {
                withTimeout(8_000L) { ReminderCoordinator(appContext, ReminderRuntime.repository(appContext)).refresh() }
            } catch (error: TimeoutCancellationException) {
                Log.w("TimeAgentReminder", "Local reminder restoration timed out", error)
            } catch (error: Exception) {
                Log.e("TimeAgentReminder", "Could not restore local reminders", error)
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        private const val ACTION_REFRESH = "com.timeagent.reminders.REFRESH"
        private val actions = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED, ACTION_REFRESH,
        )

        fun requestRefresh(context: Context) {
            context.sendBroadcast(Intent(context, ReminderRestoreReceiver::class.java).setAction(ACTION_REFRESH))
        }
    }
}
