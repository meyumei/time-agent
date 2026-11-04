package com.timeagent.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import androidx.work.*
import com.timeagent.data.TimeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object WidgetRefresh {
    fun request(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("widgets-refresh", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WidgetRefreshWorker>().setInitialDelay(1, TimeUnit.SECONDS).build())
    }

    fun start(context: Context, repository: TimeRepository, scope: CoroutineScope) {
        request(context); scheduleMidnight(context)
        scope.launch { repository.commands.events.collect { request(context) } }
        scope.launch { repository.database.invalidationTracker.createFlow("time_records", "tasks", "schedule_blocks", "quick_entries", "focus_sessions").collect { request(context) } }
        repository.workoutDatabase?.let { db -> scope.launch { db.dao().observeSessions().collect { request(context) } } }
        val prefs = context.getSharedPreferences("appearance", 0)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> request(context) }
        preferenceListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
        context.getSharedPreferences("fish_companion", 0).registerOnSharedPreferenceChangeListener(listener)
    }

    private var preferenceListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    internal fun scheduleMidnight(context: Context) {
        val next = ZonedDateTime.now().toLocalDate().plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = PendingIntent.getBroadcast(context, 4400, Intent(context, WidgetDateReceiver::class.java).setAction("com.timeagent.WIDGET_MIDNIGHT"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent)
    }
}

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        TodayWidget().updateAll(applicationContext)
        QuickTimerWidget().updateAll(applicationContext)
        VoiceWidget().updateAll(applicationContext)
        return Result.success()
    }
}

class WidgetDateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WidgetRefresh.request(context)
        WidgetRefresh.scheduleMidnight(context)
    }
}
