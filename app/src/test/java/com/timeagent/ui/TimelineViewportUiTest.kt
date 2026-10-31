package com.timeagent.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.timeagent.core.WeekState
import com.timeagent.core.TimelineSelection
import com.timeagent.data.LocalSnapshot
import com.timeagent.data.TimeRecordEntity
import com.timeagent.data.TaskEntity
import com.timeagent.data.ScheduleBlockEntity
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
class TimelineViewportUiTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-10-19T08:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val week = WeekState(LocalDate.of(2026, 10, 19))

    private fun timeline(data: LocalSnapshot = LocalSnapshot(loading = false), onSelect: (TimelineItem) -> Unit = {}, onWeek: (Long) -> Unit = {}, selectedWeek: WeekState = week, onCreateAt: (TimelineSelection) -> Unit = {}) {
        compose.setContent {
            TimeAgentTheme {
                Surface(Modifier.fillMaxSize()) {
                    TimelineScreen(data, selectedWeek, now, zone, onSelect, onWeek, onCreateAt = onCreateAt)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun `initial timeline displays midnight and end of day in the same viewport`() {
        timeline()
        compose.onNodeWithText("00:00").assertIsDisplayed()
        compose.onNodeWithText("24:00").assertIsDisplayed()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val midnight = compose.onNodeWithText("00:00").fetchSemanticsNode().boundsInRoot
        val end = compose.onNodeWithText("24:00").fetchSemanticsNode().boundsInRoot
        assertTrue(midnight.top >= root.top)
        assertTrue("24:00 must fit above the viewport bottom", end.bottom <= root.bottom)
    }

    @Test fun `small date drag retains fractional movement without a weekly callback`() {
        val weekChanges = mutableListOf<Long>()
        timeline(onWeek = weekChanges::add)
        val before = compose.onNodeWithText("19", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        compose.onRoot().performTouchInput {
            swipe(Offset(width * .7f, height * .55f), Offset(width * .7f - 18f, height * .55f), 400)
        }
        val after = compose.onNodeWithText("19", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        assertTrue("An 18 pixel drag must move the header continuously", before - after > 8f)
        assertTrue("A short drag must not jump a day or a week", before - after < 40f)
        assertTrue("Dragging must not page the selected week", weekChanges.isEmpty())
    }

    @Test fun `dragging one day exposes next Monday as part of the continuous range`() {
        val weekChanges = mutableListOf<Long>()
        timeline(onWeek = weekChanges::add)
        compose.onRoot().performTouchInput {
            swipe(Offset(width * .75f, height * .55f), Offset(width * .75f - width / 7f * 1.25f, height * .55f), 500)
        }
        compose.onNodeWithText("26", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(weekChanges.isEmpty())
    }

    @Test fun `two finger pinch enlarges both dates and hours and fit restores full day`() {
        timeline()
        val initialDays = dateDistance()
        val initialHours = hourDistance()
        compose.onRoot().performTouchInput {
            val focus = Offset(width * .28f, height * .53f)
            down(0, focus - Offset(18f, 0f))
            down(1, focus + Offset(18f, 0f))
            repeat(12) { step ->
                val radius = 18f + (step + 1) * 1.7f
                moveTo(0, focus - Offset(radius, 0f))
                moveTo(1, focus + Offset(radius, 0f))
            }
            up(0)
            up(1)
        }
        assertTrue("Pinch must enlarge date widths", dateDistance() > initialDays * 1.25f)
        assertTrue("Pinch must enlarge hour heights", hourDistance() > initialHours * 1.25f)
        compose.onNodeWithContentDescription("适合屏幕").performClick()
        compose.onNodeWithText("00:00").assertIsDisplayed()
        compose.onNodeWithText("24:00").assertIsDisplayed()
        assertEquals(initialDays, dateDistance(), 2f)
    }

    @Test fun `arrows move seven days from the fractional browsed range and current restores Monday`() {
        timeline()
        compose.onRoot().performTouchInput {
            swipe(Offset(width * .75f, height * .55f), Offset(width * .75f - width / 7f * 1.25f, height * .55f), 500)
        }
        val tuesdayX = compose.onNodeWithText("20", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        compose.onNodeWithContentDescription("下一周").performClick()
        compose.onNodeWithText("27", useUnmergedTree = true).assertIsDisplayed()
        val nextTuesdayX = compose.onNodeWithText("27", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        assertEquals("Arrow navigation must retain the fractional date offset", tuesdayX, nextTuesdayX, 1f)
        compose.onNodeWithContentDescription("上一周").performClick()
        compose.onNodeWithText("20", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(tuesdayX, compose.onNodeWithText("20", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x, 1f)
        compose.onNodeWithText("本周").performClick()
        compose.onNodeWithText("19", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("周一").assertIsDisplayed()
    }

    @Test fun `current week restores Monday even when the external range starts on a Tuesday`() {
        val changes = mutableListOf<Long>()
        timeline(onWeek = changes::add, selectedWeek = week.copy(selectedWeekStart = LocalDate.of(2026, 10, 13)))
        compose.onNodeWithText("本周").performClick()
        compose.onNodeWithText("19", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("The review week must move by one calendar week", listOf(1L), changes)
    }

    @Test fun `overlapping records remain individually selectable through the list`() {
        val selected = mutableListOf<String>()
        val start = Instant.parse("2026-10-19T02:00:00Z").toEpochMilli()
        val records = (1..8).map { index -> TimeRecordEntity("r$index", null, start, start + 3_600_000, notes = "重叠记录$index") }
        timeline(LocalSnapshot(records = records, loading = false), onSelect = { selected += it.id })
        compose.onNodeWithText("列表").performClick()
        compose.onNodeWithContentDescription("实际 重叠记录8", substring = true).performScrollTo().performClick()
        assertEquals(listOf("r8"), selected)
    }

    @Test fun `a physical tap on a timeline block still opens its details`() {
        val selected = mutableListOf<String>()
        val start = Instant.parse("2026-10-19T02:00:00Z").toEpochMilli()
        timeline(LocalSnapshot(records = listOf(TimeRecordEntity("tap-record", null, start, start + 3_600_000, notes = "点击记录")), loading = false), onSelect = { selected += it.id })
        compose.onNodeWithContentDescription("实际 点击记录", substring = true).performTouchInput { click() }
        assertEquals(listOf("tap-record"), selected)
    }

    private fun dateDistance(): Float =
        compose.onNodeWithText("21", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x -
            compose.onNodeWithText("20", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x

    private fun hourDistance(): Float =
        compose.onNodeWithText("13:00").fetchSemanticsNode().boundsInRoot.center.y -
            compose.onNodeWithText("12:00").fetchSemanticsNode().boundsInRoot.center.y

    @Test fun `holding blank grid and dragging selects a time range without panning dates`() {
        val created = mutableListOf<TimelineSelection>()
        timeline(onCreateAt = created::add)
        val x = compose.onNodeWithText("20", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        val yStart = compose.onNodeWithText("13:00").fetchSemanticsNode().boundsInRoot.center.y + 2f
        val yEnd = compose.onNodeWithText("15:00").fetchSemanticsNode().boundsInRoot.center.y + 2f
        compose.onRoot().performTouchInput { down(Offset(x, yStart)) }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        compose.onRoot().performTouchInput { moveTo(Offset(x, yEnd)); up() }
        assertEquals(1, created.size)
        assertEquals(LocalDate.of(2026, 10, 20), created.single().date)
        assertEquals(780, created.single().startMinute)
        assertEquals(900, created.single().endMinute)
        assertEquals(x, compose.onNodeWithText("20", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x, 1f)
    }
    @Test fun `pinching the grid never creates a time selection`() {
        val created = mutableListOf<TimelineSelection>()
        timeline(onCreateAt = created::add)
        compose.onRoot().performTouchInput {
            val centerPoint = Offset(width * .4f, height * .5f)
            down(0, centerPoint - Offset(20f, 0f)); down(1, centerPoint + Offset(20f, 0f))
            repeat(8) { step -> moveTo(0, centerPoint - Offset(25f + step * 4, 0f)); moveTo(1, centerPoint + Offset(25f + step * 4, 0f)) }
            up(0); up(1)
        }
        assertTrue(created.isEmpty())
    }
    @Test fun `pinch still works when the second finger lands on an event card`() {
        val selected = mutableListOf<String>()
        val start = Instant.parse("2026-10-19T02:00:00Z").toEpochMilli()
        timeline(LocalSnapshot(tasks = listOf(TaskEntity("pinch-task", "手势测试")), plans = listOf(ScheduleBlockEntity("pinch-plan", "pinch-task", start, start + 7200000)), loading = false), onSelect = { selected += it.id })
        val initial = dateDistance()
        val card = compose.onNodeWithContentDescription("计划 手势测试", substring = true).fetchSemanticsNode().boundsInRoot
        val cardX = card.center.x
        val y = card.center.y
        compose.onRoot().performTouchInput {
            val blankX = width * .5f
            down(0, Offset(blankX, y)); down(1, Offset(cardX, y))
            repeat(12) { step ->
                moveTo(0, Offset(blankX + (step + 1) * 5f, y))
                moveTo(1, Offset(cardX - (step + 1) * 1.5f, y))
            }
            up(0); up(1)
        }
        assertTrue("clickable course/plan must not swallow pinch", dateDistance() > initial * 1.15f)
        assertTrue(selected.isEmpty())
    }
    @Test fun `stationary two finger hold never becomes a create gesture`() {
        val created = mutableListOf<TimelineSelection>()
        timeline(onCreateAt = created::add)
        compose.onRoot().performTouchInput {
            down(0, Offset(width * .35f, height * .5f)); down(1, Offset(width * .6f, height * .5f))
        }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        compose.onRoot().performTouchInput { up(0); up(1) }
        assertTrue("multitouch contact must not open grid creation", created.isEmpty())
    }
}
