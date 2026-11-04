package com.timeagent.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

private val PresetReminderMinutes = listOf(0, 5, 10, 15, 30, 60, 1440)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReminderField(
    minutes: List<Int>,
    onChange: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
    permissionGranted: Boolean = true,
    onRequestPermission: (() -> Unit)? = null
) {
    var customText by rememberSaveable { mutableStateOf("") }
    val ordered = normalizeReminderMinutes(minutes)
    val customValue = customText.toIntOrNull()?.takeIf { it > 0 }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reminder_field_001), style = MaterialTheme.typography.titleMedium)
            Text(
                androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reminder_field_002),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!permissionGranted) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reminder_field_003), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { onRequestPermission?.invoke() }, enabled = onRequestPermission != null) {
                        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reminder_field_004))
                    }
                }
            }
        } else {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reminder_field_005), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PresetReminderMinutes.forEach { preset ->
                FilterChip(
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    selected = preset in minutes,
                    onClick = {
                        onChange(normalizeReminderMinutes(if (preset in minutes) minutes - preset else minutes + preset))
                    },
                    label = { Text(durationText(preset)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }
        OutlinedTextField(
            value = customText,
            onValueChange = { input -> customText = input.filter { it.isDigit() } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reminder_field_006)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        Button(
            onClick = {
                customValue?.let { value ->
                    onChange(normalizeReminderMinutes(minutes + value))
                    customText = ""
                }
            },
            enabled = customValue != null,
            modifier = Modifier.sizeIn(minHeight = 48.dp)
        ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reminder_field_007)) }
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reminder_field_008), style = MaterialTheme.typography.titleSmall)
        if (ordered.isEmpty()) {
            Text(
                androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reminder_field_009),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ordered.forEach { value ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = reminderText(value),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { onChange(normalizeReminderMinutes(minutes.filterNot { it == value })) },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_015)) }
                    }
                }
            }
        }
    }
}

private fun normalizeReminderMinutes(values: List<Int>): List<Int> = values.distinct().sorted()

@Composable
private fun durationText(minutes: Int): String {
    if (minutes == 0) return androidx.compose.ui.res.stringResource(com.timeagent.R.string.rr_duration_at_start)
    val days = minutes / 1440
    val hours = (minutes % 1440) / 60
    val rest = minutes % 60
    val dayText = if (days > 0) androidx.compose.ui.res.stringResource(com.timeagent.R.string.rr_duration_days, days) else null
    val hourText = if (hours > 0) androidx.compose.ui.res.stringResource(com.timeagent.R.string.rr_duration_hours, hours) else null
    val restText = if (rest > 0) androidx.compose.ui.res.stringResource(com.timeagent.R.string.rr_duration_minutes, rest) else null
    return listOfNotNull(dayText, hourText, restText).joinToString(" ")
}

@Composable
private fun reminderText(minutes: Int): String =
    if (minutes == 0) androidx.compose.ui.res.stringResource(com.timeagent.R.string.rr_reminder_at_start)
    else androidx.compose.ui.res.stringResource(com.timeagent.R.string.rr_reminder_advance, durationText(minutes))
