package com.timeagent.fish

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.timeagent.assistant.*
import com.timeagent.data.*
import com.timeagent.domain.command.*
import com.timeagent.core.RecurringPattern
import com.timeagent.recurring.RecurringService
import com.google.gson.Gson
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.*

abstract class FishCommandRoutingTestContract {
    private val now = Instant.parse("2026-11-02T04:00:00Z")
    private val origin = Origin.FISH("synthetic-session", "assistant:7", "call-1")
    private fun scenario(block: suspend (TimeRepository, TimeAgentDatabase) -> Unit) = runBlocking {
        val db = TimeAgentDatabase.openInMemory(ApplicationProvider.getApplicationContext<Context>())
        try { block(TimeRepository(db, Clock.fixed(now, ZoneOffset.UTC)), db) } finally { db.close() }
    }

    @Test fun lostResponseRetryAfterReopeningBridgeCreatesOneTaskAndOneUndo() = scenario { repo, _ ->
        val args = JSONObject().put("title", "synthetic task")
        val first = FishTimeTools(repo).execute("time_create_task", args, origin)
        val retry = FishTimeTools(repo).execute("time_create_task", args, origin)
        assertEquals("APPLIED", first.getString("status"))
        assertEquals("DUPLICATE", retry.getString("status"))
        assertEquals(first.getString("id"), retry.getString("id"))
        assertEquals(1, repo.snapshot().tasks.size)
        assertEquals("FISH", repo.snapshot().tasks.single().source)
        assertEquals(1, repo.snapshot().history.size)
        assertEquals(1, first.getJSONArray("affected").length())
    }

    @Test fun concurrentRetriesAndDistinctCallsHaveDifferentEffects() = scenario { repo, _ ->
        coroutineScope {
            val results = List(2) { async { FishTimeTools(repo).execute("time_create_task", JSONObject().put("title", "same"), origin) } }.map { it.await() }
            assertEquals(setOf("APPLIED", "DUPLICATE"), results.map { it.getString("status") }.toSet())
        }
        FishTimeTools(repo).execute("time_create_task", JSONObject().put("title", "different"), origin.copy(toolCallId = "call-2"))
        assertEquals(2, repo.snapshot().tasks.size)
    }

    @Test fun retryAfterUndoDoesNotRecreateTheTaskOrUndoAnotherAction() = scenario { repo, _ ->
        val tools = FishTimeTools(repo)
        tools.execute("time_create_task", JSONObject().put("title", "one"), origin)
        val undo = origin.copy(toolCallId = "undo")
        tools.execute("time_undo", JSONObject(), undo)
        tools.execute("time_create_task", JSONObject().put("title", "two"), origin.copy(toolCallId = "two"))
        assertEquals("DUPLICATE", tools.execute("time_undo", JSONObject(), undo).getString("status"))
        assertEquals("DUPLICATE", tools.execute("time_create_task", JSONObject().put("title", "one"), origin).getString("status"))
        assertEquals(listOf("two"), repo.snapshot().tasks.map { it.title })
    }

    @Test fun failedProjectPlacementRollsBackTaskHistoryAndRequest() = scenario { repo, db ->
        val result = FishTimeTools(repo).execute("time_create_task", JSONObject().put("title", "one").put("projectId", "missing"), origin)
        assertEquals("INVALID", result.getString("status"))
        assertTrue(repo.snapshot().tasks.isEmpty())
        assertTrue(repo.snapshot().history.isEmpty())
        assertNull(db.dao().commandResult(result.getString("requestId")))
    }

    @Test fun scheduleCarriesFishProvenanceAndQueriesExposeVersionsWithoutWriting() = scenario { repo, db ->
        val tools = FishTimeTools(repo)
        val result = tools.execute("time_create_plan", JSONObject().put("title", "one").put("start", "2026-11-02T05:00:00Z").put("end", "2026-11-02T06:00:00Z"), origin)
        assertEquals("APPLIED", result.getString("status"))
        val plan = repo.snapshot().plans.single()
        assertEquals("FISH", plan.source)
        assertEquals("synthetic-session/assistant:7", plan.sourceRef)
        val history = repo.snapshot().history
        val query = tools.execute("time_today", JSONObject(), origin.copy(toolCallId = "query"))
        assertEquals(plan.version, query.getJSONArray("plans").getJSONObject(0).getLong("version"))
        assertEquals(history, repo.snapshot().history)
        assertNull(db.dao().commandResult(query.getString("requestId")))
    }

