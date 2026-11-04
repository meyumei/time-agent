package com.timeagent.timer

import android.app.*
import android.content.*
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.timeagent.MainActivity
import com.timeagent.core.workout.*
import com.timeagent.data.TimeRepository
import com.timeagent.di.AppEntryPoint
import com.timeagent.domain.command.*
import com.timeagent.focus.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal data class MainTimerNotice(
    val kind: String, val id: String, val version: Long, val label: String, val destination: String,
    val elapsedMs: Long, val running: Boolean, val status: String, val canPause: Boolean,
    val startedAt: Long,
    val color: Int = 0,
)

object TimerNotifications {
    internal const val ID = 4300
    private const val QUICK_ID = 4301
    internal const val CHANNEL = "main_timer"
    private const val QUICK_CHANNEL = "quick_record"
    internal const val PREFS = "timer_notifications"
    private val gson = Gson()

    fun start(context: Context, repository: TimeRepository, scope: CoroutineScope) {
        val mainChanges = repository.database.invalidationTracker.createFlow("time_records", "focus_sessions",
            "task_placements", "projects", "entry_contexts")
        val workouts = repository.workoutDatabase?.dao()?.observeSessions()
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
        scope.launch {
            combine(mainChanges, workouts) { _, _ -> read(repository, context) }
                .distinctUntilChanged().collect { publish(context, it) }
        }
    }

    internal suspend fun read(repository: TimeRepository, context: Context): MainTimerNotice? = repository.transaction {
        run {
            suspend fun projectColor(recordId: String, taskId: String?): Int {
                val dao = repository.database.dao()
                val projectId = repository.database.entryContextDao().find("RECORD", recordId)?.projectId
                    ?: taskId?.let { dao.placement(it)?.projectId }
                return projectId?.let { dao.project(it) }?.let { com.timeagent.projects.ProjectColor.argb(it.name) } ?: 0
            }
            val focus = repository.database.focusDao().active()
            if (focus != null) return@run MainTimerNotice(
                "FOCUS", focus.id, focus.version, focus.label, "focus",
                FocusClock.focusElapsedNow(focus, FocusClock.nowElapsed(), FocusClock.bootCount(context)),
                focus.state == FocusSessionEntity.STATE_FOCUSING,
                FocusNotifications.statusText(context, focus), focus.state != FocusSessionEntity.STATE_BREAK, focus.startedAt,
                projectColor("focus:${focus.id}", focus.taskId),
            )
            repository.workoutDatabase?.dao()?.activeSession()?.let { row ->
                val session = gson.fromJson(row.payload, WorkoutSession::class.java)
                return@run MainTimerNotice("WORKOUT", session.id, session.updatedAt, session.title, "workout:${session.id}",
                    WorkoutRules.elapsedMillis(session, System.currentTimeMillis()), session.status == WorkoutSessionStatus.ACTIVE,
                    if (session.status == WorkoutSessionStatus.PAUSED) "训练已暂停" else "训练中", true, session.startedAt)
            }
            repository.database.dao().runningTimer()?.let { record ->
                val title = record.title.ifBlank { record.taskId?.let { repository.database.dao().task(it)?.title } ?: "自由计时" }
                MainTimerNotice("TIMER", record.id, record.version, title, "timer",
                    (repository.now().toEpochMilli() - record.startAt).coerceAtLeast(0), true, "自由计时中", false, record.startAt,
                    projectColor(record.id, record.taskId))
            }
        }
    }

    internal fun publish(context: Context, slot: MainTimerNotice?) {
        ensureChannels(context)
        if (slot == null) {
            context.stopService(Intent(context, TimerForegroundService::class.java))
            showQuick(context)
            return
        }
        context.getSystemService(NotificationManager::class.java).cancel(QUICK_ID)
        val intent = Intent(context, TimerForegroundService::class.java).putExtra("notice", gson.toJson(slot))
        try { ContextCompat.startForegroundService(context, intent) }
        catch (error: IllegalStateException) {
            // A manufacturer/background launch restriction must not stop the underlying timer.
            android.util.Log.w("TimeAgentTimer", "Foreground notification needs app foreground", error)
        }
    }

    fun refresh(context: Context) {
        scope.launch {
            val repository = AppEntryPoint.from(context).timeRepository()
            publish(context, read(repository, context))
        }
    }

