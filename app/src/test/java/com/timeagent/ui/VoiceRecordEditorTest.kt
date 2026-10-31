package com.timeagent.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.timeagent.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceRecordEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `editing a spoken study record keeps its independent activity and subject notes`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = Instant.parse("2026-10-23T02:00:00Z")
        val record = TimeRecordEntity("spoken", null, now.minusSeconds(86400).toEpochMilli(), now.minusSeconds(75600).toEpochMilli(), title = "学习", notes = "数字信号处理")
        var saved: EditValues? = null
        compose.setContent { TimeAgentTheme {
            EditorScreen(EditorRequest(EditorKind.RECORD, record), LocalSnapshot(records = listOf(record), loading = false),
                now, zone, false, {}, { saved = it })
        } }
        compose.onNodeWithText("学习").performScrollTo().performTextReplacement("复习")
        compose.onNodeWithText("数字信号处理").performScrollTo().performTextReplacement("数字信号处理第三章")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertNotNull(saved)
            assertEquals("复习", saved!!.title)
            assertEquals("数字信号处理第三章", saved!!.notes)
            assertEquals(record.startAt, saved!!.start)
            assertEquals(record.endAt, saved!!.end)
        }
    }
}
