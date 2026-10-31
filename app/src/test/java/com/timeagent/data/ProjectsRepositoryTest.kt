package com.timeagent.data

import android.content.Context
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
class ProjectsRepositoryTest {
    private lateinit var db: TimeAgentDatabase
    private lateinit var repo: TimeRepository
    @Before fun setup() { db = TimeAgentDatabase.openInMemory(ApplicationProvider.getApplicationContext<Context>()); repo = TimeRepository(db, Clock.fixed(Instant.parse("2026-10-27T02:00:00Z"), ZoneId.of("Asia/Shanghai"))) }
    @After fun cleanup() = db.close()
    @Test fun `project and goal edit undo redo preserve versions and source tasks`() = runBlocking<Unit> {
        val project = repo.saveProject(ProjectEntity("p", "课设"))
        val task = repo.saveTask(TaskEntity("t", "报告"))
        repo.savePlacement(TaskPlacementEntity(task.id, project.id))
        val goal = repo.saveWeeklyGoal(WeeklyGoalEntity("g", project.id, weekStart = "2026-10-26", targetMinutes = 180))
        repo.undoLastAction(); assertTrue(repo.snapshot().weeklyGoals.isEmpty())
        repo.redoLastAction(); assertTrue(repo.snapshot().weeklyGoals.single().version > goal.version)
        assertThrows(StaleChange::class.java) { runBlocking { repo.saveWeeklyGoal(goal.copy(targetMinutes=90), goal.version) } }
        repo.deleteProject(project.id, project.version)
        assertNotNull(repo.snapshot().projects.single().deletedAt)
        assertEquals(task, db.dao().task("t"))
        repo.undoLastAction(); assertNull(repo.snapshot().projects.single().deletedAt)
    }
    @Test fun `only one child level and one project per family with atomic rejection`() = runBlocking<Unit> {
        repo.saveProject(ProjectEntity("p", "课设")); repo.saveProject(ProjectEntity("q", "复习"))
        listOf("a","b","c").forEach { repo.saveTask(TaskEntity(it,it)) }
        repo.savePlacement(TaskPlacementEntity("a", "p"))
        repo.savePlacement(TaskPlacementEntity("b", "p", "a"))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.savePlacement(TaskPlacementEntity("c", "p", "b")) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.savePlacement(TaskPlacementEntity("a", "q")) } }
        assertEquals("p", db.dao().placement("a")!!.projectId)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.savePlacement(TaskPlacementEntity("b", "q", "a")) } }
        assertNull(db.dao().placement("c"))
    }
    @Test fun `new child task and placement undo together without completing parent`() = runBlocking<Unit> {
        repo.saveProject(ProjectEntity("p","项目")); repo.saveTask(TaskEntity("a","大任务")); repo.savePlacement(TaskPlacementEntity("a","p"))
        repo.assistantTransaction("child") { repo.saveTask(TaskEntity("b","子任务")); repo.savePlacement(TaskPlacementEntity("b","p","a")) }
        val child = db.dao().task("b")!!; repo.saveTask(child.copy(status="COMPLETED"), child.version)
        assertEquals("TODO", db.dao().task("a")!!.status)
        repo.undoLastAction(); repo.undoLastAction("child")
        assertNull(db.dao().task("b")); assertNull(db.dao().placement("b"))
        repo.redoLastAction(); assertEquals("a", db.dao().placement("b")!!.parentTaskId)
    }
    @Test fun `weekly goals require one valid target monday and no duplicate`() = runBlocking<Unit> {
        repo.saveProject(ProjectEntity("p","项目"))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.saveWeeklyGoal(WeeklyGoalEntity("bad",weekStart="2026-10-26",targetMinutes=30)) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.saveWeeklyGoal(WeeklyGoalEntity("bad","p",weekStart="2026-10-27",targetMinutes=30)) } }
        repo.saveWeeklyGoal(WeeklyGoalEntity("g","p",weekStart="2026-10-26",targetMinutes=30))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.saveWeeklyGoal(WeeklyGoalEntity("g2","p",weekStart="2026-10-26",targetMinutes=40)) } }
    }
    @Test fun `parent must be explicitly placed before creating child even without project`() = runBlocking<Unit> {
        repo.saveTask(TaskEntity("a","父"));repo.saveTask(TaskEntity("b","子"))
        assertThrows(IllegalArgumentException::class.java){runBlocking{repo.savePlacement(TaskPlacementEntity("b",parentTaskId="a"))}}
        assertNull(db.dao().placement("b"))
    }
}
