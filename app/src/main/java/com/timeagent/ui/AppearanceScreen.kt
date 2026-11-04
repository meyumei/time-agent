package com.timeagent.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.timeagent.ui.design.PresentationMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val preference = remember(context) { ThemePreference(context) }
    val appearance = LocalAppearance.current
    Scaffold(topBar = {
        com.timeagent.ui.components.AppTopBar("外观", onClose)
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_appearance_screen_001), style = MaterialTheme.typography.titleMedium)
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_appearance_screen_002), style = MaterialTheme.typography.bodyMedium)
            PresentationMode.entries.forEach { mode ->
                AppearanceOption(mode.label, appearance.presentation == mode,
                    if (mode == PresentationMode.TOOL) "蓝灰主题，角色静态呈现" else "暖色主题，角色动画与陪伴",
                    "appearance-${mode.name}") { preference.savePresentation(mode) }
            }
            HorizontalDivider()
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_appearance_screen_003), style = MaterialTheme.typography.titleMedium)
            ThemeMode.entries.forEach { mode ->
                AppearanceOption(mode.label, appearance.theme == mode, tag = "theme-${mode.name}") {
                    preference.save(mode)
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_appearance_screen_004))
                    Text(if (Build.VERSION.SDK_INT >= 31) "采用系统壁纸配色，状态语义色保持稳定" else "需要 Android 12 或更新版本",
                        style = MaterialTheme.typography.bodyMedium)
                }
                Switch(appearance.dynamicColor, preference::saveDynamicColor,
                    enabled = Build.VERSION.SDK_INT >= 31, modifier = Modifier.testTag("appearance-dynamic"))
            }
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_appearance_screen_005),
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AppearanceOption(title: String, selected: Boolean, description: String? = null, tag: String,
    onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = if (description == null) 56.dp else 72.dp)
        .clickable(role = Role.RadioButton, onClick = onClick).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title)
            description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
