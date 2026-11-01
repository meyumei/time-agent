package com.timeagent.ui

import android.content.Context
import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.timeagent.assistant.SpeechInputFactory
import com.timeagent.data.ScheduleBlockEntity
import com.timeagent.data.TaskEntity
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.data.TimeRepository
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
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

/** Exercises the real grid entry, draft changes and Room writes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GridBlockEditorFlowTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: TimeAgentDatabase
    private lateinit var model: TimeViewModel
    private val zone = ZoneId.of("Asia/Shanghai")
    private val speechFactory: SpeechInputFactory = { _, callbacks -> TestSpeechInput(callbacks) }

    @Before fun setup() {
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TimeAgentDatabase::class.java).allowMainThreadQueries().build()
        model = TimeViewModel(TimeRepository(db, Clock.fixed(Instant.parse("2026-10-19T08:00:00Z"), zone)))
        compose.setContent { TimeAgentTheme { TimeAgentApp(model, speechFactory) } }
        await { !model.data.value.loading }
    }

    @After fun cleanup() { model.viewModelScope.cancel(); db.close() }

    private fun await(condition: () -> Boolean) {
        compose.waitUntil(10_000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); condition() }
    }

    private fun openAt13() {
        val x = compose.onNodeWithText("19", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        val y = compose.onNodeWithText("13:00").fetchSemanticsNode().boundsInRoot.center.y + 2f
        compose.onRoot().performTouchInput { longClick(Offset(x, y)) }
        compose.onNodeWithTag("grid-block-editor").assertExists()
    }

    private fun field(tag: String, value: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
    }

    private fun chooseRecord() {
        compose.onNodeWithTag("grid-kind-record").performScrollTo().performClick()
    }

    @Test fun `releasing a blank selection opens one range editor with plan and actual types`() {
        openAt13()
        compose.onNodeWithTag("grid-kind-plan").assertIsSelected()
        compose.onNodeWithTag("grid-kind-record").assertExists()
        compose.onNodeWithTag("grid-start-date").assertTextContains("2026-10-19")
        compose.onNodeWithTag("grid-start-time").assertTextContains("13:00")
        compose.onNodeWithTag("grid-end-date").assertTextContains("2026-10-19")
        compose.onNodeWithTag("grid-end-time").assertTextContains("14:00")
        compose.onNodeWithText("任务并安排").assertDoesNotExist()
        compose.onNodeWithTag("plan-mode-reminder").assertDoesNotExist()
        compose.onNodeWithTag("reminder-save").assertDoesNotExist()
    }

    @Test fun `tapping a pending blank block opens its add page directly`() {
        val x = compose.onNodeWithText("19", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        val y = compose.onNodeWithText("13:00").fetchSemanticsNode().boundsInRoot.center.y + 2f
        compose.onRoot().performTouchInput { click(Offset(x, y)) }
        compose.onNodeWithTag("timeline-pending-selection").assertExists()
        compose.onNodeWithTag("grid-block-editor").assertDoesNotExist()
        compose.onNodeWithTag("timeline-pending-add").performClick()
        compose.onNodeWithTag("grid-block-editor").assertExists()
        compose.onNodeWithTag("grid-kind-plan").assertIsSelected()
        assertTrue(runBlocking { db.dao().history().isEmpty() })
    }

    @Test fun `switching types keeps typed title notes and edited dates and times`() {
        openAt13()
        field("grid-title", "散步和阅读")
        field("grid-notes", "保留这段草稿")
        field("grid-start-date", "2026-10-18")
        field("grid-start-time", "22:30")
        field("grid-end-date", "2026-10-19")
        field("grid-end-time", "00:30")
        chooseRecord()
        compose.onNodeWithTag("grid-kind-plan").performScrollTo().performClick()
        chooseRecord()
        compose.onNodeWithTag("grid-title").assertTextContains("散步和阅读")
        compose.onNodeWithTag("grid-notes").assertTextContains("保留这段草稿")
        compose.onNodeWithTag("grid-start-date").assertTextContains("2026-10-18")
        compose.onNodeWithTag("grid-start-time").assertTextContains("22:30")
        compose.onNodeWithTag("grid-end-date").assertTextContains("2026-10-19")
        compose.onNodeWithTag("grid-end-time").assertTextContains("00:30")
        compose.onNodeWithTag("grid-save").performClick()
        await { model.data.value.records.size == 1 }
        val saved = runBlocking { db.dao().records().single() }
        assertEquals("2026-10-18T22:30", Instant.ofEpochMilli(saved.startAt).atZone(zone).toLocalDateTime().toString())
        assertEquals("2026-10-19T00:30", Instant.ofEpochMilli(requireNotNull(saved.endAt)).atZone(zone).toLocalDateTime().toString())
        assertTrue(saved.notes.contains("散步和阅读"))
        assertTrue(saved.notes.contains("保留这段草稿"))
    }

    @Test fun `actual without a linked task saves title and notes without creating a task or plan`() {
        openAt13()
        chooseRecord()
        field("grid-title", "午后阅读")
        field("grid-notes", "读完第三章")
        compose.onNodeWithTag("grid-save").performClick()
        await { model.data.value.records.size == 1 }
        val saved = runBlocking { db.dao().records().single() }
        assertNull(saved.taskId)
        assertTrue(saved.notes.contains("午后阅读"))
        assertTrue(saved.notes.contains("读完第三章"))
        assertTrue(runBlocking { db.dao().tasks().isEmpty() })
        assertTrue(runBlocking { db.dao().plans().isEmpty() })
        assertTrue(runBlocking { db.dao().reminders().isEmpty() })
    }

    @Test fun `independent plan saves its own title and notes without creating a task`() {
        openAt13()
        compose.onNodeWithTag("grid-independent-plan").performScrollTo().performClick()
        field("grid-title", "独立阅读")
        field("grid-notes", "第三章")
        compose.onNodeWithTag("grid-save").performClick()
        await { model.data.value.plans.size == 1 }
        val plan = runBlocking { db.dao().plans().single() }
        assertNull(plan.taskId)
        assertEquals("独立阅读", plan.title)
        assertEquals("第三章", plan.notes)
        assertTrue(runBlocking { db.dao().tasks().isEmpty() })
        assertTrue(runBlocking { db.dao().records().isEmpty() })
    }

    @Test fun `actual title identical to notes is stored once`() {
        openAt13()
        chooseRecord()
        field("grid-title", "阅读")
        field("grid-notes", "阅读")
        compose.onNodeWithTag("grid-save").performClick()
        await { model.data.value.records.size == 1 }
        assertEquals("阅读", runBlocking { db.dao().records().single().notes })
    }

    @Test fun `actual keeps its title when a later note line repeats it`() {
        openAt13()
        chooseRecord()
        field("grid-title", "阅读")
        field("grid-notes", "图书馆\n阅读")
        compose.onNodeWithTag("grid-save").performClick()
        await { model.data.value.records.size == 1 }
        assertEquals("阅读\n图书馆\n阅读", runBlocking { db.dao().records().single().notes })
        assertEquals("阅读", model.data.value.timeline(Instant.parse("2026-10-19T08:00:00Z")).single().title)
    }

    @Test fun `new planned task and block save with their shared selection`() {
        openAt13()
        field("grid-title", "本周阅读计划")
        compose.onNodeWithTag("grid-save").performClick()
        await { model.data.value.plans.size == 1 && model.data.value.tasks.size == 1 }
        val task = runBlocking { db.dao().tasks().single() }
        val plan = runBlocking { db.dao().plans().single() }
        assertEquals(task.id, plan.taskId)
        assertEquals("本周阅读计划", task.title)
        assertEquals("2026-10-19T13:00", Instant.ofEpochMilli(plan.startAt).atZone(zone).toLocalDateTime().toString())
        assertEquals("2026-10-19T14:00", Instant.ofEpochMilli(plan.endAt).atZone(zone).toLocalDateTime().toString())
        assertTrue(runBlocking { db.dao().records().isEmpty() })
        assertTrue(runBlocking { db.dao().reminders().isEmpty() })
    }

    @Test fun `plan may arrange an existing task without creating another task`() {
        runBlocking { model.repository.saveTask(TaskEntity("existing", "已有作业")) }
        await { model.data.value.tasks.size == 1 }
        openAt13()
        compose.onNodeWithTag("grid-existing-task").performScrollTo().performClick()
        compose.onNodeWithTag("grid-task-existing").performScrollTo().performClick()
        compose.onNodeWithTag("grid-save").performClick()
        await { model.data.value.plans.size == 1 }
        assertEquals("existing", runBlocking { db.dao().plans().single().taskId })
        assertEquals(1, runBlocking { db.dao().tasks().size })
    }

    @Test fun `future actual requires confirmation again when its time changes`() {
        openAt13()
        chooseRecord()
        field("grid-title", "未来实际")
        field("grid-end-time", "18:00")
        compose.onNodeWithTag("grid-save").assertIsNotEnabled()
        compose.onNodeWithTag("grid-future-confirm").performScrollTo().performClick()
        compose.onNodeWithTag("grid-save").assertIsEnabled()
        field("grid-end-time", "19:00")
        compose.onNodeWithTag("grid-save").assertIsNotEnabled()
        compose.onNodeWithTag("grid-future-confirm").performScrollTo().performClick()
        compose.onNodeWithTag("grid-save").performClick()
        await { model.data.value.records.size == 1 }
        assertTrue(runBlocking { db.dao().tasks().isEmpty() })
    }

    @Test fun `canceling a selected block writes no history or entities`() {
        openAt13()
        field("grid-title", "取消草稿")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("grid-block-editor").assertDoesNotExist()
        assertTrue(runBlocking { db.dao().tasks().isEmpty() })
        assertTrue(runBlocking { db.dao().plans().isEmpty() })
        assertTrue(runBlocking { db.dao().records().isEmpty() })
        assertTrue(runBlocking { db.dao().history().isEmpty() })
    }

    @Test fun `repeated actual save clicks create one write and one undo entry`() {
        openAt13()
        chooseRecord()
        field("grid-title", "只保存一次")
        compose.onNodeWithTag("grid-save").performSemanticsAction(SemanticsActions.OnClick) { click -> click(); click() }
        await { model.data.value.records.size == 1 && !model.busy.value }
        assertEquals(1, runBlocking { db.dao().records().size })
        assertEquals(1, runBlocking { db.dao().history().size })
    }

    @Test fun `overlapping plan uses an explicit still save action`() {
        val start = Instant.parse("2026-10-19T05:30:00Z").toEpochMilli()
        runBlocking {
            model.repository.saveTask(TaskEntity("overlap", "已有计划"))
            model.repository.savePlan(ScheduleBlockEntity("overlap-plan", "overlap", start, start + 3_600_000))
        }
        await { model.data.value.plans.size == 1 }
        openAt13()
        field("grid-title", "确认重叠的新计划")
        compose.onNodeWithTag("grid-save").assertTextContains("仍然保存")
        compose.onNodeWithText("时间冲突，仍可保存：", substring = true).assertExists()
        compose.onNodeWithTag("grid-save").performClick()
        await { model.data.value.plans.size == 2 }
        assertEquals(2, runBlocking { db.dao().tasks().size })
    }
}
