package com.timeagent.assistant

import com.timeagent.data.LocalSnapshot
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class SleepInferenceTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 10, 21, 10, 0).atZone(zone).toInstant()
    private val context = AssistantContext(now, zone, LocalDate.of(2026, 10, 19), LocalSnapshot(loading = false))
    private val parser = LocalAssistantParser()
    private fun instant(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test fun `morning sleep request infers most recent night and repairs repeated spoken end time`() {
        val interpreted = parser.parse("帮我记录而睡眠一点半到八点八点半", context)
        assertTrue(interpreted.toString(), interpreted is Interpretation.Parsed)
        val command = (interpreted as Interpretation.Parsed).command as AssistantCommand.ManualRecord
        assertEquals("睡眠", command.label)
        assertEquals(instant(21, 1, 30), command.startAt)
        assertEquals(instant(21, 8, 30), command.endAt)
        assertEquals(7 * 60 * 60000L, command.endAt - command.startAt)
    }
    @Test fun `last nights sleep crosses midnight without asking a date the user did not need to give`() {
        val interpreted = parser.parse("记录睡眠23:30到08:30", context)
        assertTrue(interpreted.toString(), interpreted is Interpretation.Parsed)
        val command = (interpreted as Interpretation.Parsed).command as AssistantCommand.ManualRecord
        assertEquals(instant(20, 23, 30), command.startAt)
        assertEquals(instant(21, 8, 30), command.endAt)
    }
    @Test fun `explicit date takes precedence over the usual most recent sleep inference`() {
        val interpreted = parser.parse("记录昨天01:30到08:30睡眠", context)
        assertTrue(interpreted.toString(), interpreted is Interpretation.Parsed)
        val command = (interpreted as Interpretation.Parsed).command as AssistantCommand.ManualRecord
        assertEquals(instant(20, 1, 30), command.startAt)
        assertEquals(instant(20, 8, 30), command.endAt)
    }
    @Test fun `negated and question requests never turn into sleep writes`() {
        for (input in listOf("不要记录睡眠一点半到八点半", "有没有记录睡眠一点半到八点半", "记录睡眠了吗", "记录今天八点到九点睡眠，不要保存", "记录今天八点到九点睡眠，别记录", "记录今天八点到九点睡眠了吗？")) {
            val command = (parser.parse(input, context) as? Interpretation.Parsed)?.command
            assertFalse(input, command is AssistantCommand.ManualRecord)
        }
    }
    @Test fun `night twelve means midnight sleep rather than twenty hours from noon`() {
        for (text in listOf("记录睡眠昨晚十二点到早上八点", "记录睡眠昨天晚上十二点到今天早上八点", "记录睡眠昨晚十二点到今天早上八点")) {
            val result = parser.parse(text, context) as Interpretation.Parsed
            val command = result.command as AssistantCommand.ManualRecord
            assertEquals(text, instant(21, 0), command.startAt)
            assertEquals(text, instant(21, 8), command.endAt)
        }
    }
}
