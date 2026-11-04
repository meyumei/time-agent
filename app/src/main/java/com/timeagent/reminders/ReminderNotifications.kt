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
    const val EXTRA_INSTANCE_ID = "reminder_instance_id"

    /** MainActivity opens the snooze sheet when this boolean extra is true. */
    const val EXTRA_SNOOZE_REQUEST = "reminder_snooze_request"

    internal const val ACTION_FIRE = "com.timeagent.reminders.FIRE"
    internal const val ACTION_NOTIFICATION = "com.timeagent.reminders.NOTIFICATION"
    internal const val ACTION_OPEN = "com.timeagent.reminders.OPEN"
    internal const val EXTRA_NOTIFICATION_ACTION = "notification_action"
    internal const val ACTION_DONE = "done"
    internal const val ACTION_SNOOZE = "snooze"
    internal const val ACTION_START = "start"

    fun alarm(context: Context, id: String, remindAt: Long): Intent = Intent(context, ReminderReceiver::class.java)
        .setAction(ACTION_FIRE)
        .setData(Uri.Builder().scheme("timeagent").authority("reminder-alarm").appendPath(id).appendPath(remindAt.toString()).build())
        .putExtra(EXTRA_REMINDER_ID, id)
        .putExtra(EXTRA_REMIND_AT, remindAt)

    internal fun alarmInstance(context: Context, instanceId: String, reminderId: String, fireAt: Long): Intent =
        Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(Uri.Builder().scheme("timeagent").authority("reminder-instance").appendPath(instanceId).build())
            .putExtra(EXTRA_INSTANCE_ID, instanceId)
            .putExtra(EXTRA_REMINDER_ID, reminderId)
            .putExtra(EXTRA_REMIND_AT, fireAt)

    internal fun open(context: Context, id: String, snooze: Boolean = false, instanceId: String? = null): Intent {
        val data = Uri.Builder()
            .scheme("timeagent")
            .authority(if (snooze) "reminder-snooze" else "reminder")
            .appendPath(id)
            .apply { instanceId?.let { appendPath(it) } }
            .build()
        return Intent(context, MainActivity::class.java)
            .setData(data)
            .putExtra(EXTRA_REMINDER_ID, id)
            .putExtra(EXTRA_INSTANCE_ID, instanceId)
            .putExtra(EXTRA_SNOOZE_REQUEST, snooze)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    internal fun openRequest(context: Context, id: String, instanceId: String?): Intent =
        Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_OPEN)
            .setData(
                Uri.Builder()
                    .scheme("timeagent")
                    .authority("reminder-open")
                    .appendPath(id)
                    .appendPath(instanceId ?: "legacy")
                    .build(),
            )
            .putExtra(EXTRA_REMINDER_ID, id)
            .putExtra(EXTRA_INSTANCE_ID, instanceId)

    internal fun notificationAction(context: Context, id: String, action: String, instanceId: String? = null): Intent =
        Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_NOTIFICATION)
            .setData(
                Uri.Builder()
                    .scheme("timeagent")
                    .authority("reminder-action")
                    .appendPath(id)
                    .appendPath(action)
                    .appendPath(instanceId ?: "legacy")
                    .build(),
            )
            .putExtra(EXTRA_REMINDER_ID, id)
            .putExtra(EXTRA_INSTANCE_ID, instanceId)
            .putExtra(EXTRA_NOTIFICATION_ACTION, action)
}

object ReminderNotifications {
    const val CHANNEL_ID = "task_point_reminders"
    private const val NOTIFICATION_ID = 1

    private data class Content(
        val reminderId: String,
        val instanceId: String?,
        val title: String,
        val fireAt: Long,
        val repeat: Boolean,
    )

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "任务点提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "在设定时间发送任务或日程提醒；可重复或稍后提醒"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
        }
    }

    /** Legacy entry point retained for the one remaining old alarm/notification path. */
    internal fun post(context: Context, reminder: ReminderEntity): Boolean =
        post(context, Content(reminder.id, null, reminder.title, reminder.remindAt, repeat = false))

    internal fun post(context: Context, event: ReminderFireEvent): Boolean =
        post(context, Content(
            event.reminderId,
            event.instanceId,
            event.title,
            event.fireAt,
            repeat = event.kind == REMINDER_INSTANCE_REPEAT,
        ))

    private fun post(context: Context, content: Content): Boolean {
        ensureChannel(context)
        val click = PendingIntent.getActivity(
            context,
            reminderInstanceRequestCode("open:${content.reminderId}:${content.instanceId.orEmpty()}"),
            ReminderIntents.open(context, content.reminderId, instanceId = content.instanceId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val publicVersion = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("时间 Agent")
            .setContentText("有一条提醒")
            .build()
        fun action(label: String, value: String): Notification.Action =
            Notification.Action.Builder(
                null,
                label,
                PendingIntent.getBroadcast(
                    context,
                    reminderInstanceRequestCode("$value:${content.reminderId}:${content.instanceId.orEmpty()}"),
                    ReminderIntents.notificationAction(context, content.reminderId, value, content.instanceId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build()
        val snooze = PendingIntent.getActivity(
            context,
            reminderInstanceRequestCode("snooze-open:${content.reminderId}:${content.instanceId.orEmpty()}"),
            ReminderIntents.open(context, content.reminderId, snooze = true, instanceId = content.instanceId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(if (content.repeat) "重复提醒" else "任务提醒")
            .setContentText(content.title)
            .setStyle(Notification.BigTextStyle().bigText(content.title))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(click)
            .setWhen(content.fireAt)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!content.repeat)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .addAction(action("完成", ReminderIntents.ACTION_DONE))
            .addAction(Notification.Action.Builder(null, "稍后", snooze).build())
            .addAction(action("开始计时", ReminderIntents.ACTION_START))
            .build()
        return try {
            context.getSystemService(NotificationManager::class.java).notify(content.reminderId, NOTIFICATION_ID, notification)
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

    /** Kept for sources that need a deterministic request code without an instance id. */
    internal fun notificationRequestCode(key: String): Int = reminderInstanceRequestCode(key)
}
