package com.timeagent.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProjectsSchemaTest {
    @Test fun `project tables exist while old unassigned tasks remain unchanged`() = runBlocking {
        val db = TimeAgentDatabase.openInMemory(ApplicationProvider.getApplicationContext<Context>())
        try {
            db.dao().putTask(TaskEntity("old", "旧任务"))
            val tables = db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'").use { c -> buildSet { while(c.moveToNext()) add(c.getString(0)) } }
            assertTrue("项目数据表尚未实现", tables.containsAll(listOf("projects", "task_placements", "weekly_goals")))
            assertEquals("旧任务", db.dao().task("old")!!.title)
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM task_placements").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        } finally { db.close() }
    }
}
