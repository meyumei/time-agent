package com.timeagent.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.timeagent.R
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import java.util.UUID

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun TaskSubtasksField(
    items: List<TaskSubtaskDraft>,
    onChange: (List<TaskSubtaskDraft>) -> Unit,
    allowAdd: Boolean,
    modifier: Modifier = Modifier,
    onOpenExisting: ((String) -> Unit)? = null,
    onPendingInputChange: (String) -> Unit = {},
) {
    var input by rememberSaveable { mutableStateOf("") }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }
    val currentItems by rememberUpdatedState(items)
    val currentOnChange by rememberUpdatedState(onChange)

    fun move(from: Int, to: Int) {
        val list = currentItems
        if (from !in list.indices || to !in list.indices || from == to) return
        val m = list.toMutableList()
        m.add(to, m.removeAt(from))
        currentOnChange(m)
    }

    val done = items.count { it.completed }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.task_subtasks_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.task_subtasks_progress, done, items.size),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!allowAdd) {
            Text(
                stringResource(R.string.task_subtasks_add_disabled_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (items.isEmpty()) {
            Text(
                stringResource(R.string.task_subtasks_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                stringResource(R.string.task_subtasks_drag_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (items.any { !it.isNew }) {
                Text(
                    stringResource(R.string.task_subtasks_existing_readonly),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                key(item.id) {
                    val dragging = draggingId == item.id
                    val stateText = when {
                        item.isNew -> stringResource(R.string.task_subtasks_state_new)
                        item.completed -> stringResource(R.string.task_subtasks_state_done)
                        else -> stringResource(R.string.task_subtasks_state_todo)
                    }
                    Surface(
                        tonalElevation = if (dragging) 6.dp else 1.dp,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                            .onGloballyPositioned { heights[item.id] = it.size.height },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(item.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stateText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                                    TextButton(
                                        onClick = { move(index, index - 1) },
                                        enabled = index > 0,
                                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                                    ) { Text(stringResource(R.string.task_subtasks_move_up, item.title)) }
                                    TextButton(
                                        onClick = { move(index, index + 1) },
                                        enabled = index < items.lastIndex,
                                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                                    ) { Text(stringResource(R.string.task_subtasks_move_down, item.title)) }
                                    if (!item.isNew && onOpenExisting != null) {
                                        TextButton(
                                            onClick = { onOpenExisting(item.id) },
                                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                                        ) { Text(stringResource(R.string.task_subtasks_open, item.title)) }
                                    }
                                    if (item.isNew) {
                                        TextButton(
                                            onClick = {
                                                currentOnChange(currentItems.filterNot { it.id == item.id })
                                            },
                                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                                        ) { Text(stringResource(R.string.task_subtasks_remove, item.title)) }
                                    }
                                }
                            }
                            val handleDesc = stringResource(R.string.task_subtasks_drag_handle, item.title)
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .semantics { contentDescription = handleDesc }
                                    .pointerInput(item.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                draggingId = item.id
                                                dragOffset = 0f
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                if (draggingId != item.id) return@detectDragGesturesAfterLongPress
                                                dragOffset += amount.y
                                                // Work from one local order until the parent can recompose.
                                                val list = currentItems.toMutableList()
                                                var reordered = false
                                                while (true) {
                                                    val i = list.indexOfFirst { it.id == item.id }
                                                    if (i < 0) break
                                                    val myH = (heights[item.id] ?: 0).toFloat()
                                                    if (myH <= 0f) break
                                                    if (dragOffset > 0f && i < list.lastIndex) {
                                                        val nH = (heights[list[i + 1].id] ?: 0).toFloat()
                                                        if (nH > 0f && dragOffset > (myH + nH) / 2f) {
                                                            list.add(i + 1, list.removeAt(i))
                                                            reordered = true
                                                            dragOffset -= nH
                                                            continue
                                                        }
                                                    } else if (dragOffset < 0f && i > 0) {
                                                        val pH = (heights[list[i - 1].id] ?: 0).toFloat()
                                                        if (pH > 0f && -dragOffset > (myH + pH) / 2f) {
                                                            list.add(i - 1, list.removeAt(i))
                                                            reordered = true
                                                            dragOffset += pH
                                                            continue
                                                        }
                                                    }
                                                    break
                                                }
                                                if (reordered) currentOnChange(list)
                                            },
                                            onDragEnd = {
                                                draggingId = null
                                                dragOffset = 0f
                                            },
                                            onDragCancel = {
                                                draggingId = null
                                                dragOffset = 0f
                                            },
                                        )
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("⋮⋮", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }

        if (allowAdd) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; onPendingInputChange(it) },
                    label = { Text(stringResource(R.string.task_subtasks_input_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                )
                Button(
                    onClick = {
                        val title = input.trim()
                        if (title.isNotEmpty()) {
                            currentOnChange(
                                currentItems + TaskSubtaskDraft(
                                    id = UUID.randomUUID().toString(),
                                    title = title,
                                    isNew = true,
                                ),
                            )
                            input = ""
                            onPendingInputChange("")
                        }
                    },
                    enabled = input.isNotBlank(),
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) { Text(stringResource(R.string.task_subtasks_add)) }
            }
        }
    }
}
