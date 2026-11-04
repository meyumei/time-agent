package com.timeagent.calendar

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.timeagent.di.AppEntryPoint
import com.timeagent.domain.command.CommandStatus
import java.util.concurrent.TimeUnit

class CalendarSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = AppEntryPoint.from(applicationContext).timeRepository()
        val calendar = CalendarSyncRepository(repository, applicationContext)
        if (!calendar.canReadCalendar() || !calendar.canWriteCalendar()) {
            calendar.reportFailure("日历权限已关闭，同步暂停；已有日程和映射保留", "calendar-worker:$id:permission")
            return Result.failure(workDataOf("error" to "日历权限已关闭，同步暂停"))
        }
        val result = calendar.syncNow("calendar-worker:$id:$runAttemptCount")
        return when (result.status) {
            CommandStatus.APPLIED, CommandStatus.DUPLICATE -> Result.success()
            else -> Result.failure(workDataOf("error" to result.message))
        }
    }

    companion object {
        fun request(context: Context, debounce: Boolean = true) {
            val work = OneTimeWorkRequestBuilder<CalendarSyncWorker>()
                .setInitialDelay(if (debounce) 10 else 0, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("calendar-sync-change", ExistingWorkPolicy.REPLACE, work)
        }

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("calendar-sync-periodic",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CalendarSyncWorker>(6, TimeUnit.HOURS).build())
        }
    }
}
