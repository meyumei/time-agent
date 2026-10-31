package com.timeagent.fish

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.*
import com.timeagent.core.RecurringPattern
import com.timeagent.recurring.RecurringService
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FishTimeToolsTest {
    private lateinit var db: TimeAgentDatabase
    private lateinit var repo: TimeRepository
    private val now = Instant.parse("2026-10-29T02:00:00Z")
    @Before fun setUp() {
        db = TimeAgentDatabase.openInMemory(ApplicationProvider.getApplicationContext<Context>())
        repo = TimeRepository(db, Clock.fixed(now, ZoneId.of("Asia/Shanghai")))
    }
    @After fun close() = db.close()
    @Test fun `today reads overlapping plans and excludes cancelled items and unrelated actual`() = runBlocking<Unit> {
        repo.saveTask(TaskEntity("t", "跨日计划"))
        repo.savePlan(ScheduleBlockEntity("p", "t", Instant.parse("2026-10-28T15:30:00Z").toEpochMilli(), Instant.parse("2026-10-28T17:00:00Z").toEpochMilli()))
        val answer = FishTimeTools(repo).execute("time_today", JSONObject())
        assertEquals("2026-10-29", answer.getString("date"))
        assertEquals("跨日计划", answer.getJSONArray("plans").getJSONObject(0).getString("title"))
        assertEquals(0, answer.getJSONArray("actual").length())
    }
    @Test fun `task tools write through repository and completion creates no actual records`() = runBlocking<Unit> {
        val tools = FishTimeTools(repo)
        val created = tools.execute("time_create_task", JSONObject().put("title", "原型验收"))
        val id = created.getString("id")
        assertEquals("原型验收", repo.snapshot().tasks.single { it.id == id }.title)
        tools.execute("time_complete_task", JSONObject().put("taskId", id))
        assertEquals("COMPLETED", repo.snapshot().tasks.single().status)
        assertTrue(repo.snapshot().records.isEmpty())
        assertTrue(repo.snapshot().history.isNotEmpty())
    }
    @Test fun `future actual is rejected without creating a record`() = runBlocking<Unit> {
        val tools = FishTimeTools(repo)
        try {
            tools.execute("time_record", JSONObject().put("title", "未来不能当实际").put("start", now.plusSeconds(60).toString()).put("end", now.plusSeconds(600).toString()))
            fail("future actual accepted")
        } catch (_: IllegalArgumentException) { }
        assertTrue(repo.snapshot().records.isEmpty())
    }
    @Test fun `completing a recurring task updates the same instance without inventing actual`() = runBlocking<Unit> {
        val pattern=RecurringPattern("r","DAILY","2026-10-29",untilDate="2026-10-29")
        RecurringService(repo).saveRule(RecurringRuleEntity("r","习惯",Gson().toJson(pattern)),null,LocalDate.parse("2026-10-29"),"seed")
        val instance=repo.snapshot().recurringInstances.single()
        FishTimeTools(repo).execute("time_complete_task",JSONObject().put("taskId",instance.taskId))
        assertEquals("COMPLETED",repo.snapshot().recurringInstances.single().status)
        assertEquals(instance.id,repo.snapshot().recurringInstances.single().id)
        assertTrue(repo.snapshot().records.isEmpty())
    }
    @Test fun `local quick action remains a real business tool on the same fish`() = runBlocking<Unit> {
        FishTimeTools(repo).execute("time_quick_action",JSONObject().put("text","记个想法周末想去骑车但还没定时间"))
        assertEquals(1,repo.snapshot().ideas.size)
        assertTrue(repo.snapshot().tasks.isEmpty())
        assertTrue(repo.snapshot().plans.isEmpty())
    }
    @Test fun `a fish scheduled project task is visible in the same business snapshot`() = runBlocking<Unit> {
        repo.saveProject(ProjectEntity("p", "合成项目"), null)
        val tools = FishTimeTools(repo)
        val task = tools.execute("time_create_task", JSONObject().put("title", "合成下一步").put("projectId", "p"))
        val taskId = task.getString("id")
        tools.execute("time_create_plan", JSONObject().put("title", "合成下一步").put("taskId", taskId)
            .put("start", "2026-10-29T11:00:00+08:00").put("end", "2026-10-29T11:30:00+08:00"))
        val snapshot = repo.snapshot()
        assertEquals("p", snapshot.placements.single { it.taskId == taskId }.projectId)
        assertEquals(taskId, snapshot.plans.single().taskId)
        assertEquals("合成下一步", tools.execute("time_today", JSONObject()).getJSONArray("plans").getJSONObject(0).getString("title"))
        assertTrue(snapshot.records.isEmpty())
    }
}
