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
        Text("通知权限：${if (access.notificationsAllowed) "已允许" else "未允许"}")
        Text("精确闹钟：${if (access.exactAlarmsAllowed) "已允许" else "未允许"}")
        Text("提醒的声音、震动和锁屏显示在系统通知设置中调整。")
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }) { Text("打开系统通知设置") }
        if (Build.VERSION.SDK_INT >= 31) OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${context.packageName}")))
        }) { Text("打开精确闹钟权限") }
    }
}

@Composable
fun CalendarSettingsScreen(onClose: () -> Unit) {
    SettingsPage("发送到系统日历", onClose) {
        Text("打开一条日程或任务，在详情中选择“发送到日历”，再在系统日历中确认保存。")
        Text("修改后如需更新系统日历，请再次打开对应条目。")
    }
}

@Composable
fun AboutScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    SettingsPage("关于时间 Agent", onClose) {
        Text("时间 Agent · ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}")
        Text("时间管理、个人管家与大肥鱼陪伴。原生业务保存在本机；AI 使用你自己填写的 API Key。")
        Text("Linux（proot）与 DSH 为内置角色提供运行环境。")
        Text("开源许可")
        Text(context.assets.open("fish/licenses/DSHA-THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() })
    }
}
