package com.timeagent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.timeagent.data.LocalSnapshot
import com.timeagent.data.ScheduleBlockEntity
import com.timeagent.data.TaskEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compact-display contract for the today-page briefing header.
 *
 * Only the collapsed height is new: the expand/collapse behaviour below already exists and is
 * asserted so a compact rewrite cannot drop the full date, the next arrangement or its callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DailyBriefingCompactUiTest {
    @get:Rule val compose = createComposeRule()

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-20T01:00:00Z") // 09:00 in Shanghai
    private val today = LocalDate.of(2026, 10, 20)
    private val wrapTag = "briefing-wrap"

    /** Mirrors DailyBriefing.briefingDateFormatter so the selector matches the rendered date. */
    private val fullDate: String =
        today.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.SIMPLIFIED_CHINESE))

    private fun stamp(hour: Int, minute: Int = 0) = today.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun snapshot() = LocalSnapshot(
        tasks = listOf(TaskEntity("task-review", "产品评审")),
        plans = listOf(ScheduleBlockEntity("plan-review", "task-review", stamp(10, 30), stamp(11, 30))),
        loading = false,
    )

    private fun showBriefing(onOpen: (String, String) -> Unit = { _, _ -> }) {
        compose.setContent {
            TimeAgentTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column {
                        // Wrap-content box measures the card together with its outer spacing.
                        Box(Modifier.testTag(wrapTag)) {
                            DailyBriefingCard(
                                data = snapshot(),
                                now = now,
                                zone = zone,
                                sync = BriefingSyncStatus(BriefingSyncState.READY),
                                onOpen = onOpen,
                                onHealth = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun `collapsed briefing including outer spacing fits in eighty dp at 411dp width`() {
        showBriefing()
        // Guard against measuring an empty wrapper: the real card content must be on screen.
        compose.onNodeWithText("今日管家简报").assertIsDisplayed()
        compose.onNodeWithTag("briefing-toggle").assertIsDisplayed()
        // The full date now belongs to the expanded area, never to the collapsed header.
        compose.onNodeWithText(fullDate).assertDoesNotExist()

        val heightPx = compose.onNodeWithTag(wrapTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        val height = with(compose.density) { heightPx.toDp() }
        println("Collapsed briefing height including outer spacing: $height")
        assertTrue(
            "Default collapsed briefing (card + outer spacing) must be at most 80dp at normal font scale, was $height",
            height <= 80.dp,
        )
    }

    @Test fun `expanding keeps the full date and opens the next arrangement, collapsing hides the detail`() {
        val opened = mutableListOf<Pair<String, String>>()
        showBriefing(onOpen = { type, id -> opened += type to id })

        compose.onNodeWithContentDescription("展开今日简报").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(fullDate).assertIsDisplayed()
        compose.onNodeWithText("下一项").assertIsDisplayed()
        val nextItem = compose.onNodeWithText("日程 10:30 · 产品评审")
        nextItem.performScrollTo().performClick()
        assertEquals(listOf("PLAN" to "plan-review"), opened)

        compose.onNodeWithContentDescription("收起今日简报").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("下一项").assertDoesNotExist()
        compose.onNodeWithText("日程 10:30 · 产品评审").assertDoesNotExist()
        compose.onNodeWithContentDescription("展开今日简报").assertIsDisplayed()
        // Collapsing must also drop the full date again, it lives in the expanded area.
        compose.onNodeWithText(fullDate).assertDoesNotExist()
    }
}
