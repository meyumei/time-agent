package com.timeagent.ui

import com.timeagent.core.workout.WorkoutSession
import com.timeagent.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyBriefingTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-20T01:00:00Z")
    private val today = LocalDate.of(2026, 10, 20)
    private fun stamp(hour: Int, minute: Int = 0) = today.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test fun `next item uses earliest real instant and skips cancelled or completed entries`() {
        val data = LocalSnapshot(
            tasks = listOf(TaskEntity("done", "已完成", status = "COMPLETED")),
            plans = listOf(
                ScheduleBlockEntity("later", "", stamp(11), stamp(12)),
                ScheduleBlockEntity("ongoing", "", stamp(8), stamp(10)),
                ScheduleBlockEntity("cancelled", "", stamp(9), stamp(9, 30), status = "CANCELLED")
            ),
            loading = false
        )
        val brief = buildDailyBriefing(data, now, zone, BriefingSyncStatus(BriefingSyncState.NO_DATA), emptyList())
        assertEquals("ongoing", brief.nextItem?.id)
        assertEquals("PLAN", brief.nextItem?.type)
    }

    @Test fun `today due tasks prioritize overdue and count unique overdue tasks`() {
        val data = LocalSnapshot(tasks = listOf(
            TaskEntity("old", "过期", dueAt = stamp(7), priority = 1),
            TaskEntity("old2", "高优先过期", dueAt = stamp(7, 30), priority = 2, status = "IN_PROGRESS"),
            TaskEntity("today", "今天到期", dueAt = stamp(16), priority = 1),
            TaskEntity("done", "已完成", dueAt = stamp(8), status = "COMPLETED"),
            TaskEntity("deleted", "已删除", dueAt = stamp(8), deletedAt = 1)
        ), loading = false)
        val brief = buildDailyBriefing(data, now, zone, BriefingSyncStatus(BriefingSyncState.READY), emptyList())
        assertEquals(2, brief.overdueCount)
        assertEquals(listOf("old2", "old", "today"), brief.dueTasks.map { it.id })
        assertTrue(brief.dueTasks.first().overdue)
    }

    @Test fun `sleep keeps main and nap separate and labels latest past date`() {
        val data = LocalSnapshot(
            records = listOf(
                TimeRecordEntity("main-a", null, Instant.parse("2026-10-19T15:00:00Z").toEpochMilli(), Instant.parse("2026-10-19T18:00:00Z").toEpochMilli(), title = "睡眠"),
                TimeRecordEntity("main-b", null, Instant.parse("2026-10-19T19:00:00Z").toEpochMilli(), Instant.parse("2026-10-19T23:00:00Z").toEpochMilli(), title = "睡眠"),
                TimeRecordEntity("nap", null, stamp(13), stamp(14), title = "睡眠")
            ), loading = false)
        val brief = buildDailyBriefing(data, Instant.parse("2026-10-20T07:00:00Z"), zone, BriefingSyncStatus(BriefingSyncState.STALE, latestSourceDateText = "来源日期 10月19日"), emptyList())
        assertEquals(7 * 60L, brief.sleep.today?.mainMinutes)
        assertEquals(60L, brief.sleep.today?.napMinutes)
        assertEquals(today, brief.sleep.today?.wakeDate)
        assertEquals("来源日期 10月19日", brief.sync.latestSourceDateText)
        assertNull(brief.sleep.latestPastDate)
    }

    @Test fun `missing today's sleep stays unknown and completed workout is independent`() {
        val data = LocalSnapshot(records = listOf(
            TimeRecordEntity("old-sleep", null, Instant.parse("2026-10-18T15:00:00Z").toEpochMilli(), Instant.parse("2026-10-18T20:00:00Z").toEpochMilli(), title = "睡眠")
        ), loading = false)
        val workout = WorkoutSession(id = "session", title = "力量训练", startedAt = stamp(8), status = com.timeagent.core.workout.WorkoutSessionStatus.COMPLETED)
        val brief = buildDailyBriefing(data, now, zone, BriefingSyncStatus(BriefingSyncState.STALE), listOf(workout))
        assertNull(brief.sleep.today)
        assertEquals("未查到今天起床的睡眠记录", brief.sleep.todayMessage)
        assertEquals(today.minusDays(1), brief.sleep.latestPastDate)
        assertEquals("WORKOUT", brief.latestTraining?.type)
        assertEquals("session", brief.latestTraining?.id)
    }
}
