package com.timeagent.reminders

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.timeagent.data.TimeRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class ReminderSnoozePreferences(
    val custom1Minutes: Int = 120,
    val custom2Minutes: Int = 1440,
)

/** Two user-editable presets; the five standard choices are intentionally fixed. */
class ReminderSnoozeSettings(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("reminder_snooze_settings_v1", Context.MODE_PRIVATE)

    fun read(): ReminderSnoozePreferences = ReminderSnoozePreferences(
        custom1Minutes = preferences.getInt(KEY_CUSTOM_1, 120).coerceIn(1, 10080),
        custom2Minutes = preferences.getInt(KEY_CUSTOM_2, 1440).coerceIn(1, 10080),
    )

    fun save(value: ReminderSnoozePreferences) {
        preferences.edit()
            .putInt(KEY_CUSTOM_1, value.custom1Minutes.coerceIn(1, 10080))
            .putInt(KEY_CUSTOM_2, value.custom2Minutes.coerceIn(1, 10080))
            .apply()
    }

    private companion object {
        const val KEY_CUSTOM_1 = "custom_1_minutes"
        const val KEY_CUSTOM_2 = "custom_2_minutes"
    }
}

data class ReminderSnoozeOption(val label: String, val fireAt: Long)

private val snoozeTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")

fun reminderSnoozeOptions(
    now: Instant,
    zone: ZoneId,
    preferences: ReminderSnoozePreferences,
): List<ReminderSnoozeOption> {
    val options = mutableListOf<ReminderSnoozeOption>()
    fun add(label: String, at: Instant) {
        if (at.isAfter(now)) options += ReminderSnoozeOption(label, at.toEpochMilli())
    }
    add("10 分钟", now.plus(10, ChronoUnit.MINUTES))
    add("30 分钟", now.plus(30, ChronoUnit.MINUTES))
    add("1 小时", now.plus(1, ChronoUnit.HOURS))

    val today = now.atZone(zone).toLocalDate()
    val todayEvening = today.atTime(20, 0).atZone(zone).toInstant()
    val evening = if (todayEvening.isAfter(now)) todayEvening
    else today.plusDays(1).atTime(20, 0).atZone(zone).toInstant()
    add(if (evening.atZone(zone).toLocalDate() == today) "今晚 20:00" else "明天 20:00", evening)

    add("明天 09:00", today.plusDays(1).atTime(9, 0).atZone(zone).toInstant())
    add("${formatMinutes(preferences.custom1Minutes)}（自定义）", now.plus(preferences.custom1Minutes.toLong(), ChronoUnit.MINUTES))
    add("${formatMinutes(preferences.custom2Minutes)}（自定义）", now.plus(preferences.custom2Minutes.toLong(), ChronoUnit.MINUTES))
    return options.distinctBy { it.fireAt }
}

/**
 * Bottom sheet opened from the notification 稍后 action or the reminder detail sheet.
 * It only chooses a new fire time; the caller persists a SNOOZE instance and never rewrites the
 * reminder's original `remindAt`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderSnoozeSheet(
    reminderTitle: String,
    onDismiss: () -> Unit,
    onSnooze: (Long) -> Unit,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val context = LocalContext.current
    val settings = remember(context) { ReminderSnoozeSettings(context.applicationContext) }
    var preferences by remember { mutableStateOf(settings.read()) }
    var editing by remember { mutableStateOf(false) }
    val options = remember(now, zone, preferences) { reminderSnoozeOptions(now, zone, preferences) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reminder_snooze_sheet_001), style = MaterialTheme.typography.titleLarge)
            Text(reminderTitle, style = MaterialTheme.typography.bodyLarge)
            Text(
                androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reminder_snooze_sheet_002),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            options.forEach { option ->
                OutlinedButton(onClick = { onSnooze(option.fireAt) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(option.label, Modifier.weight(1f))
                        Text(
                            Instant.ofEpochMilli(option.fireAt).atZone(zone).format(snoozeTimeFormat),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            TextButton(onClick = { editing = true }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reminder_snooze_sheet_003)) }
        }
    }

    if (editing) {
        ReminderSnoozeCustomDialog(
            preferences = preferences,
            onDismiss = { editing = false },
            onSave = {
                settings.save(it)
                preferences = it
                editing = false
            },
        )
    }
}

@Composable
private fun ReminderSnoozeCustomDialog(
    preferences: ReminderSnoozePreferences,
    onDismiss: () -> Unit,
    onSave: (ReminderSnoozePreferences) -> Unit,
) {
    var first by remember { mutableStateOf(preferences.custom1Minutes.toString()) }
    var second by remember { mutableStateOf(preferences.custom2Minutes.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reminder_snooze_sheet_004)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reminder_snooze_sheet_005), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it.filter(Char::isDigit).take(5); error = null },
                    label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reminder_snooze_sheet_006)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = second,
                    onValueChange = { second = it.filter(Char::isDigit).take(5); error = null },
                    label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reminder_snooze_sheet_007)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = first.toIntOrNull()
                val b = second.toIntOrNull()
                if (a == null || b == null || a !in 1..10080 || b !in 1..10080) {
                    error = "请输入 1–10080 的分钟数"
                } else {
                    onSave(ReminderSnoozePreferences(a, b))
                }
            }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } },
    )
}

/** Shared entry point for the sheet/detail callback: create SNOOZE, cancel the notification, rearm. */
suspend fun applyReminderSnooze(
    context: Context,
    repository: TimeRepository,
    reminderId: String,
    fireAt: Long,
    expectedVersion: Long? = null,
) {
    val reminder = requireNotNull(repository.database.dao().reminder(reminderId)) { "提醒不存在" }
    ReminderInstanceRepository(repository).snooze(
        id = reminderId,
        fireAt = fireAt,
        expectedVersion = expectedVersion ?: reminder.version,
    )
    ReminderNotifications.cancel(context, reminderId)
    ReminderRestoreReceiver.requestRefresh(context)
}

private fun formatMinutes(minutes: Int): String = when {
    minutes % 1440 == 0 -> "${minutes / 1440} 天"
    minutes % 60 == 0 -> "${minutes / 60} 小时"
    else -> "$minutes 分钟"
}
