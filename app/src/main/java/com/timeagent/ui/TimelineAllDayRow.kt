package com.timeagent.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    Box(modifier.fillMaxSize().horizontalScroll(rememberScrollState()).height(height)) {
        dates.forEach { date ->
            val dayItems = items.filter { item ->
                item.span.overlaps(
                    TimeSpan(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant()),
                )
            }.take(2)
            Column(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset((dayWidth.toPx() * (date.toEpochDay() - viewport.value.firstDay)).toInt(), 0) }
                    .width(dayWidth)
                    .padding(horizontal = 2.dp, vertical = 2.dp),
            ) {
                dayItems.forEach { item ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        onClick = { onSelect(item) },
                        modifier = Modifier
                            .height(32.dp)
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small),
                    ) {
                        Text(item.title, Modifier.padding(horizontal = 6.dp, vertical = 6.dp), maxLines = 1)
                    }
                }
            }
        }
    }
}
