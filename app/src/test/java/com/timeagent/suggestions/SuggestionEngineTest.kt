package com.timeagent.suggestions

import com.timeagent.data.CourseOccurrenceEntity
import com.timeagent.data.HealthSyncItemEntity
import com.timeagent.data.LocalSnapshot
import com.timeagent.data.ScheduleBlockEntity
import com.timeagent.data.TaskEntity
import com.timeagent.data.TimeRecordEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SuggestionEngineTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    // 2026-10-24 12:00 Shanghai — inside the 08:00-22:00 suggestion window.
    private val now = Instant.parse("2026-10-24T04:00:00Z")

    private fun empty() = LocalSnapshot(loading = false)

    @Test fun `task longer than remaining free time is never offered`() {
        val data = empty().copy(tasks = listOf(TaskEntity("too-long", "长任务", estimatedMinutes = 700)))
        assertTrue(SuggestionEngine.generate(data, zone, now).none { it.type == SuggestionEngine.TYPE_FREE_SLOT })
    }

    @Test fun `fragmented adequate night does not become short sleep from final fragment`() {
        val end = now.minusSeconds(3 * 3600)
        val start = end.minusSeconds(8 * 3600)
        val data = empty().copy(records = listOf(
            TimeRecordEntity("health:sleep:a", null, start.toEpochMilli(), start.plusSeconds(4 * 3600).toEpochMilli(), source = "HEALTH_SYNC", title = "睡眠"),
            TimeRecordEntity("health:sleep:b", null, start.plusSeconds(4 * 3600).toEpochMilli(), end.toEpochMilli(), source = "HEALTH_SYNC", title = "睡眠"),
        ))
        assertTrue(SuggestionEngine.generate(data, zone, now).none { it.type == SuggestionEngine.TYPE_SLEEP_SHORT })
    }

    @Test fun `overdue todo task is suggested with stable dedup key`() {
        val dueAt = now.minusSeconds(3600).toEpochMilli()
        val data = empty().copy(tasks = listOf(TaskEntity("t1", "交报告", dueAt = dueAt)))
        val result = SuggestionEngine.generate(data, zone, now)
        val suggestion = result.single { it.type == SuggestionEngine.TYPE_OVERDUE }
        assertEquals("任务已截止：交报告", suggestion.title)
        assertEquals("overdue:t1:$dueAt:v0", suggestion.dedupKey)
        assertTrue(suggestion.detail.contains("已超 1 小时"))
    }

    @Test fun `recently due task inside grace window is not suggested`() {
        val dueAt = now.minusSeconds(600).toEpochMilli()
        val data = empty().copy(tasks = listOf(TaskEntity("t1", "刚截止", dueAt = dueAt)))
        assertTrue(SuggestionEngine.generate(data, zone, now).none { it.type == SuggestionEngine.TYPE_OVERDUE })
    }

    @Test fun `free slot with an unplanned task is suggested`() {
        val day = 24 * 3600_000L
        val base = now.toEpochMilli() - now.toEpochMilli() % day // local-day boundaries are close enough for a wide test window
        val data = empty().copy(
            tasks = listOf(TaskEntity("t1", "读书", estimatedMinutes = 90, priority = 1)),
            plans = listOf(
                ScheduleBlockEntity("p1", "t2", base + 9 * 3600_000L, base + 11 * 3600_000L),
                ScheduleBlockEntity("p2", "t2", base + 14 * 3600_000L, base + 18 * 3600_000L),
            ),
        )
        val result = SuggestionEngine.generate(data, zone, now)
        val suggestion = result.first { it.type == SuggestionEngine.TYPE_FREE_SLOT }
        assertTrue(suggestion.title.contains("分钟空闲"))
        assertTrue(suggestion.detail.contains("读书"))
        assertTrue(suggestion.dedupKey.startsWith("free_slot:2026-10-24:"))
    }

    @Test fun `no free slot suggestion when every task is already planned`() {
        val data = empty().copy(
            tasks = listOf(TaskEntity("t1", "已安排的任务")),
            plans = listOf(ScheduleBlockEntity("p1", "t1", now.toEpochMilli() + 3600_000, now.toEpochMilli() + 7200_000)),
        )
        assertTrue(SuggestionEngine.generate(data, zone, now).none { it.type == SuggestionEngine.TYPE_FREE_SLOT })
    }

    @Test fun `short sleep from health sync within 24h is suggested`() {
        val end = now.minusSeconds(3 * 3600)
        val start = end.minusSeconds(5 * 3600)
        val data = empty().copy(
            records = listOf(TimeRecordEntity("health:sleep:s1", null, start.toEpochMilli(), end.toEpochMilli(), source = "HEALTH_SYNC", title = "睡眠")),
            healthItems = listOf(HealthSyncItemEntity("s1", "SLEEP", "睡眠", start.toEpochMilli(), end.toEpochMilli(), "com.gb", "health:sleep:s1")),
        )
        val suggestion = SuggestionEngine.generate(data, zone, now).first { it.type == SuggestionEngine.TYPE_SLEEP_SHORT }
        assertTrue(suggestion.title.contains("5 小时"))
        assertEquals("sleep_short:health:sleep:s1", suggestion.dedupKey)
    }

    @Test fun `adequate sleep is not suggested`() {
        val end = now.minusSeconds(3 * 3600)
        val start = end.minusSeconds(8 * 3600)
        val data = empty().copy(
            records = listOf(TimeRecordEntity("health:sleep:s1", null, start.toEpochMilli(), end.toEpochMilli(), source = "HEALTH_SYNC", title = "睡眠")),
        )
        assertTrue(SuggestionEngine.generate(data, zone, now).none { it.type == SuggestionEngine.TYPE_SLEEP_SHORT })
    }

    @Test fun `manually added sleep is never suggested`() {
        val end = now.minusSeconds(3 * 3600)
        val start = end.minusSeconds(4 * 3600)
        val data = empty().copy(records = listOf(TimeRecordEntity("r1", null, start.toEpochMilli(), end.toEpochMilli(), source = "MANUAL", title = "睡眠")))
        assertTrue(SuggestionEngine.generate(data, zone, now).none { it.type == SuggestionEngine.TYPE_SLEEP_SHORT })
    }
}
