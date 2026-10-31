package com.timeagent.assistant

import com.timeagent.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class AssistantProtocolTest {
    private val context = AssistantContext(Instant.parse("2026-10-20T08:00:00Z"), ZoneId.of("Asia/Shanghai"), LocalDate.of(2026, 10, 19),
        LocalSnapshot(tasks = listOf(TaskEntity("t", "阅读", description = "private description")), records = listOf(TimeRecordEntity("r", "t", 0, 1000, notes = "private notes")),
            history = listOf(OperationEntity(actionType = "private history", entityType = "TASK", entityId = "t", beforeSnapshot = null, afterSnapshot = "secret", createdAt = 0)), loading = false))

    @Test fun `schema accepts only known typed single operation`() {
        val command = CloudCommandCodec.decode("""{"action":"create_task","title":"阅读","minutes":45}""") as Interpretation.Parsed
        assertEquals(AssistantCommand.CreateTask("阅读", 45), command.command)
        assertTrue(CloudCommandCodec.decode("""{"action":"shell","code":"anything"}""") is Interpretation.Clarify)
        assertTrue(CloudCommandCodec.decode("""{"action":"create_task","title":"阅读","minutes":-2}""") is Interpretation.Clarify)
        assertTrue(CloudCommandCodec.decode("""{"action":"create_task","title":"阅读","minutes":"30"}""") is Interpretation.Clarify)
        assertTrue(CloudCommandCodec.decode("""{"action":"create_task","title":"阅读","minutes":30,"execute":true}""") is Interpretation.Clarify)
        assertTrue(CloudCommandCodec.decode("""{"action":"create_task","title":"阅读","minutes":30.5}""") is Interpretation.Clarify)
    }
    @Test fun `cloud record preserves an activity name and separate study notes`() {
        val result = CloudCommandCodec.decode("""{"action":"record","label":"学习","notes":"数字信号处理","startAt":"2026-10-22T09:00:00+08:00","endAt":"2026-10-22T12:00:00+08:00"}""")
        assertTrue(result.toString(), result is Interpretation.Parsed)
        val command = (result as Interpretation.Parsed).command as AssistantCommand.ManualRecord
        assertEquals("学习", command.label)
        assertEquals("数字信号处理", command.notes)
        assertTrue(CloudCommandCodec.decode("""{"action":"record","label":"学习","notes":42,"startAt":"2026-10-22T09:00:00+08:00","endAt":"2026-10-22T12:00:00+08:00"}""") is Interpretation.Clarify)
    }
    @Test fun `cloud can return a record correction and a subsequent record together`() {
        val result = CloudCommandCodec.decode("""{"action":"batch","commands":[{"action":"edit_record","recordId":"training","startAt":"2026-10-27T19:00:00+08:00","endAt":"2026-10-27T20:30:00+08:00"},{"action":"record","label":"科研","notes":"配置clash和easy connect以及理解rax实验，继续跑robot rsi实验","startAt":"2026-10-27T20:50:00+08:00","endAt":"2026-10-27T23:30:00+08:00"}]}""")
        val commands = ((result as Interpretation.Parsed).command as AssistantCommand.Batch).commands
        assertEquals("training", (commands[0] as AssistantCommand.EditRecord).recordId)
        assertEquals("科研", (commands[1] as AssistantCommand.ManualRecord).label)
        assertEquals("配置clash和easy connect以及理解rax实验，继续跑robot rsi实验", (commands[1] as AssistantCommand.ManualRecord).notes)
    }
    @Test fun `malformed truncated null and multi-action output is rejected`() {
        listOf("", "not json", "null", "{}", "{\"action\":", "[]", """{"actions":[{"action":"stop_timer"}]}""").forEach {
            assertTrue(it, CloudCommandCodec.decode(it) is Interpretation.Clarify)
        }
    }
    @Test fun `missing and invented task identifiers cannot stage writes`() {
        val parsed = CloudCommandCodec.decode("""{"action":"start_timer","taskId":"invented"}""") as Interpretation.Parsed
        assertTrue(AssistantPlanner().prepare(parsed.command, context) is AssistantReply.Clarification)
    }
    @Test fun `cloud payload contains bounded inventory without notes descriptions or history`() {
        val payload = DeepSeekProtocol.request("帮我安排阅读", context, "deepseek-flash")
        assertTrue(payload.contains("阅读"))
        assertTrue(payload.contains("json_object"))
        assertFalse(payload.contains("private description"))
        assertFalse(payload.contains("private notes"))
        assertFalse(payload.contains("private history"))
        assertFalse(payload.contains("secret"))
    }
    @Test fun `transport failure never reports or stages success`() = runBlocking {
        val client = DeepSeekClient(AssistantTransport { _, _ -> throw java.io.IOException("network secret") })
        val result = client.interpret("自由表达", context, "test-key", "deepseek-flash")
        assertTrue(result is CloudResult.Failure)
        assertFalse((result as CloudResult.Failure).message.contains("secret"))
    }
    @Test fun `fake response is validated and refusal does not become a mutation`() = runBlocking {
        val response = """{"choices":[{"finish_reason":"stop","message":{"content":"{\"action\":\"clarify\",\"question\":\"请说明具体时间\"}"}}]}"""
        val client = DeepSeekClient(AssistantTransport { _, _ -> response })
        val result = client.interpret("自由表达", context, "test-key", "deepseek-flash") as CloudResult.Success
        assertTrue(result.interpretation is Interpretation.Clarify)
    }
    @Test fun `missing key avoids transport and truncated response is rejected`() = runBlocking {
        var calls = 0
        val client = DeepSeekClient(AssistantTransport { _, _ -> calls++; """{"choices":[{"finish_reason":"length","message":{"content":"{}"}}]}""" })
        assertTrue(client.interpret("text", context, "", "deepseek-flash") is CloudResult.Failure)
        assertEquals(0, calls)
        assertTrue(client.interpret("text", context, "test-key", "deepseek-flash") is CloudResult.Failure)
    }
}
