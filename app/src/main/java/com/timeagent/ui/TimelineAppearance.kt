package com.timeagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timeagent.data.CourseOccurrenceEntity
import com.timeagent.data.ScheduleBlockEntity
import com.timeagent.data.TimeRecordEntity
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Resolved colors for one timeline block; kept separate so overlays can match. */
data class TimelinePaint(
    val container: Color,
    val content: Color,
    val accent: Color,
    val secondary: Color = content,
)

private val ClockFormat = DateTimeFormatter.ofPattern("HH:mm")
private val DateClockFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm")

fun TimelinePreset.paint(dark: Boolean): TimelinePaint =
    if (dark) TimelinePaint(Color(darkArgb), Color(darkContentArgb), Color(darkAccentArgb), Color(darkSecondaryArgb))
    else TimelinePaint(Color(argb), Color(contentArgb), Color(accentArgb), Color(secondaryArgb))

/** Course/activity identity owns the hue across weeks, plans and completed records. */
@Composable
fun TimelineItem.paint(): TimelinePaint {
    val subject = colorSubject
    val preset = TimelinePalette.resolve(subject, LocalTimelineColors.current.selected[subject])
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return preset.paint(dark)
}

private fun TimelineItem.isRunningRecord(): Boolean =
    kind == ItemKind.RECORD && (entity as? TimeRecordEntity)?.endAt == null

private fun Instant.clockText(zone: ZoneId): String = atZone(zone).format(ClockFormat)

private fun TimelineItem.timeText(zone: ZoneId): String {
    val start = span.start.clockText(zone)
    return if (isRunningRecord()) "$start 起" else "$start – ${span.end.clockText(zone)}"
}

private fun TimelineItem.spokenDescription(zone: ZoneId, conflicting: Boolean): String {
    val start = span.start.atZone(zone).format(DateClockFormat)
    val end = span.end.atZone(zone).format(DateClockFormat)
    val base = "${kind.label} $title，$start 至 $end"
    val withNotes = if (subtitle.isNotBlank()) "$base，$subtitle" else base
    return if (conflicting) "$withNotes，有时间冲突" else withNotes
}

private fun Long.readableMinutes(): String =
    if (this < 60) "$this 分钟" else "${this / 60} 小时 ${this % 60} 分"

private fun eventTitleSize(width: Dp, height: Dp, zoom: Double): TextUnit {
    val base = when {
        width >= 170.dp -> 14f
        width >= 126.dp -> 13f
        width >= 88.dp -> 12f
        width >= 62.dp -> 11f
        else -> 10f
    }
    // zoom 1 ≈ seven visible days; wider spans get slightly larger type.
    val zoomFactor = 0.93f + 0.07f * zoom.coerceIn(0.6, 4.0).toFloat()
    var size = base * zoomFactor
    if (height < 24.dp) size *= 0.92f
    return (Math.round(size * 2f) / 2f).coerceIn(8.5f, 15f).sp
}

/**
 * Block-sized card. Root supplies the exact measured size; everything inside is
 * derived from those constraints, so text grows with zoom and sheds time/notes
 * instead of clamping to a fixed two lines.
 */
