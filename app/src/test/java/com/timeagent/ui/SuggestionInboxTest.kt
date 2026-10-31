package com.timeagent.ui

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.*
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SuggestionInboxTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: TimeAgentDatabase
    private lateinit var viewModel: TimeViewModel
    private val speechFactory: SpeechInputFactory = { _, callbacks -> TestSpeechInput(callbacks) }

    @Before fun setup() {
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit()
        db = TimeAgentDatabase.openInMemory(ApplicationProvider.getApplicationContext<Context>())
        viewModel = TimeViewModel(TimeRepository(db, Clock.fixed(Instant.parse("2026-10-19T02:00:00Z"), ZoneId.of("Asia/Shanghai"))))
        restorationContent()
        compose.waitForIdle()
        compose.waitUntil(10_000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); !viewModel.data.value.loading }
    }

    private fun restorationContent() {
        compose.setContent { TimeAgentTheme { TimeAgentApp(viewModel, speechFactory) } }
    }

    @After fun cleanup() {
        viewModel.viewModelScope.cancel()
        db.close()
    }

    private fun idle() { Shadows.shadowOf(Looper.getMainLooper()).idle() }

    @Test fun `overdue task appears as inbox suggestion and dismiss silences it`() {
        runBlocking {
            viewModel.repository.saveTask(TaskEntity("t1", "交周报", dueAt = viewModel.repository.now().toEpochMilli() - 3 * 3600_000L))
        }
        compose.waitUntil(10_000) { idle(); viewModel.data.value.suggestions.any { it.type == "OVERDUE_TASK" } }
        compose.onNodeWithContentDescription("计划页面").performClick()
        compose.onNodeWithTag("task-inbox-entry").performClick()
        compose.waitUntil(10_000) { idle(); compose.onAllNodesWithTag("inbox-suggestions").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("任务已截止：交周报", substring = true).assertExists()
        // Multiple rules may fire for one seed; dismiss every suggestion card.
        compose.waitUntil(10_000) { idle(); compose.onAllNodesWithTag("suggestion-dismiss").fetchSemanticsNodes().isNotEmpty() }
        var guard = 0
        while (compose.onAllNodesWithTag("suggestion-dismiss").fetchSemanticsNodes().isNotEmpty() && guard++ < 5) {
            compose.onAllNodesWithTag("suggestion-dismiss").onFirst().performClick()
            compose.waitUntil(5_000) { idle(); !viewModel.busy.value }
        }
        compose.waitUntil(10_000) { idle(); compose.onAllNodesWithTag("inbox-suggestions").fetchSemanticsNodes().isEmpty() }
        runBlocking {
            assertEquals(2, db.dao().allSuggestions().size)
            assertTrue(db.dao().allSuggestions().all { it.status == "DISMISSED" })
        }
    }

    @Test fun `dismissed suggestion does not regenerate on later refresh`() {
        runBlocking {
            viewModel.repository.saveTask(TaskEntity("t1", "交周报", dueAt = viewModel.repository.now().toEpochMilli() - 3 * 3600_000L))
        }
        compose.waitUntil(10_000) { idle(); viewModel.data.value.suggestions.any { it.type == "OVERDUE_TASK" } }
        runBlocking {
            val overdue = viewModel.data.value.suggestions.filter { it.type == "OVERDUE_TASK" }
            viewModel.repository.dismissSuggestion(overdue.single().id)
        }
        compose.waitUntil(10_000) { idle(); viewModel.data.value.suggestions.none { it.type == "OVERDUE_TASK" } }
        // A later data change re-triggers the coordinator; the dismissed kind must stay silent.
        runBlocking { viewModel.repository.saveTask(TaskEntity("t2", "另一件事")) }
        compose.waitUntil(10_000) { idle(); viewModel.data.value.tasks.any { it.id == "t2" } }
        idle(); idle()
        assertTrue(viewModel.data.value.suggestions.none { it.type == "OVERDUE_TASK" })
    }
}
