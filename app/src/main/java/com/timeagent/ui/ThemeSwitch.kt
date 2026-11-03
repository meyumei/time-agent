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
import androidx.compose.ui.platform.LocalContext
import com.timeagent.ui.design.PresentationMode
import android.os.Build

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
    val context = LocalContext.current
    val preference = remember(context) { ThemePreference(context) }
    val appearance = LocalAppearance.current
    IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp).semantics { stateDescription = "${appearance.presentation.label}，${mode.label}" }) {
        Icon(when (mode) { ThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto; ThemeMode.LIGHT -> Icons.Rounded.LightMode; ThemeMode.DARK -> Icons.Rounded.DarkMode }, "外观设置", Modifier.size(20.dp))
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            PresentationMode.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) },
                    trailingIcon = { if (option == appearance.presentation) Icon(Icons.Rounded.Check, null) },
                    onClick = { preference.savePresentation(option); expanded = false })
            }
            HorizontalDivider()
            ThemeMode.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, trailingIcon = { if (option == mode) Icon(Icons.Rounded.Check, null) },
                    onClick = { onSelect(option); expanded = false })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text(if (Build.VERSION.SDK_INT >= 31) "动态取色" else "动态取色需 Android 12") },
                enabled = Build.VERSION.SDK_INT >= 31,
                trailingIcon = { if (appearance.dynamicColor) Icon(Icons.Rounded.Check, null) },
                onClick = { preference.saveDynamicColor(!appearance.dynamicColor); expanded = false })
        }
    }
}
