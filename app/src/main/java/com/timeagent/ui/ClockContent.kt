package com.timeagent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timeagent.core.ClockSource
import com.timeagent.data.TimeRecordEntity
import java.time.Duration
import java.time.Instant

@Composable
internal fun MinuteClockContent(model: TimeViewModel, content: @Composable (Instant) -> Unit) {
    val now by model.now.collectAsStateWithLifecycle()
    content(now)
}

@Composable
internal fun RunningTimerBar(
    record: TimeRecordEntity,
    title: String,
    clock: ClockSource,
    busy: Boolean,
    onStop: () -> Unit,
) {
    val now by clock.secondTicks.collectAsStateWithLifecycle(initialValue = clock.now())
    val seconds = Duration.between(Instant.ofEpochMilli(record.startAt), now).seconds.coerceAtLeast(0)
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text("计时中 · ${"%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onStop, enabled = !busy) { Text("结束计时") }
        }
    }
}