    suspend fun restoreOnForeground(context: Context): String? {
        val repository = AppEntryPoint.from(context).timeRepository()
        val slot = read(repository, context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val permissionOff = !manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
        val dismissed = slot != null && manager.activeNotifications.none { it.id == ID }
        publish(context, slot)
        if (slot == null || (!permissionOff && !dismissed)) return null
        val prefs = context.getSharedPreferences(PREFS, 0)
        val key = "${slot.kind}:${slot.id}:${if (permissionOff) "permission" else "dismissed"}"
        if (prefs.getString("last_resume_notice", null) == key) return null
        prefs.edit().putString("last_resume_notice", key).apply()
        return if (permissionOff) "计时仍在进行。通知权限已关闭，应用内计时不受影响。"
            else "计时仍在进行，已恢复计时通知。"
    }

    internal fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "正在计时", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(QUICK_CHANNEL, "快捷记录", NotificationManager.IMPORTANCE_LOW))
    }

    internal fun build(context: Context, slot: MainTimerNotice): Notification {
        val hide = context.getSharedPreferences(PREFS, 0).getBoolean("hide_name", true)
        val public = Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("时间 Agent · ${slot.status}").setContentText("点此查看计时").build()
        return Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(slot.label).setContentText(
                "${java.time.Instant.ofEpochMilli(slot.startedAt).atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))} 开始 · ${slot.status}")
            .setColor(slot.color)
            .setWhen(System.currentTimeMillis() - slot.elapsedMs).setUsesChronometer(slot.running)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open(context, slot.destination))
            .setVisibility(if (hide) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC)
            .setPublicVersion(if (hide) public else null)
            .addAction(Notification.Action.Builder(null, "停止", action(context, slot, "STOP")).build())
            .addAction(Notification.Action.Builder(null, "切换", open(context, "quick-entries")).build())
            .apply { if (slot.canPause) addAction(Notification.Action.Builder(null,
                if (slot.running || slot.status.contains("休息")) "暂停" else "继续", action(context, slot, "PAUSE")).build()) }
            .build()
    }

    internal fun open(context: Context, destination: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setData(android.net.Uri.parse("timeagent://timer/${android.net.Uri.encode(destination)}"))
            .putExtra("appDestination", destination).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, destination.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(context: Context, slot: MainTimerNotice, action: String): PendingIntent {
        val intent = Intent(context, TimerActionReceiver::class.java)
            .setData(android.net.Uri.parse("timeagent://timer-action/${slot.kind}/${slot.id}/${slot.version}/$action"))
            .putExtra("kind", slot.kind).putExtra("id", slot.id).putExtra("version", slot.version).putExtra("action", action)
        return PendingIntent.getBroadcast(context, intent.dataString.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun showQuick(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!context.getSharedPreferences(PREFS, 0).getBoolean("quick_enabled", false)) { manager.cancel(QUICK_ID); return }
        val notification = Notification.Builder(context, QUICK_CHANNEL).setSmallIcon(android.R.drawable.ic_input_add)
            .setContentTitle("时间 Agent · 快捷记录").setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open(context, "quick-entries"))
            .addAction(Notification.Action.Builder(null, "最近计时", open(context, "quick-recent")).build())
            .addAction(Notification.Action.Builder(null, "语音记录", open(context, "voice")).build())
            .addAction(Notification.Action.Builder(null, "新建任务", open(context, "new-task")).build()).build()
        if (manager.areNotificationsEnabled()) manager.notify(QUICK_ID, notification)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

class TimerForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TimerNotifications.ensureChannels(this)
        val slot = intent?.getStringExtra("notice")?.let { Gson().fromJson(it, MainTimerNotice::class.java) }
        val notification = slot?.let { TimerNotifications.build(this, it) }
            ?: Notification.Builder(this, TimerNotifications.CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("正在恢复计时状态").build()
        ServiceCompat.startForeground(this, TimerNotifications.ID, notification,
            if (Build.VERSION.SDK_INT >= 34) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        if (slot == null) TimerNotifications.refresh(this)
        return START_STICKY
    }
}

class TimerActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra("kind") ?: return
        val id = intent.getStringExtra("id") ?: return
        val version = intent.getLongExtra("version", -1)
        val stop = intent.getStringExtra("action") == "STOP"
        val requestId = "notification-timer:$kind:$id:$version:${if (stop) "stop" else "pause"}"
        val pending = goAsync()
        scope.launch {
            try {
                val repository = AppEntryPoint.from(context).timeRepository()
                val command = when (kind) {
                    "TIMER" -> StopTimer(requestId, id, version, Origin.NOTIFICATION)
                    "FOCUS" -> {
                        val focus = repository.database.focusDao().session(id) ?: return@launch
                        val mutation = if (stop) FocusMutation.Finish(id, version)
                            else if (focus.state == FocusSessionEntity.STATE_PAUSED) FocusMutation.Resume(id, version) else FocusMutation.Pause(id, version)
                        WriteFocus(requestId, mutation, Origin.NOTIFICATION)
                    }
                    "WORKOUT" -> {
                        val row = repository.workoutDatabase?.dao()?.activeSession() ?: return@launch
                        if (row.id != id) return@launch
                        val session = Gson().fromJson(row.payload, WorkoutSession::class.java)
                        val mutation = if (stop) WorkoutMutation.Finish(id, expectedUpdatedAt = version)
                            else WorkoutMutation.SaveSession(
                                if (session.status == WorkoutSessionStatus.PAUSED) WorkoutRules.resume(session, repository.now().toEpochMilli())
                                else WorkoutRules.pause(session, repository.now().toEpochMilli()), version, "通知栏暂停或继续训练")
                        WriteWorkout(requestId, mutation, Origin.NOTIFICATION)
                    }
                    else -> return@launch
                }
                val result = repository.commands.execute(command)
                if (result.status !in setOf(CommandStatus.APPLIED, CommandStatus.DUPLICATE)) {
                    TimerNotifications.ensureChannels(context)
                    context.getSystemService(NotificationManager::class.java).notify(4302,
                        Notification.Builder(context, TimerNotifications.CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_alert)
                            .setContentTitle("计时操作未保存").setContentText(result.message)
                            .setContentIntent(TimerNotifications.open(context, "timer")).setAutoCancel(true).build())
                }
            } finally { TimerNotifications.refresh(context); pending.finish() }
        }
    }
    companion object { private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO) }
}
