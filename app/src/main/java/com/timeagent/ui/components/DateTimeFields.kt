@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.timeagent.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import com.timeagent.ui.design.DesignTokens
import com.timeagent.R
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val TimeDisplayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun FieldShell(
    label: String,
    valueText: String,
    error: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().heightIn(min = DesignTokens.touchTarget),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    valueText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = DesignTokens.spacing16, top = DesignTokens.spacing4),
            )
        }
    }
}

@Composable
fun DateField(
    label: String,
    value: LocalDate,
    onValueChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val datePattern = stringResource(R.string.component_date_display_pattern)
    val dateDisplayFormat = remember(datePattern) { DateTimeFormatter.ofPattern(datePattern) }
    FieldShell(label, value.format(dateDisplayFormat), error, { showDialog = true }, modifier)
    if (showDialog) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = value.toUtcMillis(),
            initialDisplayedMonthMillis = value.toUtcMillis(),
            initialDisplayMode = DisplayMode.Picker,
        )
        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onValueChange(it.toUtcDate()) }
                    showDialog = false
                }, enabled = state.selectedDateMillis != null) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_date_time_fields_001)) }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } },
        ) {
            DatePicker(state = state, showModeToggle = true)
        }
    }
}

@Composable
fun TimeField(
    label: String,
    value: LocalTime,
    onValueChange: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val is24Hour = android.text.format.DateFormat.is24HourFormat(context)
    val displayFormat = if (is24Hour) TimeDisplayFormat else DateTimeFormatter.ofPattern("a h:mm")
    FieldShell(label, value.format(displayFormat), error, { showDialog = true }, modifier)
    if (showDialog) {
        val fontScale = LocalDensity.current.fontScale
        var keyboard by rememberSaveable { mutableStateOf(fontScale > 1.3f) }
        val state = rememberTimePickerState(value.hour, value.minute, is24Hour = is24Hour)
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_date_time_fields_002)) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { keyboard = !keyboard }) {
                            Text(stringResource(if (keyboard) R.string.component_time_dial else R.string.component_time_keyboard))
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        if (keyboard) TimeInput(state) else TimePicker(state)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onValueChange(LocalTime.of(state.hour, state.minute))
                    showDialog = false
                }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_date_time_fields_003)) }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } },
        )
    }
}

@Composable
fun DateTimeRangeField(
    start: LocalDateTime,
    end: LocalDateTime,
    onChange: (LocalDateTime, LocalDateTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    val startDate = start.toLocalDate()
    val startTime = start.toLocalTime()
    val endDate = end.toLocalDate()
    val endTime = end.toLocalTime()
    val valid = end.isAfter(start)
    val crossDay = endDate == startDate.plusDays(1) && endTime < startTime
    val showCrossDay = crossDay || (endDate == startDate && endTime < startTime)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)) {
        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)) {
            DateField(stringResource(R.string.rr_label_start_date), startDate, { onChange(LocalDateTime.of(it, startTime), end) }, Modifier.weight(1f))
            TimeField(stringResource(R.string.component_start_time), startTime, { onChange(LocalDateTime.of(startDate, it), end) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)) {
            DateField(stringResource(R.string.rr_label_end_date), endDate, { onChange(start, LocalDateTime.of(it, endTime)) }, Modifier.weight(1f))
            TimeField(stringResource(R.string.component_end_time), endTime, { onChange(start, LocalDateTime.of(endDate, it)) }, Modifier.weight(1f))
        }
        if (showCrossDay) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = DesignTokens.touchTarget)
                    .toggleable(
                        value = crossDay,
                        role = Role.Switch,
                        onValueChange = { checked ->
                            val date = if (checked) startDate.plusDays(1) else startDate
                            onChange(start, LocalDateTime.of(date, endTime))
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(checked = crossDay, onCheckedChange = null)
                Spacer(Modifier.width(DesignTokens.spacing8))
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_date_time_fields_004), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (valid) {
            Text(
                stringResource(R.string.component_duration_summary, durationText(Duration.between(start, end))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_date_time_fields_005),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
fun DurationField(
    minutes: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    textValue: String? = null,
    onTextChange: ((String) -> Unit)? = null,
) {
    val presets = remember { listOf(15, 30, 45, 60, 90) }
    var internalText by rememberSaveable { mutableStateOf(if (minutes > 0) minutes.toString() else "") }
    val text = textValue ?: internalText
    LaunchedEffect(minutes) {
        if (textValue == null && internalText.toIntOrNull() != minutes) {
            internalText = if (minutes > 0) minutes.toString() else ""
        }
    }
    val invalid = text.isNotEmpty() && (text.toIntOrNull()?.let { it > 0 } != true)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)) {
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_date_time_fields_006), style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8),
        ) {
            presets.forEach { preset ->
                FilterChip(
                    selected = minutes == preset,
                    onClick = { internalText = preset.toString(); onTextChange?.invoke(preset.toString()); onChange(preset) },
                    label = { Text(stringResource(R.string.rr_duration_minutes, preset)) },
                )
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                internalText = input
                onTextChange?.invoke(input)
                input.toIntOrNull()?.takeIf { it > 0 }?.let(onChange)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_date_time_fields_007)) },
            singleLine = true,
            isError = invalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = if (invalid) {
                { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_date_time_fields_008)) }
            } else {
                null
            },
        )
    }
}

private fun LocalDate.toUtcMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
private fun durationText(duration: Duration): String {
    val totalMinutes = duration.toMinutes().coerceAtLeast(0)
    val hours = totalMinutes / 60
    val rest = totalMinutes % 60
    return when {
        hours > 0 && rest > 0 -> stringResource(R.string.component_duration_hours_minutes, hours, rest)
        hours > 0 -> stringResource(R.string.component_duration_hours, hours)
        else -> stringResource(R.string.component_duration_minutes, rest)
    }
}
