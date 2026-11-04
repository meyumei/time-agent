package com.timeagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.timeagent.data.LocalSnapshot
import com.timeagent.ui.timeline.*
import java.time.LocalDate

@Composable
internal fun TimelineToolbar(
    data: LocalSnapshot, dates: List<LocalDate>, today: LocalDate, zoom: Double, listMode: Boolean,
    busy: Boolean, canUndo: Boolean, onCreate: () -> Unit, onAssistant: () -> Unit, onUndo: () -> Unit,
    onRedo: () -> Unit, canRedo: Boolean,
    onPrevious: () -> Unit, onNext: () -> Unit, onCurrent: () -> Unit, onFit: () -> Unit,
    onDensity: (Double) -> Unit, onToggleList: () -> Unit,
    themeMode: ThemeMode, onThemeMode: (ThemeMode) -> Unit, conflicts: Int, onConflicts: () -> Unit,
    dayCount: Int, onDayCount: (Int) -> Unit, filterState: TimelineFilterState, onFilterState: (TimelineFilterState) -> Unit,
    onSelectTemplate: (() -> Unit)? = null,
) {
    var more by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "向前翻页")
        }
        Text(
            compactDateRange(dates),
            Modifier.weight(1f).semantics { contentDescription = fullDateRange(dates) },
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (busy) CircularProgressIndicator(Modifier.size(16.dp))
        IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, "向后翻页")
        }
        TimelineDayCountSwitch(dayCount, onDayCount)
        TimelineFilterButton(filterState, onFilterState)
        Box {
            IconButton(onClick = { more = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.MoreVert, "更多时间轴操作")
            }
            DropdownMenu(more, { more = false }) {
                DropdownMenuItem(text = { Text("回到今天") }, onClick = { more = false; onCurrent() })
                DropdownMenuItem(text = { Text(if (listMode) "显示时间轴" else "按日期显示列表") },
                    onClick = { more = false; onToggleList() })
                DropdownMenuItem(text = { Text("撤销") }, enabled = canUndo && !busy, onClick = { more = false; onUndo() })
                DropdownMenuItem(text = { Text("重做") }, enabled = canRedo && !busy, onClick = { more = false; onRedo() })
                DropdownMenuItem(text = { Text("查看冲突（$conflicts）") }, onClick = { more = false; onConflicts() })
                DropdownMenuItem(text = { Text("添加日程") }, enabled = !busy, onClick = { more = false; onCreate() })
                onSelectTemplate?.let { action ->
                    DropdownMenuItem(text = { Text("多选日程存为模板") }, enabled = !busy, onClick = { more = false; action() })
                }
                DropdownMenuItem(text = { Text("打开语音输入") }, onClick = { more = false; onAssistant() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("放大 · 每小时 ${(64 * zoom * 1.25).coerceAtMost(160.0).toInt()}dp") },
                    onClick = { more = false; onDensity(1.25) }, enabled = zoom < 2.5)
                DropdownMenuItem(text = { Text("缩小 · 每小时 ${(64 * zoom / 1.25).coerceAtLeast(32.0).toInt()}dp") },
                    onClick = { more = false; onDensity(.8) }, enabled = zoom > .5)
                DropdownMenuItem(text = { Text("全天概览") }, onClick = { more = false; onFit() })
                ThemeSwitch(themeMode, onThemeMode)
            }
        }
    }
}

private fun compactDateRange(dates: List<LocalDate>): String {
    val first = dates.first()
    val last = dates.last()
    if (first == last) return "${first.monthValue}月${first.dayOfMonth}日"
    return when {
        first.year != last.year ->
            "${first.year}年${first.monthValue}月${first.dayOfMonth}日–${last.year}年${last.monthValue}月${last.dayOfMonth}日"
        first.monthValue != last.monthValue ->
            "${first.monthValue}月${first.dayOfMonth}日–${last.monthValue}月${last.dayOfMonth}日"
        else -> "${first.monthValue}月${first.dayOfMonth}–${last.dayOfMonth}日"
    }
}

private fun fullDateRange(dates: List<LocalDate>): String {
    val first = dates.first()
    val last = dates.last()
    return if (first == last) {
        "${first.year}年${first.monthValue}月${first.dayOfMonth}日"
    } else {
        "${first.year}年${first.monthValue}月${first.dayOfMonth}日 至 ${last.year}年${last.monthValue}月${last.dayOfMonth}日"
    }
}
