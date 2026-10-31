package com.timeagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class ConflictSampleCourse(
    val id: String,
    val title: String,
    val time: String,
    val start: String,
    val locationAndTeacher: String,
    val light: Color,
    val dark: Color,
    val ink: Color,
    val darkInk: Color,
)

/** Synthetic data only. These examples never read or write the user's timetable. */
private val ConflictSampleCourses = listOf(
    ConflictSampleCourse("math", "高等数学", "08:00–09:40", "08:00", "A楼 302 · 王老师", Color(0xFFEAE6FA), Color(0xFF30294F), Color(0xFF4A3C88), Color(0xFFDFD5FF)),
    ConflictSampleCourse("physics", "大学物理", "08:30–10:10", "08:30", "理科楼 205 · 李老师", Color(0xFFE0EDF9), Color(0xFF20394D), Color(0xFF285779), Color(0xFFCCE6FF)),
    ConflictSampleCourse("coding", "程序设计基础", "09:00–10:40", "09:00", "实验楼 401 · 陈老师", Color(0xFFDEF1E8), Color(0xFF1D3B31), Color(0xFF25614C), Color(0xFFBEF0DB)),
)

/**
 * Reviewable native A/B sample adapted from the actual Gemini 3.8 Flash output.
 * The selected day is enlarged for reading; no production timeline layout changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictLayoutSample(onBack: () -> Unit = {}) {
    var selectedMode by rememberSaveable { mutableIntStateOf(0) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", Modifier.size(22.dp))
                }
                Spacer(Modifier.width(6.dp))
                Column {
                    Text("冲突课程排版样例", fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
                    Text("合成课程，仅用于排版对比", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("A 叠放摘要", "B 错层课程卡").forEachIndexed { index, label ->
                    FilterChip(
                        selected = selectedMode == index,
                        onClick = { selectedMode = index },
                        label = { Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) },
                        leadingIcon = if (selectedMode == index) {
                            { Icon(Icons.Rounded.Layers, null, Modifier.size(16.dp)) }
                        } else null,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(13.dp))
            ConflictSampleWeekContext()
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("10月21日 · 周三", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("聚焦日预览 · 08:00–10:40", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.WarningAmber, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(4.dp))
                        Text("3 门冲突", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (selectedMode == 0) {
                ConflictSummaryPreview(onOpen = { detailsOpen = true })
            } else {
                ConflictStaggeredPreview(onOpen = { detailsOpen = true })
            }
            Spacer(Modifier.height(16.dp))
            ConflictSampleExplanation(selectedMode)
        }
    }
    if (detailsOpen) {
        ModalBottomSheet(
            onDismissRequest = { detailsOpen = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.testTag("conflict-course-sheet"),
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 30.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("重叠课程 · 3 门", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("周三 · 三课共同重叠 09:00–09:40", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { detailsOpen = false }) {
                        Icon(Icons.Rounded.Close, "关闭课程详情")
                    }
                }
                ConflictSampleCourses.forEach { course -> ConflictSampleFullCard(course) }
            }
        }
    }
}

@Composable
private fun ConflictSampleWeekContext() {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp)) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, name ->
                val selected = index == 2
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("周$name", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(shape = RoundedCornerShape(9.dp), color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent) {
                        Text((19 + index).toString(), Modifier.padding(horizontal = 9.dp, vertical = 5.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                    }
                    Box(Modifier.size(4.dp).clip(CircleShape).background(if (selected) MaterialTheme.colorScheme.error else Color.Transparent))
                }
            }
        }
    }
}

@Composable
private fun ConflictSummaryPreview(onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(270.dp)) {
        Surface(Modifier.fillMaxWidth().padding(start = 16.dp).height(250.dp).offset(y = 14.dp), color = scheme.secondaryContainer, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, scheme.outlineVariant)) {}
        Surface(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp).height(250.dp).offset(y = 7.dp), color = scheme.tertiaryContainer, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, scheme.outlineVariant)) {}
        Surface(
            modifier = Modifier.fillMaxWidth().padding(end = 16.dp).height(250.dp).testTag("conflict-summary").clickable(role = Role.Button, onClick = onOpen),
            color = scheme.surfaceContainerLowest,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, scheme.outlineVariant),
            shadowElevation = 2.dp,
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Layers, null, Modifier.size(20.dp), tint = scheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("一个时段，三门课程", fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Text("重叠课程收成一组，轻点看完整信息", fontSize = 11.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(13.dp))
                ConflictSampleCourses.forEach { course ->
                    val dark = scheme.surface.luminance() < .5f
                    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (dark) course.darkInk else course.ink))
                        Spacer(Modifier.width(8.dp))
                        Text(course.title, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(5.dp))
                        Text(course.time, fontSize = 10.sp, color = scheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("展开 3 张完整课程卡", Modifier.weight(1f), fontSize = 12.sp, color = scheme.primary, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(17.dp), tint = scheme.primary)
                }
            }
        }
    }
}

@Composable
private fun ConflictStaggeredPreview(onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("conflict-staggered"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ConflictSampleCourses.forEachIndexed { index, course ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(47.dp), horizontalAlignment = Alignment.Start) {
                    Text(course.start, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("开始", fontSize = 9.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ConflictSampleFullCard(
                    course,
                    modifier = Modifier.weight(1f).padding(start = (index * 8).dp).testTag("conflict-staggered-course-${course.id}").clickable(role = Role.Button, onClick = onOpen),
                )
            }
        }
        Text("按开始时间错层排列，时长以卡片文字为准", fontSize = 10.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConflictSampleFullCard(course: ConflictSampleCourse, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val container = if (dark) course.dark else course.light
    val ink = if (dark) course.darkInk else course.ink
    Card(
        modifier = modifier.fillMaxWidth().heightIn(min = 94.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(1.dp, ink.copy(alpha = .16f)),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 94.dp)) {
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(course.title, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, color = ink)
                Text(course.time, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, color = ink)
                Text(course.locationAndTeacher, fontSize = 11.sp, lineHeight = 16.sp, color = ink.copy(alpha = .86f))
            }
        }
    }
}

@Composable
private fun ConflictSampleExplanation(mode: Int) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (mode == 0) "A · 周视图建议" else "B · 聚焦一天阅读", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (mode == 0) "冲突组保留一个入口，点开后读完整课程卡。上方是选中当天的放大示例。"
                else "每门课程保留完整标题、时间、地点和教师。错层用来区分课程，点击卡片可展开整组。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("三课共同重叠：09:00–09:40", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
        }
    }
}
