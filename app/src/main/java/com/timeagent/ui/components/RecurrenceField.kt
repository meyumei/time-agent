package com.timeagent.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.timeagent.R
import com.timeagent.core.RecurringPattern
import com.timeagent.ui.design.DesignTokens
import java.time.LocalDate
import java.time.LocalTime

private enum class Mode(@StringRes val labelRes: Int?, val frequency: String?) {
    NONE(R.string.rr_mode_none, null), ONCE(R.string.rr_mode_once, "ONCE"), DAILY(R.string.rr_mode_daily, "DAILY"), WORKDAYS(R.string.rr_mode_workdays, "WEEKDAYS"), WEEKDAYS(R.string.rr_mode_weekdays, "WEEKDAYS"),
    MONTHLY(R.string.rr_mode_monthly, "MONTHLY"), YEARLY(R.string.rr_mode_yearly, "YEARLY"), WEEKLY_COUNT(R.string.rr_mode_weekly_count, "WEEKLY_COUNT"), OTHER(null, null),
}

private val CHOICES = Mode.entries.filter { it.labelRes != null }

private fun modeOf(p: RecurringPattern?): Mode = when {
    p == null -> Mode.NONE
    p.frequency == "ONCE" -> Mode.ONCE
    p.frequency == "DAILY" -> Mode.DAILY
    p.frequency == "WEEKDAYS" -> if (p.weekdays.sorted() == listOf(1, 2, 3, 4, 5)) Mode.WORKDAYS else Mode.WEEKDAYS
    p.frequency == "MONTHLY" -> Mode.MONTHLY
    p.frequency == "YEARLY" -> Mode.YEARLY
    p.frequency == "WEEKLY_COUNT" -> Mode.WEEKLY_COUNT
    else -> Mode.OTHER
}

private fun nextPattern(mode: Mode, old: RecurringPattern?, seedId: String, seedDate: LocalDate): RecurringPattern? {
    val freq = mode.frequency ?: return null
    val base = old ?: RecurringPattern(id = seedId, frequency = freq, startDate = seedDate.toString(), effectiveFrom = seedDate.toString())
    val days = when (mode) {
        Mode.WORKDAYS -> listOf(1, 2, 3, 4, 5)
        Mode.WEEKDAYS -> old?.weekdays?.takeIf { it.isNotEmpty() } ?: listOf(1)
        Mode.ONCE, Mode.DAILY, Mode.MONTHLY, Mode.YEARLY, Mode.WEEKLY_COUNT -> emptyList()
        else -> old?.weekdays ?: emptyList()
    }
    val count = if (mode == Mode.WEEKLY_COUNT) (old?.weeklyCount ?: 1).coerceIn(1, 21) else 1
    return base.copy(frequency = freq, weekdays = days, weeklyCount = count,
        minuteOfDay = if (mode == Mode.WEEKLY_COUNT) null else old?.minuteOfDay)
}

@Composable
private fun summary(p: RecurringPattern?, dayNames: List<String>): String {
    if (p == null) return stringResource(R.string.rr_mode_none)
    val base = when {
        p.frequency == "DAILY" -> stringResource(R.string.rr_mode_daily)
        p.frequency == "ONCE" -> stringResource(R.string.rr_summary_once, p.startDate)
        p.frequency == "WEEKDAYS" && p.weekdays.sorted() == listOf(1, 2, 3, 4, 5) -> stringResource(R.string.rr_mode_workdays)
        p.frequency == "WEEKDAYS" && p.weekdays.isNotEmpty() -> stringResource(
            R.string.rr_summary_weekly_selected,
            p.weekdays.sorted().joinToString(stringResource(R.string.rr_day_separator)) { dayNames.getOrNull(it - 1) ?: "$it" }
        )
        p.frequency == "WEEKDAYS" -> stringResource(R.string.rr_summary_weekly_unselected)
        p.frequency == "MONTHLY" -> stringResource(R.string.rr_summary_monthly, LocalDate.parse(p.startDate).dayOfMonth)
        p.frequency == "YEARLY" -> stringResource(R.string.rr_summary_yearly, LocalDate.parse(p.startDate).monthValue, LocalDate.parse(p.startDate).dayOfMonth)
        p.frequency == "WEEKLY_COUNT" -> stringResource(R.string.rr_weekly_count, p.weeklyCount)
        else -> stringResource(R.string.rr_summary_custom, p.frequency)
    }
    val m = p.minuteOfDay ?: return base
    return stringResource(R.string.rr_summary_with_time, base, "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}")
}

