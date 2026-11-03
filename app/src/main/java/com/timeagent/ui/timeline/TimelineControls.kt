package com.timeagent.ui.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineDayCountSwitch(
    selected: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.testTag("timeline-days")) {
        TimelineDayCountOptions.forEachIndexed { index, days ->
            SegmentedButton(
                selected = selected == days,
                onClick = { if (selected != days) onSelected(days) },
                shape = SegmentedButtonDefaults.itemShape(index, TimelineDayCountOptions.size),
                modifier = Modifier
                    .heightIn(min = MinControlHeight)
                    .testTag("timeline-days-$days")
                    .semantics { contentDescription = "${days}日视图" },
                label = {
                    Text("${days}日", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
            )
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
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .heightIn(min = MinControlHeight)
                .testTag("timeline-filter-button"),
        ) {
            Text("过滤", maxLines = 1, overflow = TextOverflow.Ellipsis)
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
