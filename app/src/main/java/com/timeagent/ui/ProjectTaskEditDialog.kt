package com.timeagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.timeagent.R
import com.timeagent.data.TaskEntity
import com.timeagent.domain.command.CommandResult
import com.timeagent.domain.command.CommandStatus
import com.timeagent.ui.components.DurationField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
internal fun ProjectTaskEditDialog(
    initial: ProjectTaskEditorDraft,
    roots: List<TaskEntity>,
    busy: Boolean,
    onDraftChange: (ProjectTaskEditorDraft) -> Boolean,
    onRemoveDraft: () -> Boolean,
    onDismiss: () -> Unit,
    onSave: suspend (ProjectTaskEditorDraft, String, Int) -> CommandResult,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var completed by remember(initial.id) { mutableStateOf(initial.saved) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val title = draft.fields.title
    val minutesText = draft.fields.minutesText
    val parentId = draft.fields.parentId
    val editing = !busy && !submitting && !completed
    fun update(fields: ProjectTaskFields) {
        if (busy || submitting || completed || fields == draft.fields) return
        draft = draft.copy(fields = fields, requestId = UUID.randomUUID().toString())
        error = if (onDraftChange(draft)) null else context.getString(R.string.project_task_draft_write_failed)
    }
    fun cleanup() {
        if (onRemoveDraft()) onDismiss()
        else error = context.getString(if (completed) R.string.project_task_draft_cleanup_failed else R.string.project_task_draft_write_failed)
    }
    val dismiss = rememberDraftExit(initial.id, draft.fields.values(), !editing,
        onDiscard = ::cleanup,
        onKeep = {
            if (onDraftChange(draft)) onDismiss()
            else error = context.getString(R.string.project_task_draft_write_failed)
        }, initialFields = initial.baseline.values())

    AlertDialog(
        onDismissRequest = { if (completed) cleanup() else dismiss() },
        title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_new_task)) },
        text = {
            if (completed) Column {
                Text(stringResource(R.string.project_task_draft_saved))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } else Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.project_task_draft_project, initial.project.name))
                OutlinedTextField(
                    value = title,
                    onValueChange = { update(draft.fields.copy(title = it)) },
                    enabled = editing,
                    label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_editors_009)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                DurationField(
                    minutes = minutesText.trim().toIntOrNull()?.takeIf { it > 0 } ?: 0,
                    onChange = { },
                    textValue = minutesText,
                    onTextChange = { update(draft.fields.copy(minutesText = it)) }
                )
                Spacer(Modifier.height(8.dp))
                Text(text = androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_018), style = MaterialTheme.typography.bodySmall)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = editing) { update(draft.fields.copy(parentId = null)) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = parentId == null, onClick = { update(draft.fields.copy(parentId = null)) }, enabled = editing)
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_screens_019))
                }
                roots.forEach { root ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = editing) { update(draft.fields.copy(parentId = root.id)) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = parentId == root.id, onClick = { update(draft.fields.copy(parentId = root.id)) }, enabled = editing)
                        Text(root.title)
                    }
                }
                if (parentId != null && roots.none { it.id == parentId }) {
                    Text(stringResource(R.string.project_task_draft_parent_missing), color = MaterialTheme.colorScheme.error)
                }
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (completed) { cleanup(); return@TextButton }
                    if (busy || submitting) return@TextButton
                    val pending = draft
                    val trimmed = pending.fields.title.trim()
                    if (trimmed.isEmpty()) {
                        error = context.getString(R.string.project_editor_error_task_name_required)
                        return@TextButton
                    }
                    val minutes = pending.fields.minutesText.trim().toIntOrNull()
                    if (minutes == null || minutes <= 0) {
                        error = context.getString(R.string.project_editor_error_minutes_positive)
                        return@TextButton
                    }
                    if (!onDraftChange(pending)) {
                        error = context.getString(R.string.project_task_draft_write_failed)
                        return@TextButton
                    }
                    submitting = true
                    scope.launch {
                        try {
                            val result = onSave(pending, trimmed, minutes)
                            if (result.status in setOf(CommandStatus.APPLIED, CommandStatus.DUPLICATE)) {
                                completed = true
                                draft = pending.copy(saved = true)
                                onDraftChange(draft)
                                cleanup()
                            } else error = result.message
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            error = failure.message ?: context.getString(R.string.project_task_draft_save_failed)
                        } finally {
                            submitting = false
                        }
                    }
                },
                enabled = !busy && !submitting
            ) { Text(stringResource(if (completed) R.string.action_close else R.string.action_save)) }
        },
        dismissButton = {
            if (!completed) TextButton(onClick = dismiss, enabled = editing) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) }
        }
    )
}

