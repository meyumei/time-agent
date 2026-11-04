package com.timeagent.ui.timeline

import android.content.Context

internal class TimelinePreference(context: Context, private val page: String) {
    private val prefs = context.getSharedPreferences("timeline-view", Context.MODE_PRIVATE)

    fun dayCount(default: Int) = prefs.getInt("$page.days", default)
    fun density() = prefs.getFloat("$page.hourHeight", 64f).toDouble()
    fun filters() = TimelineFilterState(
        prefs.getBoolean("$page.plan", true), prefs.getBoolean("$page.record", true),
        prefs.getBoolean("$page.course", true), prefs.getBoolean("$page.health", true),
    )
    fun listMode() = prefs.getBoolean("$page.list", false)
    fun snapMinutes() = prefs.getInt("drag.snapMinutes", 15).takeIf { it in listOf(5, 15, 30) } ?: 15
    fun saveSnapMinutes(value: Int) {
        require(value in listOf(5, 15, 30))
        prefs.edit().putInt("drag.snapMinutes", value).apply()
    }

    fun save(dayCount: Int, hourHeight: Double, filters: TimelineFilterState, listMode: Boolean) {
        prefs.edit().putInt("$page.days", dayCount).putFloat("$page.hourHeight", hourHeight.toFloat())
            .putBoolean("$page.plan", filters.plan).putBoolean("$page.record", filters.record)
            .putBoolean("$page.course", filters.course).putBoolean("$page.health", filters.health)
            .putBoolean("$page.list", listMode).apply()
    }
}
