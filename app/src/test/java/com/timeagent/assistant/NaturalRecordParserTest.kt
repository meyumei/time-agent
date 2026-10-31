package com.timeagent.assistant

import com.timeagent.data.LocalSnapshot
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class NaturalRecordParserTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val context = AssistantContext(LocalDateTime.of(2026, 10, 21, 10, 0).atZone(zone).toInstant(), zone, LocalDate.of(2026, 10, 19), LocalSnapshot(loading = false))

    @Test fun `temporal separators are removed without deleting characters inside names or content`() {
        for (label in listOf("和陈到讨论方案", "和陈从讨论方案", "研究从入门到精通", "和张明天讨论方案")) {
            val result = NaturalRecordParser.parse("记录今天八点到九点$label", context)
            assertTrue("$label: $result", result is Interpretation.Parsed)
            val command = (result as Interpretation.Parsed).command as AssistantCommand.ManualRecord
            assertEquals(label, command.label)
            assertEquals(3600000L, command.endAt - command.startAt)
        }
    }

    @Test fun `record connector before dates does not leak into its label`() {
        val result = NaturalRecordParser.parse("记录从今天八点到九点阅读", context) as Interpretation.Parsed
        assertEquals("阅读", (result.command as AssistantCommand.ManualRecord).label)
    }
}
