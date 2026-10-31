package com.timeagent.assistant

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.*
import com.timeagent.ui.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
class AssistantVoiceResultTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: TimeAgentDatabase
    private lateinit var model: TimeViewModel
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TimeAgentDatabase::class.java).allowMainThreadQueries().build()
        model = TimeViewModel(TimeRepository(db, Clock.fixed(Instant.parse("2026-10-20T08:00:00Z"), ZoneId.of("Asia/Shanghai"))))
        compose.setContent { TimeAgentTheme { AssistantScreen(model, {}, "新建任务 语音阅读 半小时", true) } }
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); compose.onAllNodesWithText("确认保存").fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun cleanup() { model.viewModelScope.cancel(); db.close() }
    @Test fun `recognized text prepares a preview without writing and cancellation preserves input`() {
        assertTrue(runBlocking { db.dao().tasks().isEmpty() })
        compose.onNodeWithText("取消预览").performScrollTo().performClick()
        assertTrue(runBlocking { db.dao().tasks().isEmpty() })
        compose.onNodeWithTag("assistant-input").assertTextContains("新建任务 语音阅读 半小时")
        compose.onNodeWithText("点击说话").assertDoesNotExist()
    }
    @Test fun `recognized command writes only after explicit confirmation`() {
        assertTrue(runBlocking { db.dao().tasks().isEmpty() })
        compose.onNodeWithText("确认保存").performScrollTo().performClick()
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); runBlocking { db.dao().tasks().size == 1 } }
        assertEquals("语音阅读", runBlocking { db.dao().tasks().single().title })
        assertEquals(30, runBlocking { db.dao().tasks().single().estimatedMinutes })
    }
}
