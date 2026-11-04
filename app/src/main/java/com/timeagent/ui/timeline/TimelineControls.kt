package com.timeagent.ui.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val TimelineDayCountOptions = listOf(1, 3, 7)
private val MinControlHeight = 48.dp

private fun sourceLabel(filter: TimelineSourceFilter): String = when (filter) {
    TimelineSourceFilter.PLAN -> "计划"
    TimelineSourceFilter.RECORD -> "记录"
    TimelineSourceFilter.COURSE -> "课程"
    TimelineSourceFilter.HEALTH -> "健康"
}

@Composable
fun TimelineDayCountSwitch(
    selected: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier
                .heightIn(min = MinControlHeight)
                .testTag("timeline-days")
                .semantics { contentDescription = "时间轴天数，当前${selected}日" },
        ) {
            Text("${selected}日", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TimelineDayCountOptions.forEach { days ->
                DropdownMenuItem(
                    text = { Text("${days}日") },
                    onClick = {
                        expanded = false
                        if (selected != days) onSelected(days)
                    },
                    modifier = Modifier
                        .heightIn(min = MinControlHeight)
                        .testTag("timeline-days-$days")
                        .semantics { contentDescription = "${days}日视图" },
                )
            }
        }
    }
}

@Composable
fun TimelineFilterButton(
    state: TimelineFilterState,
    onChange: (TimelineFilterState) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier
                .size(MinControlHeight)
                .testTag("timeline-filter-button"),
        ) {
            Icon(
                Icons.Rounded.FilterList,
                contentDescription = "来源过滤",
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TimelineSourceFilter.values().forEach { filter ->
                val enabled = state.isEnabled(filter)
                DropdownMenuItem(
                    text = { Text(sourceLabel(filter)) },
                    onClick = { onChange(state.withFilter(filter, !enabled)) },
                    modifier = Modifier
                        .heightIn(min = MinControlHeight)
                        .testTag("timeline-filter-${filter.name.lowercase()}"),
                    leadingIcon = { Checkbox(checked = enabled, onCheckedChange = null) },
                )
            }
        }
    }
}
