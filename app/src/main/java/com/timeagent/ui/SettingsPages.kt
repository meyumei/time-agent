package com.timeagent.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.timeagent.reminders.reminderAccessState
import com.timeagent.ui.components.StatusBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPage(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = { com.timeagent.ui.components.AppTopBar(title, onClose) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
fun NotificationSettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(reminderAccessState(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = reminderAccessState(context) }
    SettingsPage("提醒与通知", onClose) {
        val impacts = buildList {
            if (!access.notificationsAllowed) add("通知权限未开启，到点的提醒不会显示。")
            if (!access.exactAlarmsAllowed) add("精确闹钟权限未开启，提醒可能延迟，无法保证准点。")
        }
        if (impacts.isNotEmpty()) StatusBanner(impacts.joinToString("") + "提醒仍会保存在本机。")
        Text("通知权限：${if (access.notificationsAllowed) "已允许" else "未允许"}")
        Text("精确闹钟：${if (access.exactAlarmsAllowed) "已允许" else "未允许"}")
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_settings_pages_001))
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_settings_pages_002)) }
        if (Build.VERSION.SDK_INT >= 31) OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${context.packageName}")))
        }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_settings_pages_003)) }
    }
}

@Composable
fun CalendarSettingsScreen(onClose: () -> Unit) {
    SettingsPage("发送到系统日历", onClose) {
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_settings_pages_004))
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_settings_pages_005))
    }
}

@Composable
fun AboutScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    SettingsPage("关于时间 Agent", onClose) {
        Text("时间 Agent · ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}")
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_settings_pages_006))
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_settings_pages_007))
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_settings_pages_008))
        Text(context.assets.open("fish/licenses/DSHA-THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() })
    }
}
