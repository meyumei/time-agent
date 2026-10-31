package com.timeagent.history

import com.timeagent.data.*
import com.timeagent.core.workout.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class LocalHistoryTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-27T12:00:00Z")
    private val day = LocalDate.of(2026, 10, 26)
    private fun at(date: LocalDate = day, hour: Int): Long = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun filter(keyword: String = "") = HistoryFilter(day, day, keyword)
    @Test fun `recorded duration and union duration are separate with keyword notes`() {
        val data = LocalSnapshot(loading = false, records = listOf(
            TimeRecordEntity("a", null, at(hour = 9), at(hour = 11), title = "学习", notes = "数字信号处理"),
            TimeRecordEntity("b", null, at(hour = 10), at(hour = 12), title = "学习", notes = "数字信号处理")))
        val answer = LocalHistory.answer(data, emptyList(), filter("数字信号处理"), now, zone)
        assertEquals(240L, answer.recordedMinutes)
        assertEquals(180L, answer.elapsedMinutes)
        assertEquals(setOf("a", "b"), answer.records.map { it.id }.toSet())
        assertTrue(answer.text.contains("累计"))
        assertTrue(answer.text.contains("去重"))
        assertTrue(answer.text.contains("关键词「数字信号处理」"))
        assertTrue(answer.text.contains("最近匹配记录：2026-10-26 10:00"))
    }
    @Test fun `intervals clip to date bounds and future intervals do not count`() {
        val data = LocalSnapshot(loading = false, records = listOf(
            TimeRecordEntity("night", null, at(hour = 22), at(day.plusDays(1), 2), title = "阅读"),
            TimeRecordEntity("future", null, now.plusSeconds(3600).toEpochMilli(), now.plusSeconds(7200).toEpochMilli(), title = "阅读")))
        val answer = LocalHistory.answer(data, emptyList(), filter(), now, zone)
        assertEquals(120L, answer.recordedMinutes)
        assertEquals(listOf("night"), answer.records.map { it.id })
    }
    @Test fun `deleted task titles activity and related note remain searchable in actual history`() {
        val data = LocalSnapshot(loading = false,
            tasks = listOf(TaskEntity("task", "旧课设", activityId = "study", deletedAt = at(hour = 12))),
            activities = listOf(ActivityTemplateEntity("study", "学习")),
            records = listOf(TimeRecordEntity("record", "task", at(hour = 9), at(hour = 10))),
            notes = listOf(NoteEntity("note", "RECORD", "record", body = "通信实验总结", updatedAt = at(hour = 12))))
        val answer = LocalHistory.answer(data, emptyList(), filter("通信实验"), now, zone)
        assertEquals(60L, answer.recordedMinutes)
        assertEquals("note", answer.notes.single().id)
        assertEquals(60L, LocalHistory.answer(data, emptyList(), filter("学习").copy(activityId = "study"), now, zone).recordedMinutes)
    }
    @Test fun `workout attribution uses start date and never adds its timeline duration again`() {
        val session = WorkoutSession("w", "练腿", at(hour = 8), status = WorkoutSessionStatus.COMPLETED, endedAt = at(hour = 10), totalPausedMillis = 30 * 60000L)
        val active = WorkoutSession("ongoing", "未结束训练", at(hour = 14))
        val data = LocalSnapshot(loading = false, records = listOf(TimeRecordEntity("workout:w", null, at(hour = 8), at(hour = 10), title = "练腿", source = "WORKOUT")))
        val answer = LocalHistory.answer(data, listOf(session, active), filter(), now, zone)
        assertEquals(1, answer.workoutCount)
        assertEquals(90L, answer.workoutMinutes)
        assertEquals(120L, answer.recordedMinutes)
        assertEquals(listOf("w"), answer.workouts.map { it.id })
        assertTrue(answer.text.contains("开始日期"))
    }
    @Test fun `missing data says records not found rather than claiming no activity`() {
        val answer = LocalHistory.answer(LocalSnapshot(loading = false), emptyList(), filter("课设"), now, zone)
        assertTrue(answer.text.contains("未查到"))
        assertFalse(answer.text.contains("没有学习"))
        assertTrue(answer.records.isEmpty())
    }
    @Test fun `note search works without inventing actual duration`() {
        val data = LocalSnapshot(loading = false, notes = listOf(NoteEntity("n", "IDEA", "i", title = "复盘", body = "通信实验", updatedAt = at(hour = 12))))
        val answer = LocalHistory.answer(data, emptyList(), filter("通信实验").copy(domain = HistoryDomain.NOTES), now, zone)
        assertEquals("n", answer.notes.single().id)
        assertEquals(0L, answer.recordedMinutes)
    }
    @Test fun `last week and explicit date questions become local structured ranges`() {
        val questionNow = Instant.parse("2026-10-26T02:00:00Z")
        val last = requireNotNull(HistoryQuestionParser.parse("上周数字信号处理学了多久", questionNow, zone))
        assertEquals(LocalDate.of(2026, 10, 19), last.from)
        assertEquals(LocalDate.of(2026, 10, 25), last.through)
        assertEquals("数字信号处理", last.keyword)
        val explicit = requireNotNull(HistoryQuestionParser.parse("2026-10-01到2026-10-03阅读累计多久", now, zone))
        assertEquals(LocalDate.of(2026, 10, 1), explicit.from)
        assertEquals(LocalDate.of(2026, 10, 3), explicit.through)
    }
    @Test fun `questions about last workout have all history scope and writes stay outside route`() {
        val last = requireNotNull(HistoryQuestionParser.parse("上次健身是什么时候", now, zone))
        assertEquals(HistoryDomain.WORKOUTS, last.domain)
        assertNull(last.from)
        assertFalse(HistoryQuestionParser.isHistoryQuestion("记录昨天九点到十点学习"))
        assertFalse(HistoryQuestionParser.isHistoryQuestion("安排明天九点到十点学习"))
        assertEquals("学习", requireNotNull(HistoryQuestionParser.parse("今天学习了多久", now, zone)).keyword)
    }
    @Test fun `query particles are trimmed only at boundaries of subject names`() {
        assertEquals("我的世界", requireNotNull(HistoryQuestionParser.parse("昨天我的世界累计多久呢", now, zone)).keyword)
        assertEquals("吗啡课题", requireNotNull(HistoryQuestionParser.parse("今天吗啡课题学了多久", now, zone)).keyword)
    }
    @Test fun `ordinary practice queries still count actual records`() {
        val query = requireNotNull(HistoryQuestionParser.parse("今天练习英语多久", now, zone))
        val data = LocalSnapshot(loading = false, records = listOf(TimeRecordEntity("english", null, now.minusSeconds(3600).toEpochMilli(), now.toEpochMilli(), title = "练习英语")))
        assertEquals(60L, LocalHistory.answer(data, emptyList(), query, now, zone).recordedMinutes)
        assertEquals(HistoryDomain.RECORDS, query.domain)
    }
}
