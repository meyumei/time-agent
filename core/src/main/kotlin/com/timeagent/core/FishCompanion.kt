package com.timeagent.core

import java.time.Instant
import java.time.ZoneId

enum class FishMood { CALM, HAPPY, CURIOUS, CONCERNED }
enum class FishActivity { IDLE, LISTENING, THINKING, WORKING, SLEEPY, SLEEPING, ERROR, GREETING }
enum class FishAppearance { CHIBI, ELEGANT }
data class FishPresence(
    val mood: FishMood = FishMood.CALM,
    val activity: FishActivity = FishActivity.IDLE,
    val changedAt: Long = 0,
    val lastInteractionAt: Long = 0,
)

/** Clock and event driven; rendering never makes network requests. */
object FishCompanion {
    private const val TRANSIENT_MS = 12_000L
    private const val AWAKE_GRACE_MS = 90_000L
    private const val MOOD_DECAY_MS = 30L * 60L * 1000L

    private val idleFrames = listOf(0, 0, 0, 1, 2, 1, 0)
    private val happyFrames = listOf(4, 5, 6, 5, 4, 7)
    private val thinkingFrames = listOf(8, 9, 8, 9)
    private val workingFrames = listOf(10, 11, 10, 11)
    private val sleepyFrames = listOf(12)
    private val sleepingFrames = listOf(13)
    private val concernedFrames = listOf(14, 15, 14)
    private val errorFrames = listOf(15)
    private val listeningFrames = listOf(0, 1, 2, 1, 0)

    private val negativePhrases = listOf(
        "抱歉", "对不起", "失败", "出错", "错误", "无法", "没办法", "未能", "未完成",
        "遗憾", "故障", "异常", "糟糕", "不行", "没能", "难过", "伤心", "累了", "焦虑", "fish:concerned", "fish:apologetic",
        "sorry", "apolog", "fail", "error", "cannot", "can't", "can not", "unable",
        "unfortunately", "went wrong", "not able", "not good", "not done", "broken",
    )

    private val happyPhrases = listOf(
        "开心", "高兴", "成功", "完成", "太好了", "很好", "不错", "太棒", "棒", "赞",
        "喜欢", "恭喜", "顺利", "没问题",
        "happy", "glad", "great", "success", "succeed", "complete", "finish", "done",
        "perfect", "nice", "good", "awesome", "wonderful", "love", "congrat", "yay",
        "excellent",
    )

    private val curiousPhrases = listOf(
        "吗", "呢", "如何", "为什么", "什么", "怎么", "哪", "是否", "多少", "谁",
        "何时", "能不能", "可以吗", "?", "？",
        "how", "why", "what", "when", "where", "which", "who", "curious", "wonder",
    )

    fun event(previous: FishPresence, kind: String, text: String, now: Long): FishPresence {
        return when (kind.lowercase()) {
            "send" -> previous.copy(
                mood = if (text.isBlank()) previous.mood else moodFor(text),
                activity = FishActivity.THINKING,
                changedAt = now,
                lastInteractionAt = now,
            )
            "tool" -> previous.copy(activity = FishActivity.WORKING, changedAt = now)
            "done" -> {
                val failed = previous.activity == FishActivity.ERROR || containsAny(text, negativePhrases)
                previous.copy(
                    mood = if (failed) FishMood.CONCERNED else moodFor(text).let {
                        if (it == FishMood.CALM) previous.mood else it
                    },
                    activity = FishActivity.GREETING,
                    changedAt = now,
                    lastInteractionAt = now,
                )
            }
            "reply" -> previous.copy(
                mood = moodFor(text).let { if (it == FishMood.CALM) previous.mood else it },
                activity = FishActivity.IDLE,
                changedAt = now,
                lastInteractionAt = now,
            )
            "error" -> previous.copy(
                mood = FishMood.CONCERNED,
                activity = FishActivity.ERROR,
                changedAt = now,
            )
            "cancel", "stopped" -> previous.copy(activity = FishActivity.IDLE, changedAt = now)
            "tap" -> previous.copy(
                activity = if (previous.activity in setOf(FishActivity.THINKING, FishActivity.WORKING, FishActivity.LISTENING))
                    previous.activity else FishActivity.GREETING,
                changedAt = now,
                lastInteractionAt = now,
            )
            else -> previous
        }
    }

    fun tick(previous: FishPresence, now: Long, zone: ZoneId, wakeHour: Int = 8, sleepHour: Int = 23): FishPresence {
        if (previous.activity == FishActivity.THINKING ||
            previous.activity == FishActivity.WORKING ||
            previous.activity == FishActivity.LISTENING
        ) {
            return previous
        }

        val sinceInteraction =
            if (previous.lastInteractionAt > 0) now - previous.lastInteractionAt else Long.MAX_VALUE
        val mood = if (sinceInteraction >= MOOD_DECAY_MS) FishMood.CALM else previous.mood

        if (previous.activity == FishActivity.GREETING || previous.activity == FishActivity.ERROR) {
            return if (now - previous.changedAt >= TRANSIENT_MS) {
                previous.copy(activity = FishActivity.IDLE, mood = mood, changedAt = now)
            } else {
                previous.copy(mood = mood)
            }
        }

        val hour = Instant.ofEpochMilli(now).atZone(zone).hour
        val target = when {
            sinceInteraction <= AWAKE_GRACE_MS -> FishActivity.IDLE
            isSleepyHour(hour, wakeHour, sleepHour) -> FishActivity.SLEEPY
            isAwakeHour(hour, wakeHour, sleepHour) -> FishActivity.IDLE
            else -> FishActivity.SLEEPING
        }

        return if (previous.activity == target) {
            previous.copy(mood = mood)
        } else {
            previous.copy(activity = target, mood = mood, changedAt = now)
        }
    }

    fun frames(presence: FishPresence): List<Int> {
        return when (presence.activity) {
            FishActivity.THINKING -> thinkingFrames
            FishActivity.WORKING -> workingFrames
            FishActivity.SLEEPY -> sleepyFrames
            FishActivity.SLEEPING -> sleepingFrames
            FishActivity.ERROR -> errorFrames
            FishActivity.GREETING ->
                if (presence.mood == FishMood.CONCERNED) concernedFrames else happyFrames
            FishActivity.LISTENING -> listeningFrames
            FishActivity.IDLE -> when (presence.mood) {
                FishMood.HAPPY -> happyFrames
                FishMood.CURIOUS -> thinkingFrames
                FishMood.CONCERNED -> concernedFrames
                FishMood.CALM -> idleFrames
            }
        }
    }

    private fun isAwakeHour(hour: Int, wakeHour: Int, sleepHour: Int): Boolean {
        return when {
            wakeHour == sleepHour -> true
            wakeHour < sleepHour -> hour in wakeHour until sleepHour
            else -> hour >= wakeHour || hour < sleepHour
        }
    }

    private fun isSleepyHour(hour: Int, wakeHour: Int, sleepHour: Int): Boolean {
        return wakeHour != sleepHour && hour == Math.floorMod(sleepHour - 1, 24)
    }

    private fun moodFor(text: String): FishMood {
        return when {
            containsAny(text, negativePhrases) -> FishMood.CONCERNED
            containsAny(text, happyPhrases) -> FishMood.HAPPY
            containsAny(text, curiousPhrases) -> FishMood.CURIOUS
            else -> FishMood.CALM
        }
    }

    private fun containsAny(text: String, phrases: List<String>): Boolean {
        return phrases.any { text.contains(it, ignoreCase = true) }
    }
}