    @Test fun staleFishTaskVersionReturnsConflictAndCreatesNoActual() = scenario { repo, _ ->
        val task = repo.saveTask(TaskEntity("task", "one"))
        repo.saveTask(task.copy(title = "two"), task.version)
        val result = FishTimeTools(repo).execute("time_complete_task", JSONObject().put("taskId", task.id).put("expectedVersion", task.version), origin)
        assertEquals("CONFLICT", result.getString("status"))
        assertTrue(result.has("conflict"))
        assertEquals("TODO", repo.snapshot().tasks.single().status)
        assertTrue(repo.snapshot().records.isEmpty())
    }

    @Test fun localPreviewCommitAndMemoryUseDurableCommands() = scenario { repo, db ->
        val preview = AssistantPreview("local-preview", "idea", "", "saved", mutation = AssistantMutation.SaveIdea(IdeaEntity("idea", "synthetic")))
        RepositoryAssistantWriter(repo).commit(preview, false)
        RepositoryAssistantWriter(repo).commit(preview, false)
        assertNotNull(db.dao().commandResult(preview.id))
        assertEquals(1, repo.snapshot().ideas.size)
        assertEquals(1, repo.snapshot().history.size)
        repo.rememberExplicit("记住我喜欢晚上学习", "memory-request")
        assertNotNull(db.dao().commandResult("memory-request"))
    }

    @Test fun quickActionRetryUsesFirstResultAndDoesNotDuplicateIdea() = scenario { repo, _ ->
        val args = JSONObject().put("text", "记个想法周末想去骑车但还没定时间")
        val first = FishTimeTools(repo).execute("time_quick_action", args, origin)
        val retry = FishTimeTools(repo).execute("time_quick_action", args, origin)
        assertEquals("APPLIED", first.getString("status"))
        assertEquals("DUPLICATE", retry.getString("status"))
        assertEquals(1, repo.snapshot().ideas.size)
    }

    @Test fun reminderClaimRoutesThroughOneNotificationCommandAndNoUserUndo() = scenario { repo, db ->
        TimeRepository(db, Clock.fixed(now.minusSeconds(1), ZoneOffset.UTC))
            .saveReminder(ReminderEntity("reminder", "synthetic", now.toEpochMilli()))
        val history = repo.snapshot().history
        assertTrue(repo.markReminderDelivered("reminder", now.toEpochMilli()))
        assertFalse(repo.markReminderDelivered("reminder", now.toEpochMilli()))
        assertNotNull(db.dao().commandResult("reminder:reminder:${now.toEpochMilli()}"))
        assertEquals(history, repo.snapshot().history)
    }

    @Test fun completedRecurringRetryReplaysBeforeCheckingPendingState() = scenario { repo, _ ->
        val pattern = RecurringPattern("r", "DAILY", "2026-11-02", untilDate = "2026-11-02")
        RecurringService(repo).saveRule(RecurringRuleEntity("r", "synthetic", Gson().toJson(pattern)), null, LocalDate.parse("2026-11-02"), "seed")
        val instance = repo.snapshot().recurringInstances.single()
        val args = JSONObject().put("taskId", instance.taskId).put("expectedVersion", repo.snapshot().tasks.single().version)
        val first = FishTimeTools(repo).execute("time_complete_task", args, origin)
        val history = repo.snapshot().history
        assertEquals("APPLIED", first.getString("status"))
        assertEquals("DUPLICATE", FishTimeTools(repo).execute("time_complete_task", args, origin).getString("status"))
        assertEquals("COMPLETED", repo.snapshot().recurringInstances.single().status)
        assertTrue(repo.snapshot().records.isEmpty())
        assertEquals(history, repo.snapshot().history)
    }

