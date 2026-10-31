package com.timeagent.assistant

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.*
import com.timeagent.ui.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
class AssistantScreenFlowTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: TimeAgentDatabase
    private lateinit var model: TimeViewModel
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TimeAgentDatabase::class.java).allowMainThreadQueries().build()
        model = TimeViewModel(TimeRepository(db, Clock.fixed(Instant.parse("2026-10-20T08:00:00Z"), ZoneId.of("Asia/Shanghai"))))
        compose.setContent { TimeAgentTheme { AssistantScreen(model, onClose = {}) } }
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); !model.data.value.loading }
    }
    @After fun cleanup() { model.viewModelScope.cancel(); db.close() }
    private fun enter(value: String) { compose.onNodeWithTag("assistant-input").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(value)) } }
    @Test fun `text preview cancel and confirm change real Room only after confirmation`() {
        enter("新建任务 阅读 30分钟")
        compose.onNodeWithText("本地解析").performScrollTo().performClick()
        compose.onNodeWithText("确认保存").assertExists()
        assertTrue(runBlocking { db.dao().tasks().isEmpty() })
        compose.onNodeWithText("取消预览").performScrollTo().performClick()
        assertTrue(runBlocking { db.dao().tasks().isEmpty() })
        compose.onNodeWithTag("assistant-input").assertTextContains("新建任务 阅读 30分钟")
        compose.onNodeWithText("本地解析").performScrollTo().performClick()
        compose.onNodeWithText("确认保存").performScrollTo().performClick()
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); runBlocking { db.dao().tasks().size == 1 } }
        assertEquals("阅读", runBlocking { db.dao().tasks().single().title })
        assertEquals(1, runBlocking { db.dao().history().count { it.entityType == "TASK" } })
    }
}
