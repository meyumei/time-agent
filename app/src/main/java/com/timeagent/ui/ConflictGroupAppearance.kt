// ConflictGroupAppearance.kt
package com.timeagent.ui

import java.time.ZoneId
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineConflictGroupCard(
    items: List<TimelineItem>,
    zone: ZoneId,
    modifier: Modifier = Modifier,
    cardSize: DpSize? = null,
    onClick: () -> Unit,
) {
    val scheduledItems = remember(items) {
        items.filter { it.kind != ItemKind.RECORD }.distinctBy { it.kind to it.id }.sortedBy { it.span.start }
    }
    val count = scheduledItems.size
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val headerInk = if (dark) Color(0xFFDFB18E) else Color(0xFFA56538)
    val headerFill = if (dark) Color(0xFF332A22) else Color(0xFFFFFAF4)
    val edge = if (dark) Color(0xFF8F6951) else Color(0xFFD5B7A5)
    val divider = MaterialTheme.colorScheme.surface
    val shape = RoundedCornerShape(7.dp)

    // The outer time interval stays accurate. The inner rows are a summary of its members.
    TimelineCardGeometry(cardSize, modifier) { width, height ->
        val density = LocalDensity.current
        val titleSize = when {
            width >= 160.dp -> 12.sp
            width >= 100.dp -> 11.sp
            width >= 62.dp -> 10.sp
            else -> 9.sp
        }
        val lineHeight = (titleSize.value + 1).sp
        val headerSize = if (width < 62.dp) 7.sp else 9.sp
        val showHeader = height >= 24.dp && width >= 24.dp
        val headerHeight = if (showHeader) with(density) { (headerSize.value + 2).sp.toDp() + 4.dp } else 0.dp
        val lineDp = with(density) { lineHeight.toDp() }
        val rowMinimum = lineDp * 2 + 4.dp
        val room = (height - headerHeight).coerceAtLeast(0.dp)
        val limit = ((room.value + 1) / (rowMinimum.value + 1)).toInt()
            .coerceIn(1, 3).coerceAtMost(count)
        val rowHeight = if (limit > 0) (room - (limit - 1).dp) / limit else 0.dp
        val paddingTop = if (rowHeight >= lineDp * 3 + 2.dp) 2.dp else 0.dp
        val lines = ((rowHeight - paddingTop).value / lineDp.value).toInt().coerceIn(1, 3)
        val textWidth = (kotlin.math.floor((width - 6.dp).coerceAtLeast(0.dp).value / 2) * 2).dp

        Box(
            Modifier.fillMaxSize().clip(shape).background(headerFill).border(.8.dp, edge, shape)
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = "查看${count}项时间冲突" },
        ) {
            Column(Modifier.fillMaxSize()) {
                if (showHeader) {
                    Row(
                        Modifier.fillMaxWidth().height(headerHeight).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("重叠", fontSize = headerSize, lineHeight = (headerSize.value + 2).sp,
                            color = headerInk, fontWeight = FontWeight.Medium, maxLines = 1)
                        Spacer(Modifier.weight(1f))
                        Text(if (count > limit) "+${count - limit}" else "$count",
                            fontSize = (headerSize.value + 1).sp, lineHeight = (headerSize.value + 2).sp,
                            color = headerInk, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
                scheduledItems.take(limit).forEachIndexed { index, item ->
                    val paint = item.paint()
                    Row(Modifier.fillMaxWidth().weight(1f).background(paint.container)) {
                        Box(Modifier.weight(1f).padding(start = 4.dp, end = 2.dp, top = paddingTop)) {
                            Text(
                                item.title, modifier = Modifier.width(textWidth),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = titleSize, lineHeight = lineHeight, letterSpacing = 0.sp,
                                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                                ),
                                fontWeight = FontWeight.SemiBold, color = paint.content,
                                maxLines = lines, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (index < limit - 1) Box(Modifier.fillMaxWidth().height(1.dp).background(divider))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineConflictGroupSheet(
    items: List<TimelineItem>,
    zone: ZoneId,
    onSelect: (TimelineItem) -> Unit,
    onClose: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scheduledItems = remember(items) {
        items
            .filter { it.kind != ItemKind.RECORD }
            .distinctBy { it.kind to it.id }
            .sortedBy { it.span.start }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("timeline-conflict-sheet"),
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "冲突组 · ${scheduledItems.size} 项",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "共 ${scheduledItems.size} 项冲突日程",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭冲突详情"
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = scheduledItems,
                    key = { it.layoutKey }
                ) { item ->
                    val paint = item.paint()

                    Card(
                        onClick = {
                            onClose()
                            onSelect(item)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("conflict-sheet-item-${item.id}")
                            .defaultMinSize(minHeight = 90.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = paint.container,
                            contentColor = paint.content
                        ),
                        border = BorderStroke(1.dp, paint.accent.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = paint.container
                                    ) {
                                        Text(
                                            text = item.kind.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium,
                                            color = paint.secondary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Text(
                                        text = "${item.span.start.label(zone)} - ${item.span.end.label(zone)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = paint.secondary
                                    )
                                }

                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = paint.content
                                )

                                if (item.subtitle.isNotEmpty()) {
                                    Text(
                                        text = item.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = paint.secondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