    @Test fun quickTimerRetryAndStoppingPreserveFishSourceAndUndo() = scenario { repo, db ->
        repo.saveTask(TaskEntity("study", "学习"))
        val tools = FishTimeTools(repo)
        val start = tools.execute("time_quick_action", JSONObject().put("text", "开始计时 学习"), origin)
        assertEquals("APPLIED", start.getString("status"))
        assertEquals("DUPLICATE", tools.execute("time_quick_action", JSONObject().put("text", "开始计时 学习"), origin).getString("status"))
        assertEquals("FISH", repo.snapshot().records.single().source)
        val later = FishTimeTools(TimeRepository(db, Clock.fixed(now.plusSeconds(2), ZoneOffset.UTC)))
        val stop = later.execute("time_quick_action", JSONObject().put("text", "停止计时"), origin.copy(toolCallId = "stop"))
        assertEquals("APPLIED", stop.getString("status"))
        assertNotNull(repo.snapshot().records.single().endAt)
    }

    @Test fun pointAndAllDayKindsUseTheSameCommandAndKeepDateMeaning() = scenario { repo, _ ->
        val tools = FishTimeTools(repo)
        val point = tools.execute("time_create_plan", JSONObject().put("title", "point").put("kind", "POINT")
            .put("start", "2026-11-02T05:00:00Z").put("end", "2026-11-02T05:00:00Z"), origin)
        assertEquals("APPLIED", point.getString("status"))
        val day = tools.execute("time_create_plan", JSONObject().put("title", "day").put("kind", "ALL_DAY")
            .put("start", "2026-11-03T00:00:00Z").put("end", "2026-11-04T00:00:00Z"), origin.copy(toolCallId = "day"))
        assertEquals("APPLIED", day.getString("status"))
        val plan = repo.snapshot().plans.single { it.kind == "ALL_DAY" }
        assertEquals("2026-11-03", plan.allDayStartDate)
        assertEquals("2026-11-04", plan.allDayEndDate)
        assertEquals(Instant.parse("2026-11-05T00:00:00Z").toEpochMilli(), plan.endAt)
    }

    @Test fun rejectedQuickActionReturnsFailureWithoutClaimingApplied() = scenario { repo, _ ->
        val result = FishTimeTools(repo).execute("time_quick_action", JSONObject().put("text", "开始计时 不存在的任务"), origin)
        assertFalse(result.getString("status") in setOf("APPLIED", "DUPLICATE"))
        assertTrue(repo.snapshot().records.isEmpty())
    }

    @Test fun invalidReminderBroadcastDoesNotPoisonOuterTransaction() = scenario { repo, _ ->
        assertFalse(repo.markReminderDelivered("missing", now.toEpochMilli()))
        repo.saveTask(TaskEntity("task", "still usable"))
        assertEquals(1, repo.snapshot().tasks.size)
    }

    @Test fun productionToolsRequireObservedTaskVersionBeforeWriting() = scenario { repo, _ ->
        repo.saveTask(TaskEntity("task", "one"))
        val result = FishTimeTools(repo).execute("time_complete_task", JSONObject().put("taskId", "task"), origin)
        assertEquals("INVALID", result.getString("status"))
        assertEquals("TODO", repo.snapshot().tasks.single().status)
    }

    @Test fun quickQueryUsesAReadCommandAndClarificationIsInvalid() = scenario { repo, db ->
        repo.saveTask(TaskEntity("task", "学习"))
        val tools = FishTimeTools(repo)
        val history = repo.snapshot().history
        val query = tools.execute("time_quick_action", JSONObject().put("text", "查看任务"), origin)
        assertEquals("APPLIED", query.getString("status"))
        assertTrue(query.getString("message").contains("学习"))
        assertEquals(history, repo.snapshot().history)
        assertNull(db.dao().commandResult(query.getString("requestId")))
        val invalid = tools.execute("time_quick_action", JSONObject().put("text", "开始计时 不存在的任务"), origin.copy(toolCallId = "missing"))
        assertEquals("INVALID", invalid.getString("status"))
    }

    @Test fun largeDeletionRequiresAppConfirmationEvenWhenFishSaysConfirm() = scenario { repo, _ ->
        val context: Context = ApplicationProvider.getApplicationContext()
        val bundle = JsonCourseAdapter().parse(context.assets.open("course-import-example.json").bufferedReader().use { it.readText() })
        repo.commitImport(repo.previewImport(bundle))
        val before = repo.snapshot().occurrences
        val tools = FishTimeTools(repo)
        val pending = tools.execute("time_quick_action", JSONObject().put("text", "取消示例课程"), origin)
        assertEquals("NEEDS_CONFIRMATION", pending.getString("status"))
        val confirm = tools.execute("time_quick_action", JSONObject().put("text", "确认"), origin.copy(toolCallId = "confirm"))
        assertEquals("NEEDS_CONFIRMATION", confirm.getString("status"))
        assertTrue(confirm.getString("message").contains("App"))
        assertEquals(before, repo.snapshot().occurrences)
    }

