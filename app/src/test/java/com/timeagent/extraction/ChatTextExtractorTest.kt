package com.timeagent.extraction

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ChatTextExtractorTest {
    private val now = Instant.parse("2026-10-26T02:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun undatedTextStaysInboxEvenWithClock() {
        val item = ChatTextExtractor.extract("小王: 19:00 开会", now, zone).items.single()
        assertEquals("小王: 19:00 开会", item.original)
        assertEquals(ExtractionKind.INBOX, item.kind)
        assertNull(item.startAt)
        assertTrue(item.needsCommitment)
        assertFalse(item.acceptedAsMine)
    }

    @Test fun explicitPointIsReminderWithoutInventedEnd() {
        val item = ChatTextExtractor.extract("明天 19:00 开会", now, zone).items.single()
        assertEquals(ExtractionKind.REMINDER, item.kind)
        assertEquals(Instant.parse("2026-10-27T11:00:00Z").toEpochMilli(), item.startAt)
        assertNull(item.endAt)
    }

    @Test fun explicitRangeAndDurationBecomePlans() {
        val items = ChatTextExtractor.extract("明天 19:00到20:00 开会；后天 9:00 学习 90分钟", now, zone).items
        assertEquals(2, items.size)
        assertEquals(ExtractionKind.PLAN, items[0].kind)
        assertEquals(60 * 60 * 1000L, items[0].endAt!! - items[0].startAt!!)
        assertEquals(90 * 60 * 1000L, items[1].endAt!! - items[1].startAt!!)
    }

    @Test fun questionsNegationsAndQuotedCommandsAreIgnored() {
        val result = ChatTextExtractor.extract("明天开会吗？不要安排明天开会。‘明天 9:00 开会’", now, zone)
        assertTrue(result.items.isEmpty())
        assertEquals(3, result.ignored.size)
    }

    @Test fun dateOnlyIsTaskDueAtEndOfDay() {
        val item = ChatTextExtractor.extract("明天交报告", now, zone).items.single()
        assertEquals(ExtractionKind.TASK, item.kind)
        assertEquals(Instant.parse("2026-10-27T15:59:59.999Z").toEpochMilli(), item.dueAt)
    }

    @Test fun datePrefixWithColonIsNotMistakenForSpeaker() {
        val item = ChatTextExtractor.extract("明天：交报告", now, zone).items.single()
        assertEquals(ExtractionKind.TASK, item.kind)
        assertEquals("交报告", item.title)
    }

    @Test fun twoDatesNeedExplicitRange() {
        val preview = ChatTextExtractor.extract("明天和后天交报告", now, zone)
        assertTrue(preview.items.isEmpty())
        assertEquals(1, preview.ignored.size)
    }

    @Test fun explicitCrossMidnightRangeUsesSecondDate() {
        val item = ChatTextExtractor.extract("明天23:00到后天01:00值班", now, zone).items.single()
        assertEquals(ExtractionKind.PLAN, item.kind)
        assertEquals(2 * 60 * 60 * 1000L, item.endAt!! - item.startAt!!)
    }

    @Test fun multiSentenceQuotationDoesNotBecomeCandidates() {
        val preview = ChatTextExtractor.extract("“明天开会。后天交报告”", now, zone)
        assertTrue(preview.items.isEmpty())
        assertTrue(preview.ignored.isNotEmpty())
    }

    @Test fun unmatchedQuotationIsConservativelyIgnored() {
        val preview = ChatTextExtractor.extract("“明天开会。后天交报告", now, zone)
        assertTrue(preview.items.isEmpty())
        assertTrue(preview.ignored.isNotEmpty())
    }

    @Test fun ordinaryNegationsAreIgnoredWithoutRejectingMaterialName() {
        val preview = ChatTextExtractor.extract("明天不去开会。后天不练腿。不锈钢加工", now, zone)
        assertEquals(listOf("不锈钢加工"), preview.items.map { it.original })
        assertEquals(ExtractionKind.INBOX, preview.items.single().kind)
        assertEquals(2, preview.ignored.size)
    }

    @Test fun temporalNarrativeNegationsDoNotBecomePositiveItems() {
        val preview = ChatTextExtractor.extract("明天不锻炼。明天不复习。明天19:00我不锻炼。不锈钢加工", now, zone)
        assertEquals(listOf("不锈钢加工"), preview.items.map { it.original })
        assertEquals(3, preview.ignored.size)
    }
}
