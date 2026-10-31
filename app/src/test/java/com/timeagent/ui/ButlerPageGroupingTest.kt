package com.timeagent.ui

import com.timeagent.data.RecurringInstanceEntity
import com.timeagent.data.RecurringRuleEntity
import com.timeagent.data.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ButlerPageGroupingTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-27T02:00:00Z") // 上海 2026-10-27 10:00

    private fun at(date: String, time: String = "09:00"): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    private fun raw(id: String, ruleId: String, dueAt: Long?, originalDate: String, ordinal: Int = 1): Pair<RecurringInstanceEntity, TaskEntity> {
        val task = TaskEntity("task:$id", "事项 $id", dueAt = dueAt, status = "TODO")
        val instance = RecurringInstanceEntity(
            id = id,
            ruleId = ruleId,
            taskId = task.id,
            originalDate = originalDate,
            ordinal = ordinal,
            scheduledAt = dueAt,
            ruleSnapshot = "{}",
            ruleVersion = 0,
            taskVersion = 0,
        )
        return instance to task
    }

    private fun rule(id: String, title: String) =
        RecurringRuleEntity(id = id, title = title, patternJson = "{}", domain = "DEADLINE")

    @Test fun `life deadline groups use the effective due date and keep overdue pending visible`() {
        val today = now.toEpochMilli()
        assertEquals(DeadlineBucket.TODAY_OR_OVERDUE, deadlineBucketFor(at("2026-10-27", "00:05"), today, zone))
        assertEquals(DeadlineBucket.TODAY_OR_OVERDUE, deadlineBucketFor(at("2026-10-27", "23:59"), today, zone))
        assertEquals(DeadlineBucket.TODAY_OR_OVERDUE, deadlineBucketFor(at("2026-10-19"), today, zone))
        assertEquals(DeadlineBucket.WITHIN_7_DAYS, deadlineBucketFor(at("2026-10-28", "00:01"), today, zone))
        assertEquals(DeadlineBucket.WITHIN_7_DAYS, deadlineBucketFor(at("2026-11-03", "23:59"), today, zone))
        assertEquals(DeadlineBucket.LATER, deadlineBucketFor(at("2026-11-04", "00:00"), today, zone))
    }

    @Test fun `next life deadline sorts by moved due date and excludes handled instances`() {
        val life = rule("life", "归还资料")
        val (moved, movedTask) = raw("life:1", "life", at("2026-10-27", "18:00"), "2026-10-30")
        val (later, laterTask) = raw("life:2", "life", at("2026-11-02", "09:00"), "2026-11-02")
        val (done, doneTask) = raw("life:3", "life", at("2026-10-28", "09:00"), "2026-10-28")
        val completed = doneTask.copy(status = "COMPLETED", completedAt = now.toEpochMilli())
        val tasks = mapOf(movedTask.id to movedTask, laterTask.id to laterTask, doneTask.id to completed)

        val next = nextPendingDeadlines(listOf(life), listOf(moved, later, done), tasks, zone)
        assertEquals(1, next.size)
        assertEquals("life:1", next.single().instance.id)
        assertEquals(at("2026-10-27", "18:00"), next.single().dueAt)
        assertEquals(DeadlineBucket.TODAY_OR_OVERDUE, deadlineBucketFor(next.single().dueAt, now.toEpochMilli(), zone))

        val handled = handledDeadlines(listOf(life), listOf(moved, later, done), tasks, zone)
        assertEquals(listOf("life:3"), handled.map { it.instance.id })
    }

    @Test fun `recurring focus shows a moved occurrence and hides completed ones`() {
        val (moved, movedTask) = raw("habit:1", "r", at("2026-10-27", "07:30"), "2026-10-18")
        val (soon, soonTask) = raw("habit:2", "r", at("2026-10-30", "09:00"), "2026-10-30")
        val (done, doneTask) = raw("habit:3", "r", at("2026-10-28", "09:00"), "2026-10-28")
        val completed = doneTask.copy(status = "COMPLETED", completedAt = now.toEpochMilli())
        val tasks = mapOf(movedTask.id to movedTask, soonTask.id to soonTask, doneTask.id to completed)

        val focus = recurringFocusInstances(listOf(done, soon, moved), tasks, now.toEpochMilli(), zone)
        assertEquals(listOf("habit:1", "habit:2"), focus.map { it.id })
        assertTrue(focus.none { it.id == "habit:3" })
    }
}
