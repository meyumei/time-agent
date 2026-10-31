package com.timeagent.assistant

import com.timeagent.data.LocalSnapshot
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class LanguageSafetyRegressionTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 10, 21, 10, 0).atZone(zone).toInstant()
    private val context = AssistantContext(now, zone, LocalDate.of(2026, 10, 19), LocalSnapshot(loading = false))
    private val interpreter = ConversationInterpreter()

    private fun instant(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun record(text: String): AssistantCommand.ManualRecord {
        val result = NaturalRecordParser.parse(text, context)
        assertTrue("$text: $result", result is Interpretation.Parsed)
        return (result as Interpretation.Parsed).command as AssistantCommand.ManualRecord
    }

    @Test fun `preview request is not an immediate write`() {
        for (text in listOf("记录今天八点到九点阅读，先预览", "安排明天19点到20点健身先预览")) {
            assertTrue(text, ConversationIntentGuard.preventsWrite(text))
            val result = interpreter.parse(text, context)
            assertFalse("$text: $result", result is Interpretation.Parsed)
        }
    }

    @Test fun `record without a date prefers the latest finished interval and keeps a name containing 明天`() {
        val result = interpreter.parse("记录八点到九点和张明天讨论方案", context)
        assertTrue("$result", result is Interpretation.Parsed)
        val command = (result as Interpretation.Parsed).command as AssistantCommand.ManualRecord
        assertEquals("和张明天讨论方案", command.label)
        assertEquals(instant(21, 8), command.startAt)
        assertEquals(instant(21, 9), command.endAt)
    }

    @Test fun `only the connector between the two clocks is grammar`() {
        assertEquals("到书店买书", record("记录今天八点到九点到书店买书").label)
        assertEquals("从零开始学Kotlin", record("记录今天八点到九点从零开始学Kotlin").label)
    }

    @Test fun `a title merely containing 睡眠 is not replaced by a sleep record`() {
        val command = record("记录今天八点到九点阅读睡眠论文")
        assertEquals("阅读睡眠论文", command.label)
        assertEquals(3600000L, command.endAt - command.startAt)
    }

    @Test fun `a wish whose title contains 明天 is still an idea`() {
        val text = "有空想看《明天也要在一起》"
        val result = interpreter.parse(text, context)
        assertTrue("$result", result is Interpretation.Parsed)
        val command = (result as Interpretation.Parsed).command as AssistantCommand.CreateIdea
        assertEquals("看《明天也要在一起》", command.title)
        assertEquals(text, command.content)
    }

    @Test fun `明晚八点 is the evening of the next day`() {
        val result = interpreter.parse("提醒明晚八点和陈到见面", context)
        assertTrue("$result", result is Interpretation.Parsed)
        val command = (result as Interpretation.Parsed).command as AssistantCommand.CreateReminder
        assertEquals("和陈到见面", command.title)
        assertEquals(instant(22, 20), command.at)
    }

    @Test fun `the first word of a reminder body is not a polite filler`() {
        for ((text, title) in listOf(
            "提醒明天下午三点下楼拿快递" to "下楼拿快递",
            "提醒明天下午三点我和陈到见面" to "我和陈到见面"
        )) {
            val result = interpreter.parse(text, context)
            assertTrue("$text: $result", result is Interpretation.Parsed)
            val command = (result as Interpretation.Parsed).command as AssistantCommand.CreateReminder
            assertEquals(text, title, command.title)
            assertEquals(text, instant(22, 15), command.at)
        }
    }
}
