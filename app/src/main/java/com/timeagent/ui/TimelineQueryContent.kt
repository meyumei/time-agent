package com.timeagent.ui

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timeagent.core.WeekState
import com.timeagent.data.LocalSnapshot
import com.timeagent.data.TimelineRange
import java.time.LocalDate

@Composable
internal fun TimelineQueryContent(
    model: TimeViewModel,
    week: WeekState,
    legacyReady: Boolean,
    content: @Composable (LocalSnapshot, (LocalDate, LocalDate) -> Unit) -> Unit,
) {
    var range by remember(model) {
        mutableStateOf(TimelineRange.visible(week.today.minusDays(1), week.today.plusDays(1), model.zone))
    }
    val query = remember(model, range) { model.timeline(range) }
    val data = key(query) { query.collectAsStateWithLifecycle(LocalSnapshot()).value }
    ReportDrawnWhen { legacyReady && !data.loading }
    content(data) { first, last -> range = TimelineRange.visible(first, last, model.zone) }
}
