package com.timeagent.ui

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ButlerBatchOneFlowTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: TimeAgentDatabase
    private lateinit var model: TimeViewModel
    @Before fun setup() {
        db = TimeAgentDatabase.openInMemory(ApplicationProvider.getApplicationContext<Context>())
        model = TimeViewModel(TimeRepository(db, Clock.fixed(Instant.parse("2026-10-26T02:00:00Z"), ZoneId.of("Asia/Shanghai"))))
    }
    @After fun cleanup() { model.viewModelScope.cancel(); db.close() }
    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()
    @Test fun `suggestion button creates real plan once and toolbar undo removes it`() {
        runBlocking { model.repository.saveTask(TaskEntity("read", "阅读记录", estimatedMinutes = 45)) }
        compose.setContent { TimeAgentTheme { TimeAgentApp(model, { _, cb -> TestSpeechInput(cb) }) } }
        compose.waitUntil(10_000) { idle(); model.data.value.suggestions.any { it.taskId == "read" } }
        compose.onNodeWithContentDescription("计划页面").performClick()
        compose.onNodeWithTag("task-inbox-entry").performClick()
        compose.onNodeWithTag("suggestion-schedule").performScrollTo().performClick()
        compose.waitUntil(10_000) { idle(); model.data.value.plans.any { it.taskId == "read" } && !model.busy.value }
        assertEquals(1, runBlocking { db.dao().plans().size })
        compose.onNodeWithContentDescription("撤销最近操作").performClick()
        compose.waitUntil(10_000) { idle(); model.data.value.plans.isEmpty() && !model.busy.value }
        assertTrue(runBlocking { db.dao().plans().isEmpty() })
    }
    @Test fun `briefing expands while keeping timeline and opens original due task`() {
        runBlocking { model.repository.saveTask(TaskEntity("due", "今天交报告", dueAt = model.repository.now().plusSeconds(3600).toEpochMilli())) }
        compose.setContent { TimeAgentTheme { TimeAgentApp(model, { _, cb -> TestSpeechInput(cb) }) } }
        compose.waitUntil(10_000) { idle(); !model.data.value.loading }
        compose.onNodeWithTag("briefing-toggle").performClick()
        compose.onNodeWithTag("timeline-viewport").assertExists()
        compose.onAllNodesWithText("今天交报告").onFirst().performScrollTo().performClick()
        compose.onNodeWithTag("task-detail-title").assertTextEquals("今天交报告")
    }
}
