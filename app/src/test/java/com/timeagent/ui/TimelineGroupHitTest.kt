package com.timeagent.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.timeagent.core.TimelineSelection
import com.timeagent.core.WeekState
import com.timeagent.data.*
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
class TimelineGroupHitTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `long pressing a reminder dot never turns it into a blank time selection`() {
        val at = Instant.parse("2026-10-19T02:30:00Z").toEpochMilli()
        val created = mutableListOf<TimelineSelection>()
        compose.setContent { TimeAgentTheme { Surface(Modifier.fillMaxSize()) {
            TimelineScreen(LocalSnapshot(reminders = listOf(ReminderEntity("dot", "交作业", at)), loading = false),
                WeekState(LocalDate.of(2026, 10, 19)), Instant.parse("2026-10-19T08:00:00Z"),
                ZoneId.of("Asia/Shanghai"), {}, {}, onCreateAt = created::add)
        } } }
        val dot = compose.onNodeWithTag("timeline-reminder-dot").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { longClick(dot) }
        assertTrue("The reminder is an interactive item, not blank grid", created.isEmpty())
    }

    @Test fun `long pressing the painted group after its left member ends never creates a blank slot`() {
        val start = Instant.parse("2026-10-19T02:00:00Z").toEpochMilli()
        val data = LocalSnapshot(
            tasks = listOf(TaskEntity("task", "重叠计划")),
            plans = listOf(ScheduleBlockEntity("p", "task", start + 1_800_000, start + 5_400_000)),
            courses = listOf(CourseEntity("c", "合成课程", "合成教师", "合成教室", "term", "manual", "c", 0, 0)),
            occurrences = listOf(CourseOccurrenceEntity("o", "c", "2026-10-19", start, start + 3_600_000, "o")),
            loading = false,
        )
        val created = mutableListOf<TimelineSelection>()
        compose.setContent { TimeAgentTheme { Surface(Modifier.fillMaxSize()) {
            TimelineScreen(data, WeekState(LocalDate.of(2026, 10, 19)), Instant.parse("2026-10-19T08:00:00Z"),
                ZoneId.of("Asia/Shanghai"), {}, {}, onCreateAt = created::add)
        } } }
        val group = compose.onNodeWithTag("timeline-conflict-group").fetchSemanticsNode().boundsInRoot
        val point = Offset(group.left + group.width * .1f, group.top + group.height * .85f)
        compose.onRoot().performTouchInput { longClick(point) }
        assertTrue("The painted conflict summary is occupied even where an old individual lane is empty", created.isEmpty())
        compose.onNodeWithTag("timeline-pending-selection").assertDoesNotExist()
    }
}
