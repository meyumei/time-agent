package com.timeagent.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.timeagent.core.TimelineSelection
import com.timeagent.core.WeekState
import com.timeagent.data.LocalSnapshot
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimelinePendingSelectionTest {
    @get:Rule val compose = createComposeRule()
    private val selected = mutableListOf<TimelineSelection>()

    private fun show() {
        compose.setContent { TimeAgentTheme { Surface(Modifier.fillMaxSize()) {
            TimelineScreen(LocalSnapshot(loading = false), WeekState(LocalDate.of(2026, 10, 19)),
                Instant.parse("2026-10-19T08:00:00Z"), ZoneId.of("Asia/Shanghai"), {}, {}, onCreateAt = selected::add)
        } } }
    }

    private fun tap13() {
        val x = compose.onNodeWithText("20", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        val y = compose.onNodeWithText("13:00").fetchSemanticsNode().boundsInRoot.center.y + 2f
        compose.onRoot().performTouchInput { click(Offset(x, y)) }
    }

    @Test fun `blank tap stages a range and second tap adds exactly one selected range`() {
        show(); tap13()
        assertTrue(selected.isEmpty())
        compose.onNodeWithTag("timeline-pending-selection").assertIsDisplayed()
        compose.onNodeWithTag("timeline-pending-add").performClick()
        assertEquals(listOf(TimelineSelection(LocalDate.of(2026, 10, 20), 780, 840)), selected)
        compose.onNodeWithTag("timeline-pending-selection").assertDoesNotExist()
    }

    @Test fun `dragging the end handle preserves start and commits on release`() {
        show(); tap13()
        val hour = compose.onNodeWithText("14:00").fetchSemanticsNode().boundsInRoot.center.y -
            compose.onNodeWithText("13:00").fetchSemanticsNode().boundsInRoot.center.y
        compose.onNodeWithTag("timeline-selection-end-handle").performTouchInput {
            down(center); moveBy(Offset(0f, hour)); up()
        }
        assertEquals(1, selected.size)
        assertEquals(780, selected.single().startMinute)
        assertEquals(900, selected.single().endMinute)
    }

    @Test fun `a second finger cancels a pending drag and never opens an add page`() {
        show(); tap13()
        val point = compose.onNodeWithTag("timeline-pending-selection").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput {
            down(0, point); down(1, point + Offset(35f, 0f))
            moveBy(1, Offset(40f, 0f)); up(0); up(1)
        }
        assertTrue(selected.isEmpty())
        compose.onNodeWithTag("timeline-pending-selection").assertDoesNotExist()
    }

    @Test fun `long pressing without dragging retains the default one hour`() {
        show()
        val x = compose.onNodeWithText("20", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        val y = compose.onNodeWithText("13:00").fetchSemanticsNode().boundsInRoot.center.y + 2f
        compose.onRoot().performTouchInput { longClick(Offset(x, y)) }
        assertEquals(1, selected.size)
        assertEquals(60, selected.single().endMinute - selected.single().startMinute)
    }
}
