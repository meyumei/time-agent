package com.timeagent.reports

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.await
import kotlinx.coroutines.launch
import com.timeagent.MainActivity
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Each opted-in report has one dated job; delivery schedules the next local calendar boundary.
 */
object ReportNotifications {
    const val CHANNEL_REPORT = "time_agent_reports"
    const val CHANNEL_EXPORT = "time_agent_export"

    private const val PREFS = "report_notifications"
    private const val KEY_WEEKLY = "weekly_enabled"
    private const val KEY_MONTHLY = "monthly_enabled"
    private const val WORK_WEEKLY = "report-weekly-reminder"
    private const val WORK_MONTHLY = "report-monthly-reminder"
    private const val NOTIFICATION_WEEKLY = 4210
    private const val NOTIFICATION_MONTHLY = 4211
    private const val NOTIFICATION_EXPORT_PROGRESS = 4220
    private const val NOTIFICATION_EXPORT_DONE = 4221
    private const val EXTRA_KIND = "kind"
    private const val KIND_WEEKLY = "WEEKLY"
    private const val KIND_MONTHLY = "MONTHLY"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REPORT,
                "周报与月报",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "周一或每月 1 日的本地报表提醒"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EXPORT,
                "数据导出",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "导出进度与完成提示"
            },
        )
    }

    fun weeklyEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_WEEKLY, false)

    fun monthlyEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_MONTHLY, false)

    fun setWeeklyEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_WEEKLY, enabled).apply()
        if (enabled) scheduleWeekly(context) else WorkManager.getInstance(context).cancelAllWorkByTag(WORK_WEEKLY)
    }

    fun setMonthlyEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_MONTHLY, enabled).apply()
        if (enabled) scheduleMonthly(context) else WorkManager.getInstance(context).cancelAllWorkByTag(WORK_MONTHLY)
    }

    fun scheduleWeekly(context: Context) {
        if (!weeklyEnabled(context)) return
        val delay = nextWeeklyDelayMillis()
        val period = Instant.ofEpochMilli(System.currentTimeMillis() + delay).atZone(ZoneId.systemDefault()).toLocalDate()
        val request = OneTimeWorkRequestBuilder<ReportReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(EXTRA_KIND to KIND_WEEKLY, "anchor" to period.minusWeeks(1).toString()))
            .addTag(TAG_REPORT_REMINDER)
            .addTag(WORK_WEEKLY)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$WORK_WEEKLY:$period",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun scheduleMonthly(context: Context) {
        if (!monthlyEnabled(context)) return
        val delay = nextMonthlyDelayMillis()
        val period = Instant.ofEpochMilli(System.currentTimeMillis() + delay).atZone(ZoneId.systemDefault()).toLocalDate()
        val request = OneTimeWorkRequestBuilder<ReportReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(EXTRA_KIND to KIND_MONTHLY, "anchor" to period.minusMonths(1).toString()))
            .addTag(TAG_REPORT_REMINDER)
            .addTag(WORK_MONTHLY)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$WORK_MONTHLY:$period",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Called by the app after startup if the user opted in; safe to call repeatedly. */
    fun restoreSchedules(context: Context) {
        if (weeklyEnabled(context)) scheduleWeekly(context)
        if (monthlyEnabled(context)) scheduleMonthly(context)
    }

    internal fun notifyReport(context: Context, weekly: Boolean, anchor: LocalDate) {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        val title = if (weekly) "上周报告已生成" else "上月报告已生成"
        val text = if (weekly) {
            "打开复盘 → 报表查看上周的本地统计。数字在本机计算。"
        } else {
            "打开复盘 → 报表查看上月的本地统计。数字在本机计算。"
        }
        val notification = Notification.Builder(context, CHANNEL_REPORT)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(reportIntent(context, weekly, anchor))
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(if (weekly) NOTIFICATION_WEEKLY else NOTIFICATION_MONTHLY, notification) }
    }

    internal fun showExportProgress(context: Context, workId: UUID, percent: Int, stage: String) {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        val cancel = cancelIntent(context, workId)
        val notification = Notification.Builder(context, CHANNEL_EXPORT)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在导出数据")
            .setContentText(stage.take(80))
            .setProgress(100, percent.coerceIn(0, 100), false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
                    "取消",
                    cancel,
                ).build(),
            )
            .build()
        runCatching { manager.notify(NOTIFICATION_EXPORT_PROGRESS, notification) }
    }

    internal fun notifyExportDone(context: Context, fileName: String) {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        manager.cancel(NOTIFICATION_EXPORT_PROGRESS)
        val notification = Notification.Builder(context, CHANNEL_EXPORT)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("数据导出完成")
            .setContentText(fileName.take(80))
            .setContentIntent(reportIntent(context))
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_EXPORT_DONE, notification) }
    }

    internal fun cancelExport(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_EXPORT_PROGRESS)
    }

    internal fun notifyIncompleteExport(context: Context, message: String) {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.areNotificationsEnabled()) manager.notify(4222,
            Notification.Builder(context, CHANNEL_EXPORT).setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("未完成的导出文件需要清理").setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message)).setAutoCancel(true)
                .setContentIntent(reportIntent(context)).build())
    }

    private fun reportIntent(context: Context, weekly: Boolean? = null, period: LocalDate? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            val date = java.time.LocalDate.now()
            val anchor = period ?: if (weekly == true) date.minusWeeks(1) else date.minusMonths(1)
            val destination = if (weekly == null) "export" else "report-period:${if (weekly) "WEEK" else "MONTH"}:$anchor"
            putExtra("appDestination", destination)
            data = android.net.Uri.parse("timeagent://$destination")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            4210,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelIntent(context: Context, workId: UUID): PendingIntent {
        val intent = Intent(context, ExportCancelReceiver::class.java).apply {
            putExtra(ExportWorker.EXTRA_WORK_ID, workId.toString())
        }
        return PendingIntent.getBroadcast(
            context,
            workId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun nextWeeklyDelayMillis(): Long {
        val now = ZonedDateTime.now()
        var target = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
            .withHour(9).withMinute(0).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusWeeks(1)
        return Duration.between(now, target).toMillis().coerceAtLeast(1_000L)
    }

    private fun nextMonthlyDelayMillis(): Long {
        val now = ZonedDateTime.now()
        var target = now.toLocalDate().withDayOfMonth(1)
            .atTime(LocalTime.of(9, 0)).atZone(now.zone)
        if (!target.isAfter(now)) {
            target = now.toLocalDate().withDayOfMonth(1).plusMonths(1)
                .atTime(LocalTime.of(9, 0)).atZone(now.zone)
        }
        return Duration.between(now, target).toMillis().coerceAtLeast(1_000L)
    }

    private const val TAG_REPORT_REMINDER = "report-reminder"

    internal suspend fun resetSchedules(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG_REPORT_REMINDER).await()
        restoreSchedules(context)
    }
}

class ReportScheduleReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try { ReportNotifications.resetSchedules(context.applicationContext) }
            finally { pending.finish() }
        }
    }
    companion object {
        private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    }
}

class ReportReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val kind = inputData.getString("kind") ?: return Result.success()
        val weekly = kind == "WEEKLY"
        if (weekly && !ReportNotifications.weeklyEnabled(applicationContext)) return Result.success()
        if (!weekly && !ReportNotifications.monthlyEnabled(applicationContext)) return Result.success()
        val repository = ReportRepository(applicationContext)
        val anchor = inputData.getString("anchor")?.let(LocalDate::parse)
            ?: if (weekly) repository.today().minusWeeks(1) else repository.today().minusMonths(1)
        repository.load(if (weekly) ReportKind.WEEK else ReportKind.MONTH, anchor)
        ReportNotifications.notifyReport(applicationContext, weekly, anchor)
        if (weekly) ReportNotifications.scheduleWeekly(applicationContext) else ReportNotifications.scheduleMonthly(applicationContext)
        return Result.success()
    }
}