    @Test fun staleQuickConfirmationRetainsStructuredConflictAfterRollback() = scenario { repo, _ ->
        val task = repo.saveTask(TaskEntity("task", "学习"))
        val tools = FishTimeTools(repo)
        val preview = tools.execute("time_quick_action", JSONObject().put("text", "先预览 取消任务 学习"), origin)
        assertEquals("NEEDS_CONFIRMATION", preview.getString("status"))
        repo.saveTask(task.copy(title = "改名"), task.version)
        val result = tools.execute("time_quick_action", JSONObject().put("text", "确认"), origin.copy(toolCallId = "confirm"))
        assertEquals("CONFLICT", result.getString("status"))
        assertEquals(task.version, result.getJSONObject("conflict").getLong("expectedVersion"))
        assertEquals("TODO", repo.snapshot().tasks.single().status)
    }

    @Test fun actualRetryPreservesOneRecordAndMalformedInputIsInvalid() = scenario { repo, _ ->
        val tools = FishTimeTools(repo)
        val args = JSONObject().put("title", "synthetic actual").put("start", "2026-11-02T01:00:00Z").put("end", "2026-11-02T02:00:00Z")
        assertEquals("APPLIED", tools.execute("time_record", args, origin).getString("status"))
        assertEquals("DUPLICATE", tools.execute("time_record", args, origin).getString("status"))
        assertEquals("FISH", repo.snapshot().records.single().source)
        assertEquals(1, repo.snapshot().history.size)
        val invalid = tools.execute("time_record", JSONObject().put("title", "bad").put("start", "no timezone").put("end", "bad"), origin.copy(toolCallId = "bad"))
        assertEquals("INVALID", invalid.getString("status"))
        val missing = tools.execute("time_create_task", JSONObject(), origin.copy(toolCallId = "missing"))
        assertEquals("INVALID", missing.getString("status"))
    }

    @Test fun linkedPlanRequiresTaskVersionAndMemoryRetryKeepsDestination() = scenario { repo, _ ->
        repo.saveTask(TaskEntity("task", "one"))
        val tools = FishTimeTools(repo)
        val args = JSONObject().put("taskId", "task").put("title", "one").put("start", "2026-11-02T05:00:00Z").put("end", "2026-11-02T06:00:00Z")
        assertEquals("INVALID", tools.execute("time_create_plan", args, origin).getString("status"))
        assertTrue(repo.snapshot().plans.isEmpty())
        val memoryOrigin = origin.copy(toolCallId = "memory")
        val memoryArgs = JSONObject().put("text", "记住我喜欢晚上学习")
        assertEquals("memory", tools.execute("time_quick_action", memoryArgs, memoryOrigin).getString("destination"))
        val retry = tools.execute("time_quick_action", memoryArgs, memoryOrigin)
        assertEquals("DUPLICATE", retry.getString("status"))
        assertEquals("memory", retry.getString("destination"))
        assertEquals(1, repo.snapshot().memories.size)
    }

    @Test fun recordQuestionUsesReadCommandWithoutWriting() = scenario { repo, _ ->
        val tools = FishTimeTools(repo)
        val query = tools.execute("time_quick_action", JSONObject().put("text", "记录今天八点到九点睡眠了吗？"), origin)
        assertEquals("APPLIED", query.getString("status"))
        assertEquals(0, query.getJSONArray("affected").length())
        assertTrue(repo.snapshot().records.isEmpty())
    }

    @Test fun sqlFailureIsFailedWithNoPartialWrites() = scenario { repo, db ->
        val tools = FishTimeTools(repo)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_idea BEFORE INSERT ON ideas BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        val result = tools.execute("time_quick_action", JSONObject().put("text", "记个想法周末去骑车"), origin.copy(toolCallId = "failure"))
        assertEquals("FAILED", result.getString("status"))
        assertTrue(repo.snapshot().ideas.isEmpty())
        assertTrue(repo.snapshot().history.isEmpty())
        assertNull(db.dao().commandResult(result.getString("requestId")))
    }

