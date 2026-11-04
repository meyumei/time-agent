package com.timeagent.timer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.timeagent.ui.components.AppTopBar

@Composable
fun TimerNotificationSettings(onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(TimerNotifications.PREFS, 0) }
    var quick by remember { mutableStateOf(prefs.getBoolean("quick_enabled", false)) }
    var hide by remember { mutableStateOf(prefs.getBoolean("hide_name", true)) }
    Scaffold(topBar = { AppTopBar("计时与快捷通知", onClose) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("自由计时、专注和训练进行时共用一条通知。关闭通知权限不会中断计时。")
            Row {
                Text("无计时时显示快捷通知", Modifier.weight(1f))
                Switch(quick, { quick = it; prefs.edit().putBoolean("quick_enabled", it).apply(); TimerNotifications.refresh(context) })
            }
            Row {
                Text("锁屏隐藏计时名称", Modifier.weight(1f))
                Switch(hide, { hide = it; prefs.edit().putBoolean("hide_name", it).apply(); TimerNotifications.refresh(context) })
            }
            Text("划掉通知后计时仍在进行，下次打开应用会恢复显示。")
        }
    }
}
