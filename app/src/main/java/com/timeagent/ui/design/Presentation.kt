package com.timeagent.ui.design

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class PresentationMode(val label: String) {
    TOOL("工具模式"),
    COMPANION("陪伴模式")
}

data class PresentationStyle(
    val mode: PresentationMode = PresentationMode.COMPANION,
    val reducedMotion: Boolean = false
) {
    val companion: Boolean
        get() = mode == PresentationMode.COMPANION

    val avatarSize: Dp
        get() = if (companion) 40.dp else 24.dp

    val animateCharacter: Boolean
        get() = companion && !reducedMotion

    val motionMillis: Int
        get() = if (reducedMotion) 0 else 200
}

val LocalPresentation = staticCompositionLocalOf { PresentationStyle() }
