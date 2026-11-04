package com.timeagent.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.timeagent.core.RecurringPattern
import com.timeagent.ui.design.DesignTokens
import java.time.LocalDate
import java.time.LocalTime

private enum class Mode(val label: String?, val frequency: String?) {
    NONE("不重复", null), ONCE("仅一次", "ONCE"), DAILY("每天", "DAILY"), WORKDAYS("工作日", "WEEKDAYS"), WEEKDAYS("每周几", "WEEKDAYS"),
    MONTHLY("每月", "MONTHLY"), YEARLY("每年", "YEARLY"), WEEKLY_COUNT("每周频次", "WEEKLY_COUNT"), OTHER(null, null),
}

private val CHOICES = Mode.entries.filter { it.label != null }
private val DAY_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")

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

private fun summary(p: RecurringPattern?): String {
    if (p == null) return "不重复"
    val base = when {
        p.frequency == "DAILY" -> "每天"
        p.frequency == "ONCE" -> "仅 ${p.startDate} 一次"
        p.frequency == "WEEKDAYS" && p.weekdays.sorted() == listOf(1, 2, 3, 4, 5) -> "工作日"
        p.frequency == "WEEKDAYS" && p.weekdays.isNotEmpty() -> "每周" + p.weekdays.sorted().joinToString("、") { DAY_NAMES.getOrNull(it - 1) ?: "$it" }
        p.frequency == "WEEKDAYS" -> "每周（未选日期）"
        p.frequency == "MONTHLY" -> "每月 ${LocalDate.parse(p.startDate).dayOfMonth} 日（短月取月末）"
        p.frequency == "YEARLY" -> "每年 ${LocalDate.parse(p.startDate).monthValue} 月 ${LocalDate.parse(p.startDate).dayOfMonth} 日"
        p.frequency == "WEEKLY_COUNT" -> "每周 ${p.weeklyCount} 次"
        else -> "自定义：${p.frequency}"
    }
    val m = p.minuteOfDay ?: return base
    return "$base ${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
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
                    label = { Text(m.label.orEmpty()) }, modifier = Modifier.heightIn(min = DesignTokens.touchTarget))
            }
        }
        if (current != null && (mode == Mode.WORKDAYS || mode == Mode.WEEKDAYS)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)) {
                (1..7).forEach { day ->
                    FilterChip(selected = day in current.weekdays, label = { Text(DAY_NAMES[day - 1]) },
                        onClick = {
                            val days = if (day in current.weekdays) current.weekdays - day else current.weekdays + day
                            if (days.isNotEmpty()) onChange(current.copy(weekdays = days.sorted()))
                        },
                        modifier = Modifier.widthIn(min = DesignTokens.touchTarget).heightIn(min = DesignTokens.touchTarget)
                            .semantics { contentDescription = "周${DAY_NAMES[day - 1]}" })
                }
            }
        }
        if (mode == Mode.WEEKLY_COUNT && current != null) {
            val count = current.weeklyCount.coerceIn(1, 21)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing12)) {
                OutlinedButton(onClick = { onChange(current.copy(weeklyCount = (count - 1).coerceIn(1, 21))) }, enabled = count > 1,
                    modifier = Modifier.size(DesignTokens.touchTarget).semantics { contentDescription = "减少每周次数" },
                    contentPadding = PaddingValues(0.dp)) { Text("−") }
                Text("每周 $count 次", style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = { onChange(current.copy(weeklyCount = (count + 1).coerceIn(1, 21))) }, enabled = count < 21,
                    modifier = Modifier.size(DesignTokens.touchTarget).semantics { contentDescription = "增加每周次数" },
                    contentPadding = PaddingValues(0.dp)) { Text("+") }
            }
        }
        Text(summary(current), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (current != null) {
            TextButton(onClick = { customOpen = !customOpen }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (customOpen) "收起自定义规则" else "自定义：日期、时段与提醒")
            }
            if (customOpen) {
                DateField("开始日期", LocalDate.parse(current.startDate), {
                    onChange(current.copy(startDate = it.toString()))
                })
                if (mode != Mode.WEEKLY_COUNT) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(current.minuteOfDay != null, { onChange(current.copy(minuteOfDay = if (it) 9 * 60 else null)) })
                        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurrence_field_002), Modifier.weight(1f))
                    }
                    current.minuteOfDay?.let { minute ->
                        TimeField("发生时间", LocalTime.of(minute / 60, minute % 60), {
                            onChange(current.copy(minuteOfDay = it.hour * 60 + it.minute))
                        })
                    }
                }
                DurationField(current.durationMinutes, { onChange(current.copy(durationMinutes = it)) })
                InlineError(if (current.durationMinutes !in 1..1440) "每次时长须为 1–1440 分钟" else null)
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurrence_field_003), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(current.untilDate != null, {
                        onChange(current.copy(untilDate = if (it) current.startDate else null))
                    })
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurrence_field_004), Modifier.weight(1f))
                }
                current.untilDate?.let { until ->
                    DateField("结束日期", LocalDate.parse(until), { onChange(current.copy(untilDate = it.toString())) },
                        error = if (until < current.startDate) "重复结束日期早于开始" else null)
                }
                DateField("规则生效日期", LocalDate.parse(current.effectiveFrom), {
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
                Text("时区：${current.zoneId}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
