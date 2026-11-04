package com.timeagent.focus

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.timeagent.MainActivity
import com.timeagent.data.StaleChange
import com.timeagent.data.TimeRepository
import com.timeagent.di.AppEntryPoint
import com.timeagent.domain.command.FocusMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

object FocusIntents {
    const val ACTION_PHASE = "com.timeagent.focus.PHASE"
    const val ACTION_OPEN = "com.timeagent.focus.OPEN"
    const val EXTRA_SESSION_ID = "focus_session_id"
    const val EXTRA_VERSION = "focus_version"
    const val EXTRA_TRIGGER_AT = "focus_trigger_at"

    val RESTORE_ACTIONS = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED,
        AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
    )

    internal fun alarm(context: Context, sessionId: String): Intent =
        Intent(context, FocusPhaseReceiver::class.java)
            .setAction(ACTION_PHASE)
            .setData(Uri.Builder().scheme("timeagent").authority("focus-alarm").appendPath(sessionId).build())
            .putExtra(EXTRA_SESSION_ID, sessionId)

    internal fun open(context: Context, sessionId: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN)
            .setData(Uri.Builder().scheme("timeagent").authority("focus").appendPath(sessionId).build())
            .putExtra(EXTRA_SESSION_ID, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}

/**
 * Phase notifications are deliberately low-frequency and are posted by the runtime/receiver.
 * T5.3's foreground service should reuse [CHANNEL_ID] and [NOTIFICATION_ID] instead of creating a
 * second timer notification.
 */
object FocusNotifications {
    const val CHANNEL_ID = "focus_sessions"
    const val NOTIFICATION_ID = 4101
    private const val TAG = "focus"
    private const val WARN_TAG = "focus-exact"
    private const val WARN_ID = 4102

    @Volatile private var lastKey: String? = null
    @Volatile private var warnedInexact = false

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "专注与番茄钟", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "专注阶段、休息阶段与暂停超时提醒"
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
            )
        }
    }

    fun update(context: Context, session: FocusSessionEntity, event: String? = null) {
        if (!allowed(context)) return
        if (session.isActive && event == null) return
        ensureChannel(context)
        val key = "${session.id}:${session.version}:${session.state}:${session.finishReason.orEmpty()}|${event.orEmpty()}"
        if (key == lastKey) return
        lastKey = key
        val open = PendingIntent.getActivity(
            context,
            session.id.hashCode(),
            FocusIntents.open(context, session.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = event ?: statusText(context, session)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title(session))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setWhen(session.startedAt)
            .setOnlyAlertOnce(event == null)
            .setAutoCancel(!session.isActive)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(TAG, NOTIFICATION_ID, notification) }
    }

    fun cancel(context: Context) {
        lastKey = null
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(TAG, NOTIFICATION_ID) }
    }

    fun warnInexact(context: Context) {
        if (warnedInexact || !allowed(context)) return
        warnedInexact = true
        ensureChannel(context)
        val text = "精确闹钟未开启，后台的专注阶段提醒可能延迟；专注时长仍按实际经过时间计算。"
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("专注提醒可能延迟")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(WARN_TAG, WARN_ID, notification) }
    }

    private fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun title(session: FocusSessionEntity): String = when (session.state) {
        FocusSessionEntity.STATE_FOCUSING -> "专注中 · ${session.label}"
        FocusSessionEntity.STATE_BREAK -> "休息 · ${session.label}"
        FocusSessionEntity.STATE_PAUSED -> "专注已暂停 · ${session.label}"
        FocusSessionEntity.STATE_COMPLETED -> "专注结束 · ${session.label}"
        else -> "专注已放弃 · ${session.label}"
    }

    internal fun statusText(context: Context, session: FocusSessionEntity): String {
        val boot = FocusClock.bootCount(context)
        val elapsed = FocusClock.focusElapsedNow(session, FocusClock.nowElapsed(), boot)
        return when (session.state) {
            FocusSessionEntity.STATE_FOCUSING -> {
                val mode = runCatching { FocusMode.valueOf(session.mode) }.getOrDefault(FocusMode.POMODORO)
                if (mode == FocusMode.COUNT_UP) {
                    "已专注 ${FocusClock.formatDuration(elapsed)}"
                } else {
                    val remaining = FocusClock.remainingRoundMs(session, FocusClock.nowElapsed(), boot)
                    val round = if (session.roundsPlanned > 1) " · 第 ${session.roundIndex}/${session.roundsPlanned} 轮" else ""
                    "本轮剩余 ${FocusClock.formatClock(remaining)}$round"
                }
            }
            FocusSessionEntity.STATE_BREAK ->
                "休息剩余 ${FocusClock.formatClock(FocusClock.breakRemainingMs(session, FocusClock.nowElapsed(), boot))}"
            FocusSessionEntity.STATE_PAUSED ->
                "已暂停 · 已专注 ${FocusClock.formatDuration(elapsed)}；暂停超过 60 分钟将自动结束并保存"
            FocusSessionEntity.STATE_COMPLETED ->
                if (session.actualRecordId != null) "已保存 ${FocusClock.formatDuration(session.focusedElapsedMs)}"
                else "已完成，未生成实际记录"
            else -> "本次专注未保存实际记录"
        }
    }
}

