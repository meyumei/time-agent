package com.timeagent.assistant

import com.timeagent.data.*
import com.timeagent.ui.timeline
import org.junit.Assert.*
import org.junit.Test
import java.time.*

/** Spoken examples use the device's current date, even while an older week is visible. */
class VoiceUnderstandingRegressionTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val context = AssistantContext(LocalDateTime.of(2026, 10, 23, 10, 0).atZone(zone).toInstant(), zone,
        LocalDate.of(2026, 10, 5), LocalSnapshot(categories = listOf(CategoryEntity("study", "学习")), loading = false))
    private val interpreter = ConversationInterpreter()
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun command(text: String): AssistantCommand {
        val result = interpreter.parse(text, context)
        assertTrue("$text: $result", result is Interpretation.Parsed)
        return (result as Interpretation.Parsed).command
    }

    @Test fun `user study narration becomes yesterday morning with a separate activity and subject`() {
        for (text in listOf("昨天上午9点到中午12点进行学习数字信号处理。", "帮我记录昨天上午九点到中午十二点学习数字信号处理",
            "我昨天从上午九点到中午十二点进行了学习数字信号处理。")) {
            val value = command(text) as AssistantCommand.ManualRecord
            assertEquals(text, "学习", value.label)
            assertEquals(text, at(22, 9), value.startAt)
            assertEquals(text, at(22, 12), value.endAt)
            val preview = AssistantPlanner().prepare(value, context) as AssistantReply.Preview
            val record = (preview.value.mutation as AssistantMutation.SaveRecord).record
            assertEquals(text, "数字信号处理", record.notes)
            assertEquals(text, "study", record.categoryId)
            assertEquals(text, "学习", context.snapshot.copy(records = listOf(record)).timeline(context.now).single().title)
            assertTrue(preview.value.after.contains("备注：数字信号处理"))
        }
    }

    @Test fun `last nights two am sleep belongs to this morning regardless of word order`() {
        for (text in listOf("昨晚睡眠两点到9点", "记录昨晚睡眠两点到9点", "记录睡眠昨晚两点到9点", "昨晚两点到早上九点睡觉",
            "记录昨天晚上两点到今天早上九点睡眠")) {
            val value = command(text) as AssistantCommand.ManualRecord
            assertEquals(text, "睡眠", value.label)
            assertEquals(text, at(23, 2), value.startAt)
            assertEquals(text, at(23, 9), value.endAt)
        }
    }

    @Test fun `last nights eleven pm sleep still starts yesterday`() {
        val value = command("记录昨晚十一点到早上九点睡眠") as AssistantCommand.ManualRecord
        assertEquals(at(22, 23), value.startAt)
        assertEquals(at(23, 9), value.endAt)
    }

    @Test fun `afternoon qualifier carries through an abbreviated record range`() {
        val value = command("记录昨天下午三点到五点学习数字信号处理") as AssistantCommand.ManualRecord
        assertEquals(at(22, 15), value.startAt)
        assertEquals(at(22, 17), value.endAt)
    }

    @Test fun `afternoon qualifier carries through an abbreviated plan range`() {
        assertEquals(AssistantCommand.CreateEvent("复习", at(24, 15), at(24, 17)), command("安排明天下午三点到五点复习"))
        assertEquals(AssistantCommand.CreateEvent("阅读", at(24, 19), at(24, 21)), command("安排明晚七点到九点阅读"))
    }

    @Test fun `natural plan weekday uses the real current week`() {
        assertEquals(AssistantCommand.CreateEvent("学习", at(26, 9), at(26, 11)), command("安排下周一上午九点到十一点学习"))
    }

    @Test fun `last week record resolves weekday against real time`() {
        val value = command("记录上周五上午九点到十一点学习数字信号处理") as AssistantCommand.ManualRecord
        assertEquals(at(16, 9), value.startAt)
        assertEquals(at(16, 11), value.endAt)
    }

    @Test fun `spoken month day zero minutes and quarters are exact`() {
        val value = command("记录十月二十二号上午九点零五分到十点一刻学习数字信号处理") as AssistantCommand.ManualRecord
        assertEquals(at(22, 9, 5), value.startAt)
        assertEquals(at(22, 10, 15), value.endAt)
        assertEquals(AssistantCommand.CreateReminder("交报告", at(24, 15)), command("提醒十月二十四号下午三点交报告"))
    }

    @Test fun `relative reminder duration is measured from now instead of becoming a clock or a plan`() {
        assertEquals(AssistantCommand.CreateReminder("喝水", at(23, 10, 30)), command("提醒半小时后喝水"))
        assertEquals(AssistantCommand.CreateReminder("交报告", at(23, 11, 30)), command("提醒我一个半小时后交报告"))
        assertEquals(AssistantCommand.CreateReminder("喝水", at(23, 10, 20)), command("二十分钟后提醒我喝水"))
    }

    @Test fun `time first reminder removes only the request frame`() {
        assertEquals(AssistantCommand.CreateReminder("下楼拿快递", at(24, 15)), command("明天下午三点提醒我下楼拿快递"))
    }

    @Test fun `record with a matching stated duration does not put that duration into activity notes`() {
        val value = command("记录昨天九点到十二点学习数字信号处理三小时") as AssistantCommand.ManualRecord
        val record = ((AssistantPlanner().prepare(value, context) as AssistantReply.Preview).value.mutation as AssistantMutation.SaveRecord).record
        assertEquals("学习", value.label)
        assertEquals("数字信号处理", record.notes)
    }

    @Test fun `a date mentioned in study material stays in the notes and never changes the interval`() {
        val value = command("记录昨天九点到十二点学习十月二十三号考试内容") as AssistantCommand.ManualRecord
        assertEquals(at(22, 9), value.startAt)
        assertEquals(at(22, 12), value.endAt)
        val record = ((AssistantPlanner().prepare(value, context) as AssistantReply.Preview).value.mutation as AssistantMutation.SaveRecord).record
        assertEquals("十月二十三号考试内容", record.notes)
    }

    @Test fun `explicit dawn overrides night context for a study session too`() {
        val value = command("记录昨晚凌晨两点到三点学习数字信号处理") as AssistantCommand.ManualRecord
        assertEquals(at(23, 2), value.startAt)
        assertEquals(at(23, 3), value.endAt)
    }

    @Test fun `an explicit new end date prevents inherited evening hours`() {
        val value = command("记录前天晚上七点到昨天九点睡眠") as AssistantCommand.ManualRecord
        assertEquals(at(21, 19), value.startAt)
        assertEquals(at(22, 9), value.endAt)
    }

    @Test fun `narration punctuation questions why questions and unperformed activities never write`() {
        for (text in listOf("昨天上午九点到十二点学习数字信号处理？", "昨天上午九点到十二点为什么没学习？",
            "昨天上午九点到十二点为什么没学习", "昨天上午九点到十二点没有学习", "昨天上午九点到十二点没学习数字信号处理",
            "昨天九点到十二点我没有学习", "昨天九点到十二点我为什么没学习"))
            assertFalse("$text: ${interpreter.parse(text, context)}", interpreter.parse(text, context) is Interpretation.Parsed)
    }

    @Test fun `question punctuation still permits read only queries`() {
        assertEquals(AssistantCommand.Query(QueryScope.TOMORROW), command("明天有什么安排？"))
        assertEquals(AssistantCommand.Query(QueryScope.TASKS), command("查看任务？"))
    }

    @Test fun `explicit noon after last nights start is never mistaken for midnight`() {
        val value = command("记录昨晚十一点到今天中午十二点学习数字信号处理") as AssistantCommand.ManualRecord
        assertEquals(at(22, 23), value.startAt)
        assertEquals(at(23, 12), value.endAt)
    }

    @Test fun `location and activity words beginning with zai keep their first character`() {
        for (label in listOf("在家学习数字信号处理", "在职考研"))
            assertEquals(label, (command("记录昨天九点到十二点$label") as AssistantCommand.ManualRecord).label)
    }

    @Test fun `edit preview includes the changed activity and preserved subject notes`() {
        val record = TimeRecordEntity("study", null, at(22, 9), at(22, 12), title = "学习", notes = "数字信号处理")
        val current = context.copy(snapshot = context.snapshot.copy(records = listOf(record)))
        val preview = (AssistantPlanner().prepare(AssistantCommand.EditRecord("study", label = "复习"), current) as AssistantReply.Preview).value
        assertTrue(preview.before.contains("学习"))
        assertTrue(preview.after.contains("复习"))
        assertTrue(preview.after.contains("数字信号处理"))
    }

    @Test fun `narration questions refusals future intentions and invalid clocks do not create records`() {
        for (text in listOf("昨天上午九点到中午十二点学习了吗？", "不要记录昨晚睡眠两点到九点", "明天上午九点到十二点想学习数字信号处理",
            "记录昨天9:70到12:00学习", "记录昨天25点到十二点学习", "记录二月三十号九点到十二点学习", "记录昨天九点到十二点学习半小时"))
            assertFalse("$text: ${interpreter.parse(text, context)}", interpreter.parse(text, context) is Interpretation.Parsed)
    }
}
