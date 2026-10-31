package com.timeagent.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import com.timeagent.data.LocalSnapshot
import com.timeagent.data.ScheduleBlockEntity
import com.timeagent.data.TaskEntity
import com.timeagent.data.TimeRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TimelinePaletteTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `a module keeps its chosen color across plans and actual records`() {
        val at = Instant.parse("2026-10-22T00:00:00Z")
        val items = LocalSnapshot(
            tasks = listOf(TaskEntity("sleep-task", "睡眠", activityId = "sleep")),
            plans = listOf(ScheduleBlockEntity("p", "sleep-task", at.toEpochMilli(), at.plusSeconds(3600).toEpochMilli())),
            records = listOf(TimeRecordEntity("r", "sleep-task", at.toEpochMilli(), at.plusSeconds(3600).toEpochMilli())),
            loading = false,
        ).timeline(at.plusSeconds(7200))
        val paints = mutableListOf<TimelinePaint>()
        compose.setContent { TimeAgentTheme { paints.clear(); items.forEach { paints += it.paint() } } }
        compose.runOnIdle {
            assertEquals("The activity module owns the hue, rather than plan/record kind", paints[0].container, paints[1].container)
            paints.forEach { assertEquals("One module shares its foreground as well", paints[0].content, it.content) }
        }
    }

    @Test fun `different free activities use distinct preset colors`() {
        val at = Instant.parse("2026-10-22T00:00:00Z")
        val items = LocalSnapshot(records = listOf(
            TimeRecordEntity("sleep", null, at.toEpochMilli(), at.plusSeconds(3600).toEpochMilli(), notes = "睡眠"),
            TimeRecordEntity("gym", null, at.toEpochMilli(), at.plusSeconds(3600).toEpochMilli(), notes = "健身"),
        ), loading = false).timeline(at.plusSeconds(7200))
        val paints = mutableListOf<TimelinePaint>()
        compose.setContent { TimeAgentTheme { paints.clear(); items.forEach { paints += it.paint() } } }
        compose.runOnIdle { assertNotEquals("Different activity modules need distinct default hues", paints[0].container, paints[1].container) }
    }

    @Test fun `every preset has readable title and secondary text in light and dark modes`() {
        val at = Instant.parse("2026-10-22T00:00:00Z")
        val item = LocalSnapshot(records = listOf(
            TimeRecordEntity("r", null, at.toEpochMilli(), at.plusSeconds(3600).toEpochMilli(), notes = "阅读"),
        ), loading = false).timeline(at.plusSeconds(7200)).single()
        val paints = mutableListOf<TimelinePaint>()
        compose.setContent {
            paints.clear()
            listOf(false, true).forEach { dark -> TimeAgentTheme(darkTheme = dark) {
                TimelinePalette.presets.forEach { preset ->
                    androidx.compose.runtime.CompositionLocalProvider(LocalTimelineColors provides TimelineColors(mapOf(item.colorSubject to preset.id))) {
                        paints += item.paint()
                    }
                }
            } }
        }
        compose.runOnIdle {
            paints.forEachIndexed { index, p ->
                val a = p.container.luminance().toDouble()
                val b = p.content.luminance().toDouble()
                val contrast = (maxOf(a, b) + .05) / (minOf(a, b) + .05)
                org.junit.Assert.assertTrue("Preset $index title needs 7:1, measured $contrast", contrast >= 7)
                val s = p.secondary.luminance().toDouble()
                val secondaryContrast = (maxOf(a, s) + .05) / (minOf(a, s) + .05)
                org.junit.Assert.assertTrue("Preset $index secondary text needs 4.5:1, measured $secondaryContrast", secondaryContrast >= 4.5)
            }
        }
    }

    @Test fun `rendered agenda keeps its small running status and notes readable`() {
        val at = Instant.parse("2026-10-22T00:00:00Z")
        val item = LocalSnapshot(records = listOf(
            TimeRecordEntity("r", null, at.toEpochMilli(), null, notes = "睡眠"),
        ), loading = false).timeline(at.plusSeconds(7200)).single()
        var background = Color.Unspecified
        compose.setContent { TimeAgentTheme(darkTheme = false) {
            background = item.paint().container
            TimelineAgendaCard(item, java.time.ZoneId.of("Asia/Shanghai"), false, onClick = {})
        } }
        listOf("实际", "计时中", "● 计时中").forEach { text ->
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val foreground = results.single().layoutInput.style.color
            val rendered = foreground.compositeOver(background)
            val a = background.luminance().toDouble()
            val b = rendered.luminance().toDouble()
            val contrast = (maxOf(a, b) + .05) / (minOf(a, b) + .05)
            org.junit.Assert.assertTrue("Rendered '$text' needs 4.5:1, measured $contrast", contrast >= 4.5)
        }
    }
}
