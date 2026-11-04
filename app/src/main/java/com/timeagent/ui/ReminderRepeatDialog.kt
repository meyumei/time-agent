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
    AlertDialog(onDismissRequest = onClose, title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reminder_repeat_dialog_001)) },
        text = { Column {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reminder_repeat_dialog_002))
            Switch(checked = enabled, onCheckedChange = { enabled = it })
            if (enabled) {
                OutlinedTextField(interval, { interval = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reminder_repeat_dialog_003)) })
                OutlinedTextField(count, { count = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reminder_repeat_dialog_004)) })
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
        ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } })
}
