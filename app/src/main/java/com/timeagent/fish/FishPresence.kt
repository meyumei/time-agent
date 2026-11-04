package com.timeagent.fish

import com.timeagent.core.FishActivity
import com.timeagent.core.FishMood
import com.timeagent.core.FishPresence
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONObject

enum class FishPresencePhase { IDLE, LISTENING, THINKING, SPEAKING, HAPPY, SLEEPY }

data class FishPresenceSnapshot(
    val phase: FishPresencePhase,
    val mood: FishMood,
    val activity: FishActivity,
    val text: String = "",
    val updatedAt: Long = 0L,
)

/**
 * The character state read by the app, the overlay and future widgets. It derives one
 * phase from the existing companion state and the shared voice engine; it never owns a
 * second conversation.
 *
 * The class is named ...Controller because `FishPresence` is already the core model used
 * by the character sprite and overlay in this package.
 */
class FishPresenceController(
    private val companion: FishCompanionState,
    private val zone: ZoneId,
    private val scope: CoroutineScope,
    private val speaking: () -> Boolean,
) {
    val presence: StateFlow<FishPresence> = companion.presence

    private val ticks = flow {
        while (currentCoroutineContext().isActive) {
            emit(Unit)
            delay(1_000L)
        }
    }

    val snapshot: StateFlow<FishPresenceSnapshot> =
        combine(companion.presence, ticks) { value, _ -> snapshotOf(value) }
            .stateIn(scope, SharingStarted.Eagerly, snapshotOf(companion.presence.value))

    init {
        scope.launch {
            while (isActive) {
                companion.tick(zone)
                delay(15_000L)
            }
        }
    }

    fun event(kind: String, text: String = "") {
        companion.event(kind, text)
    }

    /** Payload for the `fish_presence` tool; same fields the host received before the split. */
    fun toolPayload(): JSONObject {
        val state = companion.presence.value
        val config = companion.settings.value
        return JSONObject()
            .put("mood", state.mood.name)
            .put("activity", state.activity.name)
            .put("localTime", ZonedDateTime.now(zone).toString())
            .put("timezone", zone.id)
            .put("wakeHour", config.wakeHour)
            .put("sleepHour", config.sleepHour)
            .put("appearance", config.appearance.name)
            .put("identity", "同一个大肥鱼，外观不改变人格和共同记忆")
    }

    private fun snapshotOf(value: FishPresence): FishPresenceSnapshot {
        val phase = when {
            value.activity == FishActivity.SLEEPING || value.activity == FishActivity.SLEEPY ->
                FishPresencePhase.SLEEPY
            speaking() -> FishPresencePhase.SPEAKING
            value.activity == FishActivity.LISTENING -> FishPresencePhase.LISTENING
            value.activity == FishActivity.THINKING || value.activity == FishActivity.WORKING ->
                FishPresencePhase.THINKING
            value.activity == FishActivity.ERROR -> FishPresencePhase.IDLE
            value.mood == FishMood.HAPPY || value.activity == FishActivity.GREETING ->
                FishPresencePhase.HAPPY
            else -> FishPresencePhase.IDLE
        }
        return FishPresenceSnapshot(
            phase = phase,
            mood = value.mood,
            activity = value.activity,
            text = "",
            updatedAt = value.changedAt,
        )
    }
}
