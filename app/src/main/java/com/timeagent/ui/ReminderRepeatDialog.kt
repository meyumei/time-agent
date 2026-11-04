package com.timeagent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.timeagent.data.ReminderEntity

@Composable
internal fun ReminderRepeatDialog(model: TimeViewModel, reminder: ReminderEntity, onClose: () -> Unit) {
    val busy by model.busy.collectAsState()
    var enabled by rememberSaveable(reminder.id) { mutableStateOf(reminder.repeatIntervalMinutes != null) }
    var interval by rememberSaveable(reminder.id) { mutableStateOf((reminder.repeatIntervalMinutes ?: 5).toString()) }
    var count by rememberSaveable(reminder.id) { mutableStateOf((reminder.repeatMaxCount ?: 3).toString()) }
    val minutes = interval.toIntOrNull()
    val repeats = count.toIntOrNull()
    AlertDialog(onDismissRequest = onClose, title = { Text("未处理时重复提醒") },
        text = { Column {
            Text("打开详情或完成后停止重复；稍后提醒不会移动日程。")
            Switch(checked = enabled, onCheckedChange = { enabled = it })
            if (enabled) {
                OutlinedTextField(interval, { interval = it }, label = { Text("每隔多少分钟") })
                OutlinedTextField(count, { count = it }, label = { Text("最多重复次数") })
            }
        } },
        confirmButton = { TextButton(
            enabled = !busy && (!enabled || minutes != null && minutes > 0 && repeats != null && repeats > 0),
            onClick = { model.perform(onSuccess = onClose) {
                model.repository.saveReminder(reminder.copy(
                    repeatIntervalMinutes = minutes.takeIf { enabled },
                    repeatMaxCount = repeats.takeIf { enabled },
                ), reminder.version)
            } },
        ) { Text("保存") } },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("取消") } })
}
