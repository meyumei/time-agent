@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.timeagent.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.timeagent.R
import com.timeagent.data.AttachmentEntity
import com.timeagent.data.ReminderEntity
import com.timeagent.ui.components.DateField
import com.timeagent.ui.components.TimeField
import com.timeagent.ui.design.DesignTokens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private val DeliveredTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())

@Composable
internal fun TaskRemindersField(
    taskId: String,
    taskTitle: String,
    reminders: List<ReminderEntity>,
    now: Instant,
    zone: ZoneId,
    onChange: (List<ReminderEntity>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sectionTitle = stringResource(R.string.task_reminders_section_title)
    val emptyHint = stringResource(R.string.task_reminders_empty_hint)
    val addActionText = stringResource(R.string.task_reminders_add_action)
    val defaultTitle = stringResource(R.string.task_reminders_default_title)
    val dateLabel = stringResource(R.string.task_reminders_date_label)
    val timeLabel = stringResource(R.string.task_reminders_time_label)
    val pendingText = stringResource(R.string.task_reminders_status_pending)
    val enabledLabel = stringResource(R.string.task_reminders_enabled_label)
    val removeActionText = stringResource(R.string.task_reminders_remove_action)
    val pastWarningText = stringResource(R.string.task_reminders_past_warning)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing8),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = sectionTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = DesignTokens.spacing4),
            )
            OutlinedButton(
                onClick = {
                    val resolvedTitle = taskTitle.trim().ifEmpty { defaultTitle }
                    val newReminder = ReminderEntity(
                        id = UUID.randomUUID().toString(),
                        title = resolvedTitle,
                        remindAt = now.plusSeconds(3600).toEpochMilli(),
                        taskId = taskId,
                        enabled = true,
                        ownerType = "TASK",
                        ownerId = taskId,
                    )
                    onChange(reminders + newReminder)
                },
                modifier = Modifier.heightIn(min = DesignTokens.touchTarget),
            ) {
                Text(addActionText)
            }
        }

        if (reminders.isEmpty()) {
            Text(
                text = emptyHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val nowMillis = now.toEpochMilli()
            reminders.forEach { reminder ->
                key(reminder.id) {
                val reminderDateTime = remember(reminder.remindAt, zone) {
                    Instant.ofEpochMilli(reminder.remindAt).atZone(zone).toLocalDateTime()
                }
                val reminderDate = reminderDateTime.toLocalDate()
                val reminderTime = reminderDateTime.toLocalTime()
                val isPast = reminder.remindAt <= nowMillis
                val repeatingAfterDelivery = reminder.deliveredAt != null &&
                    (reminder.repeatIntervalMinutes ?: 0) > 0 && (reminder.repeatMaxCount ?: 0) > 0
                val canEnable = !isPast || repeatingAfterDelivery
                val displayTitle = reminder.title.ifBlank {
                    taskTitle.trim().ifEmpty { defaultTitle }
                }
                val statusText = if (reminder.deliveredAt != null) {
                    val deliveredFormatted = Instant.ofEpochMilli(reminder.deliveredAt)
                        .atZone(zone)
                        .toLocalDateTime()
                        .format(DeliveredTimeFormatter)
                    stringResource(R.string.task_reminders_status_delivered, deliveredFormatted)
                } else {
                    pendingText
                }
                val repeatText = reminderRepeatSummary(
                    intervalMinutes = reminder.repeatIntervalMinutes,
                    maxCount = reminder.repeatMaxCount,
                )

                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(DesignTokens.spacing16),
                        verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing8),
                    ) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f, fill = false),
                                verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing4),
                            ) {
                                Text(
                                    text = displayTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = {
                                    onChange(reminders.filterNot { it.id == reminder.id })
                                },
                                modifier = Modifier.heightIn(min = DesignTokens.touchTarget),
                            ) {
                                Text(
                                    text = removeActionText,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        if (repeatText != null) {
                            Text(
                                text = repeatText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8),
                            verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing8),
                        ) {
                            DateField(
                                label = dateLabel,
                                value = reminderDate,
                                onValueChange = { selectedDate ->
                                    val updatedLdt = selectedDate.atTime(reminderTime)
                                    val updatedMillis = updatedLdt.atZone(zone).toInstant().toEpochMilli()
                                    val updatedInPast = updatedMillis <= now.toEpochMilli()
                                    val updatedItem = reminder.copy(
                                        remindAt = updatedMillis,
                                        enabled = if (updatedInPast) false else reminder.enabled,
                                    )
                                    onChange(
                                        reminders.map { item ->
                                            if (item.id == reminder.id) updatedItem else item
                                        },
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            )
                            TimeField(
                                label = timeLabel,
                                value = reminderTime,
                                onValueChange = { selectedTime ->
                                    val updatedLdt = reminderDateTime
                                        .withHour(selectedTime.hour)
                                        .withMinute(selectedTime.minute)
                                    val updatedMillis = updatedLdt.atZone(zone).toInstant().toEpochMilli()
                                    val updatedInPast = updatedMillis <= now.toEpochMilli()
                                    val updatedItem = reminder.copy(
                                        remindAt = updatedMillis,
                                        enabled = if (updatedInPast) false else reminder.enabled,
                                    )
                                    onChange(
                                        reminders.map { item ->
                                            if (item.id == reminder.id) updatedItem else item
                                        },
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = DesignTokens.touchTarget)
                                .toggleable(
                                    value = reminder.enabled,
                                    enabled = canEnable || reminder.enabled,
                                    role = Role.Switch,
                                    onValueChange = { checked ->
                                        if (canEnable || !checked) {
                                            val updatedItem = reminder.copy(enabled = checked)
                                            onChange(
                                                reminders.map { item ->
                                                    if (item.id == reminder.id) updatedItem else item
                                                },
                                            )
                                        }
                                    },
                                )
                                .semantics {
                                    contentDescription = enabledLabel
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Switch(
                                checked = reminder.enabled,
                                onCheckedChange = null,
                                enabled = canEnable || reminder.enabled,
                            )
                            Spacer(Modifier.width(DesignTokens.spacing8))
                            Text(
                                text = enabledLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isPast) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }

                        if (isPast) {
                            Text(
                                text = if (repeatingAfterDelivery) stringResource(R.string.task_reminders_past_repeat_hint)
                                    else pastWarningText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun reminderRepeatSummary(
    intervalMinutes: Int?,
    maxCount: Int?,
): String? {
    val validInterval = intervalMinutes?.takeIf { it > 0 }
    val validCount = maxCount?.takeIf { it > 0 }
    return when {
        validInterval != null && validCount != null ->
            stringResource(R.string.task_reminders_repeat_interval_and_count, validInterval, validCount)
        validInterval != null ->
            stringResource(R.string.task_reminders_repeat_interval_only, validInterval)
        validCount != null ->
            stringResource(R.string.task_reminders_repeat_count_only, validCount)
        else -> null
    }
}

@Composable
internal fun TaskAttachmentsField(
    attachments: List<AttachmentEntity>,
    selectedIds: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    thumbnail: @Composable (AttachmentEntity) -> Unit = {},
) {
    val context = LocalContext.current
    val sectionTitle = stringResource(R.string.task_attachments_section_title)
    val emptyHint = stringResource(R.string.task_attachments_empty_hint)
    val removeMissingText = stringResource(R.string.task_attachments_remove_missing_action)
    val selectedEmptyText = stringResource(R.string.task_attachments_selected_empty)
    val unlinkActionText = stringResource(R.string.task_attachments_unlink_action)
    val linkActionText = stringResource(R.string.task_attachments_link_action)
    val searchLabel = stringResource(R.string.task_attachments_search_label)
    val searchClearText = stringResource(R.string.task_attachments_search_clear)
    val searchEmptyText = stringResource(R.string.task_attachments_search_empty)
    val unknownTypeText = stringResource(R.string.task_attachments_unknown_type)

    val attachmentIds = remember(attachments) { attachments.mapTo( linkedSetOf() ) { it.id } }
    val missingIds = remember(selectedIds, attachmentIds) {
        selectedIds.filterNot { it in attachmentIds }.toSet()
    }
    val (selectedAttachments, unselectedAttachments) = remember(attachments, selectedIds) {
        attachments.partition { it.id in selectedIds }
    }

    var unselectedExpanded by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val filteredUnselected = remember(unselectedAttachments, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            unselectedAttachments
        } else {
            unselectedAttachments.filter { item ->
                item.originalName.contains(query, ignoreCase = true) ||
                    item.mimeType.contains(query, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing8),
    ) {
        Text(
            text = sectionTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (missingIds.isNotEmpty()) {
            val missingErrorText = stringResource(
                R.string.task_attachments_missing_error,
                missingIds.size,
                missingIds.joinToString(", "),
            )
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(DesignTokens.spacing16),
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing8),
                ) {
                    Text(
                        text = missingErrorText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    OutlinedButton(
                        onClick = {
                            onSelectionChange(selectedIds - missingIds)
                        },
                        modifier = Modifier.heightIn(min = DesignTokens.touchTarget),
                    ) {
                        Text(removeMissingText)
                    }
                }
            }
        }

        if (attachments.isEmpty()) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = emptyHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(DesignTokens.spacing16),
                )
            }
        } else {
            Text(
                text = stringResource(R.string.task_attachments_selected_header, selectedAttachments.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (selectedAttachments.isEmpty()) {
                Text(
                    text = selectedEmptyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                selectedAttachments.forEach { item ->
                    AttachmentSelectionRow(
                        item = item,
                        selected = true,
                        actionLabel = unlinkActionText,
                        unknownTypeText = unknownTypeText,
                        context = context,
                        onToggle = {
                            onSelectionChange(selectedIds - item.id)
                        },
                        thumbnail = thumbnail,
                    )
                }
            }

            if (unselectedAttachments.isNotEmpty()) {
                val toggleText = if (unselectedExpanded) {
                    stringResource(R.string.task_attachments_unselected_collapse, unselectedAttachments.size)
                } else {
                    stringResource(R.string.task_attachments_unselected_expand, unselectedAttachments.size)
                }

                TextButton(
                    onClick = { unselectedExpanded = !unselectedExpanded },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = DesignTokens.touchTarget),
                ) {
                    Text(
                        text = toggleText,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (unselectedExpanded) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(searchLabel) },
                        trailingIcon = if (searchQuery.isNotEmpty()) {
                            {
                                TextButton(
                                    onClick = { searchQuery = "" },
                                    modifier = Modifier.heightIn(min = DesignTokens.touchTarget),
                                ) {
                                    Text(searchClearText)
                                }
                            }
                        } else {
                            null
                        },
                    )

                    if (filteredUnselected.isEmpty()) {
                        Text(
                            text = searchEmptyText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        filteredUnselected.forEach { item ->
                            AttachmentSelectionRow(
                                item = item,
                                selected = false,
                                actionLabel = linkActionText,
                                unknownTypeText = unknownTypeText,
                                context = context,
                                onToggle = {
                                    onSelectionChange(selectedIds + item.id)
                                },
                                thumbnail = thumbnail,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentSelectionRow(
    item: AttachmentEntity,
    selected: Boolean,
    actionLabel: String,
    unknownTypeText: String,
    context: Context,
    onToggle: () -> Unit,
    thumbnail: @Composable (AttachmentEntity) -> Unit,
) {
    val typeText = item.mimeType.ifBlank { unknownTypeText }
    val sizeText = remember(item.sizeBytes, context) {
        formatAttachmentSize(context, item.sizeBytes)
    }
    val metaText = stringResource(R.string.task_attachments_meta_format, typeText, sizeText)

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DesignTokens.touchTarget)
                .toggleable(
                    value = selected,
                    role = Role.Checkbox,
                    onValueChange = { onToggle() },
                )
                .padding(DesignTokens.spacing16)
                .semantics {
                    contentDescription = "${item.originalName}, $metaText, $actionLabel"
                },
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8),
            verticalArrangement = Arrangement.Center,
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = null,
            )
            Box(contentAlignment = Alignment.Center) {
                thumbnail(item)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing4),
            ) {
                Text(
                    text = item.originalName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = metaText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onToggle,
                modifier = Modifier.heightIn(min = DesignTokens.touchTarget),
            ) {
                Text(actionLabel)
            }
        }
    }
}

private fun formatAttachmentSize(context: Context, sizeBytes: Long): String {
    val safeBytes = sizeBytes.coerceAtLeast(0L)
    return when {
        safeBytes >= 1024L * 1024L ->
            context.getString(R.string.task_attachments_size_mb, safeBytes / (1024f * 1024f))
        safeBytes >= 1024L ->
            context.getString(R.string.task_attachments_size_kb, safeBytes / 1024f)
        else ->
            context.getString(R.string.task_attachments_size_bytes, safeBytes)
    }
}
