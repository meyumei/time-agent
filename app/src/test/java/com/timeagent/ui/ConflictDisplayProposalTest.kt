package com.timeagent.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timeagent.core.TimeSpan
import com.timeagent.data.CourseOccurrenceEntity
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

/** Review fixture only: no production route, repository access, or user data writes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConflictDisplayProposalTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var view: View

    @Test fun `render proposed narrow conflict summaries and their full details`() {
        compose.setContent {
            view = LocalView.current
            TimeAgentTheme(darkTheme = false) { ProposalWeek() }
        }
        compose.waitForIdle()
        capture("conflict-proposal-week")
        compose.onNodeWithTag("proposal-tuesday").performClick()
        compose.onNodeWithTag("timeline-conflict-sheet").assertIsDisplayed()
        capture("conflict-proposal-details")
    }

    @Test fun `render three short items at the actual fifty dp column width`() {
        compose.setContent {
            view = LocalView.current
            TimeAgentTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text("50dp 窄列 · 边界演示", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("以下均是合成课程。列宽与七天周视图一致。", fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("2项 / 90dp", fontSize = 11.sp)
                                ProposalConflict(proposalCourses(), Modifier.width(50.dp).height(90.dp)) {}
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("2项 / 65dp", fontSize = 11.sp)
                                ProposalConflict(proposalCourses(), Modifier.width(50.dp).height(65.dp)) {}
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("3项 / 65dp", fontSize = 11.sp)
                                ProposalConflict(proposalCourses() + proposalCourses().first().copy(id = "third", title = "大学物理"), Modifier.width(50.dp).height(65.dp)) {}
                            }
                        }
                        Text("高度不足时保留课程名摘要与“+1”，点击查看全部。", fontSize = 12.sp, lineHeight = 19.sp)
                        Text("分段用于列出课程；外框对应整组时段。\n每门课的准确时间在详情中显示。", fontSize = 12.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        compose.waitForIdle()
        capture("conflict-proposal-compact")
    }

    private fun capture(name: String) = compose.runOnIdle {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        view.draw(canvas)
        org.robolectric.shadows.ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.draw(canvas)
        val file = File("build/outputs/previews/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

private val proposalZone = ZoneId.of("Asia/Shanghai")
private fun proposalCourseEntity(id: String, title: String, start: Instant, end: Instant) =
    CourseOccurrenceEntity(id, "preview-$title", "2026-10-20", start.toEpochMilli(), end.toEpochMilli(), "preview-$id")
private fun proposalCourses(): List<TimelineItem> {
    val start = Instant.parse("2026-10-20T06:30:00Z")
    return listOf(
        TimelineItem("design", ItemKind.COURSE, "数字系统设计", TimeSpan(start, start.plusSeconds(9600)), "合成地点：A楼201\n教师：合成教师甲", proposalCourseEntity("design", "数字系统设计", start, start.plusSeconds(9600))),
        TimelineItem("signal", ItemKind.COURSE, "数字信号处理", TimeSpan(start.plusSeconds(1800), start.plusSeconds(12600)), "合成地点：B楼302\n教师：合成教师乙", proposalCourseEntity("signal", "数字信号处理", start.plusSeconds(1800), start.plusSeconds(12600))),
    )
}

@Composable private fun ProposalConflict(items: List<TimelineItem>, modifier: Modifier, onClick: () -> Unit) {
    TimelineConflictGroupCard(items, proposalZone, modifier, onClick = onClick)
}

@Composable private fun ProposalWeek() {
    var selected by remember { mutableStateOf<List<TimelineItem>?>(null) }
    val courses = remember { proposalCourses() }
    val evening = remember {
        val start = Instant.parse("2026-10-22T11:00:00Z")
        listOf(
            TimelineItem("lab", ItemKind.COURSE, "信号处理实验", TimeSpan(start, start.plusSeconds(9000)), entity = proposalCourseEntity("lab", "信号处理实验", start, start.plusSeconds(9000))),
            TimelineItem("study", ItemKind.PLAN, "课程复习", TimeSpan(start.plusSeconds(1800), start.plusSeconds(9600)), entity = Unit),
        )
    }
    Surface(Modifier.fillMaxSize(), color = Color(0xFFFAF9F6)) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text("冲突显示 · 待确认演示", color = Color(0xFF6252C7), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text("10月19日 — 25日", fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
                Text("合成课表 · 七天同屏 · 全天 00:00–24:00", fontSize = 10.sp, color = Color(0xFF77747C))
                Spacer(Modifier.height(11.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("‹       本周       ›", Modifier.weight(1f), color = Color(0xFF6252C7), fontSize = 13.sp)
                    Text("● 课程   ● 计划   ● 实际    100%", fontSize = 10.sp, color = Color(0xFF77747C))
                }
            }
            Row(Modifier.padding(start = 38.dp, end = 3.dp).fillMaxWidth().height(42.dp)) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, name ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("周$name", fontSize = 10.sp, color = Color(0xFF77747C))
                        Text((19 + index).toString(), Modifier.clip(RoundedCornerShape(7.dp)).background(if (index == 3) Color(0xFF6252C7) else Color.Transparent).padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (index == 3) Color.White else Color(0xFF23272C))
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val topInset = 7.dp
                val bodyHeight = maxHeight - 14.dp
                val minuteHeight = bodyHeight / 1440f
                repeat(25) { hour ->
                    Text("%02d:00".format(hour), Modifier.offset(y = (topInset + minuteHeight * (hour * 60) - 6.dp).coerceAtLeast(0.dp)).width(34.dp), fontSize = 8.sp, lineHeight = 11.sp, color = Color(0xFF77747C), textAlign = TextAlign.End)
                }
                BoxWithConstraints(Modifier.offset(x = 38.dp, y = topInset).width(maxWidth - 41.dp).height(bodyHeight)) {
                    val dayWidth = maxWidth / 7
                    Canvas(Modifier.fillMaxSize()) {
                        repeat(8) { day -> val x = day * dayWidth.toPx(); drawLine(Color(0xFFEAE8E5), Offset(x, 0f), Offset(x, size.height), .7f) }
                        repeat(25) { hour -> val y = size.height * hour / 24; drawLine(Color(0xFFEAE8E5), Offset(0f, y), Offset(size.width, y), .7f) }
                    }
                    data class Sample(val day: Int, val from: Int, val to: Int, val name: String, val kind: ItemKind)
                    val samples = listOf(
                        Sample(0, 480, 700, "数字信号处理实验", ItemKind.COURSE), Sample(0, 870, 1030, "数字系统设计", ItemKind.COURSE),
                        Sample(1, 600, 700, "数字信号处理", ItemKind.RECORD), Sample(1, 1140, 1240, "C++ 程序设计", ItemKind.COURSE),
                        Sample(2, 90, 510, "睡眠", ItemKind.RECORD), Sample(2, 560, 620, "沟通", ItemKind.RECORD), Sample(2, 660, 720, "做ai", ItemKind.RECORD),
                        Sample(2, 870, 1080, "数字系统设计", ItemKind.COURSE), Sample(2, 1110, 1210, "练背", ItemKind.PLAN), Sample(2, 1210, 1300, "科研", ItemKind.RECORD),
                        Sample(3, 120, 540, "睡眠", ItemKind.RECORD), Sample(3, 600, 700, "数字信号处理", ItemKind.RECORD), Sample(4, 900, 960, "党会", ItemKind.PLAN),
                    )
                    samples.forEach { sample ->
                        val start = LocalDate.of(2026, 10, 19).plusDays(sample.day.toLong()).atStartOfDay(proposalZone).toInstant().plusSeconds(sample.from * 60L)
                        val end = start.plusSeconds((sample.to - sample.from) * 60L)
                        val id = "sample-${sample.day}-${sample.from}"
                        val item = TimelineItem(id, sample.kind, sample.name, TimeSpan(start, end), entity = if (sample.kind == ItemKind.COURSE) proposalCourseEntity(id, sample.name, start, end) else Unit)
                        TimelineEventCard(item, proposalZone, 1.0, false, Modifier.offset(x = dayWidth * sample.day + 1.dp, y = minuteHeight * sample.from).width(dayWidth - 2.dp).height(minuteHeight * (sample.to - sample.from))) {}
                    }
                    ProposalConflict(courses, Modifier.offset(x = dayWidth + 1.dp, y = minuteHeight * 870).width(dayWidth - 2.dp).height(minuteHeight * 210).testTag("proposal-tuesday")) { selected = courses }
                    ProposalConflict(evening, Modifier.offset(x = dayWidth * 3 + 1.dp, y = minuteHeight * 1140).width(dayWidth - 2.dp).height(minuteHeight * 160)) { selected = evening }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Text("▥ 时间轴", color = Color(0xFF6252C7), fontSize = 12.sp)
                Text("☑ 任务", fontSize = 12.sp, color = Color(0xFF77747C))
                Text("● 说话", color = Color(0xFF6252C7), fontSize = 12.sp)
                Text("◷ 复盘", fontSize = 12.sp, color = Color(0xFF77747C))
                Text("♙ 我的", fontSize = 12.sp, color = Color(0xFF77747C))
            }
        }
    }
    selected?.let { group -> TimelineConflictGroupSheet(group, proposalZone, onSelect = {}, onClose = { selected = null }) }
}
