package com.timeagent.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class FishCompanionTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val noon = Instant.parse("2026-10-29T04:00:00Z").toEpochMilli()

    @Test fun `real events drive work error and completion`() {
        val thinking = FishCompanion.event(FishPresence(), "send", "帮我看看安排", noon)
        assertEquals(FishActivity.THINKING, thinking.activity)
        val work = FishCompanion.event(thinking, "tool", "", noon + 1)
        assertEquals(FishActivity.WORKING, work.activity)
        val failed = FishCompanion.event(work, "error", "", noon + 2)
        assertEquals(FishActivity.ERROR, failed.activity)
        assertEquals(FishMood.CONCERNED, failed.mood)
        val done = FishCompanion.event(work, "done", "已安排好了", noon + 3)
        assertEquals(FishActivity.GREETING, done.activity)
    }
    @Test fun `negative content is concerned before generic positive word matching`() {
        val state = FishCompanion.event(FishPresence(), "reply", "没能成功，听起来你有些难过，我陪你。", noon)
        assertEquals(FishMood.CONCERNED, state.mood)
        assertEquals(FishMood.CONCERNED, FishCompanion.event(state, "done", "我在这里陪着你。", noon + 1).mood)
        assertEquals(FishMood.HAPPY, FishCompanion.event(state, "reply", "太好了，恭喜完成！", noon + 1).mood)
    }
    @Test fun `local clock quiet hours settle but never replace a running task`() {
        val night = Instant.parse("2026-10-29T16:00:00Z").toEpochMilli()
        assertEquals(FishActivity.SLEEPING, FishCompanion.tick(FishPresence(), night, zone).activity)
        assertEquals(FishActivity.IDLE, FishCompanion.tick(FishPresence(), night, ZoneId.of("America/Los_Angeles")).activity)
        val busy = FishPresence(activity = FishActivity.WORKING)
        assertEquals(FishActivity.WORKING, FishCompanion.tick(busy, night, zone).activity)
        assertEquals(FishActivity.WORKING, FishCompanion.event(busy, "tap", "", night).activity)
        val fresh = FishCompanion.event(FishPresence(), "send", "你好", night)
        assertEquals(FishActivity.THINKING, FishCompanion.tick(fresh, night + 1000, zone).activity)
    }
    @Test fun `transient response settles and moods decay with elapsed time`() {
        val happy = FishCompanion.event(FishPresence(), "reply", "太好了", noon)
        val settled = FishCompanion.tick(happy, noon + 60_000, zone)
        assertEquals(FishActivity.IDLE, settled.activity)
        assertEquals(FishMood.HAPPY, settled.mood)
        assertEquals(FishMood.CALM, FishCompanion.tick(settled, noon + 45 * 60_000, zone).mood)
        assertTrue(FishCompanion.frames(happy).distinct().size >= 3)
    }
    @Test fun `cross midnight routine and wake up work`() {
        val night = Instant.parse("2026-10-29T16:00:00Z").toEpochMilli()
        val asleep = FishCompanion.tick(FishPresence(), night, zone, 7, 22)
        val awake = FishCompanion.tick(asleep, noon, zone, 7, 22)
        assertEquals(FishActivity.IDLE, awake.activity)
        assertEquals(FishActivity.SLEEPY, FishCompanion.tick(FishPresence(), Instant.parse("2026-10-29T13:30:00Z").toEpochMilli(), zone, 7, 22).activity)
    }
}
