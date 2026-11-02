package com.timeagent.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.staticCompositionLocalOf
import com.timeagent.ui.design.PresentationMode

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色")
}

data class AppearanceSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val presentation: PresentationMode = PresentationMode.COMPANION,
    val dynamicColor: Boolean = false,
    val modeHintPending: Boolean = false
)

val LocalAppearance = staticCompositionLocalOf { AppearanceSettings() }

class ThemePreference(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        migrateIfNeeded()
    }

    fun read(): ThemeMode {
        val stored = preferences.getString(KEY_THEME, null) ?: return ThemeMode.SYSTEM
        return runCatching { ThemeMode.valueOf(stored) }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun save(mode: ThemeMode) {
        preferences.edit().putString(KEY_THEME, mode.name).apply()
    }

    fun readAll(): AppearanceSettings = AppearanceSettings(
        theme = read(),
        presentation = readPresentation(),
        dynamicColor = preferences.getBoolean(KEY_DYNAMIC_COLOR, false),
        modeHintPending = preferences.getBoolean(KEY_MODE_HINT_PENDING, false)
    )

    fun savePresentation(mode: PresentationMode) {
        preferences.edit().putString(KEY_PRESENTATION, mode.name).apply()
    }

    fun saveDynamicColor(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
    }

    fun acknowledgeModeHint() {
        preferences.edit().putBoolean(KEY_MODE_HINT_PENDING, false).apply()
    }

    fun observe(listener: () -> Unit): () -> Unit {
        val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            listener()
        }
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        return {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        }
    }

    private fun readPresentation(): PresentationMode {
        val stored = preferences.getString(KEY_PRESENTATION, null) ?: return PresentationMode.COMPANION
        return runCatching { PresentationMode.valueOf(stored) }.getOrDefault(PresentationMode.COMPANION)
    }

    private fun migrateIfNeeded() {
        if (preferences.getInt(KEY_APPEARANCE_VERSION, 0) >= APPEARANCE_VERSION) return

        val existingUser = preferences.contains(KEY_THEME) ||
            applicationContext.getDatabasePath(DATABASE_NAME).exists()

        val editor = preferences.edit()
        if (!preferences.contains(KEY_PRESENTATION)) {
            editor.putString(KEY_PRESENTATION, PresentationMode.COMPANION.name)
        }
        if (!preferences.contains(KEY_DYNAMIC_COLOR)) {
            editor.putBoolean(KEY_DYNAMIC_COLOR, false)
        }
        if (!preferences.contains(KEY_MODE_HINT_PENDING)) {
            editor.putBoolean(KEY_MODE_HINT_PENDING, existingUser)
        }
        editor.putInt(KEY_APPEARANCE_VERSION, APPEARANCE_VERSION)
        editor.apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "appearance"
        const val KEY_THEME = "theme"
        const val KEY_PRESENTATION = "presentation"
        const val KEY_DYNAMIC_COLOR = "dynamicColor"
        const val KEY_MODE_HINT_PENDING = "modeHintPending"
        const val KEY_APPEARANCE_VERSION = "appearanceVersion"
        const val APPEARANCE_VERSION = 1
        const val DATABASE_NAME = "time-agent.db"
    }
}
