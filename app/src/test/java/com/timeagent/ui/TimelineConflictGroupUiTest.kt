package com.timeagent.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.timeagent.core.WeekState
import com.timeagent.data.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * Phase A2 UI contract for the newly approved conflict layout (week view keeps A, the expanded
 * group uses the wide B card). Components supplied by the Gemini worker are still stubs and are
 * not referenced here; this test only pins the stable nodes the timeline must expose once wired.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimelineConflictGroupUiTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 10, 19)
    private val now: Instant = LocalDateTime.of(2026, 10, 19, 10, 0).atZone(zone).toInstant()

    private fun at(hour: Int, minute: Int) =
        LocalDateTime.of(2026, 10, 19, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun data(courseStart: Int, courseEnd: Int, planStart: Int, planEnd: Int, withRecord: Boolean = false) = LocalSnapshot(
        tasks = listOf(TaskEntity("t1", "写报告")),
        plans = listOf(ScheduleBlockEntity("p1", "t1", at(planStart / 60, planStart % 60), at(planEnd / 60, planEnd % 60))),
        records = if (withRecord) listOf(TimeRecordEntity("rec", null, at(10, 15), at(10, 45), notes = "专注")) else emptyList(),
        courses = listOf(CourseEntity("c1", "高等数学", "张老师", "A101", "term", "manual", "c1", 0, 0)),
        occurrences = listOf(CourseOccurrenceEntity("o1", "c1", "2026-10-19", at(courseStart / 60, courseStart % 60), at(courseEnd / 60, courseEnd % 60), "o1")),
        loading = false,
    )

    private fun render(data: LocalSnapshot, onSelect: (TimelineItem) -> Unit = {}) {
        compose.setContent {
            TimeAgentTheme {
                Surface(Modifier.fillMaxSize()) {
                    TimelineScreen(data, WeekState(day), now, zone, onSelect, {})
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * TimelineDayLayout.bounds puts a record in a split lane: 18% of the day (clamped to 7dp–30%),
     * then the scheduled region loses a 2dp inset. Without a record the group owns the full column.
     */
    private fun assertGroupWidth(group: SemanticsNode, dayHeader: SemanticsNode, withRecord: Boolean) {
        val density = group.layoutInfo.density.density
        val dayWidth = dayHeader.boundsInRoot.width
        val expected = dayWidth * (if (withRecord) 0.82f else 1f) - 2f * density
        val tolerance = 4f * density
        // Surface exposes its minimum 48dp touch target in semantics. The visual scheduled lane
        // can be narrower when an actual record is beside it; do not mistake that touch target
        // for the painted card width. This also reproduces on the original timeline.
        val maximumTarget = maxOf(expected, 48f * density)
        assertTrue(
            "group target ${group.boundsInRoot.width} must contain the scheduled lane $expected without exceeding its minimum touch target $maximumTarget",
            group.boundsInRoot.width >= expected - tolerance && group.boundsInRoot.width <= maximumTarget + tolerance,
        )
    }

    private fun dayHeader() = compose.onNodeWithContentDescription("2026-10-19 周一").fetchSemanticsNode()

    @Test fun `overlapping course and plan collapse into one full column group and hide individuals`() {
        render(data(courseStart = 10 * 60, courseEnd = 11 * 60, planStart = 10 * 60 + 30, planEnd = 11 * 60 + 30))

        // Contract: one summary per connected overlap group, tagged for stable UI tests.
        compose.onNodeWithTag("timeline-conflict-group").assertExists()
        compose.onAllNodesWithContentDescription("计划 写报告", substring = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("课程 高等数学", substring = true).assertCountEquals(0)
        assertGroupWidth(compose.onNodeWithTag("timeline-conflict-group").fetchSemanticsNode(), dayHeader(), withRecord = false)
    }

    @Test fun `actual record keeps its lane outside the group`() {
        render(data(courseStart = 10 * 60, courseEnd = 11 * 60, planStart = 10 * 60 + 30, planEnd = 11 * 60 + 30, withRecord = true))

        compose.onNodeWithTag("timeline-conflict-group").assertExists()
        // Actual records keep their own layout and never join a conflict group.
        compose.onNodeWithContentDescription("实际 专注", substring = true).assertExists()
        compose.onAllNodesWithContentDescription("计划 写报告", substring = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("课程 高等数学", substring = true).assertCountEquals(0)
        assertGroupWidth(compose.onNodeWithTag("timeline-conflict-group").fetchSemanticsNode(), dayHeader(), withRecord = true)
    }

    @Test fun `touching half open intervals are not a conflict group`() {
        render(data(courseStart = 10 * 60, courseEnd = 11 * 60, planStart = 11 * 60, planEnd = 12 * 60))

        compose.onNodeWithTag("timeline-conflict-group").assertDoesNotExist()
        compose.onNodeWithContentDescription("课程 高等数学", substring = true).assertExists()
        compose.onNodeWithContentDescription("计划 写报告", substring = true).assertExists()
    }

    @Test fun `clicking the group opens wide cards and a course selects its original item`() {
        var selected: TimelineItem? = null
        render(data(courseStart = 10 * 60, courseEnd = 11 * 60, planStart = 10 * 60 + 30, planEnd = 11 * 60 + 30)) { selected = it }

        compose.onNodeWithTag("timeline-conflict-group").performClick()
        compose.onNodeWithTag("timeline-conflict-sheet").assertIsDisplayed()
        compose.onNodeWithText("冲突组 · 2 项").assertIsDisplayed()
        compose.onNodeWithTag("conflict-sheet-item-o1").performClick()
        compose.waitForIdle()

        // TimelineScreen only reports the selection; TimeAgentApp owns the detail screen.
        assertEquals("o1", selected?.id)
        assertEquals(ItemKind.COURSE, selected?.kind)
        compose.onNodeWithTag("timeline-conflict-sheet").assertDoesNotExist()
    }
}