    @Test fun fishEditsPreserveWorkoutAndHealthProvenance() = scenario { repo, _ ->
        for (source in listOf("WORKOUT", "HEALTH_SYNC")) {
            val record = TimeRecordEntity("$source:record", null, now.minusSeconds(7_200).toEpochMilli(),
                now.minusSeconds(3_600).toEpochMilli(), source = source, title = source)
            repo.saveRecord(record)
            val saved = repo.snapshot().records.single { it.id == record.id }
            val preview = AssistantPreview("edit-$source", "edit", "", "edited", mutation =
                AssistantMutation.SaveRecord(saved.copy(notes = "edited"), expectedVersion = saved.version))
            val result = repo.commands.execute(CommitAssistant("edit-$source", preview, origin = origin))
            assertEquals(CommandStatus.APPLIED, result.status)
            assertEquals(source, repo.snapshot().records.single { it.id == record.id }.source)
            assertEquals("synthetic-session/assistant:7", result.sourceRef)
        }
    }

    @Test fun historicalRecordRemainsEditableAfterItsTaskIsCancelled() = scenario { repo, _ ->
        val task = repo.saveTask(TaskEntity("task", "old task"))
        repo.saveRecord(TimeRecordEntity("record", task.id, now.minusSeconds(7_200).toEpochMilli(), now.minusSeconds(3_600).toEpochMilli()))
        repo.saveTask(task.copy(status = "CANCELLED"), task.version)
        val record = repo.snapshot().records.single()
        val preview = AssistantPreview("edit", "edit", "", "edited", mutation =
            AssistantMutation.SaveRecord(record.copy(notes = "historical correction"), expectedVersion = record.version))
        RepositoryAssistantWriter(repo).commit(preview, false)
        assertEquals("historical correction", repo.snapshot().records.single().notes)
    }

    @Test fun outerRollbackRestoresPendingQuickConfirmationForRetry() = scenario { repo, _ ->
        val tools = FishTimeTools(repo)
        val preview = tools.execute("time_quick_action", JSONObject().put("text", "先预览 新建任务 合成验证"), origin)
        assertEquals("NEEDS_CONFIRMATION", preview.getString("status"))
        val confirmOrigin = origin.copy(toolCallId = "confirm")
        try {
            repo.transaction {
                assertEquals("APPLIED", tools.execute("time_quick_action", JSONObject().put("text", "确认"), confirmOrigin).getString("status"))
                throw IllegalStateException("synthetic outer rollback")
            }
        } catch (_: IllegalStateException) { }
        assertTrue(repo.snapshot().tasks.isEmpty())
        val retry = tools.execute("time_quick_action", JSONObject().put("text", "确认"), confirmOrigin)
        assertEquals("APPLIED", retry.getString("status"))
        assertEquals(1, repo.snapshot().tasks.size)
        assertEquals(1, repo.snapshot().history.size)
    }

    @Test fun cancellingFishPreviewCannotBeConfirmedLater() = scenario { repo, _ ->
        val tools = FishTimeTools(repo)
        assertEquals("NEEDS_CONFIRMATION", tools.execute("time_quick_action",
            JSONObject().put("text", "先预览 新建任务 合成验证"), origin).getString("status"))
        tools.execute("time_quick_action", JSONObject().put("text", "取消"), origin.copy(toolCallId = "cancel"))
        tools.execute("time_quick_action", JSONObject().put("text", "确认"), origin.copy(toolCallId = "confirm"))
        assertTrue(repo.snapshot().tasks.isEmpty())
        assertTrue(repo.snapshot().history.isEmpty())
    }

    @Test fun fishClarificationKeepsUnfinishedInputForTheNextCall() = scenario { repo, _ ->
        val tools = FishTimeTools(repo)
        tools.execute("time_quick_action", JSONObject().put("text", "记录睡眠"), origin)
        val followup = tools.execute("time_quick_action", JSONObject().put("text", "一点半到八点半"), origin.copy(toolCallId = "followup"))
        assertEquals("APPLIED", followup.getString("status"))
        assertEquals(1, repo.snapshot().records.size)
    }
}
