package com.timeagent.ui

import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.timeagent.ui.design.*

val Indigo = Color(0xFF425F76)
val CourseBlue = Color(0xFF3575B5)
val RecordGreen = Color(0xFF1E8268)

/** Warm accent follows red-work.png; darker tones preserve text contrast. */
fun themeColors(mode: PresentationMode, dark: Boolean): ColorScheme {
    val companion = mode == PresentationMode.COMPANION
    return if (dark) darkColorScheme(
        primary = if (companion) Color(0xFFFFB4A8) else Color(0xFFA9CBE4),
        onPrimary = if (companion) Color(0xFF60150F) else Color(0xFF103449),
        primaryContainer = if (companion) Color(0xFF7D2A21) else Color(0xFF294B61),
        onPrimaryContainer = if (companion) Color(0xFFFFDAD3) else Color(0xFFCBE6FB),
        secondary = Color(0xFFB8CCC3), onSecondary = Color(0xFF24372F),
        secondaryContainer = Color(0xFF3A4E45), onSecondaryContainer = Color(0xFFD4E8DE),
        tertiary = Color(0xFFEAC18D), onTertiary = Color(0xFF432C0B),
        tertiaryContainer = Color(0xFF5C421E), onTertiaryContainer = Color(0xFFFFDDB1),
        background = if (companion) Color(0xFF1B1311) else Color(0xFF11171C), onBackground = Color(0xFFECE1DC),
        surface = if (companion) Color(0xFF1B1311) else Color(0xFF11171C), onSurface = Color(0xFFECE1DC),
        surfaceVariant = Color(0xFF464747), onSurfaceVariant = Color(0xFFCAC6C1),
        outline = Color(0xFF96928D), outlineVariant = Color(0xFF494541),
        error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
        inverseSurface = Color(0xFFECE1DC), inverseOnSurface = Color(0xFF352F2C),
        inversePrimary = if (companion) Color(0xFFA7362A) else Color(0xFF425F76),
        surfaceTint = if (companion) Color(0xFFFFB4A8) else Color(0xFFA9CBE4),
        surfaceBright = Color(0xFF3B3532), surfaceDim = Color(0xFF171310),
        surfaceContainerLowest = Color(0xFF100E0D), surfaceContainerLow = Color(0xFF201C19),
        surfaceContainer = Color(0xFF26221F), surfaceContainerHigh = Color(0xFF302C29), surfaceContainerHighest = Color(0xFF3B3734),
    ) else lightColorScheme(
        primary = if (companion) Color(0xFFA7362A) else Color(0xFF425F76), onPrimary = Color.White,
        primaryContainer = if (companion) Color(0xFFFFDAD3) else Color(0xFFCBE6FB),
        onPrimaryContainer = if (companion) Color(0xFF410500) else Color(0xFF001E30),
        secondary = Color(0xFF456355), onSecondary = Color.White,
        secondaryContainer = Color(0xFFC7EBD6), onSecondaryContainer = Color(0xFF062014),
        tertiary = Color(0xFF785626), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFDDB1), onTertiaryContainer = Color(0xFF291800),
        background = if (companion) Color(0xFFFFF8F4) else Color(0xFFF5F8FA), onBackground = Color(0xFF201B18),
        surface = if (companion) Color(0xFFFFF8F4) else Color(0xFFF5F8FA), onSurface = Color(0xFF201B18),
        surfaceVariant = Color(0xFFE8E3DC), onSurfaceVariant = Color(0xFF514A44),
        outline = Color(0xFF7E756E), outlineVariant = Color(0xFFD1C7BF),
        error = Color(0xFFBA1A1A), onError = Color.White,
        errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
        inverseSurface = Color(0xFF352F2C), inverseOnSurface = Color(0xFFF9EEE7),
        inversePrimary = if (companion) Color(0xFFFFB4A8) else Color(0xFFA9CBE4),
        surfaceTint = if (companion) Color(0xFFA7362A) else Color(0xFF425F76),
        surfaceBright = Color(0xFFFFFAF6), surfaceDim = Color(0xFFE3D9D1),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFCF2EA),
        surfaceContainer = Color(0xFFF6ECE5), surfaceContainerHigh = Color(0xFFF0E6DF), surfaceContainerHighest = Color(0xFFEAE0D9),
    )
}

@Composable
private fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun disabled() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduced by remember(resolver) { mutableStateOf(disabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = disabled() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

@Composable
fun TimeAgentTheme(darkTheme: Boolean? = null, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preference = remember(context) { ThemePreference(context) }
    var appearance by remember(preference) { mutableStateOf(preference.readAll()) }
    DisposableEffect(preference) {
        val cancel = preference.observe { appearance = preference.readAll() }
        appearance = preference.readAll()
        onDispose(cancel)
    }
    val dark = darkTheme ?: when (appearance.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (appearance.dynamicColor && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else themeColors(appearance.presentation, dark)
    val presentation = PresentationStyle(appearance.presentation, rememberReducedMotion())
    MatchThemeSystemBars(dark)
    CompositionLocalProvider(LocalAppearance provides appearance, LocalPresentation provides presentation,
        LocalSemanticColors provides semanticColors(dark)) {
        MaterialTheme(colorScheme = colors, typography = DesignTokens.typography, shapes = DesignTokens.shapes, content = content)
    }
}
