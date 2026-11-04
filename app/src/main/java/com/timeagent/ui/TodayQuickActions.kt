package com.timeagent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun TodayQuickActions(
    onRecord: () -> Unit,
    onRecurring: () -> Unit,
    onDeadline: () -> Unit,
    onWorkout: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp).testTag("today-quick-actions")) {
            Icon(Icons.Rounded.Add, "今天快捷操作")
        }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("补记时间") }, onClick = { expanded = false; onRecord() },
                modifier = Modifier.testTag("today-record"))
            DropdownMenuItem(text = { Text("本次习惯") }, onClick = { expanded = false; onRecurring() },
                modifier = Modifier.testTag("today-recurring"))
            DropdownMenuItem(text = { Text("处理期限") }, onClick = { expanded = false; onDeadline() },
                modifier = Modifier.testTag("today-deadlines"))
            DropdownMenuItem(text = { Text("训练") }, onClick = { expanded = false; onWorkout() },
                modifier = Modifier.testTag("today-workout"))
        }
    }
}
