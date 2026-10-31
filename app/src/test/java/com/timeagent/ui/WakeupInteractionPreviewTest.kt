package com.timeagent.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.timeagent.core.TimelineSelection
import com.timeagent.core.WeekState
import com.timeagent.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Native production components with synthetic data; no phone connection or database writes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WakeupInteractionPreviewTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var view: View
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-22T08:00:00Z")
    private fun at(day: Int, minute: Int) = LocalDate.of(2026, 10, day).atStartOfDay(zone).toInstant().plusSeconds(minute * 60L).toEpochMilli()
    private fun sample(): LocalSnapshot {
        val names = listOf("数字信号处理实验", "数字信号处理", "数字系统设计", "信息论与编码", "C++程序设计", "光纤通信技术")
        val courses = names.mapIndexed { index, name -> CourseEntity("c$index", name, "合成教师", "合成教室", "term", "preview", "c$index", 0, 0) }
        val lessons = listOf(
            Triple(0, 19, 480 to 700), Triple(2, 19, 870 to 1030), Triple(1, 20, 600 to 700),
            Triple(2, 20, 870 to 1030), Triple(3, 20, 900 to 1080), Triple(4, 20, 1140 to 1240),
            Triple(2, 21, 870 to 1080), Triple(1, 22, 600 to 700), Triple(5, 22, 1140 to 1300),
        ).mapIndexed { index, (course, day, range) ->
            CourseOccurrenceEntity("o$index", "c$course", "2026-10-$day", at(day, range.first), at(day, range.second), "preview-$index")
        }
        return LocalSnapshot(
            courses = courses, occurrences = lessons,
            tasks = listOf(TaskEntity("sleep", "睡眠", activityId = "sleep"), TaskEntity("back", "练背", activityId = "fitness-back"), TaskEntity("review", "课程复习"), TaskEntity("meeting", "党会")),
            plans = listOf(ScheduleBlockEntity("back", "back", at(21, 1110), at(21, 1210)), ScheduleBlockEntity("review", "review", at(22, 1170), at(22, 1300)), ScheduleBlockEntity("meeting", "meeting", at(23, 900), at(23, 960))),
            records = listOf(TimeRecordEntity("sleep-wed", "sleep", at(21, 90), at(21, 510)), TimeRecordEntity("sleep-thu", "sleep", at(22, 120), at(22, 540)),
                TimeRecordEntity("chat", null, at(21, 560), at(21, 620), notes = "沟通"), TimeRecordEntity("ai", null, at(21, 660), at(21, 720), notes = "做AI"), TimeRecordEntity("research", null, at(21, 1210), at(21, 1300), notes = "科研")),
            loading = false,
        )
    }

    @Test fun `native preview covers week range selection and both add types`() {
        var selection by mutableStateOf<TimelineSelection?>(null)
        val data = sample()
        compose.setContent {
            view = LocalView.current
            TimeAgentTheme {
                TimelineColorEnvironment {
                    Surface(Modifier.fillMaxSize()) {
                        val picked = selection
                        if (picked == null) Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f)) {
                                TimelineScreen(data, WeekState(LocalDate.of(2026, 10, 22)), now, zone, {}, {}, onCreateAt = { selection = it })
                            }
                            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                listOf("时间轴", "任务", "按住说话", "复盘", "我的").forEach { Text(it) }
                            }
                        } else {
                            val request = remember(picked) { EditorRequest(EditorKind.PLAN, selection = picked, scheduleRange = true) }
                            EditorScreen(request, data, now, zone, false, { selection = null }, {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle(); capture("timeline-wakeup-final-week")
        val x = compose.onNodeWithText("23", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        val y = compose.onNodeWithText("13:00").fetchSemanticsNode().boundsInRoot.center.y + 2
        val hour = compose.onNodeWithText("14:00").fetchSemanticsNode().boundsInRoot.center.y - y + 2
        compose.onRoot().performTouchInput { click(Offset(x, y)) }
        compose.onNodeWithTag("timeline-pending-selection").assertIsDisplayed()
        capture("timeline-wakeup-selection")
        compose.onNodeWithTag("timeline-selection-end-handle").performTouchInput { down(center); moveBy(Offset(0f, hour)); up() }
        compose.onNodeWithTag("grid-block-editor").assertIsDisplayed()
        compose.onNodeWithTag("grid-end-time").assertTextContains("15:00")
        capture("timeline-wakeup-add-plan")
        compose.onNodeWithTag("grid-kind-record").performClick()
        capture("timeline-wakeup-add-actual")
    }

    @Test fun `native color picker applies a named preset and persists the choice`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("timeline_colors_v1", Context.MODE_PRIVATE).edit().clear().commit()
        compose.setContent {
            view = LocalView.current
            TimeAgentTheme { TimelineColorEnvironment { Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(20.dp)) { Text("活动模块 · 睡眠"); TimelineColorChoice("activity:sleep", "睡眠") }
            } } }
        }
        compose.onNodeWithTag("timeline-color-choice").performClick()
        capture("timeline-wakeup-palette")
        compose.onNodeWithTag("timeline-preset-violet").performClick()
        compose.runOnIdle { assertEquals("violet", TimelineColorPreferences(context).read()["activity:sleep"]) }
    }

    private fun capture(name: String) = compose.runOnIdle {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        org.robolectric.shadows.ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.draw(canvas)
        val file = File("build/outputs/previews/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