@Composable
fun TimelineEventCard(
    item: TimelineItem,
    zone: ZoneId,
    zoom: Double,
    conflicting: Boolean,
    modifier: Modifier = Modifier,
    cardSize: DpSize? = null,
    onClick: () -> Unit,
) {
    val colors = item.paint()
    val description = androidx.compose.runtime.remember(item, zone, conflicting) { item.spokenDescription(zone, conflicting) }
    val muted = colors.secondary

    TimelineCardGeometry(cardSize, modifier) { width, height ->
        val shape = RoundedCornerShape(if (height < 24.dp) 6.dp else 10.dp)
        val titleSize = eventTitleSize(width, height, zoom)
        val titleStyle = MaterialTheme.typography.labelLarge.copy(
            fontSize = titleSize,
            lineHeight = (titleSize.value * 1.18f).sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.1).sp,
        )
        val timeSize = (titleSize.value * 0.82f).coerceAtLeast(8.5f).sp
        val noteSize = (titleSize.value * 0.85f).coerceAtLeast(8.5f).sp
        val padTop = if (height < 26.dp) 1.5.dp else 4.dp
        val padBottom = if (height < 26.dp) 1.5.dp else 3.5.dp
        val innerWidth = (width - 10.dp).coerceAtLeast(0.dp)
        val contentHeight = (height - padTop - padBottom).coerceAtLeast(0.dp)
        val badge = conflicting && innerWidth >= 32.dp && height >= 18.dp
        val density = LocalDensity.current
        val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 16)
        val availableTextWidth = (innerWidth - if (badge) 10.dp else 0.dp).coerceAtLeast(0.dp)
        // Keep the block geometry continuous, but reuse paragraphs within a 2dp text-width step.
        // Rounding down always leaves enough room, including at large system font scales.
        val measureWidth = (kotlin.math.floor(availableTextWidth.value / 2f) * 2f).dp
        val measureWidthPx = with(density) { measureWidth.toPx() }.toInt().coerceAtLeast(1)
        val roomPx = with(density) { contentHeight.toPx() }
        val linePx = with(density) { (titleSize.value * 1.18f).sp.toPx() }.coerceAtLeast(1f)
        val maxTitleLines = (roomPx / linePx).toInt().coerceAtLeast(1)
        // Full-title geometry depends on width and typography, never on the changing block height.
        val measuredTitle = if (measureWidth > 0.dp && height >= 11.dp) {
            androidx.compose.runtime.remember(textMeasurer, item.title, titleStyle, measureWidthPx) {
                textMeasurer.measure(
                    text = androidx.compose.ui.text.AnnotatedString(item.title),
                    style = titleStyle,
                    overflow = TextOverflow.Clip,
                    softWrap = true,
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = measureWidthPx),
                )
            }
        } else {
            null
        }
        val titlePx = measuredTitle?.size?.height ?: 0
        val fullTitleFits = measuredTitle != null && !measuredTitle.hasVisualOverflow && titlePx <= roomPx
        val sparePx = (roomPx - titlePx).coerceAtLeast(0f)
        val timeBlockPx = with(density) { (timeSize.value * 1.25f).sp.toPx() + 1.dp.toPx() }
        // Metadata is revealed only once the whole title is visible, never instead of it.
        val showTime = fullTitleFits && innerWidth >= 34.dp && sparePx >= timeBlockPx
        val showFullTime = showTime && innerWidth >= 90.dp
        val noteSpacingPx = with(density) { 2.dp.toPx() }
        val remainingNotePx = sparePx - timeBlockPx - noteSpacingPx
        val noteLinePx = with(density) { (noteSize.value * 1.15f).sp.toPx() }.coerceAtLeast(1f)
        val maxNoteLines = (remainingNotePx / noteLinePx).toInt()
        val noteStyle = MaterialTheme.typography.bodySmall.copy(
            fontSize = noteSize,
            lineHeight = (noteSize.value * 1.15f).sp,
            letterSpacing = 0.sp,
        )
        val noteLayout = if (showTime && item.subtitle.isNotBlank() && innerWidth >= 52.dp && maxNoteLines > 0 && measureWidth > 0.dp) {
            androidx.compose.runtime.remember(textMeasurer, item.subtitle, noteStyle, measureWidthPx, maxNoteLines) {
                textMeasurer.measure(
                    text = androidx.compose.ui.text.AnnotatedString(item.subtitle),
                    style = noteStyle,
                    overflow = TextOverflow.Clip,
                    softWrap = true,
                    maxLines = maxNoteLines,
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = measureWidthPx),
                )
            }
        } else {
            null
        }
        val noteBlockPx = noteLayout?.size?.height?.toFloat() ?: 0f
        val showSubtitle = showTime && item.subtitle.isNotBlank() && innerWidth >= 52.dp &&
            noteLayout != null && !noteLayout.hasVisualOverflow &&
            sparePx >= timeBlockPx + noteBlockPx + noteSpacingPx
        // No fixed cap: when the title cannot fully fit, use every line the block affords.
        val titleLines = if (fullTitleFits) Int.MAX_VALUE else maxTitleLines
        val timeLabel = when {
            showFullTime -> item.timeText(zone)
            showTime -> item.span.start.clockText(zone) + (if (item.isRunningRecord()) " 起" else "")
            else -> ""
        }
        val showTitle = measuredTitle != null
        val borderWidth = if (conflicting) 1.5.dp else 0.8.dp
        val borderColor = if (conflicting) {
            MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
        } else {
            colors.accent.copy(alpha = 0.30f)
        }

        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(colors.container)
                .border(borderWidth, borderColor, shape)
                .drawWithContent {
                    drawContent()
                    if (item.kind == ItemKind.PLAN && !conflicting) drawLine(
                        colors.accent, androidx.compose.ui.geometry.Offset(6.dp.toPx(), 1.dp.toPx()),
                        androidx.compose.ui.geometry.Offset((size.width - 6.dp.toPx()).coerceAtLeast(6.dp.toPx()), 1.dp.toPx()),
                        strokeWidth = 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                    )
                }
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = description }
        ) {
            Row(Modifier.fillMaxSize()) {
                if (showTitle) {
                    Column(
                        Modifier
                            .fillMaxHeight()
                            .weight(1f)
                            .padding(
                                start = 5.dp,
                                end = if (badge) 14.dp else 4.dp,
                                top = padTop,
                                bottom = padBottom,
                            )
                    ) {
                        Text(
                            text = item.title,
                            modifier = Modifier.width(measureWidth),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = titleSize,
                                lineHeight = (titleSize.value * 1.18f).sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-0.1).sp,
                            ),
                            color = colors.content,
                            maxLines = titleLines,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (showTime) {
                            Spacer(Modifier.height(1.dp))
                            Text(
                                text = timeLabel,
                                modifier = Modifier.width(measureWidth),
                                fontSize = timeSize,
                                lineHeight = (timeSize.value * 1.25f).sp,
                                color = muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (showSubtitle) {
                            Text(
                                text = item.subtitle,
                                modifier = Modifier.width(measureWidth),
                                fontSize = noteSize,
                                lineHeight = (noteSize.value * 1.15f).sp,
                                letterSpacing = 0.sp,
                                color = muted,
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
            if (badge) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 2.dp, end = 2.dp)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.90f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(9.dp),
                    )
                }
            }
        }
    }
}

