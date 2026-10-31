package com.timeagent.ui

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import com.timeagent.assistant.SpeechInputFactory
import com.timeagent.data.TaskEntity
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.data.TimeRepository
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Phase A2 UI contract for the next iteration.
 *
 * Every test below is written against nodes/labels that do not exist yet. They compile against the
 * current TimeAgentApp and fail on assertions, which is the RED evidence the root agent records
 * before production work starts. Labels and testTags used here are listed in
 * evidence/native-ui-phase-a2-contract.md and are the implementation contract for phase B.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskPointInboxFlowTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: TimeAgentDatabase
    private lateinit var viewModel: TimeViewModel
    private lateinit var restoration: StateRestorationTester
    private val speechFactory: SpeechInputFactory = { _, callbacks -> TestSpeechInput(callbacks) }

    @Before fun setup() {
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit()
        db = TimeAgentDatabase.openInMemory(ApplicationProvider.getApplicationContext<Context>())
        viewModel = TimeViewModel(TimeRepository(db, Clock.fixed(Instant.parse("2026-10-19T02:00:00Z"), ZoneId.of("Asia/Shanghai"))))
        restoration = StateRestorationTester(compose)
        restoration.setContent { TimeAgentTheme { TimeAgentApp(viewModel, speechFactory) } }
        compose.waitForIdle()
        compose.waitUntil(10_000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); !viewModel.data.value.loading }
    }

    @After fun cleanup() {
        viewModel.viewModelScope.cancel()
        db.close()
    }

    private fun idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun seedTask(id: String = "t1", title: String = "写报告") {
        runBlocking { viewModel.repository.saveTask(TaskEntity(id, title)) }
        compose.waitUntil(10_000) { idle(); viewModel.data.value.tasks.any { it.id == id } }
    }

    private fun openPlanEditor() {
        compose.onNodeWithContentDescription("复盘页面").performClick()
        compose.onNodeWithContentDescription("新建").performClick()
        compose.onNodeWithText("安排计划").performClick()
    }

    @Test fun `new plan defaults to one reminder and saves without a plan`() {
        val beforeReminders = viewModel.data.value.reminders.size
        val beforePlans = viewModel.data.value.plans.size
        openPlanEditor()

        // Contract: the default mode is a single point reminder.
        compose.onNodeWithText("提醒时间").assertIsDisplayed()
        compose.onNodeWithText("结束时间 HH:mm").assertDoesNotExist()
        compose.onNodeWithTag("plan-mode-reminder").assertIsSelected()

        // Contract: the point save writes one reminder and never silently creates a plan block.
        compose.onNodeWithTag("reminder-title").performTextReplacement("交作业")
        compose.onNodeWithTag("reminder-save").performClick()
        compose.waitUntil(10_000) { idle(); viewModel.data.value.reminders.size == beforeReminders + 1 }
        assertEquals(beforeReminders + 1, runBlocking { db.dao().reminders().size })
        assertEquals(beforePlans, runBlocking { db.dao().plans().size })
        val saved = runBlocking { db.dao().reminders().single { it.title == "交作业" } }
        assertNull(saved.taskId)
    }

    @Test fun `choosing an explicit range keeps the start and end editor`() {
        openPlanEditor()

        // Contract: the mode chips are visible and "时段" switches back to the old span editor.
        compose.onNodeWithText("时段").assertIsDisplayed().performClick()
        compose.onNodeWithText("结束时间 HH:mm").assertIsDisplayed()
        compose.onNodeWithTag("plan-mode-range").assertIsSelected()
    }

    @Test fun `task page exposes the unscheduled inbox entry`() {
        runBlocking { viewModel.repository.saveIdea(com.timeagent.data.IdeaEntity("idea", "周末想去骑车")) }
        compose.waitUntil(10_000) { idle(); viewModel.data.value.ideas.any { it.id == "idea" } }

        compose.onNodeWithContentDescription("计划页面").performClick()

        // The plan shortcut keeps the collection flow available before anything is scheduled.
        compose.onNodeWithTag("task-inbox-entry").assertIsDisplayed()
        compose.onNodeWithText("收集想法").assertIsDisplayed()
        compose.onNodeWithTag("task-inbox-entry").performClick()
        compose.onNodeWithText("周末想去骑车").assertIsDisplayed()
        compose.onNodeWithTag("idea-schedule-reminder").assertIsDisplayed()
    }

    @Test fun `task note draft survives restoration and asks before discarding`() {
        seedTask()
        compose.onNodeWithContentDescription("计划页面").performClick()
        compose.onNodeWithText("写报告").performClick()

        // Contract: the task detail sheet offers a note entry bound to this task.
        compose.onNodeWithTag("task-note-entry").assertIsDisplayed()
        compose.onNodeWithText("记笔记").performClick()
        compose.onNodeWithTag("note-body").performTextInput("第一章")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("note-body").assertTextContains("第一章")
        compose.onNodeWithTag("note-back").performClick()
        compose.onNodeWithText("放弃未保存的笔记？").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithTag("note-save").performClick()

        compose.waitUntil(10_000) { idle(); viewModel.data.value.notes.isNotEmpty() }
        val note = runBlocking { db.dao().notes().single() }
        assertEquals("TASK", note.targetType)
        assertEquals("t1", note.targetId)
        assertTrue(note.body.contains("第一章"))
    }

    @Test fun `redo is enabled after undo and restores the task`() {
        seedTask()
        compose.onNodeWithContentDescription("计划页面").performClick()
        compose.onNodeWithContentDescription("撤销最近操作").performClick()
        compose.waitUntil(10_000) { idle(); viewModel.data.value.history.any { it.undone && it.redoable } }
        assertTrue(viewModel.data.value.tasks.isEmpty())

        // Contract: redo sits beside undo and follows history.undone && history.redoable.
        compose.onNodeWithContentDescription("重做").assertIsEnabled().performClick()

        compose.waitUntil(10_000) { idle(); viewModel.data.value.tasks.any { it.id == "t1" } }
        compose.onNodeWithText("写报告").assertIsDisplayed()
    }

    @Test fun `settings announces installed application version`() {
        compose.onNodeWithContentDescription("管家页面").performClick()
        compose.onNodeWithText("打开设置").performClick()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        compose.onNodeWithText("开发测试版 · $version").assertIsDisplayed()
    }

    @Test fun `production hides demo entries and offers training on task page`() {
        compose.onNodeWithContentDescription("管家页面").performClick()
        compose.onNodeWithText("打开设置").performClick()
        compose.onNodeWithTag("settings-conflict-sample").assertDoesNotExist()
        compose.onNodeWithText("预览示例课表（合成数据）").assertDoesNotExist()
        compose.onNodeWithText("冲突排版示例").assertDoesNotExist()
        compose.onNodeWithText("返回管家").performClick()
        compose.onNodeWithContentDescription("计划页面").performClick()
        compose.onNodeWithTag("task-workout-entry").assertIsDisplayed()
    }
}
