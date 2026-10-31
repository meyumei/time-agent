package com.timeagent.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectNextActionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: TimeAgentDatabase
    private lateinit var model: TimeViewModel

    @Before fun setup() {
        db = TimeAgentDatabase.openInMemory(ApplicationProvider.getApplicationContext<Context>())
        model = TimeViewModel(TimeRepository(db))
    }

    @After fun cleanup() { model.viewModelScope.cancel(); db.close() }

    private val now = Instant.parse("2026-10-29T02:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")

    /** One active project; the earliest due pending task is "a". Deleted/cancelled rows are earlier on purpose. */
    private fun pendingProject(): LocalSnapshot = LocalSnapshot(
        loading = false,
        projects = listOf(ProjectEntity("p", "课设演示")),
        tasks = listOf(
            TaskEntity("c", "已删除的旧任务", dueAt = now.plusSeconds(500).toEpochMilli(), deletedAt = 1),
            TaskEntity("d", "已取消的任务", dueAt = now.plusSeconds(600).toEpochMilli(), status = "CANCELLED"),
            TaskEntity("a", "写报告", dueAt = now.plusSeconds(86400).toEpochMilli()),
        ),
        placements = listOf(TaskPlacementEntity("a", "p"), TaskPlacementEntity("c", "p"), TaskPlacementEntity("d", "p")),
        plans = listOf(
            ScheduleBlockEntity("s1", "a", now.plusSeconds(3600).toEpochMilli(), now.plusSeconds(5400).toEpochMilli()),
            ScheduleBlockEntity("s2", "a", now.plusSeconds(7200).toEpochMilli(), now.plusSeconds(9000).toEpochMilli()),
        ),
        records = listOf(TimeRecordEntity("r", "a", now.minusSeconds(1200).toEpochMilli(), now.toEpochMilli())),
    )

    private fun render(
        data: LocalSnapshot,
        onTask: (TaskEntity) -> Unit = {},
        onSchedule: ((TaskEntity) -> Unit)? = null,
    ) {
        compose.setContent {
            TimeAgentTheme { ProjectScreen(data, model, now, zone, onTask = onTask, onClose = {}, onSchedule = onSchedule) }
        }
    }

    @Test fun `next task ignores deleted and cancelled rows and can be scheduled directly`() {
        var opened: String? = null
        var scheduled: String? = null
        render(pendingProject(), onTask = { opened = it.id }, onSchedule = { scheduled = it.id })
        compose.onNodeWithTag("project-p").performClick()

        compose.onNodeWithText("下一步 · 写报告", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("计划 60 分钟", substring = true).assertExists()
        compose.onNodeWithText("实际 20 分钟", substring = true).assertExists()

        compose.onNodeWithTag("project-schedule-next").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("a", scheduled)
            assertNull(opened)
        }
        compose.onNodeWithTag("project-next-task").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("a", opened) }
    }

    @Test fun `schedule button is absent without callback while next task stays available`() {
        var opened: String? = null
        render(pendingProject(), onTask = { opened = it.id })
        compose.onNodeWithTag("project-p").performClick()

        compose.onNodeWithTag("project-next-task").assertExists()
        compose.onNodeWithTag("project-schedule-next").assertDoesNotExist()
        compose.onNodeWithTag("project-next-task").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("a", opened) }
    }

    @Test fun `completed project recommends nothing even with pending tasks`() {
        var opened: String? = null
        var scheduled: String? = null
        val data = pendingProject().copy(projects = listOf(ProjectEntity("p", "课设演示", status = "COMPLETED")))
        render(data, onTask = { opened = it.id }, onSchedule = { scheduled = it.id })
        compose.onNodeWithTag("project-p").performClick()

        compose.onNodeWithTag("project-next-task").assertDoesNotExist()
        compose.onNodeWithTag("project-schedule-next").assertDoesNotExist()
        compose.onNodeWithText("下一步投入", substring = true).assertDoesNotExist()
        compose.runOnIdle {
            assertNull(opened)
            assertNull(scheduled)
        }
    }
}
