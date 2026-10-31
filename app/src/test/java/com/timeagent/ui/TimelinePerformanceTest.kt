package com.timeagent.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.swipe
import com.timeagent.core.WeekState
import com.timeagent.core.TimelineSelection
import com.timeagent.data.LocalSnapshot
import com.timeagent.data.ScheduleBlockEntity
import com.timeagent.data.TaskEntity
import com.timeagent.data.ReminderEntity
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
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimelinePerformanceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `pinch frames reuse the reminder index instead of scanning all reminders per day`() {
        val start = Instant.parse("2026-10-19T08:00:00Z")
        val reminders = CountingList((0 until 120).map { index ->
            ReminderEntity("r$index", "提醒$index", start.plusSeconds(index * 3600L).toEpochMilli())
        })
        compose.setContent {
            TimeAgentTheme {
                Surface(Modifier.fillMaxSize()) {
                    TimelineScreen(
                        LocalSnapshot(reminders = reminders, loading = false), WeekState(LocalDate.of(2026, 10, 19)),
                        start, ZoneId.of("Asia/Shanghai"), {}, {},
                    )
                }
            }
        }
        compose.waitForIdle()
        val baseline = reminders.reads
        compose.onRoot().performTouchInput {
            val focus = Offset(width * .35f, height * .5f)
            down(0, focus - Offset(18f, 0f))
            down(1, focus + Offset(18f, 0f))
            repeat(18) { step ->
                val radius = 18f + (step + 1) * 1.5f
                moveTo(0, focus - Offset(radius, 0f))
                moveTo(1, focus + Offset(radius, 0f))
            }
            up(0); up(1)
        }
        compose.runOnIdle {
            println("ZOOM_REMINDER_READS initial=$baseline gesture=${reminders.reads - baseline}")
            assertEquals("Changing only the viewport must not rescan any reminder entities", baseline, reminders.reads)
        }
    }

    @Test fun `repeated fractional date drags do not rebuild the semester source`() {
        val tasks = CountingList(listOf(TaskEntity("task", "连续平移课程")))
        val start = Instant.parse("2026-10-20T02:00:00Z").toEpochMilli()
        val snapshot = LocalSnapshot(
            tasks = tasks,
            plans = listOf(ScheduleBlockEntity("plan", "task", start, start + 7_200_000)),
            loading = false,
        )
        compose.setContent {
            TimeAgentTheme {
                Surface(Modifier.fillMaxSize()) {
                    TimelineScreen(
                        snapshot, WeekState(LocalDate.of(2026, 10, 19)),
                        Instant.parse("2026-10-19T08:00:00Z"), ZoneId.of("Asia/Shanghai"), {}, {},
                    )
                }
            }
        }
        compose.waitForIdle()
        // Warm the eighth, partially visible day before measuring repeated frames.
        drag(-14f)
        val readsAfterWarmup = tasks.reads

        repeat(3) { drag(-8f); drag(8f) }

        compose.runOnIdle {
            assertEquals(
                "Fractional pans must reuse the source instead of mapping the semester every frame",
                readsAfterWarmup, tasks.reads,
            )
        }
    }

    @Test fun `retained dates preserve event text nodes when the previous day leaves the screen`() {
        showPlan()
        val before = compose.onNodeWithText("连续平移课程", useUnmergedTree = true).fetchSemanticsNode().id

        drag(-65f)

        val after = compose.onNodeWithText("连续平移课程", useUnmergedTree = true).fetchSemanticsNode().id
        assertEquals("A shared date must retain its event composition and text layout", before, after)
    }

    @Test fun `card clicks and blank creation use the moved positions after fractional panning`() {
        val selected = mutableListOf<String>()
        val created = mutableListOf<TimelineSelection>()
        showPlan(onSelect = { selected += it.id }, onCreateAt = created::add)
        val original = compose.onNodeWithContentDescription("计划 连续平移课程", substring = true).fetchSemanticsNode().boundsInRoot.center

        drag(-40f)
        compose.onNodeWithContentDescription("计划 连续平移课程", substring = true).performTouchInput { click() }
        assertEquals(listOf("plan"), selected)
        assertTrue("A card click must not also create an item behind it", created.isEmpty())

        compose.onRoot().performTouchInput { click(original) }
        assertTrue("A first blank tap stages the add affordance", created.isEmpty())
        compose.onNodeWithTag("timeline-pending-add").performClick()
        assertEquals(1, created.size)
        assertEquals("The vacated position is now Wednesday's blank grid", LocalDate.of(2026, 10, 21), created.single().date)
    }

    private fun showPlan(onSelect: (TimelineItem) -> Unit = {}, onCreateAt: (TimelineSelection) -> Unit = {}) {
        val start = Instant.parse("2026-10-20T02:00:00Z").toEpochMilli()
        val snapshot = LocalSnapshot(
            tasks = listOf(TaskEntity("task", "连续平移课程")),
            plans = listOf(ScheduleBlockEntity("plan", "task", start, start + 7_200_000)),
            loading = false,
        )
        compose.setContent {
            TimeAgentTheme {
                Surface(Modifier.fillMaxSize()) {
                    TimelineScreen(
                        snapshot, WeekState(LocalDate.of(2026, 10, 19)),
                        Instant.parse("2026-10-19T08:00:00Z"), ZoneId.of("Asia/Shanghai"), onSelect, {}, onCreateAt = onCreateAt,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun drag(distance: Float) {
        compose.onRoot().performTouchInput {
            val from = Offset(width * .7f, height * .55f)
            swipe(from, from + Offset(distance, 0f), 180)
        }
        compose.waitForIdle()
    }

    private class CountingList<T>(private val values: List<T>) : AbstractList<T>() {
        var reads = 0
            private set
        override val size: Int get() = values.size
        override fun get(index: Int): T = values[index].also { reads++ }
    }
}
