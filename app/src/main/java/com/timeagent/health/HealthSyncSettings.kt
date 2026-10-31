package com.timeagent.health

import android.content.Context

class HealthSyncSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("health_sync", Context.MODE_PRIVATE)
    fun enabled(): Boolean = prefs.getBoolean("enabled", false)
    fun setEnabled(value: Boolean) = prefs.edit().putBoolean("enabled", value).apply()
    fun sleepEnabled(): Boolean = prefs.getBoolean("sleep", true)
    fun setSleepEnabled(value: Boolean) = prefs.edit().putBoolean("sleep", value).apply()
    fun exerciseEnabled(): Boolean = prefs.getBoolean("exercise", true)
    fun setExerciseEnabled(value: Boolean) = prefs.edit().putBoolean("exercise", value).apply()
    fun windowDays(): Int = prefs.getInt("windowDays", 14)
    fun setWindowDays(value: Int) = prefs.edit().putInt("windowDays", value).apply()

    /**
     * Preferred sleep source label (e.g. "华为运动健康"); empty means first-arrival wins.
     * When set, a sleep sample from this origin replaces overlapping stored sleep from any
     * other origin (manual edits still always win).
     */
    fun preferredSleepOrigin(): String = prefs.getString("preferredSleepOrigin", "") ?: ""
    fun setPreferredSleepOrigin(value: String) = prefs.edit().putString("preferredSleepOrigin", value).apply()

    /** Persisted SAF document URI of the Gadgetbridge database export; empty means the channel is off. */
    fun gbExportUri(): String = prefs.getString("gbExportUri", "") ?: ""
    fun setGbExportUri(value: String) = prefs.edit().putString("gbExportUri", value).apply()
    fun lastSyncAt(): Long = prefs.getLong("lastSyncAt", 0L)
    fun lastResult(): String = prefs.getString("lastResult", "").orEmpty()
    fun setLastResult(value: String) = prefs.edit().putString("lastResult", value).apply()
    fun markSynced() = prefs.edit().putLong("lastSyncAt", System.currentTimeMillis()).apply()
    fun readReport(): HealthReadReport = runCatching {
        com.google.gson.Gson().fromJson(prefs.getString("readReport", ""), HealthReadReport::class.java)
    }.getOrNull() ?: HealthReadReport()
    fun saveReadReport(report: HealthReadReport) = prefs.edit().putString("readReport", com.google.gson.Gson().toJson(report)).apply()
}
