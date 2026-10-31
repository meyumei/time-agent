package com.timeagent.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.timeagent.core.TimeSpan
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConflictGroupAppearanceTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val start = Instant.parse("2026-10-21T00:00:00Z")
    private val courses = listOf(
        TimelineItem("a", ItemKind.COURSE, "高等数学完整课程名称", TimeSpan(start, start.plusSeconds(6000)), "A楼 302\n教师：王老师", Unit),
        TimelineItem("b", ItemKind.COURSE, "大学物理完整课程名称", TimeSpan(start.plusSeconds(1800), start.plusSeconds(7800)), "理科楼 205\n教师：李老师", Unit),
        TimelineItem("c", ItemKind.PLAN, "程序设计基础完整计划名称", TimeSpan(start.plusSeconds(3600), start.plusSeconds(9600)), "实验楼 401\n教师：陈老师", Unit),
    )

    @Test fun `a fifty dp day column exposes one readable conflict entry`() {
        var opens = 0
        compose.setContent { TimeAgentTheme { TimelineConflictGroupCard(courses, zone, Modifier.width(50.dp).height(100.dp)) { opens++ } } }
        compose.onNodeWithContentDescription("查看3项时间冲突").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun `approved narrow summary shows course names instead of a large warning`() {
        val shortNames = courses.take(2).mapIndexed { index, item ->
            item.copy(title = if (index == 0) "数字系统设计" else "数字信号处理")
        }
        compose.setContent { TimeAgentTheme { TimelineConflictGroupCard(shortNames, zone, Modifier.width(50.dp).height(65.dp)) {} } }
        compose.onNodeWithText("重叠", useUnmergedTree = true).assertIsDisplayed()
        shortNames.forEach { item ->
            compose.onNodeWithText(item.title, useUnmergedTree = true).assertIsDisplayed()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(item.title, useUnmergedTree = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            org.junit.Assert.assertFalse("Both course names must fit the actual 50dp by 65dp block", layouts.single().hasVisualOverflow)
        }
    }

    @Test fun `compact approved summary keeps two names and the remaining count`() {
        compose.setContent { TimeAgentTheme { TimelineConflictGroupCard(courses, zone, Modifier.width(50.dp).height(65.dp)) {} } }
        compose.onNodeWithText(courses.first().title, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(courses[1].title, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("+1", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("查看3项时间冲突").assertIsDisplayed()
    }

    @Test fun `expanded conflict sheet shows full wide cards and selects the requested original item`() {
        val selected = mutableListOf<String>()
        var closed = 0
        compose.setContent { TimeAgentTheme { TimelineConflictGroupSheet(courses, zone, { selected += it.id }, { closed++ }) } }
        courses.forEach { course ->
            compose.onNodeWithText(course.title, useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText(course.subtitle, useUnmergedTree = true).assertIsDisplayed()
        }
        compose.onNodeWithText(courses.last().title).performClick()
        compose.runOnIdle { assertEquals(listOf("c"), selected); assertEquals(1, closed) }
    }
}
