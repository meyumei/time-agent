package com.timeagent.assistant

import com.timeagent.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

/** Local fallback coverage; no external model was used to produce these expectations. */
class ConversationInterpreterTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 10, 21, 10, 0).atZone(zone).toInstant()
    private val context = AssistantContext(now, zone, LocalDate.of(2026, 10, 19), LocalSnapshot(loading = false))
    private val interpreter = ConversationInterpreter()
    private fun instant(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun command(text: String, current: AssistantContext = context): AssistantCommand {
        val result = interpreter.parse(text, current)
        assertTrue("$text: $result", result is Interpretation.Parsed)
        return (result as Interpretation.Parsed).command
    }

    @Test fun `spontaneous inspiration becomes an unscheduled idea with original content`() {
        val text = "灵感来了想做一个番茄钟小程序"
        assertEquals(AssistantCommand.CreateIdea("做一个番茄钟小程序", text), command(text))
    }

    @Test fun `weekend wish without a decided time stays an idea`() {
        val text = "记个想法周末想去骑车但还没定时间"
        assertEquals(AssistantCommand.CreateIdea("周末想去骑车", text), command(text))
    }

    @Test fun `free time wish does not become a free time query or a one hour plan`() {
        val text = "有空想看三体"
        assertEquals(AssistantCommand.CreateIdea("看三体", text), command(text))
        assertEquals(AssistantCommand.Query(QueryScope.FREE_TIME, LocalDate.of(2026, 10, 22)), command("明天有什么空闲"))
    }

    @Test fun `a title alone is not silently turned into an idea`() {
        assertFalse(interpreter.parse("骑车", context) is Interpretation.Parsed)
        assertTrue(interpreter.parse("记个想法", context) is Interpretation.Clarify)
    }

    @Test fun `one explicit future point creates a reminder`() {
        assertEquals(AssistantCommand.CreateReminder("健身", instant(22, 15)), command("提醒明天下午三点健身"))
        assertEquals(AssistantCommand.CreateReminder("阅读", instant(22, 19)), command("安排明天19点阅读"))
    }

    @Test fun `stacked spoken request prefixes and filler words preserve the activity`() {
        assertEquals(AssistantCommand.CreateReminder("阅读", instant(22, 19, 30)), command("那个，能不能麻烦你帮我安排一下明天晚上七点半阅读"))
        assertEquals(AssistantCommand.CreateReminder("健身", instant(22, 15)), command("给我提醒一下明天下午三点健身"))
    }

    @Test fun `reminder may follow a clear date and time phrase`() {
        assertEquals(AssistantCommand.CreateReminder("健身", instant(22, 15)), command("明天下午三点提醒我健身"))
    }

    @Test fun `a point without a date uses its next occurrence`() {
        assertEquals(AssistantCommand.CreateReminder("喝水", instant(21, 15)), command("提醒我下午三点喝水"))
        assertEquals(AssistantCommand.CreateReminder("喝水", instant(22, 8)), command("提醒早上八点喝水"))
        val later = context.copy(now = LocalDateTime.of(2026, 10, 21, 16, 0).atZone(zone).toInstant())
        assertEquals(AssistantCommand.CreateReminder("喝水", instant(22, 15)), command("提醒下午三点喝水", later))
    }

    @Test fun `bare weekday reminder uses the next weekday from now instead of the selected calendar week`() {
        val oldWeek = context.copy(selectedWeekStart = LocalDate.of(2026, 10, 5))
        assertEquals(AssistantCommand.CreateReminder("开会", instant(26, 9)), command("提醒星期一早上九点开会", oldWeek))
        assertEquals(AssistantCommand.CreateReminder("开会", instant(23, 15)), command("提醒礼拜五下午三点开会", oldWeek))
    }

    @Test fun `spoken quarter hours and short tomorrow morning are explicit clock times`() {
        assertEquals(AssistantCommand.CreateReminder("健身", instant(22, 15, 15)), command("提醒明天下午三点一刻健身"))
        assertEquals(AssistantCommand.CreateReminder("阅读", instant(22, 19, 45)), command("安排明天晚上七点三刻阅读"))
        assertEquals(AssistantCommand.CreateReminder("吃药", instant(22, 8)), command("提醒明早八点吃药"))
    }

    @Test fun `vague time past dates and ambiguous midnight require clarification`() {
        for (text in listOf("提醒明天下午健身", "提醒周末去骑车", "安排晚点阅读", "提醒昨天三点健身", "提醒今天早上八点健身", "提醒明天晚上十二点健身", "提醒明天25点健身", "提醒明天3:70健身", "提醒2026-10-40下午三点健身"))
            assertTrue("$text: ${interpreter.parse(text, context)}", interpreter.parse(text, context) is Interpretation.Clarify)
    }

    @Test fun `homophone clock errors are never silently executed`() {
        assertFalse(interpreter.parse("提醒明天下午三店健身", context) is Interpretation.Parsed)
    }

    @Test fun `a stated range remains an event and a stated duration remains an explicit interval`() {
        assertEquals(AssistantCommand.CreateEvent("健身", instant(22, 19), instant(22, 20)), command("安排明天19点到20点健身"))
        assertEquals(AssistantCommand.CreateEvent("阅读", instant(22, 19), instant(22, 19, 30)), command("安排明天19点阅读半小时"))
    }

    @Test fun `negated requests questions and two independent actions never write`() {
        for (text in listOf("不用提醒明天下午三点健身", "我不想记录睡眠一点半到八点半", "先别帮我安排明天19点阅读", "提醒明天下午三点健身了吗", "提醒明天下午三点健身，然后安排晚上七点阅读"))
            assertFalse(text, interpreter.parse(text, context) is Interpretation.Parsed)
    }

    @Test fun `reminder activity keeps name characters and internal courtesy words`() {
        assertEquals(AssistantCommand.CreateReminder("和陈到讨论方案", instant(22, 15)), command("帮我提醒明天下午三点和陈到讨论方案"))
        assertEquals(AssistantCommand.CreateReminder("请陈到家里喝茶", instant(22, 15)), command("提醒明天下午三点请陈到家里喝茶"))
    }

    @Test fun `sleep inference and record reference still use the saved record`() {
        val record = TimeRecordEntity("record", null, instant(21, 1, 30), instant(21, 8, 30), notes = "睡眠")
        val current = context.copy(snapshot = LocalSnapshot(records = listOf(record), loading = false))
        interpreter.lastMutation = AssistantMutation.SaveRecord(record)
        assertEquals(AssistantCommand.EditRecord("record", instant(21, 2), instant(21, 9)), command("把刚才睡眠改成两点到九点", current))
        assertEquals(AssistantCommand.DeleteRecord("record"), command("删除刚才的记录", current))
        assertEquals(AssistantCommand.ManualRecord(label = "睡眠", startAt = instant(21, 1, 30), endAt = instant(21, 8, 30)), command("麻烦帮我记录一下而睡眠一点半到八点八点半"))
    }
}
