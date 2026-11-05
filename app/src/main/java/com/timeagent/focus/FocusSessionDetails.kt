package com.timeagent.focus

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.timeagent.R
import com.timeagent.ui.components.LoadingSkeleton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun FocusSessionDetails(
    session: FocusSessionEntity?,
    loading: Boolean,
    zone: ZoneId,
    onClose: () -> Unit,
    onOpenActive: () -> Unit,
    onOpenActual: (String) -> Unit,
) {
    BackHandler(onBack = onClose)
    val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(zone)
    fun time(value: Long): String = format.format(Instant.ofEpochMilli(value))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
        Text(stringResource(R.string.focus_source_title), style = MaterialTheme.typography.headlineSmall)
        if (loading) {
            LoadingSkeleton(true)
        } else if (session == null) {
            Text(stringResource(R.string.focus_source_missing))
        } else {
            Text(session.label, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.focus_source_start, time(session.startedAt)))
            session.endedAt?.let { Text(stringResource(R.string.focus_source_end, time(it))) }
            Text(stringResource(R.string.focus_source_duration, session.focusedElapsedMs / 60_000L))
            Text(stringResource(when (session.state) {
                FocusSessionEntity.STATE_FOCUSING -> R.string.focus_source_running
                FocusSessionEntity.STATE_PAUSED -> R.string.focus_source_paused
                FocusSessionEntity.STATE_BREAK -> R.string.focus_source_break
                FocusSessionEntity.STATE_COMPLETED -> R.string.focus_source_completed
                FocusSessionEntity.STATE_ABANDONED -> R.string.focus_source_abandoned
                else -> R.string.focus_source_unknown
            }))
            if (session.isActive) Button(onClick = onOpenActive) {
                Text(stringResource(R.string.focus_source_open_running))
            }
            session.actualRecordId?.let { id ->
                Button(onClick = { onOpenActual(id) }) { Text(stringResource(R.string.focus_source_open_actual)) }
            }
            FocusSegments.decode(session.segmentsJson).forEach { segment ->
                Text(stringResource(R.string.focus_source_segment, segment.round,
                    time(segment.startAt), time(segment.endAt), segment.elapsedMs / 60_000L))
            }
        }
    }
}
