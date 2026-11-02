package com.timeagent.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

data class SemanticColors(
    val plan: Color,
    val onPlan: Color,
    val actual: Color,
    val onActual: Color,
    val conflict: Color,
    val onConflict: Color,
    val running: Color,
    val onRunning: Color,
    val completed: Color,
    val onCompleted: Color
)

object DesignTokens {
    val typography: Typography = Typography(
        displaySmall = TextStyle(
            fontSize = 28.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.Normal
        ),
        titleLarge = TextStyle(
            fontSize = 20.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.Normal
        ),
        titleMedium = TextStyle(
            fontSize = 18.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Medium
        ),
        bodyLarge = TextStyle(
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Normal
        ),
        bodyMedium = TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Normal
        ),
        bodySmall = TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Normal
        ),
        labelLarge = TextStyle(
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Medium
        ),
        labelMedium = TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium
        ),
        labelSmall = TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium
        )
    )

    val shapes: Shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(20.dp)
    )

    val spacing4 = 4.dp
    val spacing8 = 8.dp
    val spacing12 = 12.dp
    val spacing16 = 16.dp
    val spacing24 = 24.dp
    val spacing32 = 32.dp

    val touchTarget = 48.dp
    val singleRow = 56.dp
    val doubleRow = 72.dp
}

val LocalSemanticColors = staticCompositionLocalOf { semanticColors(false) }

private val lightSemanticColors = SemanticColors(
    plan = Color(0xFF1A5FB4),
    onPlan = Color.White,
    actual = Color(0xFF006A60),
    onActual = Color.White,
    conflict = Color(0xFF8A4B00),
    onConflict = Color.White,
    running = Color(0xFF0B57D0),
    onRunning = Color.White,
    completed = Color(0xFF2E6B2E),
    onCompleted = Color.White
)

private val darkSemanticColors = SemanticColors(
    plan = Color(0xFFA8C7FA),
    onPlan = Color(0xFF001B3D),
    actual = Color(0xFF7FDBCA),
    onActual = Color(0xFF003731),
    conflict = Color(0xFFFFB77C),
    onConflict = Color(0xFF2D1600),
    running = Color(0xFF7FD7F5),
    onRunning = Color(0xFF003544),
    completed = Color(0xFFA6D89A),
    onCompleted = Color(0xFF0C2E08)
)

fun semanticColors(dark: Boolean): SemanticColors =
    if (dark) darkSemanticColors else lightSemanticColors

fun readableContentColor(background: Color): Color =
    if (contrastRatio(Color.Black, background) >= contrastRatio(Color.White, background)) {
        Color.Black
    } else {
        Color.White
    }

fun contrastRatio(foreground: Color, background: Color): Float {
    val foregroundLuminance = foreground.luminance()
    val backgroundLuminance = background.luminance()
    val lighter = max(foregroundLuminance, backgroundLuminance)
    val darker = min(foregroundLuminance, backgroundLuminance)
    return (lighter + 0.05f) / (darker + 0.05f)
}
