package com.timeagent.ui

import android.content.Context

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

class ThemePreference(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    fun read(): ThemeMode = runCatching { ThemeMode.valueOf(preferences.getString("theme", ThemeMode.SYSTEM.name)!!) }.getOrDefault(ThemeMode.SYSTEM)
    fun save(mode: ThemeMode) { preferences.edit().putString("theme", mode.name).apply() }
}
