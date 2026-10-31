package com.timeagent.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AssistantAtomicityTest {
    private lateinit var db: TimeAgentDatabase
    private lateinit var repo: TimeRepository
    private val now = Instant.parse("2026-10-20T08:00:00Z")
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TimeAgentDatabase::class.java).allowMainThreadQueries().build()
        repo = TimeRepository(db, Clock.fixed(now, ZoneId.of("Asia/Shanghai")))
    }
    @After fun cleanup() { db.close() }
    @Test fun `stale linked task preview cannot commit a new plan or timer or record`() = runBlocking {
        val original = repo.saveTask(TaskEntity("task", "复习"))
        repo.saveTask(original.copy(title = "新的名称"), original.version)
        try { repo.savePlan(ScheduleBlockEntity("plan", "task", now.toEpochMilli(), now.plusSeconds(1800).toEpochMilli()), expectedTaskVersion = original.version); fail("Expected stale task rejection") } catch (_: StaleChange) {}
        try { repo.startTimer("task", "timer", expectedTaskVersion = original.version); fail("Expected stale task rejection") } catch (_: StaleChange) {}
        try { repo.saveRecord(TimeRecordEntity("record", "task", now.minusSeconds(1800).toEpochMilli(), now.toEpochMilli()), expectedTaskVersion = original.version); fail("Expected stale task rejection") } catch (_: StaleChange) {}
        assertTrue(db.dao().plans().isEmpty())
        assertTrue(db.dao().records().isEmpty())
    }
    @Test fun `stale stop preview cannot overwrite a later edit to running timer`() = runBlocking {
        val started = repo.startTimer(null, "timer")
        db.dao().putRecord(started.copy(notes = "更新后的记录", version = started.version + 1))
        val later = TimeRepository(db, Clock.fixed(now.plusSeconds(60), ZoneId.of("Asia/Shanghai")))
        try { later.stopTimer("timer", expectedVersion = started.version); fail("Expected stale timer rejection") } catch (_: StaleChange) {}
        assertNull(db.dao().runningTimer()!!.endAt)
        assertEquals("更新后的记录", db.dao().runningTimer()!!.notes)
    }
    @Test fun `new conflicts after preview require another confirmation`() = runBlocking {
        repo.saveTask(TaskEntity("task", "复习"))
        val plan = ScheduleBlockEntity("plan", "task", now.toEpochMilli(), now.plusSeconds(1800).toEpochMilli())
        val preview = repo.conflicts(com.timeagent.core.TimeSpan(now, now.plusSeconds(1800)))
        repo.savePlan(plan.copy(id = "other"))
        try { repo.savePlan(plan, expectedConflicts = preview); fail("Expected stale conflict preview rejection") } catch (_: StaleChange) {}
        assertEquals(listOf("other"), db.dao().plans().map { it.id })
    }
}
