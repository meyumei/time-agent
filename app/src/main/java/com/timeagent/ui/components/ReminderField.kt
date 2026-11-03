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
            Text("提醒时间", style = MaterialTheme.typography.titleMedium)
            Text(
                "0 表示事件开始时提醒；可多选，也可添加自定义分钟数。",
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
                    Text("通知权限未开启，提醒可能无法送达。", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { onRequestPermission?.invoke() }, enabled = onRequestPermission != null) {
                        Text("开启通知权限")
                    }
                }
            }
        } else {
            Text("通知权限已开启", style = MaterialTheme.typography.bodySmall,
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
            label = { Text("自定义分钟数（正整数）") },
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
        ) { Text("添加自定义提醒") }
        Text("已选提醒（按提前时间升序）", style = MaterialTheme.typography.titleSmall)
        if (ordered.isEmpty()) {
            Text(
                "尚未选择提醒时间。",
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
                        ) { Text("移除") }
                    }
                }
            }
        }
    }
}

private fun normalizeReminderMinutes(values: List<Int>): List<Int> = values.distinct().sorted()

private fun durationText(minutes: Int): String {
    if (minutes == 0) return "开始时"
    val days = minutes / 1440
    val hours = (minutes % 1440) / 60
    val rest = minutes % 60
    return buildList {
        if (days > 0) add("$days 天")
        if (hours > 0) add("$hours 小时")
        if (rest > 0) add("$rest 分钟")
    }.joinToString(" ")
}

private fun reminderText(minutes: Int): String =
    if (minutes == 0) "事件开始时" else "提前 ${durationText(minutes)}"
