package com.timeagent.fish

import android.content.Context
import com.timeagent.core.*
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class FishCompanionSettings(
    val appearance: FishAppearance = FishAppearance.CHIBI,
    val visible: Boolean = true,
    val wakeHour: Int = 8,
    val sleepHour: Int = 23,
    val positionX: Float = 1f,
    val positionY: Float = .72f,
    val globalVisible: Boolean = false,
    val globalX: Float = 1f,
    val globalY: Float = .72f,
)

/** Presentation of the application-owned fish; contains no second chat or memory store. */
class FishCompanionState(context: Context) {
    private val prefs = context.getSharedPreferences("fish_companion", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(FishCompanionSettings(
        appearance = runCatching { FishAppearance.valueOf(prefs.getString("appearance", "CHIBI")!!) }.getOrDefault(FishAppearance.CHIBI),
        visible = prefs.getBoolean("visible", true),
        wakeHour = prefs.getInt("wakeHour", 8),
        sleepHour = prefs.getInt("sleepHour", 23),
        positionX = prefs.getFloat("x", 1f),
        positionY = prefs.getFloat("y", .72f),
        globalVisible = prefs.getBoolean("globalVisible", false),
        globalX = prefs.getFloat("globalX", 1f),
        globalY = prefs.getFloat("globalY", .72f),
    ))
    val settings = _settings.asStateFlow()
    private val _presence = MutableStateFlow(FishPresence(
        mood = runCatching { FishMood.valueOf(prefs.getString("mood", "CALM")!!) }.getOrDefault(FishMood.CALM),
        changedAt = prefs.getLong("changedAt", 0),
        lastInteractionAt = prefs.getLong("lastInteractionAt", 0),
    ))
    val presence = _presence.asStateFlow()

    fun event(kind: String, text: String = "") {
        _presence.update { FishCompanion.event(it, kind, text, System.currentTimeMillis()) }
        val state = _presence.value
        prefs.edit().putString("mood", state.mood.name).putLong("changedAt", state.changedAt)
            .putLong("lastInteractionAt", state.lastInteractionAt).apply()
    }
    fun tick(zone: ZoneId) {
        val config = _settings.value
        _presence.update { FishCompanion.tick(it, System.currentTimeMillis(), zone, config.wakeHour, config.sleepHour) }
    }
    fun save(value: FishCompanionSettings) {
        _settings.value = value
        prefs.edit().putString("appearance", value.appearance.name).putBoolean("visible", value.visible)
            .putInt("wakeHour", value.wakeHour).putInt("sleepHour", value.sleepHour)
            .putFloat("x", value.positionX).putFloat("y", value.positionY)
            .putBoolean("globalVisible", value.globalVisible)
            .putFloat("globalX", value.globalX).putFloat("globalY", value.globalY).apply()
    }
}
