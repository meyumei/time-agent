package com.timeagent.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ActivityTemplatesTest {
    private lateinit var db: TimeAgentDatabase
    private lateinit var repo: TimeRepository
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TimeAgentDatabase::class.java).allowMainThreadQueries().build()
        repo = TimeRepository(db, Clock.fixed(Instant.parse("2026-10-20T08:00:00Z"), ZoneId.of("Asia/Shanghai")))
    }
    @After fun teardown() { db.close() }

    @Test fun `initial templates contain sleep and fitness with four children without duplicate seeding`() = runBlocking {
        repo.initialize(); repo.initialize()
        val templates = db.dao().activities()
        assertEquals(6, templates.size)
        assertEquals(480, templates.single { it.name == "睡觉" }.defaultMinutes)
        val fitness = templates.single { it.name == "健身" }
        assertEquals(setOf("练胸", "练背", "练肩", "练手臂"), templates.filter { it.parentId == fitness.id }.map { it.name }.toSet())
        assertEquals(6, repo.observe().first().activities.size)
    }
    @Test fun `user can add hierarchy and task retains its activity association`() = runBlocking {
        repo.initialize()
        repo.saveActivity(ActivityTemplateEntity("reading", "阅读", defaultMinutes = 30, categoryId = "study"))
        repo.saveActivity(ActivityTemplateEntity("papers", "论文", parentId = "reading", defaultMinutes = 45, categoryId = "study"))
        repo.saveTask(TaskEntity("task", "阅读 / 论文", estimatedMinutes = 45, categoryId = "study", activityId = "papers"))
        assertEquals("papers", db.dao().task("task")!!.activityId)
        assertEquals("reading", db.dao().activities().single { it.id == "papers" }.parentId)
    }
    @Test fun `invalid hierarchy and duplicate sibling names are rejected without writes`() = runBlocking {
        repo.initialize()
        repo.saveActivity(ActivityTemplateEntity("root", "阅读"))
        repo.saveActivity(ActivityTemplateEntity("child", "文献", "root"))
        val before = db.dao().activities().size
        try { repo.saveActivity(ActivityTemplateEntity("duplicate", "文献", "root")); fail() } catch (_: IllegalArgumentException) {}
        try { repo.saveActivity(ActivityTemplateEntity("grandchild", "精读", "child")); fail() } catch (_: IllegalArgumentException) {}
        try { repo.saveActivity(ActivityTemplateEntity("self", "循环", "self")); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(before, db.dao().activities().size)
    }
    @Test fun `archive preserves historical tasks and can be undone without reseeding`() = runBlocking {
        repo.initialize()
        val sleep = db.dao().activities().single { it.name == "睡觉" }
        repo.saveTask(TaskEntity("sleep-task", "睡觉", activityId = sleep.id))
        repo.archiveActivity(sleep.id)
        repo.initialize()
        assertNotNull(db.dao().activities().single { it.id == sleep.id }.archivedAt)
        assertEquals(sleep.id, db.dao().task("sleep-task")!!.activityId)
        repo.undoLastAction()
        assertNull(db.dao().activities().single { it.id == sleep.id }.archivedAt)
    }
    @Test fun `stale template editor cannot overwrite a later edit`() = runBlocking {
        repo.initialize()
        val sleep = db.dao().activities().single { it.name == "睡觉" }
        repo.saveActivity(sleep.copy(defaultMinutes = 420), sleep.version)
        try { repo.saveActivity(sleep.copy(name = "旧编辑"), sleep.version); fail() } catch (_: StaleChange) {}
        assertEquals(420, db.dao().activities().single { it.id == sleep.id }.defaultMinutes)
    }
}
