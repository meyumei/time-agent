package com.timeagent.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun MatchThemeSystemBars(dark: Boolean) {
    val view = LocalView.current
    SideEffect {
        var current = view.context
        while (current is ContextWrapper && current !is Activity) current = current.baseContext
        (current as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
}

@Composable
fun ThemeSwitch(mode: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }, modifier = Modifier.size(36.dp).semantics { stateDescription = mode.label }) {
        Icon(when (mode) { ThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto; ThemeMode.LIGHT -> Icons.Rounded.LightMode; ThemeMode.DARK -> Icons.Rounded.DarkMode }, "切换深浅色", Modifier.size(20.dp))
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            ThemeMode.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, trailingIcon = { if (option == mode) Icon(Icons.Rounded.Check, null) },
                    onClick = { onSelect(option); expanded = false })
            }
        }
    }
}
