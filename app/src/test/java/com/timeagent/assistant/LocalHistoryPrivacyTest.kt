package com.timeagent.assistant

import com.timeagent.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class LocalHistoryPrivacyTest {
    @Test fun `local private answers and memory never enter cloud request`() {
        val now = Instant.parse("2026-10-26T02:00:00Z")
        val data = LocalSnapshot(loading = false, memories = listOf(PersonalMemoryEntity("private", "FACT", "PRIVATE_MEMORY_BODY")))
        val context = AssistantContext(now, ZoneId.of("Asia/Shanghai"), LocalDate.of(2026, 10, 26), data)
        val turns = listOf(ConversationTurn("assistant", "PRIVATE_LOCAL_HISTORY", localOnly = true), ConversationTurn("user", "正常公开表达"))
        val request = DeepSeekProtocol.request("看看今天安排", context, DeepSeekProtocol.DEFAULT_MODEL, turns)
        assertFalse(request.contains("PRIVATE_LOCAL_HISTORY"))
        assertFalse(request.contains("PRIVATE_MEMORY_BODY"))
        assertTrue(request.contains("正常公开表达"))
    }
}
