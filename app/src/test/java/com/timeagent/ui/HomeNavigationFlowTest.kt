package com.timeagent.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.*
import kotlinx.coroutines.cancel
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
class HomeNavigationFlowTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: TimeAgentDatabase
    private lateinit var model: TimeViewModel

    @Before fun setup() {
        db = TimeAgentDatabase.openInMemory(ApplicationProvider.getApplicationContext<Context>())
        model = TimeViewModel(TimeRepository(db))
        compose.setContent { TimeAgentApp(model, speechFactory = { _, callbacks -> TestSpeechInput(callbacks) }) }
    }

    @After fun close() { model.viewModelScope.cancel(); db.close() }

    @Test fun `nested settings returns to settings before the butler home`() {
        compose.onNodeWithContentDescription("管家页面").performClick()
        compose.onNodeWithText("打开设置").performClick()
        compose.onNodeWithTag("settings-backup").performScrollTo().performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("返回管家").assertExists()
        compose.onNodeWithText("返回管家").performClick()
        compose.onNodeWithContentDescription("管家页面").assertIsSelected()
    }

    @Test fun `today offers recording habit deadline and training without visiting settings`() {
        compose.onNodeWithTag("today-record").assertExists().performClick()
        compose.onNodeWithText("实际记录", substring = true).assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("today-recurring").performClick()
        compose.onNodeWithText("周期任务与习惯").assertExists()
    }
}
