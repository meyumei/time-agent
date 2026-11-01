package com.timeagent.workout

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.timeagent.core.workout.*
import com.timeagent.di.AppEntryPoint
import com.timeagent.workout.data.WorkoutRepository
import kotlinx.coroutines.*

internal object WorkoutNotifications {
    private const val ONGOING = 521
    private const val CUE = 522
    private const val CHANNEL = "workout_progress"
    private const val CUE_CHANNEL = "workout_cues"
    private const val SILENT_CUE_CHANNEL = "workout_cues_silent"
    internal const val ACTION = "com.timeagent.workout.TIMER"

    internal fun nextDeadline(session: WorkoutSession, now: Long): Long? {
        if (session.status != WorkoutSessionStatus.ACTIVE) return null
        val interval = WorkoutRules.intervalState(session, now)
        return if (interval != null) { if (interval.phase == WorkoutIntervalPhase.FINISHED) null else now + interval.remainingMillis }
        else session.restEndsAt?.takeIf { it > now }
    }

    @Synchronized fun sync(context: Context, session: WorkoutSession?, now: Long = System.currentTimeMillis()) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "训练进行中", NotificationManager.IMPORTANCE_LOW))
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, ONGOING, Intent(context, WorkoutTimerReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        val prefs = context.getSharedPreferences("workout_timer", 0)
        if (session == null || session.status !in listOf(WorkoutSessionStatus.ACTIVE, WorkoutSessionStatus.PAUSED)) {
            prefs.edit().remove("session").remove("deadline").remove("token").apply(); manager.cancel(ONGOING); manager.cancel(CUE); return
        }
        val deadline = nextDeadline(session, now)
        prefs.edit().putString("session", session.id).putLong("deadline", deadline ?: 0).putString("token", timerToken(session)).apply()
        if (deadline != null) {
            try {
                if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadline, pending)
                else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadline, pending)
            } catch (_: SecurityException) { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadline, pending) }
        }
        val detail = when {
            session.status == WorkoutSessionStatus.PAUSED -> "已暂停 · 点此继续"
            session.intervalTimer != null -> "Tabata 阶段计时中 · 点此查看"
            (session.restEndsAt ?: 0) > now -> "组间休息 · 点此继续"
            else -> "训练进行中 · 点此继续"
        }
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(session.title).setContentText(detail).setContentIntent(open(context, session.id)).setOngoing(true)
            .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setWhen(now - WorkoutRules.elapsedMillis(session, now)).setUsesChronometer(session.status == WorkoutSessionStatus.ACTIVE).build()
        if (allowed(context)) runCatching { manager.notify(ONGOING, notification) }
    }

    internal fun cue(context: Context, session: WorkoutSession, message: String) {
        val sound = context.getSharedPreferences("workout", 0).getBoolean("sound", true)
        val channel = if (sound) CUE_CHANNEL else SILENT_CUE_CHANNEL
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channel, if (sound) "训练计时提示" else "训练计时提示（静音）", if (sound) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_LOW))
        if (allowed(context)) runCatching { manager.notify(CUE, Notification.Builder(context, channel).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(message).setContentText(session.title).setContentIntent(open(context, session.id)).setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).build()) }
    }

    @Synchronized internal fun claimDue(context: Context, session: WorkoutSession, now: Long): Boolean {
        val prefs = context.getSharedPreferences("workout_timer", 0)
        val deadline = prefs.getLong("deadline", 0)
        if (session.status != WorkoutSessionStatus.ACTIVE || prefs.getString("session", null) != session.id ||
            prefs.getString("token", null) != timerToken(session) || deadline <= 0 || now < deadline) return false
        return prefs.edit().putLong("deadline", 0).commit()
    }

    private fun timerToken(session: WorkoutSession): String = "${session.id}|${session.status}|${session.restEndsAt}|${session.intervalTimer}|${session.totalPausedMillis}"

    private fun allowed(context: Context): Boolean = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun open(context: Context, id: String): PendingIntent = PendingIntent.getActivity(context, ONGOING,
        WorkoutActivity.intent(context, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

class WorkoutTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val restore = intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
        if (!restore && intent.action != WorkoutNotifications.ACTION) return
        val pending = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                withTimeout(8_000) {
                    val dependencies = AppEntryPoint.from(app)
                    val repository = WorkoutRepository(dependencies.workoutDatabase(), dependencies.timeRepository())
                    val session = repository.activeSession()
                    val now = System.currentTimeMillis()
                    if (!restore && session != null && WorkoutNotifications.claimDue(app, session, now)) {
                        val state = WorkoutRules.intervalState(session, now)
                        val text = when (state?.phase) {
                            WorkoutIntervalPhase.WORK -> "开始第 ${state.round} 轮训练"
                            WorkoutIntervalPhase.REST -> "第 ${state.round} 轮完成，开始休息"
                            WorkoutIntervalPhase.FINISHED -> "Tabata 已结束，打开训练记录本组"
                            else -> "组间休息结束"
                        }
                        WorkoutNotifications.cue(app, session, text)
                    }
                    WorkoutNotifications.sync(app, session, now)
                }
            } catch (error: Exception) { if (error is CancellationException && error !is TimeoutCancellationException) throw error; Log.e("WorkoutTimer", "Could not restore workout timer", error) }
            finally { pending?.finish() }
        }
    }
    companion object { private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO) }
}
