package com.timeagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timeagent.fish.FishSession
import com.timeagent.fish.fishPlainText

@Composable
fun FishButlerSummary(session: FishSession, summary: String, onOpen: () -> Unit) {
    val profile by session.character.profile.collectAsStateWithLifecycle()
    val chat by session.chat.collectAsStateWithLifecycle()
    val latest = chat.turns.lastOrNull { it.role != "user" }?.text
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("butler-summary").clickable(onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${profile.name} · 今日简报", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(summary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                latest?.let { Text(fishPlainText(it), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "打开管家对话")
        }
    }
}
