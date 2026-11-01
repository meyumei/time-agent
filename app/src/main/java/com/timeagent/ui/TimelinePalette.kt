package com.timeagent.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timeagent.data.CourseOccurrenceEntity
import com.timeagent.data.ScheduleBlockEntity
import com.timeagent.data.TaskEntity
import com.timeagent.data.TimeRecordEntity

data class TimelinePreset(
    val id: String,
    val name: String,
    val argb: Long,
    val contentArgb: Long,
    val secondaryArgb: Long,
    val accentArgb: Long,
    val darkArgb: Long,
    val darkContentArgb: Long,
    val darkSecondaryArgb: Long,
    val darkAccentArgb: Long,
)

/** Distinct module hues; the timeline keeps a plain white light-theme background. */
object TimelinePalette {
    val presets = GeneratedTimelinePresets

    fun resolve(subject: String, selected: String? = null): TimelinePreset {
        presets.firstOrNull { it.id == selected }?.let { return it }
        val fixed = when (subject.substringAfterLast(':')) {
            "sleep", "睡觉", "睡眠" -> "cyan"
            "fitness", "健身" -> "coral"
            "fitness-chest" -> "pink"
            "fitness-back" -> "violet"
            "fitness-shoulders" -> "blue"
            "fitness-arms" -> "gold"
            else -> null
        }
        if (fixed != null) return presets.first { it.id == fixed }
        var hash = 7
        subject.forEach { hash = hash * 31 + it.code }
        return presets[(hash and 0x7FFFFFFF) % presets.size]
    }
}

fun TaskEntity.timelineColorSubject(): String = activityId?.let { "activity:$it" } ?: "task:$id"

val TimelineItem.colorSubject: String get() = colorKey.ifBlank {
    when (val value = entity) {
        is CourseOccurrenceEntity -> "course:${title.filterNot(Char::isWhitespace)}"
        is ScheduleBlockEntity -> value.taskId?.let { "task:$it" } ?: "schedule:${value.id}"
        is TimeRecordEntity -> value.taskId?.let { "task:$it" } ?: "activity-title:${title.filterNot(Char::isWhitespace)}"
        else -> "${kind.name}:${title.filterNot(Char::isWhitespace)}"
    }
}

/** Presentation preferences are independent of task/record transactions and schema migrations. */
class TimelineColorPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("timeline_colors_v1", Context.MODE_PRIVATE)
    fun read(): Map<String, String> = preferences.all.mapNotNull { (key, value) ->
        (value as? String)?.takeIf { id -> TimelinePalette.presets.any { it.id == id } }?.let { key to it }
    }.toMap()
    fun save(subject: String, preset: String?) {
        require(subject.isNotBlank())
        require(preset == null || TimelinePalette.presets.any { it.id == preset })
        preferences.edit().apply { if (preset == null) remove(subject) else putString(subject, preset) }.apply()
    }
}

data class TimelineColors(val selected: Map<String, String> = emptyMap(), val choose: (String, String?) -> Unit = { _, _ -> })
val LocalTimelineColors = compositionLocalOf { TimelineColors() }

@Composable fun TimelineColorEnvironment(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { TimelineColorPreferences(context) }
    var choices by remember(preferences) { mutableStateOf(preferences.read()) }
    val colors = remember(choices, preferences) {
        TimelineColors(choices) { subject, preset ->
            preferences.save(subject, preset)
            choices = if (preset == null) choices - subject else choices + (subject to preset)
        }
    }
    CompositionLocalProvider(LocalTimelineColors provides colors, content = content)
}

@Composable fun timelineBackground(): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    return if (dark) MaterialTheme.colorScheme.surface else Color.White
}

@Composable fun TimelineColorChoice(subject: String, title: String, modifier: Modifier = Modifier) {
    val colors = LocalTimelineColors.current
    val current = TimelinePalette.resolve(subject, colors.selected[subject])
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val currentPaint = current.paint(dark)
    var open by remember(subject) { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier.testTag("timeline-color-choice"), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(currentPaint.container).border(.8.dp, currentPaint.accent.copy(alpha = .4f), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text("颜色 · ${current.name}", fontSize = 12.sp)
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("选择颜色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text("同一模块的计划和实际使用相同颜色。", style = MaterialTheme.typography.bodySmall)
                TimelinePalette.presets.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        row.forEach { preset ->
                            val paint = preset.paint(dark)
                            Column(
                                Modifier.width(57.dp).clip(RoundedCornerShape(8.dp))
                                    .background(if (current.id == preset.id) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                    .clickable(role = Role.Button) { colors.choose(subject, preset.id); open = false }
                                    .padding(vertical = 6.dp)
                                    .semantics { contentDescription = "选择${preset.name}" }
                                    .testTag("timeline-preset-${preset.id}"),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(Modifier.size(30.dp).clip(CircleShape).background(paint.container).border(.8.dp, paint.accent.copy(alpha = .4f), CircleShape), contentAlignment = Alignment.Center) {
                                    Text("Aa", color = paint.content, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(preset.name, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { colors.choose(subject, null); open = false }) { Text("恢复默认") } },
        dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } },
    )
}
