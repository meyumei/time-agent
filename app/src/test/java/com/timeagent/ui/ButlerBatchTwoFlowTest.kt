package com.timeagent.ui

import android.content.Context
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ButlerBatchTwoFlowTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: TimeAgentDatabase
    private lateinit var model: TimeViewModel
    private lateinit var view: View
    @Before fun setup() {
        db = TimeAgentDatabase.openInMemory(ApplicationProvider.getApplicationContext<Context>())
        model = TimeViewModel(TimeRepository(db, Clock.fixed(Instant.parse("2026-10-26T02:00:00Z"), ZoneId.of("Asia/Shanghai"))))
    }
    @After fun cleanup() { model.viewModelScope.cancel(); db.close() }
    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()
    private fun shot(name: String) = compose.runOnIdle {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap)); val file = File("build/outputs/butler-previews/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun `memory screen shows no seeded preferences and explicit memory can be deactivated`() {
        runBlocking { model.repository.rememberExplicit("记住周三不练腿", "test-memory") }
        compose.setContent { view = LocalView.current; TimeAgentTheme { PersonalMemoryScreen(model, {}) } }
        compose.waitUntil(10000) { idle(); model.data.value.memories.isNotEmpty() }
        compose.onNodeWithText("周三不练腿").assertExists()
        shot("personal-memory")
        compose.onNodeWithText("停用").performScrollTo().performClick()
        compose.waitUntil(10000) { idle(); model.data.value.memories.single().enabled == false }
        assertFalse(runBlocking { db.dao().memory(model.data.value.memories.single().id)!!.enabled })
    }
    @Test fun `history question returns computed result and opens exact original record`() {
        var opened: Pair<String,String>? = null
        runBlocking { model.repository.saveRecord(TimeRecordEntity("history-real", null, model.repository.now().minusSeconds(3600).toEpochMilli(), model.repository.now().toEpochMilli(), title = "阅读")) }
        compose.setContent { view = LocalView.current; TimeAgentTheme { HistoryScreen(model, {}, { type, id -> opened = type to id }) } }
        compose.onNodeWithTag("history-keyword").performScrollTo().performTextInput("阅读")
        compose.onNodeWithTag("history-search").performScrollTo().performClick()
        compose.waitUntil(10000) { idle(); model.historyAnswer.value != null }
        compose.onNodeWithTag("history-answer").performScrollTo().assertTextContains("60 分钟", substring = true)
        shot("history-answer")
        compose.onNodeWithTag("history-record-history-real").performScrollTo().performClick()
        assertEquals("RECORD" to "history-real", opened)
    }
    @Test fun `shared text stays candidate until explicitly selected and saved`() {
        compose.setContent { view = LocalView.current; TimeAgentTheme { ChatExtractionScreen(model, "明天19:00交报告", {}) } }
        compose.onNodeWithText("提取候选事项").performScrollTo().performClick()
        compose.onNodeWithText("保存已确认事项").performScrollTo().assertIsNotEnabled()
        assertTrue(runBlocking { db.dao().reminders().isEmpty() })
        compose.onNodeWithText("确认为我的事项").performScrollTo().performClick()
        shot("chat-extraction")
        compose.onNodeWithText("保存已确认事项").performScrollTo().performClick()
        compose.waitUntil(10000) { idle(); runBlocking { db.dao().reminders().size == 1 } && !model.busy.value }
        assertEquals(1, runBlocking { db.dao().notes().size })
    }
}
