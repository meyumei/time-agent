package com.timeagent.assistant

import com.timeagent.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class AssistantConfirmationTest {
    private val now = Instant.parse("2026-10-20T08:00:00Z")
    private val context = AssistantContext(now, ZoneId.of("Asia/Shanghai"), LocalDate.of(2026, 10, 19), LocalSnapshot(loading = false))
    private fun taskPreview() = (AssistantPlanner().prepare(AssistantCommand.CreateTask("阅读", 30), context) as AssistantReply.Preview).value

    @Test fun `preview and cancel have no write effect`() = runBlocking {
        var writes = 0
        val gate = AssistantConfirmation(AssistantWriter { _, _ -> writes++ })
        val preview = taskPreview()
        gate.stage(preview)
        assertEquals(0, writes)
        gate.cancel()
        assertTrue(gate.confirm(preview.id) is CommitResult.Rejected)
        assertEquals(0, writes)
    }
    @Test fun `repeat confirm writes once`() = runBlocking {
        var writes = 0
        val gate = AssistantConfirmation(AssistantWriter { _, _ -> writes++ })
        val preview = taskPreview()
        gate.stage(preview)
        assertTrue(gate.confirm(preview.id) is CommitResult.Saved)
        assertTrue(gate.confirm(preview.id) is CommitResult.AlreadySaved)
        assertEquals(1, writes)
    }
    @Test fun `stale expected version is carried to writer and refused`() = runBlocking {
        val original = TaskEntity("t", "阅读", version = 7)
        val reply = AssistantPlanner().prepare(AssistantCommand.CancelTask(taskId = "t"), context.copy(snapshot = LocalSnapshot(tasks = listOf(original), loading = false))) as AssistantReply.Preview
        val mutation = reply.value.mutation as AssistantMutation.SaveTask
        assertEquals(7L, mutation.expectedVersion)
        val gate = AssistantConfirmation(AssistantWriter { _, _ -> throw StaleChange() })
        gate.stage(reply.value)
        assertTrue(gate.confirm(reply.value.id) is CommitResult.Rejected)
    }
    @Test fun `future actual cannot save without future approval`() = runBlocking {
        var writes = 0
        val gate = AssistantConfirmation(AssistantWriter { _, _ -> writes++ })
        val preview = (AssistantPlanner().prepare(AssistantCommand.ManualRecord(label = "阅读", startAt = now.toEpochMilli(), endAt = now.plusSeconds(1800).toEpochMilli()), context) as AssistantReply.Preview).value
        gate.stage(preview)
        assertTrue(gate.confirm(preview.id) is CommitResult.Rejected)
        assertEquals(0, writes)
        assertTrue(gate.confirm(preview.id, allowFuture = true) is CommitResult.Saved)
        assertEquals(1, writes)
    }
    @Test fun `save failures keep preview available and never fake success`() = runBlocking {
        var attempts = 0
        val gate = AssistantConfirmation(AssistantWriter { _, _ -> if (++attempts == 1) throw java.io.IOException("failure") })
        val preview = taskPreview()
        gate.stage(preview)
        assertTrue(gate.confirm(preview.id) is CommitResult.Rejected)
        assertTrue(gate.confirm(preview.id) is CommitResult.Saved)
        assertEquals(2, attempts)
    }
}
