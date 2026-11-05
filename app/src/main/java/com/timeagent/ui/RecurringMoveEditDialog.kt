package com.timeagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.timeagent.R
import com.timeagent.domain.command.*
import com.timeagent.recurring.RecurringService
import com.timeagent.ui.components.DateField
import com.timeagent.ui.components.TimeField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
internal fun RecurringMoveEditDialog(
    initial: RecurringMoveDraft,
    model: TimeViewModel,
    onDraftChange: (RecurringMoveDraft) -> Boolean,
    onRemoveDraft: () -> Boolean,
    onDismiss: () -> Unit,
    onSaved: (CommandResult) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember(model) { RecurringService(model.repository) }
    val busy by model.busy.collectAsState()
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var submitting by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(initial.saved) }
    var error by remember { mutableStateOf<String?>(null) }
    val editing = !busy && !submitting && !completed
    val zone = remember(initial.zoneId) { ZoneId.of(initial.zoneId) }
    val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val date = runCatching { LocalDate.parse(draft.fields.dateText) }.getOrNull()
    val time = runCatching { LocalTime.parse(draft.fields.timeText, timeFormat) }.getOrNull()
    fun update(fields: MoveFields) {
        if (busy || submitting || completed || fields == draft.fields) return
        draft = draft.copy(fields = fields, preview = null, requestId = UUID.randomUUID().toString())
        error = if (onDraftChange(draft)) null else context.getString(R.string.move_draft_write_failed)
    }
    fun cleanup() {
        if (onRemoveDraft()) onDismiss()
        else error = context.getString(if (completed) R.string.move_draft_cleanup_failed else R.string.move_draft_write_failed)
    }
    fun prepare() {
        if (busy || submitting || completed) return
        val pending = draft
        val at = runCatching {
            LocalDate.parse(pending.fields.dateText).atTime(LocalTime.parse(pending.fields.timeText, timeFormat))
                .atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
        if (at == null) {
            error = context.getString(if (date == null) R.string.move_draft_invalid_date else R.string.move_draft_invalid_time)
            return
        }
        submitting = true
        scope.launch {
            try {
                val check = service.previewMoveDetails(pending.instance.id, pending.instance.version, at, pending.fields.planned)
                if (check.taskVersion != pending.taskVersion) {
                    error = context.getString(R.string.move_draft_task_changed)
                } else {
                    val checked = pending.copy(preview = MovePreviewDraft.from(check))
                    if (onDraftChange(checked)) {
                        draft = checked
                        error = null
                    } else error = context.getString(R.string.move_draft_write_failed)
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: context.getString(R.string.move_draft_save_failed)
            } finally {
                submitting = false
            }
        }
    }
    fun commit() {
        if (busy || submitting || completed) return
        val pending = draft
        val check = pending.preview ?: return
        if (!onDraftChange(pending)) {
            error = context.getString(R.string.move_draft_write_failed)
            return
        }
        submitting = true
        scope.launch {
            try {
                val result = model.repository.commands.execute(MoveRecurringInstance(pending.requestId, check.preview(pending.instance)))
                if (result.status in setOf(CommandStatus.APPLIED, CommandStatus.DUPLICATE)) {
                    completed = true
                    draft = pending.copy(saved = true)
                    onDraftChange(draft)
                    onSaved(result)
                    cleanup()
                } else error = result.message
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: context.getString(R.string.move_draft_save_failed)
            } finally {
                submitting = false
            }
        }
    }
    val dismiss = rememberDraftExit(initial.id, draft.fields.values(), !editing,
        onDiscard = ::cleanup,
        onKeep = {
            if (onDraftChange(draft)) onDismiss()
            else error = context.getString(R.string.move_draft_write_failed)
        }, initialFields = initial.baseline.values())
    val check = draft.preview
    AlertDialog(
        onDismissRequest = { if (completed) cleanup() else dismiss() },
        title = { Text(stringResource(R.string.move_draft_title, initial.title)) },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (completed) Text(stringResource(R.string.move_draft_saved))
            else if (check == null) {
                DateField(stringResource(R.string.move_draft_date), date ?: LocalDate.parse(initial.instance.originalDate),
                    { update(draft.fields.copy(dateText = it.toString())) },
                    error = if (date == null) stringResource(R.string.move_draft_invalid_date) else null)
                TimeField(stringResource(R.string.move_draft_time), time ?: LocalTime.MIDNIGHT,
                    { update(draft.fields.copy(timeText = it.format(timeFormat))) },
                    error = if (time == null) stringResource(R.string.move_draft_invalid_time) else null)
                if (initial.domain != "DEADLINE") Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(draft.fields.planned, { update(draft.fields.copy(planned = it)) }, enabled = editing)
                    Text(stringResource(R.string.text_recurring_screen_017))
                }
                Text(stringResource(if (draft.fields.planned) R.string.move_draft_plan_hint else R.string.move_draft_date_hint))
            } else {
                Text(stringResource(R.string.move_draft_confirm_target,
                    Instant.ofEpochMilli(check.at).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))))
                if (check.conflicts.isEmpty()) Text(stringResource(R.string.text_recurring_screen_019))
                else {
                    Text(stringResource(R.string.move_draft_conflicts, check.conflicts.size), color = MaterialTheme.colorScheme.error)
                    check.conflicts.forEach { Text(it.title) }
                }
                Text(stringResource(R.string.text_recurring_screen_020))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = {
            TextButton(onClick = { if (completed) cleanup() else if (check == null) prepare() else commit() },
                enabled = !busy && !submitting, modifier = Modifier.testTag(if (check == null) "recurring-move-check" else "recurring-move-confirm")) {
                Text(stringResource(when {
                    completed -> R.string.action_close
                    check == null -> R.string.text_recurring_screen_018
                    check.conflicts.isEmpty() -> R.string.move_draft_confirm
                    else -> R.string.move_draft_confirm_conflicts
                }))
            }
        },
        dismissButton = { if (!completed) Row {
            if (check != null) TextButton(onClick = {
                if (!busy && !submitting) {
                    val next = draft.copy(preview = null, requestId = UUID.randomUUID().toString())
                    if (onDraftChange(next)) { draft = next; error = null }
                    else error = context.getString(R.string.move_draft_write_failed)
                }
            }, enabled = editing) { Text(stringResource(R.string.text_recurring_screen_021)) }
            TextButton(onClick = dismiss, enabled = editing) { Text(stringResource(R.string.action_cancel)) }
        } },
    )
}
