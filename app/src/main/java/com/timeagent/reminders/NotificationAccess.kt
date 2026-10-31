package com.timeagent.reminders

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** User-initiated permission controls and the system's currently reported timing capability. */
@Composable
fun NotificationAccess(modifier: Modifier = Modifier, onAccessChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var access by remember(context) { mutableStateOf(reminderAccessState(context)) }
    val onChanged by rememberUpdatedState(onAccessChanged)
    val refreshAccess = {
        access = reminderAccessState(context)
        ReminderRestoreReceiver.requestRefresh(context)
        onChanged()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshAccess() }
    DisposableEffect(context, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshAccess() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    Card(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (access.notificationsAllowed) "通知权限已开启" else "通知未开启", style = MaterialTheme.typography.titleSmall)
            Text(
                if (access.notificationsAllowed) "到点后将向系统提交通知，声音和显示方式由系统设置决定。"
                else "提醒会保存在本机。允许通知后，未来提醒才能显示在状态栏。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    TextButton(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("允许通知") }
                }
                TextButton(onClick = {
                    openSystemScreen(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text("通知设置") }
            }
            Text(
                if (access.exactAlarmsAllowed) "精确闹钟可用；实际显示仍受系统和省电策略影响。"
                else "精确闹钟未授权，将使用系统允许的延后提醒，不能保证准点。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !access.exactAlarmsAllowed) {
                TextButton(onClick = {
                    openSystemScreen(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                }) { Text("设置提醒精度") }
            }
        }
    }
}

private fun openSystemScreen(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "请在系统设置中查找时间 Agent 的通知和闹钟权限", Toast.LENGTH_LONG).show()
        }
    }
}
