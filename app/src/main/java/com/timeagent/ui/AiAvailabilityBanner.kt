package com.timeagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timeagent.assistant.AssistantSettings

data class AiAvailability(val available: Boolean, val reason: String = "")

/**
 * Read-only availability check shared by the extraction and reply screens. Local parsing never
 * depends on this value; it only explains why fish/model features are unavailable.
 */
@Composable
fun rememberAiAvailability(model: TimeViewModel): AiAvailability {
    val context = LocalContext.current
    val settings = remember(context) { AssistantSettings(context.applicationContext) }
    val fish = model.fish
    val runtimeState = if (fish != null) fish.runtime.state.collectAsStateWithLifecycle().value else null
    val fishReady = runtimeState?.ready == true
    val hasKey = settings.hasApiKey()
    val cloudEnabled = settings.cloudEnabled()
    return when {
        fishReady -> AiAvailability(true)
        hasKey && cloudEnabled -> AiAvailability(true)
        hasKey -> AiAvailability(false, "已保存自带 API Key，但“智能理解”尚未开启。")
        else -> AiAvailability(false, "未配置自带 API Key（BYOK），内置角色也没有在运行。")
    }
}

@Composable
fun AiAvailabilityBanner(
    availability: AiAvailability,
    onOpenSettings: (() -> Unit)? = null,
    detail: String? = null,
    modifier: Modifier = Modifier,
) {
    if (!availability.available) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = modifier.fillMaxWidth().testTag("ai-unavailable"),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("AI 暂不可用", fontWeight = FontWeight.SemiBold)
                Text(availability.reason, style = MaterialTheme.typography.bodySmall)
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (onOpenSettings != null) {
                    TextButton(onClick = onOpenSettings, modifier = Modifier.testTag("ai-open-settings")) { Text("去设置") }
                }
            }
        }
    }
}
