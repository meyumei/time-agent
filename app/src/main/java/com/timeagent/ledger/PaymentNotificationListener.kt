package com.timeagent.ledger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.timeagent.data.TimeAgentDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object LedgerSettings {
    const val PREFS = "ledger_settings"
    const val ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, value).apply()
    }

    fun notificationsAllowed(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: false

    fun notificationAccessGranted(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it.packageName == context.packageName }
    }
}

/**
 * Only reads notifications after the package whitelist check. The parsed fields become a pending
 * draft; nothing is booked until the user chooses an account and a category.
 */
class PaymentNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database = lazy { TimeAgentDatabase.open(applicationContext) }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val status = sbn ?: return
        val packageName = status.packageName ?: return
        if (packageName !in PaymentParser.ALLOWED_PACKAGES) return
        if (!LedgerSettings.enabled(this)) return
        val extras = status.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = listOf(
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_SUMMARY_TEXT,
        ).mapNotNull { key ->
            extras.getCharSequence(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }.distinct().joinToString(" ")
        if (title.isBlank() && body.isBlank()) return
        val postedAt = status.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        scope.launch {
            val parsed = PaymentParser.parseNotification(packageName, title, body, postedAt) ?: return@launch
            try {
                val repository = LedgerRepository(database.value, Clock.systemDefaultZone())
                val capture = repository.recordCapture(parsed, status.key.orEmpty()) ?: return@launch
                LedgerNotifications.postDraft(this@PaymentNotificationListener, capture)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Notification text is private: failures are silent and never logged.
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (database.isInitialized()) database.value.close()
        super.onDestroy()
    }
}

object LedgerNotifications {
    const val CHANNEL_ID = "ledger_capture"

    fun postDraft(context: Context, capture: PaymentCaptureEntity) {
        if (!LedgerSettings.notificationsAllowed(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager)
        val open = PendingIntent.getActivity(
            context,
            capture.id.hashCode(),
            Intent(context, LedgerActivity::class.java).apply {
                putExtra(LedgerActivity.EXTRA_CAPTURE_ID, capture.id)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val ignore = PendingIntent.getActivity(
            context,
            capture.id.hashCode() + 1,
            Intent(context, LedgerActivity::class.java).apply {
                putExtra(LedgerActivity.EXTRA_CAPTURE_ID, capture.id)
                putExtra(LedgerActivity.EXTRA_ACTION, LedgerActivity.ACTION_IGNORE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val time = Instant.ofEpochMilli(capture.occurredAt).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))
        val smallIcon = context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info
        val merchant = capture.merchant.ifBlank { "未识别商户" }
        val title = "待确认 · ${directionLabel(capture.direction)} ${Money.format(capture.amountMinor)}"
        val publicVersion = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle("有一笔支付待确认")
            .setContentText("打开个人管家查看")
            .build()
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText("${capture.sourceLabel} · $time · $merchant")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .addAction(action(context, android.R.drawable.ic_menu_edit, "选择分类", open))
            .addAction(action(context, android.R.drawable.ic_menu_close_clear_cancel, "忽略", ignore))
            .build()
        try {
            manager.notify(capture.id.hashCode(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS was revoked between the check and notify.
        }
    }

    fun cancel(context: Context, captureId: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(captureId.hashCode())
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "支付待确认", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "微信、支付宝支付通知形成的待确认草稿"
                },
            )
        }
    }

    private fun action(context: Context, icon: Int, title: String, intent: PendingIntent): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(context, icon), title, intent).build()
}
