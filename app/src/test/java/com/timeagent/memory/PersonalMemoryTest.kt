package com.timeagent.memory

import com.timeagent.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class PersonalMemoryTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-26T02:00:00Z")
    @Test fun `explicit remember has a source and typed personal weekday rule`() {
        val card = requireNotNull(PersonalMemoryRules.fromExplicit("记住：我周三不练腿", now))
        assertEquals("PREFERENCE", card.kind)
        assertEquals("EXPLICIT", card.sourceType)
        assertTrue(card.confirmed)
        assertEquals(3, card.weekday)
        assertEquals("练腿", card.keyword)
        assertEquals("AVOID_WEEKDAY", card.ruleType)
        assertTrue(card.sourceText.contains("记住"))
    }
    @Test fun `quoted other speaker and uncertain remember never become active preference`() {
        assertNull(PersonalMemoryRules.fromExplicit("他说：记住我周三不练腿", now))
        assertNull(PersonalMemoryRules.fromExplicit("记住我周三不练腿可以吗？", now))
        assertNull(PersonalMemoryRules.fromExplicit("不要记住周三不练腿", now))
    }
    @Test fun `only confirmed enabled in-scope memory restricts matching plan`() {
        val card = requireNotNull(PersonalMemoryRules.fromExplicit("记住周三不练腿", now)).copy(id = "memory")
        val task = TaskEntity("leg", "练腿")
        val wed = LocalDate.of(2026, 10, 28).atTime(19, 0).atZone(zone).toInstant()
        assertFalse(PersonalMemoryRules.allows(listOf(card), task, wed, wed.plusSeconds(3600), zone))
        listOf(card.copy(confirmed = false), card.copy(enabled = false), card.copy(validUntil = wed.minusSeconds(1).toEpochMilli()), card.copy(deletedAt = now.toEpochMilli())).forEach {
            assertTrue(PersonalMemoryRules.allows(listOf(it), task, wed, wed.plusSeconds(3600), zone))
        }
        assertTrue(PersonalMemoryRules.allows(listOf(card), task.copy(title = "练胸"), wed, wed.plusSeconds(3600), zone))
    }
    @Test fun `daily window and cross midnight weekday checks use local dates`() {
        val card = PersonalMemoryEntity("window", "AGREEMENT", "阅读安排在晚上", ruleType = "TIME_WINDOW", keyword = "阅读", startMinute = 19 * 60, endMinute = 22 * 60)
        val day = LocalDate.of(2026, 10, 28)
        val start = day.atTime(18, 0).atZone(zone).toInstant()
        assertFalse(PersonalMemoryRules.allows(listOf(card), TaskEntity("r", "阅读"), start, start.plusSeconds(3600), zone))
        assertTrue(PersonalMemoryRules.allows(listOf(card), TaskEntity("r", "阅读"), start.plusSeconds(3600), start.plusSeconds(7200), zone))
        val avoid = requireNotNull(PersonalMemoryRules.fromExplicit("记住周三不练腿", now))
        val tue = day.minusDays(1).atTime(23, 30).atZone(zone).toInstant()
        assertFalse(PersonalMemoryRules.allows(listOf(avoid), TaskEntity("l", "练腿"), tue, tue.plusSeconds(3600), zone))
    }
}
