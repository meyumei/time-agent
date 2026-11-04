package com.timeagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneId
import com.timeagent.core.TimeSpan

@Composable
internal fun TimelineAllDayRow(
    items: List<TimelineItem>,
    dates: List<LocalDate>,
    viewport: State<com.timeagent.core.TimelineViewport>,
    dayWidth: Dp,
    height: Dp,
    zone: ZoneId,
    onSelect: (TimelineItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expandedDate by remember { mutableStateOf<LocalDate?>(null) }
    Box(modifier.fillMaxWidth().height(height).clipToBounds()) {
        dates.forEach { date ->
            val dayItems = items.filter { item ->
                item.span.overlaps(
                    TimeSpan(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant()),
                )
            }
            Column(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset((dayWidth.toPx() * (date.toEpochDay() - viewport.value.firstDay)).toInt(), 0) }
                    .width(dayWidth)
                    .padding(horizontal = 2.dp, vertical = 2.dp),
            ) {
                dayItems.take(1).forEach { item ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        onClick = { onSelect(item) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small),
                    ) {
                        Text(item.title, Modifier.padding(horizontal = 6.dp, vertical = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (dayItems.size > 1) TextButton(onClick = { expandedDate = date }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("+${dayItems.size - 1} 项全天")
                }
            }
        }
    }
    expandedDate?.let { date ->
        AlertDialog(
            onDismissRequest = { expandedDate = null },
            title = { Text("$date · 全天") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    items.filter { it.span.overlaps(TimeSpan(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())) }
                        .forEach { item ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                                expandedDate = null
                                onSelect(item)
                            }.padding(12.dp)) { Text(item.title) }
                        }
                }
            },
            confirmButton = { TextButton(onClick = { expandedDate = null }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_close)) } },
        )
    }
}
