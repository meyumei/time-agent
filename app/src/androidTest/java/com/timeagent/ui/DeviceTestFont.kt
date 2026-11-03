package com.timeagent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry

@Composable
internal fun DeviceTestFont(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val scale = InstrumentationRegistry.getArguments().getString("fontScale")?.toFloat() ?: density.fontScale
    CompositionLocalProvider(LocalDensity provides Density(density.density, scale), content = content)
}
