package com.timeagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.timeagent.data.CourseEntity
import com.timeagent.data.CourseOccurrenceEntity
import com.timeagent.data.LocalSnapshot
import com.timeagent.ui.components.AppTopBar
import com.timeagent.ui.components.StatusBanner
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private val scheduleTimeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val scheduleDateFormat = DateTimeFormatter.ofPattern("M月d日")
private val scheduleDayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

@Composable
fun CourseScheduleScreen(
    data: LocalSnapshot,
    zone: ZoneId,
    onImport: () -> Unit,
    onSelect: (CourseOccurrenceEntity) -> Unit,
    onClose: () -> Unit,
) {
    val currentMonday = remember(zone) {
        LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }
    var weekOffset by rememberSaveable { mutableIntStateOf(0) }
    val weekStart = currentMonday.plusWeeks(weekOffset.toLong())
    val weekEnd = weekStart.plusDays(6)
    val rangeStart = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
    val rangeEnd = weekStart.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val coursesById = remember(data.courses) { data.courses.associateBy { it.id } }
    val weekDays = remember(data.occurrences, zone, rangeStart, rangeEnd) {
        data.occurrences
            .filter { it.startAt in rangeStart until rangeEnd }
            .sortedBy { it.startAt }
            .groupBy { Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate() }
            .toSortedMap()
    }
    val termNames = remember(data.terms, weekStart) {
        data.terms.filter { term ->
            val firstWeek = runCatching { LocalDate.parse(term.firstWeekStart) }.getOrNull()
                ?: return@filter false
            weekStart >= firstWeek && weekStart < firstWeek.plusWeeks(term.teachingWeeks.toLong())
        }.map { it.name }.distinct()
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("course-schedule-screen"),
        topBar = {
            AppTopBar(
                title = "课表",
                onBack = onClose,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("course-schedule-list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "week-navigation") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "${weekStart.format(scheduleDateFormat)} – ${weekEnd.format(scheduleDateFormat)}",
                        modifier = Modifier.testTag("course-week-range"),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (termNames.isNotEmpty()) {
                        Text(
                            text = termNames.joinToString("、"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { weekOffset-- },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("course-week-previous"),
                        ) { Text("上一周") }
                        TextButton(
                            onClick = { weekOffset++ },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("course-week-next"),
                        ) { Text("下一周") }
                    }
                    if (weekOffset != 0) {
                        TextButton(
                            onClick = { weekOffset = 0 },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("course-week-current"),
                        ) { Text("回到本周") }
                    }
                    Button(
                        onClick = onImport,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("course-import"),
                    ) { Text("导入课表") }
                }
            }
            if (data.loading) {
                item(key = "loading") { StatusBanner("正在读取课表…") }
            } else if (weekDays.isEmpty()) {
                item(key = "empty") {
                    StatusBanner(
                        message = if (weekOffset == 0) "本周没有课程" else "这一周没有课程",
                        modifier = Modifier.testTag("course-empty"),
                    )
                }
            } else {
                weekDays.forEach { (date, occurrences) ->
                    item(key = "day-$date") {
                        Text(
                            text = "${scheduleDayNames[date.dayOfWeek.value - 1]} · ${date.format(scheduleDateFormat)}",
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .semantics { heading() },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    items(items = occurrences, key = { it.id }) { occurrence ->
                        CourseOccurrenceCard(
                            occurrence = occurrence,
                            course = coursesById[occurrence.courseId],
                            zone = zone,
                            onSelect = onSelect,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseOccurrenceCard(
    occurrence: CourseOccurrenceEntity,
    course: CourseEntity?,
    zone: ZoneId,
    onSelect: (CourseOccurrenceEntity) -> Unit,
) {
    val start = Instant.ofEpochMilli(occurrence.startAt).atZone(zone)
    val end = Instant.ofEpochMilli(occurrence.endAt).atZone(zone)
    val room = occurrence.locationOverride?.takeIf { it.isNotBlank() }
        ?: course?.location?.takeIf { it.isNotBlank() }
    val teacher = occurrence.teacherOverride?.takeIf { it.isNotBlank() }
        ?: course?.teacher?.takeIf { it.isNotBlank() }
    val statusLabel = when (occurrence.status) {
        "CANCELLED" -> "已取消"
        "RESCHEDULED" -> "已调整"
        else -> null
    }
    val statusColor = when (occurrence.status) {
        "CANCELLED" -> MaterialTheme.colorScheme.error
        "RESCHEDULED" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = { onSelect(occurrence) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("course-occurrence-${occurrence.id}"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = course?.name?.takeIf { it.isNotBlank() } ?: "课程信息缺失",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (statusLabel != null) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                    )
                }
            }
            Text(
                text = "${start.format(scheduleTimeFormat)}–${end.format(scheduleTimeFormat)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "教室 ${room ?: "未记录"} · 教师 ${teacher ?: "未记录"} · 来源 ${courseSourceLabel(course?.sourceId)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun courseSourceLabel(sourceId: String?): String {
    val source = sourceId?.trim().orEmpty()
    return when {
        source.isEmpty() -> "未记录"
        source == "example-v1" -> "示例数据"
        source.startsWith("scut", ignoreCase = true) -> "华工教务"
        source.contains("pdf", ignoreCase = true) -> "PDF 导入"
        source.contains("json", ignoreCase = true) -> "JSON 导入"
        else -> source
    }
}