/** The timeline already knows the card size; only standalone callers need subcomposition. */
@Composable
internal fun TimelineCardGeometry(size: DpSize?, modifier: Modifier, content: @Composable (Dp, Dp) -> Unit) {
    if (size != null) {
        Box(modifier) { content(size.width, size.height) }
    } else {
        BoxWithConstraints(modifier) {
            content(if (maxWidth.value.isFinite()) maxWidth else 160.dp, if (maxHeight.value.isFinite()) maxHeight else 40.dp)
        }
    }
}

/** Roomier list row for the same item: label, time, title, notes and duration. */
@Composable
fun TimelineAgendaCard(
    item: TimelineItem,
    zone: ZoneId,
    conflicting: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = item.paint()
    val secondary = colors.secondary
    val shape = RoundedCornerShape(14.dp)
    val description = item.spokenDescription(zone, conflicting)
    val running = item.isRunningRecord()
    val duration = Duration.between(item.span.start, item.span.end).toMinutes().coerceAtLeast(1)

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.container)
            .border(
                width = if (conflicting) 1.5.dp else 0.8.dp,
                color = if (conflicting) MaterialTheme.colorScheme.error.copy(alpha = 0.80f) else colors.accent.copy(alpha = 0.20f),
                shape = shape,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(colors.accent))
            Spacer(Modifier.width(6.dp))
            Text(
                text = item.kind.label,
                style = MaterialTheme.typography.labelMedium,
                color = secondary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = item.timeText(zone),
                style = MaterialTheme.typography.labelMedium,
                color = secondary,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.content,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.subtitle.isNotBlank()) {
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.width(8.dp))
            if (conflicting) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(11.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = "时间冲突",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = if (running) "● 计时中" else duration.readableMinutes(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (running) FontWeight.SemiBold else FontWeight.Medium,
                color = secondary,
                maxLines = 1,
            )
        }
    }
}