@Composable
fun RecurrenceField(
    value: RecurringPattern?,
    onChange: (RecurringPattern?) -> Unit,
    modifier: Modifier = Modifier,
    seedDate: LocalDate = LocalDate.now(),
    seedId: String = "draft-rule",
    allowNone: Boolean = true,
    onValidityChange: (Boolean) -> Unit = {},
) {
    val mode = modeOf(value)
    val dayNames = stringArrayResource(R.array.rr_day_names_short).toList()
    val current = value
    var customOpen by rememberSaveable { mutableStateOf(false) }
    var lead by rememberSaveable(value?.id, value?.leadMinutes) {
        mutableStateOf(value?.leadMinutes?.toString().orEmpty())
    }
    val leadValid = lead.isBlank() || lead.toIntOrNull()?.let { it in 0..527040 } == true
    val isValid = current == null || (
        leadValid &&
            current.durationMinutes in 1..1440 &&
            (current.untilDate?.let { it >= current.startDate } != false)
        )
    val latestOnValidityChange by rememberUpdatedState(onValidityChange)
    LaunchedEffect(isValid) { latestOnValidityChange(isValid) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)) {
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurrence_field_001), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)) {
            CHOICES.filter { allowNone || it != Mode.NONE }.forEach { m ->
                FilterChip(selected = mode == m, onClick = { onChange(nextPattern(m, current, seedId, seedDate)) },
                    label = { Text(m.labelRes?.let { stringResource(it) }.orEmpty()) }, modifier = Modifier.heightIn(min = DesignTokens.touchTarget))
            }
        }
        if (current != null && (mode == Mode.WORKDAYS || mode == Mode.WEEKDAYS)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)) {
                (1..7).forEach { day ->
                    val dayDescription = stringResource(R.string.rr_day_content_description, dayNames[day - 1])
                    FilterChip(selected = day in current.weekdays, label = { Text(dayNames[day - 1]) },
                        onClick = {
                            val days = if (day in current.weekdays) current.weekdays - day else current.weekdays + day
                            if (days.isNotEmpty()) onChange(current.copy(weekdays = days.sorted()))
                        },
                        modifier = Modifier.widthIn(min = DesignTokens.touchTarget).heightIn(min = DesignTokens.touchTarget)
                            .semantics { contentDescription = dayDescription })
                }
            }
        }
        if (mode == Mode.WEEKLY_COUNT && current != null) {
            val count = current.weeklyCount.coerceIn(1, 21)
            val decreaseDescription = stringResource(R.string.rr_weekly_count_decrease)
            val increaseDescription = stringResource(R.string.rr_weekly_count_increase)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing12)) {
                OutlinedButton(onClick = { onChange(current.copy(weeklyCount = (count - 1).coerceIn(1, 21))) }, enabled = count > 1,
                    modifier = Modifier.size(DesignTokens.touchTarget).semantics { contentDescription = decreaseDescription },
                    contentPadding = PaddingValues(0.dp)) { Text("−") }
                Text(stringResource(R.string.rr_weekly_count, count), style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = { onChange(current.copy(weeklyCount = (count + 1).coerceIn(1, 21))) }, enabled = count < 21,
                    modifier = Modifier.size(DesignTokens.touchTarget).semantics { contentDescription = increaseDescription },
                    contentPadding = PaddingValues(0.dp)) { Text("+") }
            }
        }
        Text(summary(current, dayNames), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (current != null) {
            TextButton(onClick = { customOpen = !customOpen }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (customOpen) stringResource(R.string.rr_custom_collapse) else stringResource(R.string.rr_custom_expand))
            }
            if (customOpen) {
                DateField(stringResource(R.string.rr_label_start_date), LocalDate.parse(current.startDate), {
                    onChange(current.copy(startDate = it.toString()))
                })
                if (mode != Mode.WEEKLY_COUNT) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(current.minuteOfDay != null, { onChange(current.copy(minuteOfDay = if (it) 9 * 60 else null)) })
                        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurrence_field_002), Modifier.weight(1f))
                    }
                    current.minuteOfDay?.let { minute ->
                        TimeField(stringResource(R.string.rr_label_time), LocalTime.of(minute / 60, minute % 60), {
                            onChange(current.copy(minuteOfDay = it.hour * 60 + it.minute))
                        })
                    }
                }
                DurationField(current.durationMinutes, { onChange(current.copy(durationMinutes = it)) })
                InlineError(if (current.durationMinutes !in 1..1440) stringResource(R.string.rr_duration_range_error) else null)
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurrence_field_003), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(current.untilDate != null, {
                        onChange(current.copy(untilDate = if (it) current.startDate else null))
                    })
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurrence_field_004), Modifier.weight(1f))
                }
                current.untilDate?.let { until ->
                    DateField(stringResource(R.string.rr_label_end_date), LocalDate.parse(until), { onChange(current.copy(untilDate = it.toString())) },
                        error = if (until < current.startDate) stringResource(R.string.rr_until_before_start_error) else null)
                }
                DateField(stringResource(R.string.rr_label_effective_from), LocalDate.parse(current.effectiveFrom), {
                    onChange(current.copy(effectiveFrom = it.toString()))
                })
                OutlinedTextField(lead, { input ->
                    lead = input
                    if (input.isBlank()) onChange(current.copy(leadMinutes = null))
                    else input.toIntOrNull()?.takeIf { it in 0..527040 }?.let {
                        onChange(current.copy(leadMinutes = it))
                    }
                }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurrence_field_005)) }, modifier = Modifier.fillMaxWidth(),
                    isError = lead.isNotBlank() && lead.toIntOrNull()?.let { it in 0..527040 } != true,
                    supportingText = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurrence_field_006)) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(current.paused, { onChange(current.copy(paused = it)) })
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurrence_field_007), Modifier.weight(1f))
                }
                Text(stringResource(R.string.rr_timezone, current.zoneId), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