class FocusRuntime(
    context: Context,
    timeRepository: TimeRepository,
) {
    private val appContext = context.applicationContext
    private val repository = FocusRepository(timeRepository, appContext)
    private var job: Job? = null
    private var scheduledId: String? = null

    @Synchronized
    fun start(scope: CoroutineScope): Job {
        job?.takeIf { it.isActive }?.let { return it }
        FocusNotifications.ensureChannel(appContext)
        return scope.launch(Dispatchers.IO) {
            val reconciled = try {
                repository.reconcile()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (reconciled != null && !reconciled.isActive) {
                FocusNotifications.update(appContext, reconciled, completionText(reconciled))
            }
            val seenActive = mutableSetOf<String>()
            repository.observeLatest()
                .catch { Log.w("TimeAgentFocus", "Focus observation stopped", it) }
                .collect { session ->
                    if (session == null) {
                        scheduledId?.let { cancelAlarm(appContext, it) }
                        scheduledId = null
                        FocusNotifications.cancel(appContext)
                        return@collect
                    }
                    if (session.isActive) {
                        seenActive += session.id
                        scheduledId?.takeIf { it != session.id }?.let { cancelAlarm(appContext, it) }
                        scheduledId = session.id
                        scheduleAlarm(appContext, session)
                        FocusNotifications.update(appContext, session)
                        return@collect
                    }
                    if (scheduledId == session.id) scheduledId = null
                    cancelAlarm(appContext, session.id)
                    if (seenActive.remove(session.id)) {
                        FocusNotifications.update(appContext, session, completionText(session))
                    } else if (session.endedAt != null && System.currentTimeMillis() - session.endedAt > 10L * 60_000L) {
                        // A notification left over from a previous process must not look current.
                        FocusNotifications.cancel(appContext)
                    }
                }
        }.also { job = it }
    }

    fun stop() {
        job?.cancel()
        job = null
        scheduledId?.let { cancelAlarm(appContext, it) }
        scheduledId = null
    }

    companion object {
        internal fun scheduleAlarm(context: Context, session: FocusSessionEntity) {
            if (!session.isActive) {
                cancelAlarm(context, session.id)
                return
            }
            val remaining = FocusClock.nextBoundaryInMs(session, FocusClock.nowElapsed(), FocusClock.bootCount(context))
            if (remaining == null) {
                cancelAlarm(context, session.id)
                return
            }
            val triggerAt = System.currentTimeMillis() + remaining.coerceAtLeast(1_000L)
            val operation = PendingIntent.getBroadcast(
                context,
                session.id.hashCode(),
                FocusIntents.alarm(context, session.id)
                    .putExtra(FocusIntents.EXTRA_VERSION, session.version)
                    .putExtra(FocusIntents.EXTRA_TRIGGER_AT, triggerAt),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val alarms = context.getSystemService(AlarmManager::class.java)
            if (FocusClock.exactAlarmsAllowed(context)) {
                try {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
                    return
                } catch (_: SecurityException) {
                    // Permission can be revoked between the capability check and this call.
                }
            }
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            FocusNotifications.warnInexact(context)
        }

        internal fun cancelAlarm(context: Context, sessionId: String) {
            val operation = PendingIntent.getBroadcast(
                context,
                sessionId.hashCode(),
                FocusIntents.alarm(context, sessionId),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ) ?: return
            context.getSystemService(AlarmManager::class.java).cancel(operation)
            operation.cancel()
        }

        internal suspend fun handleAlarm(context: Context, timeRepository: TimeRepository, intent: Intent) {
            val id = intent.getStringExtra(FocusIntents.EXTRA_SESSION_ID) ?: return
            val triggerAt = intent.getLongExtra(FocusIntents.EXTRA_TRIGGER_AT, 0L)
            val focus = FocusRepository(timeRepository, context)
            val active = focus.active()
            if (active == null) {
                cancelAlarm(context, id)
                return
            }
            if (active.id != id) {
                cancelAlarm(context, id)
                scheduleAlarm(context, active)
                FocusNotifications.update(context, active)
                return
            }
            val before = active
            val after = try {
                focus.tick(
                    active.id,
                    active.version,
                    requestId = "focus-alarm:${active.id}:${active.version}:$triggerAt",
                )
            } catch (_: StaleChange) {
                focus.active()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("TimeAgentFocus", "Focus phase alarm failed", error)
                null
            }
            if (after == null) {
                scheduleAlarm(context, active)
                return
            }
            if (after.isActive) scheduleAlarm(context, after) else cancelAlarm(context, after.id)
            FocusNotifications.update(context, after, eventFor(before, after))
        }

        internal suspend fun handleRestore(context: Context, timeRepository: TimeRepository) {
            val focus = FocusRepository(timeRepository, context)
            val before = focus.active()
            val after = try {
                focus.reconcile()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (after == null) {
                before?.let { cancelAlarm(context, it.id) }
                return
            }
            if (after.isActive) scheduleAlarm(context, after) else cancelAlarm(context, after.id)
            val event = when {
                after.isActive && after.needsReview ->
                    "设备重启后已暂停，仅保留重启前已核实的时长；缺失时段可手动补录"
                !after.isActive -> completionText(after)
                else -> null
            }
            FocusNotifications.update(context, after, event)
        }

        private fun eventFor(before: FocusSessionEntity, after: FocusSessionEntity): String? = when {
            after.state == FocusSessionEntity.STATE_BREAK && before.state != FocusSessionEntity.STATE_BREAK ->
                "本轮专注完成，休息 ${FocusClock.formatDuration(FocusClock.breakDurationMs(after))}"
            after.state == FocusSessionEntity.STATE_FOCUSING && before.state == FocusSessionEntity.STATE_BREAK ->
                "休息结束，开始第 ${after.roundIndex} 轮"
            after.state == FocusSessionEntity.STATE_COMPLETED -> completionText(after)
            after.state == FocusSessionEntity.STATE_ABANDONED -> "本次专注未保存实际记录"
            after.needsReview && !before.needsReview ->
                "检测到设备重启或系统时间变化，专注已暂停，请核对时长"
            else -> null
        }

        private fun completionText(session: FocusSessionEntity): String = when (session.finishReason) {
            "PAUSE_TIMEOUT" -> "暂停超过 60 分钟，已保存 ${FocusClock.formatDuration(session.focusedElapsedMs)}"
            "ROUNDS_DONE" -> "全部轮次完成，已保存 ${FocusClock.formatDuration(session.focusedElapsedMs)}"
            "COUNTDOWN_DONE" -> "倒计时结束，已保存 ${FocusClock.formatDuration(session.focusedElapsedMs)}"
            "MANUAL_DISCARD" -> "本次专注未保存实际记录"
            else -> if (session.actualRecordId != null) "已保存 ${FocusClock.formatDuration(session.focusedElapsedMs)}"
            else "已完成，未生成实际记录"
        }
    }
}

/**
 * Manifest:
 * <receiver android:name=".focus.FocusPhaseReceiver" android:exported="false">
 *   <intent-filter>
 *     <action android:name="android.intent.action.BOOT_COMPLETED"/>
 *     <action android:name="android.intent.action.MY_PACKAGE_REPLACED"/>
 *     <action android:name="android.intent.action.TIME_SET"/>
 *     <action android:name="android.intent.action.TIMEZONE_CHANGED"/>
 *     <action android:name="android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"/>
 *   </intent-filter>
 * </receiver>
 */
class FocusPhaseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != FocusIntents.ACTION_PHASE && action !in FocusIntents.RESTORE_ACTIONS) return
        val pending = goAsync()
        val appContext = context.applicationContext
        scope.launch {
            try {
                withTimeout(8_000L) {
                    val repository = AppEntryPoint.from(appContext).timeRepository()
                    if (action == FocusIntents.ACTION_PHASE) {
                        FocusRuntime.handleAlarm(appContext, repository, intent)
                    } else {
                        FocusRuntime.handleRestore(appContext, repository)
                    }
                }
            } catch (error: TimeoutCancellationException) {
                Log.w("TimeAgentFocus", "Focus receiver timed out", error)
            } catch (error: Exception) {
                Log.e("TimeAgentFocus", "Focus receiver failed", error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
