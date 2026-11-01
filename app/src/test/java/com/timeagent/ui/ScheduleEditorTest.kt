package com.timeagent.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.timeagent.data.LocalSnapshot
import com.timeagent.data.ScheduleBlockEntity
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScheduleEditorTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-11-01T01:00:00Z")

    @Test fun `editing a point writes equal instants rather than the timeline display anchor`() {
        val plan = ScheduleBlockEntity("p", null, now.toEpochMilli(), now.toEpochMilli(), title = "Point", kind = "POINT", timezoneId = zone.id)
        var saved: EditValues? = null
        compose.setContent {
            TimeAgentTheme { EditorScreen(EditorRequest(EditorKind.PLAN, plan), LocalSnapshot(loading = false), now, zone, false, {}, { saved = it }) }
        }
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertNotNull(saved)
            assertEquals(saved!!.start, saved!!.end)
            assertEquals("Point", saved!!.title)
            assertNull(saved!!.taskId)
        }
    }

    @Test fun `editing all day preserves inclusive dates rather than adding an extra day`() {
        val plan = ScheduleBlockEntity("a", null, now.toEpochMilli(), now.plusSeconds(86400).toEpochMilli(),
            title = "All day", kind = "ALL_DAY", allDayStartDate = "2026-11-01", allDayEndDate = "2026-11-01", timezoneId = zone.id)
        var saved: EditValues? = null
        compose.setContent {
            TimeAgentTheme { EditorScreen(EditorRequest(EditorKind.PLAN, plan), LocalSnapshot(loading = false), now, zone, false, {}, { saved = it }) }
        }
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertNotNull(saved)
            assertEquals("2026-11-01", saved!!.allDayStartDate)
            assertEquals("2026-11-01", saved!!.allDayEndDate)
        }
    }
}
