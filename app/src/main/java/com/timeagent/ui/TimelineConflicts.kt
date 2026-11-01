package com.timeagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.timeagent.core.TimeSpan
import java.time.Instant
import java.time.ZoneId

data class TimelineConflict(val first: TimelineItem, val second: TimelineItem, val overlap: TimeSpan)

/** Connected scheduled overlaps within the day supplied by the caller. */
fun timelineConflictGroups(items: List<TimelineItem>): List<List<TimelineItem>> {
    val scheduled = items.filter { !it.isPoint && (it.kind == ItemKind.COURSE || it.kind == ItemKind.PLAN) }
        .distinctBy { it.kind to it.id }
        .sortedWith(compareBy<TimelineItem> { it.span.start }.thenBy { it.span.end })
    if (scheduled.size < 2) return emptyList()
    val groups = mutableListOf<List<TimelineItem>>()
    var currentGroup = mutableListOf<TimelineItem>()
    var currentMaxEnd: Instant? = null
    for (item in scheduled) {
        if (currentGroup.isEmpty()) {
            currentGroup.add(item)
            currentMaxEnd = item.span.end
        } else if (item.span.start < requireNotNull(currentMaxEnd)) {
            currentGroup.add(item)
            currentMaxEnd = maxOf(requireNotNull(currentMaxEnd), item.span.end)
        } else {
            if (currentGroup.size >= 2) groups.add(currentGroup)
            currentGroup = mutableListOf(item)
            currentMaxEnd = item.span.end
        }
    }
    if (currentGroup.size >= 2) groups.add(currentGroup)
    return groups
}

/** Actual records describe what happened; they are not scheduling conflicts. */
fun timelineConflicts(items: List<TimelineItem>): List<TimelineConflict> {
    val scheduled = items.filter { it.kind != ItemKind.RECORD && !it.isPoint }.distinctBy { "${it.kind}:${it.id}" }.sortedBy { it.span.start }
    return buildList {
        scheduled.forEachIndexed { index, first ->
            for (next in (index + 1) until scheduled.size) {
                val second = scheduled[next]
                if (second.span.start >= first.span.end) break
                first.span.clip(second.span)?.let { add(TimelineConflict(first, second, it)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineConflictsSheet(conflicts: List<TimelineConflict>, zone: ZoneId, onSelect: (TimelineItem) -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("时间冲突 · ${conflicts.size} 组", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("红色描边表示课程或计划重叠；绿色实际记录独立展示。点击项目查看完整内容。", style = MaterialTheme.typography.bodySmall)
            conflicts.forEach { conflict ->
                OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = .35f))) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.WarningAmber, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.error)
                            Text("重叠 ${conflict.overlap.start.label(zone)} — ${conflict.overlap.end.label(zone)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        }
                        TimelineAgendaCard(conflict.first, zone, true, onClick = { onClose(); onSelect(conflict.first) })
                        TimelineAgendaCard(conflict.second, zone, true, onClick = { onClose(); onSelect(conflict.second) })
                    }
                }
            }
            TextButton(onClick = onClose) { Text("关闭") }
        }
    }
}
