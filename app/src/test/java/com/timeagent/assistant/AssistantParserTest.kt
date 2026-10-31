package com.timeagent.assistant

import com.timeagent.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class AssistantParserTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-20T08:00:00Z")
    private val context = AssistantContext(now, zone, LocalDate.of(2026, 10, 19), LocalSnapshot(loading = false))
    private val parser = LocalAssistantParser()

    @Test fun `explicit sample course cleanup produces an actionable local command`() {
        for (input in listOf("取消示例课程", "删除所有示例课程", "帮我清理一下示例课表")) {
            assertTrue("$input should be locally executable", parser.parse(input, context) is Interpretation.Parsed)
        }
    }

    @Test fun `tomorrow query uses local date`() {
        val result = parser.parse("明天有什么安排", context) as Interpretation.Parsed
        assertEquals(AssistantCommand.Query(QueryScope.TOMORROW), result.command)
    }
    @Test fun `task duration understands Chinese hours and minutes`() {
        val result = parser.parse("新建任务 论文阅读 一小时三十分钟", context) as Interpretation.Parsed
        assertEquals(AssistantCommand.CreateTask("论文阅读", 90), result.command)
    }
    @Test fun `one and half Chinese hour has exact ninety minute duration`() {
        val result = parser.parse("新建任务 阅读 一个半小时", context) as Interpretation.Parsed
        assertEquals(AssistantCommand.CreateTask("阅读", 90), result.command)
    }
    @Test fun `conflicting and decimal durations are clarified without truncating task name`() {
        assertTrue(parser.parse("新建任务 阅读 1.5小时", context) is Interpretation.Clarify)
        assertTrue(parser.parse("新建任务 阅读 半小时 45分钟", context) is Interpretation.Clarify)
        assertTrue(parser.parse("安排 明天 19:00 到 20:00 阅读 半小时", context) is Interpretation.Clarify)
    }
    @Test fun `plan dates and explicit clock preserve timezone`() {
        val result = parser.parse("安排 明天 19:00 到 20:30 论文阅读", context) as Interpretation.Parsed
        val command = result.command as AssistantCommand.CreatePlan
        assertEquals("论文阅读", command.taskTitle)
        assertEquals(Instant.parse("2026-10-21T11:00:00Z").toEpochMilli(), command.startAt)
        assertEquals(90 * 60000L, command.endAt - command.startAt)
    }
    @Test fun `weekday uses selected week and duration understands half hour`() {
        val result = parser.parse("安排 周五 19:00 论文阅读 半小时", context) as Interpretation.Parsed
        val command = result.command as AssistantCommand.CreatePlan
        assertEquals(Instant.parse("2026-10-23T11:00:00Z").toEpochMilli(), command.startAt)
        assertEquals(30 * 60000L, command.endAt - command.startAt)
    }
    @Test fun `missing date and conflicting intervals require clarification`() {
        assertTrue(parser.parse("安排 19:00 到 20:00 论文阅读", context) is Interpretation.Clarify)
        assertTrue(parser.parse("安排 明天 20:00 到 19:00 论文阅读", context) is Interpretation.Clarify)
    }
    @Test fun `night twelve is ambiguous between calendar dates and requires clarification`() {
        assertTrue(parser.parse("安排 明天 晚上十二点 阅读 半小时", context) is Interpretation.Clarify)
        assertTrue(parser.parse("安排 明天 晚上12:00 阅读 半小时", context) is Interpretation.Clarify)
    }
    @Test fun `record preview shows exact notes even when linked task has another title`() {
        val linked = context.copy(snapshot = LocalSnapshot(tasks = listOf(TaskEntity("t", "课程学习")), loading = false))
        val reply = AssistantPlanner().prepare(AssistantCommand.ManualRecord(taskId = "t", label = "复习第三章并整理错题", startAt = now.minusSeconds(3600).toEpochMilli(), endAt = now.toEpochMilli()), linked) as AssistantReply.Preview
        val record = (reply.value.mutation as AssistantMutation.SaveRecord).record
        assertTrue(reply.value.after.contains("课程学习"))
        assertTrue(reply.value.after.contains(record.notes))
        assertEquals("复习第三章并整理错题", record.notes)
    }
    @Test fun `multiple operations and vague commands never silently execute`() {
        assertTrue(parser.parse("新建任务 阅读 并开始计时", context) is Interpretation.Clarify)
        assertTrue(parser.parse("帮我把事情都安排好", context) is Interpretation.Unsupported)
    }
    @Test fun `duplicate task names ask for identity`() {
        val duplicate = context.copy(snapshot = LocalSnapshot(tasks = listOf(TaskEntity("a", "阅读"), TaskEntity("b", "阅读")), loading = false))
        val reply = AssistantPlanner().prepare(AssistantCommand.StartTimer(taskTitle = "阅读"), duplicate)
        assertTrue(reply is AssistantReply.Clarification)
    }
    @Test fun `plan for absent task does not invent or partially create a task`() {
        val reply = AssistantPlanner().prepare(AssistantCommand.CreatePlan(taskTitle = "阅读", startAt = now.toEpochMilli(), endAt = now.plusSeconds(1800).toEpochMilli()), context)
        assertTrue(reply is AssistantReply.Clarification)
        assertTrue((reply as AssistantReply.Clarification).text.contains("先新建任务"))
    }
    @Test fun `free time excludes both courses and planned tasks`() {
        val start = LocalDate.of(2026, 10, 21).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val snapshot = LocalSnapshot(tasks = listOf(TaskEntity("t", "阅读")), plans = listOf(ScheduleBlockEntity("p", "t", start, start + 60 * 60000)),
            courses = listOf(CourseEntity("c", "数学", "", "", "term", "source", "key", 0, 0)),
            occurrences = listOf(CourseOccurrenceEntity("o", "c", "2026-10-21", start + 60 * 60000, start + 120 * 60000, "key")), loading = false)
        val reply = AssistantPlanner().prepare(AssistantCommand.Query(QueryScope.FREE_TIME, LocalDate.of(2026, 10, 21), 30), context.copy(snapshot = snapshot)) as AssistantReply.Answer
        assertEquals(listOf("2026-10-21 10:00–22:00"), reply.text.lines().drop(1))
    }
    @Test fun `manual future actual needs additional explicit approval`() {
        val reply = AssistantPlanner().prepare(AssistantCommand.ManualRecord(label = "阅读", startAt = now.toEpochMilli(), endAt = now.plusSeconds(1800).toEpochMilli()), context) as AssistantReply.Preview
        assertTrue(reply.value.needsFutureApproval)
    }
}
