package com.timeagent.reminders

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.data.TimeRepository
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
    private var backgroundRepository: TimeRepository? = null

    fun attach(repository: TimeRepository) { attachedRepository = repository }

    @Synchronized
    fun repository(context: Context): TimeRepository = attachedRepository ?: backgroundRepository ?: TimeRepository(
        TimeAgentDatabase.open(context.applicationContext),
    ).also { backgroundRepository = it }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderIntents.ACTION_FIRE) return
        val pending = goAsync()
        val appContext = context.applicationContext
        val payload = Intent(intent)
        ReminderRuntime.scope.launch {
            try {
                withTimeout(8_000L) {
                    val repository=ReminderRuntime.repository(appContext)
                    if(deliver(appContext,repository,payload))ReminderCoordinator(appContext,repository).refresh()
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

    companion object {
        internal suspend fun deliver(context: Context, repository: TimeRepository, intent: Intent, clock: Clock = Clock.systemUTC()): Boolean {
            if (intent.action != ReminderIntents.ACTION_FIRE) return false
            val id = intent.getStringExtra(ReminderIntents.EXTRA_REMINDER_ID)?.takeIf { it.isNotBlank() } ?: return false
            if (!intent.hasExtra(ReminderIntents.EXTRA_REMIND_AT)) return false
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
