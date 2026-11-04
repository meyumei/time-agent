package com.timeagent.habits

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.timeagent.data.TimeRepository
import com.timeagent.di.AppEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object HabitReminderRefresh {
    fun request(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("habit-reminders",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<HabitReminderWorker>().setInitialDelay(1, TimeUnit.SECONDS).build())
    }

    fun start(context: Context, repository: TimeRepository, scope: CoroutineScope) {
        scheduleMidnight(context)
        scope.launch {
            repository.database.invalidationTracker.createFlow(*HabitRepository.TABLES).collect { request(context) }
        }
        repository.workoutDatabase?.let { db ->
            scope.launch { db.dao().observeSessions().collect { request(context) } }
        }
    }

    fun scheduleMidnight(context: Context) {
        val zone = ZoneId.systemDefault()
        val next = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val pending = PendingIntent.getBroadcast(context, 4500,
            Intent(context, HabitReminderDateReceiver::class.java).setAction("com.timeagent.HABIT_MIDNIGHT"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
    }
}

class HabitReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val repository = AppEntryPoint.from(applicationContext).timeRepository()
        val habits = HabitRepository(repository, applicationContext)
        repository.database.habitDao().habits().forEach { habits.syncReminderForToday(it.id) }
        HabitReminderRefresh.scheduleMidnight(applicationContext)
        Result.success()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.e("HabitReminders", "Habit reminder refresh failed", error)
        Result.failure(androidx.work.workDataOf("error" to (error.message ?: error.javaClass.simpleName)))
    }
}

class HabitReminderDateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HabitReminderRefresh.request(context)
        HabitReminderRefresh.scheduleMidnight(context)
    }
}
