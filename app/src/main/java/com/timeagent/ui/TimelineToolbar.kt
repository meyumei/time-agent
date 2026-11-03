package com.timeagent.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
) {
    var more by remember { mutableStateOf(false) }
    var density by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "向前翻页")
            }
            Text(
                "${dates.first()} — ${dates.last()}",
                Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (busy) CircularProgressIndicator(Modifier.size(20.dp))
            IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, "向后翻页")
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TimelineDayCountSwitch(dayCount, onDayCount)
            if ((dayCount == 7 && today !in dates) || (dayCount != 7 && dates[dates.size / 2] != today))
                TextButton(onClick = onCurrent, modifier = Modifier.heightIn(min = 48.dp)) { Text("今天") }
            Box {
                IconButton(onClick = { density = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Height, "时间轴密度") }
                DropdownMenu(density, { density = false }) {
                    DropdownMenuItem(text = { Text("放大 · 每小时 ${(64 * zoom * 1.25).coerceAtMost(160.0).toInt()}dp") },
                        onClick = { density = false; onDensity(1.25) }, enabled = zoom < 2.5)
                    DropdownMenuItem(text = { Text("缩小 · 每小时 ${(64 * zoom / 1.25).coerceAtLeast(32.0).toInt()}dp") },
                        onClick = { density = false; onDensity(.8) }, enabled = zoom > .5)
                    DropdownMenuItem(text = { Text("全天概览") }, onClick = { density = false; onFit() })
                }
            }
            TimelineFilterButton(filterState, onFilterState)
            Box {
                IconButton(onClick = { more = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.MoreVert, "更多时间轴操作") }
                DropdownMenu(more, { more = false }) {
                    DropdownMenuItem(text = { Text(if (listMode) "显示时间轴" else "按日期显示列表") },
                        onClick = { more = false; onToggleList() })
                    DropdownMenuItem(text = { Text("撤销") }, enabled = canUndo && !busy, onClick = { more = false; onUndo() })
                    DropdownMenuItem(text = { Text("重做") }, enabled = canRedo && !busy, onClick = { more = false; onRedo() })
                    DropdownMenuItem(text = { Text("查看冲突（$conflicts）") }, onClick = { more = false; onConflicts() })
                    DropdownMenuItem(text = { Text("添加日程") }, enabled = !busy, onClick = { more = false; onCreate() })
                    DropdownMenuItem(text = { Text("打开语音输入") }, onClick = { more = false; onAssistant() })
                    ThemeSwitch(themeMode, onThemeMode)
                }
            }
        }
    }
}
