package com.timeagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import com.timeagent.core.TimeSpan
import com.timeagent.data.CourseOccurrenceEntity
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimelineCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `cards no longer reserve a gutter for a dark vertical stripe`() {
        val start = Instant.parse("2026-10-19T02:00:00Z")
        val item = TimelineItem("no-stripe", ItemKind.RECORD, "睡眠", TimeSpan(start, start.plusSeconds(7200)), entity = Unit)
        compose.setContent { TimeAgentTheme { Box(Modifier.fillMaxSize()) {
            TimelineEventCard(item, ZoneId.of("Asia/Shanghai"), 2.0, false, Modifier.width(110.dp).height(160.dp).testTag("unstriped-card")) {}
        } } }
        val card = compose.onNodeWithTag("unstriped-card").fetchSemanticsNode()
        val title = compose.onNodeWithText("睡眠", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("Title should start near the edge after the dark stripe and its gutter are removed", title.boundsInRoot.left - card.boundsInRoot.left <= 6f * card.layoutInfo.density.density)
    }

    @Test fun `subpixel card width changes preserve title layout inputs during zoom`() {
        var width by mutableStateOf(110.dp)
        val start = Instant.parse("2026-10-19T02:00:00Z")
        val title = "数字信号处理实验与课程设计的完整课程名称"
        val item = TimelineItem("zoom-paragraph", ItemKind.COURSE, title, TimeSpan(start, start.plusSeconds(7200)), entity = Unit)
        compose.setContent {
            TimeAgentTheme {
                TimelineEventCard(item, ZoneId.of("Asia/Shanghai"), 2.0, false, Modifier.width(width).height(260.dp), cardSize = DpSize(width, 260.dp)) {}
            }
        }
        fun layout(): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(title, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single()
        }
        val before = layout()
        compose.runOnIdle { width = 111.dp }
        val after = layout()
        println("ZOOM_TITLE before=${before.layoutInput.constraints} after=${after.layoutInput.constraints} equalInputs=${before.layoutInput == after.layoutInput}")
        assertEquals("Text wrapping constraints should stay fixed within the same width step", before.layoutInput.constraints, after.layoutInput.constraints)
        assertEquals("Text shaping and wrapping inputs must remain reusable", before.layoutInput, after.layoutInput)
        assertEquals(before.lineCount, after.lineCount)
        assertFalse(after.hasVisualOverflow)
    }

    @Test fun `tiny pinch changes preserve the card typography until the next readable size step`() {
        var zoom by mutableDoubleStateOf(2.0)
        val start = Instant.parse("2026-10-19T02:00:00Z")
        val title = "合成课程的完整标题与课程设计"
        val item = TimelineItem("stable-type", ItemKind.COURSE, title, TimeSpan(start, start.plusSeconds(7200)), entity = Unit)
        compose.setContent { TimeAgentTheme { TimelineEventCard(item, ZoneId.of("Asia/Shanghai"), zoom, false, Modifier.width(110.dp).height(260.dp)) {} } }
        fun titleLayout(): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(title, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single()
        }
        val before = titleLayout()
        compose.runOnIdle { zoom = 2.01 }
        val after = titleLayout()
        assertEquals("Subpixel pinch movement should reuse one readable font step", before.layoutInput.style.fontSize, after.layoutInput.style.fontSize)
        assertEquals(before.lineCount, after.lineCount)
        assertFalse(after.hasVisualOverflow)
    }

    @Test fun `notes become fully readable after the title and time gain enough room`() {
        var height by mutableStateOf(16.dp)
        val note = "合成地点与教师说明，不应在无显示空间时预先排版"
        val start = Instant.parse("2026-10-19T02:00:00Z")
        val item = TimelineItem("deferred-note", ItemKind.COURSE, "课程标题", TimeSpan(start, start.plusSeconds(7200)), note, Unit)
        compose.setContent {
            TimeAgentTheme {
                TimelineEventCard(item, ZoneId.of("Asia/Shanghai"), 3.0, false, Modifier.width(110.dp).height(height)) {}
            }
        }
        compose.onNodeWithText(note, useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { height = 260.dp }
        compose.onNodeWithText(note, useUnmergedTree = true).assertIsDisplayed()
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(note, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertFalse("Growing the card must reveal the complete metadata", results.single().hasVisualOverflow)
    }

    @Test fun `expanded event card shows a long title beyond four lines`() {
        val title = "数字信号处理实验与课程设计的完整课程名称测试文本用于缩放时展示"
        val start = Instant.parse("2026-10-19T02:00:00Z")
        val subtitle = "五山校区 · 博学楼201教室\n教师：合成教师"
        val item = TimelineItem("long", ItemKind.COURSE, title, TimeSpan(start, start.plusSeconds(7200)), subtitle, Unit)
        compose.setContent { TimeAgentTheme { Box(Modifier.fillMaxSize()) { TimelineEventCard(item, ZoneId.of("Asia/Shanghai"), 3.0, false, Modifier.width(110.dp).height(260.dp)) {} } } }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(title, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertTrue("long title should use more than four lines; actual lines=${layouts.single().lineCount}, width=${layouts.single().size.width}, textLength=${title.length}", layouts.single().lineCount > 4)
        assertFalse("zoomed title should remain complete", layouts.single().hasVisualOverflow)
        val subtitleLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(subtitle, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(subtitleLayouts) }
        assertFalse("room and teacher should wrap completely when the block has space", subtitleLayouts.single().hasVisualOverflow)
    }
    @Test fun `same course keeps its color across teaching sections and rooms`() {
        val start = Instant.parse("2026-10-19T02:00:00Z")
        val occurrence = CourseOccurrenceEntity("a", "section-a", "2026-10-19", start.toEpochMilli(), start.plusSeconds(3600).toEpochMilli(), "a")
        val first = TimelineItem("a", ItemKind.COURSE, "合成课程", TimeSpan(start, start.plusSeconds(3600)), "一号教室", occurrence)
        val second = first.copy(id = "b", subtitle = "二号教室", entity = occurrence.copy(id = "b", courseId = "section-b", sourceOccurrenceKey = "b"))
        val paints = mutableListOf<TimelinePaint>()
        compose.setContent { TimeAgentTheme { paints.clear(); paints += first.paint(); paints += second.paint() } }
        compose.runOnIdle { assertEquals("same subject should keep one hue", paints[0], paints[1]) }
    }
}
