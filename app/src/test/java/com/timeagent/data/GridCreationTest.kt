package com.timeagent.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.timeagent.core.TimeSpan
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GridCreationTest {
    private lateinit var db: TimeAgentDatabase
    private lateinit var repo: TimeRepository
    private val now = Instant.parse("2026-10-19T05:00:00Z")
    private val span get() = TimeSpan(now, now.plusSeconds(3600))
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TimeAgentDatabase::class.java).allowMainThreadQueries().build()
        repo = TimeRepository(db, Clock.fixed(now, ZoneId.of("Asia/Shanghai")))
    }
    @After fun cleanup() { db.close() }
    @Test fun `task and selected plan save together with a stable request id`() = runBlocking {
        val task = TaskEntity("new-task", "时间轴任务")
        repo.saveTaskAndPlan(task, "grid-plan", span)
        repo.saveTaskAndPlan(task, "grid-plan", span)
        assertEquals(1, db.dao().tasks().size)
        assertEquals(1, db.dao().plans().size)
        assertEquals("new-task", db.dao().plans().single().taskId)
        assertEquals(2, db.dao().history().size)
    }
    @Test fun `a failed plan insert rolls back task and operation history`() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_grid_plan BEFORE INSERT ON schedule_blocks BEGIN SELECT RAISE(ABORT, 'controlled plan failure'); END")
        try { repo.saveTaskAndPlan(TaskEntity("new-task", "不会留下半个任务"), "grid-plan", span); fail("Expected controlled plan failure") } catch (_: android.database.SQLException) { }
        assertTrue(db.dao().tasks().isEmpty())
        assertTrue(db.dao().plans().isEmpty())
        assertTrue(db.dao().history().isEmpty())
    }
}
