package com.timeagent.assistant

import com.timeagent.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class AssistantInboxProtocolTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-21T02:00:00Z")
    private fun context(snapshot: LocalSnapshot = LocalSnapshot(loading = false)) =
        AssistantContext(now, zone, LocalDate.of(2026, 10, 19), snapshot)

    @Test fun `typed protocol decodes idea and point reminder`() {
        assertEquals(
            Interpretation.Parsed(AssistantCommand.CreateIdea("番茄钟", "想做一个番茄钟小程序")),
            CloudCommandCodec.decode("""{"action":"create_idea","title":"番茄钟","content":"想做一个番茄钟小程序"}"""),
        )
        assertEquals(
            Interpretation.Parsed(AssistantCommand.CreateIdea("番茄钟", "")),
            CloudCommandCodec.decode("""{"action":"create_idea","title":"番茄钟"}"""),
        )
        val at = Instant.parse("2026-10-22T07:00:00Z").toEpochMilli()
        assertEquals(
            Interpretation.Parsed(AssistantCommand.CreateReminder("健身", at)),
            CloudCommandCodec.decode("""{"action":"create_reminder","title":"健身","at":"2026-10-22T15:00:00+08:00"}"""),
        )
    }

    @Test fun `missing or unsafe reminder fields stay a clarification`() {
        listOf(
            """{"action":"create_reminder","title":"健身"}""",
            """{"action":"create_reminder","title":"健身","at":"明天下午三点"}""",
            """{"action":"create_reminder","title":"健身","at":"2026-10-22"}""",
            """{"action":"create_reminder","title":"健身","at":"2026-10-22T15:00:00+08:00","minutes":60}""",
            """{"action":"create_reminder","title":"","at":"2026-10-22T15:00:00+08:00"}""",
            """{"action":"create_idea","title":"","content":"想做一个番茄钟"}""",
            """{"action":"create_idea","title":"番茄钟","content":42}""",
            """{"action":"create_idea","title":"番茄钟","content":"想做一个番茄钟","at":"2026-10-22T15:00:00+08:00"}""",
        ).forEach { assertTrue(it, CloudCommandCodec.decode(it) is Interpretation.Clarify) }
    }

    @Test fun `system prompt advertises idea and point reminder actions`() {
        val payload = DeepSeekProtocol.request("帮我记住这件事", context(), "deepseek-flash")
        assertTrue(payload.contains("create_idea"))
        assertTrue(payload.contains("create_reminder"))
    }

    @Test fun `bounded context excludes idea content and note body`() {
        val ideaSentinel = "IDEA-BODY-SENTINEL-4f1c"
        val noteSentinel = "NOTE-BODY-SENTINEL-92ab"
        val snapshot = LocalSnapshot(
            tasks = listOf(TaskEntity("t", "阅读")),
            ideas = listOf(IdeaEntity("idea-1", "番茄钟提醒应用", ideaSentinel)),
            notes = listOf(NoteEntity("note-1", "TASK", "t", "阅读笔记", noteSentinel)),
            loading = false,
        )
        val json = DeepSeekProtocol.contextJson(context(snapshot))
        assertFalse(json.contains(ideaSentinel))
        assertFalse(json.contains(noteSentinel))
        val payload = DeepSeekProtocol.request("帮我看看", context(snapshot), "deepseek-flash")
        assertFalse(payload.contains(ideaSentinel))
        assertFalse(payload.contains(noteSentinel))
    }
}
