package com.timeagent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Legacy accents kept for existing call sites; the theme drives the real tokens. */
val Indigo = Color(0xFF524FD5)
val CourseBlue = Color(0xFF3575B5)
val RecordGreen = Color(0xFF1E8268)

/**
 * Daylight: warm paper instead of pure white, ink navy text, and refined
 * restrained indigo accents with high-readability small labels.
 */
private val DaylightColors = lightColorScheme(
    primary = Color(0xFF534ED4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDEAFB),
    onPrimaryContainer = Color(0xFF201B68),
    inversePrimary = Color(0xFFC5C1FF),
    secondary = Color(0xFF1B7A63),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCF3E8),
    onSecondaryContainer = Color(0xFF083C2F),
    tertiary = Color(0xFF2E6B9E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDEBFA),
    onTertiaryContainer = Color(0xFF0B304C),
    background = Color(0xFFF8F7F4),
    onBackground = Color(0xFF141923),
    surface = Color(0xFFF8F7F4),
    onSurface = Color(0xFF141923),
    surfaceVariant = Color(0xFFE5E4DE),
    onSurfaceVariant = Color(0xFF595D6B),
    surfaceTint = Color(0xFF534ED4),
    inverseSurface = Color(0xFF2D3038),
    inverseOnSurface = Color(0xFFF1F1F4),
    error = Color(0xFFBA2D2A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE8E7),
    onErrorContainer = Color(0xFF4A100F),
    outline = Color(0xFF868A97),
    outlineVariant = Color(0xFFD2D4DC),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFCFBF9),
    surfaceDim = Color(0xFFDEDCD5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F3EF),
    surfaceContainer = Color(0xFFEEEEEA),
    surfaceContainerHigh = Color(0xFFE8E6E0),
    surfaceContainerHighest = Color(0xFFE2DFD8),
)

/**
 * Midnight: deep ink blue-black canvas with luminous pastel lavender,
 * mint and sky accents across multi-tiered surfaces.
 */
private val MidnightColors = darkColorScheme(
    primary = Color(0xFFC2BFFF),
    onPrimary = Color(0xFF25206B),
    primaryContainer = Color(0xFF3B3682),
    onPrimaryContainer = Color(0xFFE5E2FF),
    inversePrimary = Color(0xFF5550D6),
    secondary = Color(0xFF82DFC1),
    onSecondary = Color(0xFF03392D),
    secondaryContainer = Color(0xFF17473A),
    onSecondaryContainer = Color(0xFFBFF3DF),
    tertiary = Color(0xFF9BC9F3),
    onTertiary = Color(0xFF0A334F),
    tertiaryContainer = Color(0xFF1F4462),
    onTertiaryContainer = Color(0xFFD2E8FB),
    background = Color(0xFF10121A),
    onBackground = Color(0xFFEAEBF2),
    surface = Color(0xFF10121A),
    onSurface = Color(0xFFEAEBF2),
    surfaceVariant = Color(0xFF282B3A),
    onSurfaceVariant = Color(0xFFAEB2C4),
    surfaceTint = Color(0xFFC2BFFF),
    inverseSurface = Color(0xFFEAEBF2),
    inverseOnSurface = Color(0xFF2B2E3A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF5B1A17),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF838799),
    outlineVariant = Color(0xFF383C4D),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF353949),
    surfaceDim = Color(0xFF10121A),
    surfaceContainerLowest = Color(0xFF0A0C12),
    surfaceContainerLow = Color(0xFF161822),
    surfaceContainer = Color(0xFF1C1F2B),
    surfaceContainerHigh = Color(0xFF232735),
    surfaceContainerHighest = Color(0xFF2B3040),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.15).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = (-0.05).sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.5.sp,
        letterSpacing = 0.1.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 17.5.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.15.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.25.sp,
    ),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun TimeAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) MidnightColors else DaylightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
