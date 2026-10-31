package com.timeagent.reminders

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.timeagent.MainActivity
import com.timeagent.data.ReminderEntity

data class ReminderAccessState(val notificationsAllowed: Boolean, val exactAlarmsAllowed: Boolean)

fun reminderAccessState(context: Context): ReminderAccessState {
    ReminderNotifications.ensureChannel(context)
    val manager = context.getSystemService(NotificationManager::class.java)
    val runtimeAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val channelAllowed = manager.getNotificationChannel(ReminderNotifications.CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    return ReminderAccessState(runtimeAllowed && manager.areNotificationsEnabled() && channelAllowed, exactAlarmsAllowed(context))
}

object ReminderIntents {
    const val EXTRA_REMINDER_ID = "reminder_id"
    const val EXTRA_REMIND_AT = "remind_at"
    internal const val ACTION_FIRE = "com.timeagent.reminders.FIRE"

    fun alarm(context: Context, id: String, remindAt: Long): Intent = Intent(context, ReminderReceiver::class.java)
        .setAction(ACTION_FIRE)
        .setData(Uri.Builder().scheme("timeagent").authority("reminder-alarm").appendPath(id).appendPath(remindAt.toString()).build())
        .putExtra(EXTRA_REMINDER_ID, id)
        .putExtra(EXTRA_REMIND_AT, remindAt)

    internal fun open(context: Context, id: String): Intent = Intent(context, MainActivity::class.java)
        .setData(Uri.Builder().scheme("timeagent").authority("reminder").appendPath(id).build())
        .putExtra(EXTRA_REMINDER_ID, id)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}

object ReminderNotifications {
    const val CHANNEL_ID = "task_point_reminders"
    private const val NOTIFICATION_ID = 1

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "任务点提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "在你设置的时间发送一次任务提醒"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
        }
    }

    internal fun post(context: Context, reminder: ReminderEntity): Boolean {
        ensureChannel(context)
        val click = PendingIntent.getActivity(
            context, 0, ReminderIntents.open(context, reminder.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val publicVersion = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("时间 Agent")
            .setContentText("有一条任务提醒")
            .build()
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("任务提醒")
            .setContentText(reminder.title)
            .setStyle(Notification.BigTextStyle().bigText(reminder.title))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(click)
            .setWhen(reminder.remindAt)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        return try {
            context.getSystemService(NotificationManager::class.java).notify(reminder.id, NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            // A permission revocation can race the preflight check. Never report success here.
            false
        }
    }

    internal fun cancel(context: Context, id: String) {
        context.getSystemService(NotificationManager::class.java).cancel(id, NOTIFICATION_ID)
    }

    /**
     * Dismiss any of this app's reminder notifications whose reminder is gone or no longer valid,
     * including notifications posted before the current process started.
     */
    internal fun cancelOrphans(context: Context, expectedTimes: Map<String, Long>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        runCatching {
            manager.activeNotifications.forEach { active ->
                val tag = active.tag ?: return@forEach
                if (active.notification.channelId != CHANNEL_ID) return@forEach
                val expected = expectedTimes[tag]
                if (expected == null || active.notification.`when` != expected) {
                    manager.cancel(tag, active.id)
                }
            }
        }
    }
}
