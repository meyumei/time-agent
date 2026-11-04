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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timeagent.fish.FishSession
import com.timeagent.fish.CharacterSprite

@Composable
fun FishButlerSummary(session: FishSession, summary: String, onOpen: () -> Unit) {
    val profile by session.character.profile.collectAsStateWithLifecycle()
    val title = "${profile.name} · 今日简报"
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag("butler-summary")
            .semantics { contentDescription = "$title：$summary" }
            .clickable(onClick = onOpen)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CharacterSprite(session, Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                summary,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                "打开管家对话",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
