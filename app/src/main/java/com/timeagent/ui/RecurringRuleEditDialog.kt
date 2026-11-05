package com.timeagent.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.timeagent.R
import com.timeagent.data.ProjectEntity
import com.timeagent.domain.command.*
import com.timeagent.recurring.RecurringService
import com.timeagent.recurring.RuleChangePreview
import com.timeagent.recurring.RuleChangeScope
import com.timeagent.ui.components.DateField
import com.timeagent.ui.components.RecurrenceField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

@Composable
internal fun RecurringRuleEditDialog(
    initial: RecurringEditorDraft,
    projects: List<ProjectEntity>,
    model: TimeViewModel,
    onDraftChange: (RecurringEditorDraft) -> Boolean,
    onRemoveDraft: () -> Boolean,
    onDismiss: () -> Unit,
    onSaved: (CommandResult) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember(model) { RecurringService(model.repository) }
    val busy by model.busy.collectAsState()
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var completed by remember(initial.id) { mutableStateOf(initial.saved) }
    var submitting by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(initial.submission != null && !initial.saved) }
    var preview by remember { mutableStateOf<RuleChangePreview?>(initial.submission?.preview()) }
    var error by remember { mutableStateOf<String?>(null) }
    val fields = draft.fields
    val editing = !busy && !submitting && !completed

    fun update(next: RecurringEditorFields) {
        if (busy || submitting || completed || next == draft.fields) return
        draft = draft.copy(fields = next, requestId = UUID.randomUUID().toString(), submission = null)
        error = if (onDraftChange(draft)) null else context.getString(R.string.recurring_draft_write_failed)
    }
    fun cleanup() {
        if (onRemoveDraft()) onDismiss()
        else error = context.getString(if (completed) R.string.recurring_draft_cleanup_failed else R.string.recurring_draft_write_failed)
    }
    fun returnToEditor() {
        if (busy || submitting) return
        val next = draft.copy(submission = null)
        if (onDraftChange(next)) {
            draft = next
            confirming = false
            preview = null
            error = null
        } else error = context.getString(R.string.recurring_draft_write_failed)
    }
    fun commit(selectedScope: RuleChangeScope) {
        if (busy || submitting || completed) return
        val pending = draft.copy(submission = draft.submission ?: RecurringSubmission.create(selectedScope, preview))
        if (!onDraftChange(pending)) {
            error = context.getString(R.string.recurring_draft_write_failed)
            return
        }
        draft = pending
        submitting = true
        scope.launch {
            try {
                val submission = requireNotNull(pending.submission)
                val result = model.repository.commands.execute(SaveRecurringRule(
                    "${pending.requestId}:${submission.scope}", pending.entity(), pending.original?.version,
                    LocalDate.parse(pending.fields.pattern.effectiveFrom), RuleChangeScope.valueOf(submission.scope),
                    submission.preview()))
                if (result.status in setOf(CommandStatus.APPLIED, CommandStatus.DUPLICATE)) {
                    completed = true
                    confirming = false
                    draft = pending.copy(saved = true)
                    onDraftChange(draft)
                    onSaved(result)
                    cleanup()
                } else error = result.message
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: context.getString(R.string.recurring_draft_save_failed)
            } finally {
                submitting = false
            }
        }
    }
    fun prepare() {
        if (busy || submitting || completed) return
        val pending = draft
        val pattern = pending.fields.pattern
        if (pending.fields.title.isBlank()) {
            error = context.getString(R.string.recurring_draft_title_required)
            return
        }
        val valid = pending.fields.input.isValid(pattern) &&
            runCatching { LocalDate.parse(pattern.startDate); LocalDate.parse(pattern.effectiveFrom) }.isSuccess &&
            (pattern.untilDate?.let { runCatching { !LocalDate.parse(it).isBefore(LocalDate.parse(pattern.startDate)) }.getOrDefault(false) } != false) &&
            (pattern.frequency != "WEEKDAYS" || pattern.weekdays.isNotEmpty()) &&
            (pattern.frequency != "WEEKLY_COUNT" || pattern.weeklyCount in 1..21)
        if (!valid) {
            error = context.getString(R.string.recurring_draft_invalid_input)
            return
        }
        if (!onDraftChange(pending)) {
            error = context.getString(R.string.recurring_draft_write_failed)
            return
        }
        if (pending.original == null) {
            commit(RuleChangeScope.INCLUDING_PENDING)
        } else {
            submitting = true
            scope.launch {
                try {
                    preview = service.previewRuleChange(pending.id, LocalDate.parse(pattern.effectiveFrom))
                    confirming = true
                    error = null
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = failure.message ?: context.getString(R.string.recurring_draft_save_failed)
                } finally {
                    submitting = false
                }
            }
        }
    }
    val dismiss = rememberDraftExit(initial.id, fields.values(), !editing,
        onDiscard = ::cleanup,
        onKeep = {
            if (onDraftChange(draft)) onDismiss()
            else error = context.getString(R.string.recurring_draft_write_failed)
        }, initialFields = initial.baseline.values())

    if (confirming && !completed) {
        val chosen = draft.submission
        val counts = preview
        AlertDialog(
            onDismissRequest = ::returnToEditor,
            title = { Text(stringResource(R.string.recurring_draft_scope_title, fields.pattern.effectiveFrom)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.text_recurring_screen_009))
                counts?.let {
                    Text(stringResource(R.string.recurring_draft_scope_future, it.firstUnmaterializedDate.toString()))
                    Text(stringResource(R.string.recurring_draft_scope_counts, it.editable, it.protectedCount))
                }
                if (chosen != null) {
                    Text(stringResource(R.string.recurring_draft_scope_locked))
                    Text(stringResource(if (chosen.scope == RuleChangeScope.FUTURE_ONLY.name)
                        R.string.recurring_draft_scope_only_future else R.string.recurring_draft_scope_including))
                }
                Text(stringResource(R.string.text_recurring_screen_011))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = {
                TextButton(onClick = { commit(chosen?.scope?.let(RuleChangeScope::valueOf) ?: RuleChangeScope.INCLUDING_PENDING) },
                    enabled = editing && (chosen != null || counts != null),
                    modifier = Modifier.testTag("recurring-save-including-pending")) {
                    Text(stringResource(if (chosen != null) R.string.action_save else R.string.text_recurring_screen_012))
                }
            },
            dismissButton = { Row {
                if (chosen == null) TextButton(onClick = { commit(RuleChangeScope.FUTURE_ONLY) }, enabled = editing && counts != null,
                    modifier = Modifier.testTag("recurring-save-future-only")) { Text(stringResource(R.string.text_recurring_screen_013)) }
                TextButton(onClick = ::returnToEditor, enabled = editing) { Text(stringResource(R.string.action_cancel)) }
            } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (completed) cleanup() else dismiss() },
        title = { Text(stringResource(if (initial.original == null) R.string.recurring_draft_create else R.string.recurring_draft_edit)) },
        text = {
            if (completed) Column {
                Text(stringResource(R.string.recurring_draft_saved))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } else Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(fields.title, { update(draft.fields.copy(title = it)) }, enabled = editing,
                    label = { Text(stringResource(R.string.text_template_editor_006)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("recurring-title"))
                if (initial.original != null) DateField(stringResource(R.string.rr_label_effective_from),
                    LocalDate.parse(fields.pattern.effectiveFrom),
                    { update(draft.fields.copy(pattern = draft.fields.pattern.copy(effectiveFrom = it.toString()))) })
                RecurrenceField(fields.pattern,
                    onChange = { next -> if (next != null) update(draft.fields.copy(pattern = next)) },
                    seedDate = LocalDate.parse(fields.pattern.startDate), seedId = initial.id, allowNone = false,
                    inputDraft = fields.input,
                    onInputDraftChange = { update(draft.fields.copy(input = it)) })
                Text(stringResource(R.string.text_recurring_screen_024))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(fields.projectId == null, { update(draft.fields.copy(projectId = null)) }, enabled = editing,
                        label = { Text(stringResource(R.string.text_deadline_screen_032)) })
                    projects.forEach { project ->
                        FilterChip(fields.projectId == project.id, { update(draft.fields.copy(projectId = project.id)) }, enabled = editing,
                            label = { Text(project.name) })
                    }
                }
                if (fields.projectId != null && projects.none { it.id == fields.projectId })
                    Text(stringResource(R.string.recurring_draft_project_missing), color = MaterialTheme.colorScheme.error)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (completed) cleanup() else prepare() }, enabled = !busy && !submitting,
                modifier = Modifier.testTag("recurring-save")) { Text(stringResource(if (completed) R.string.action_close else R.string.action_save)) }
        },
        dismissButton = {
            if (!completed) TextButton(onClick = dismiss, enabled = editing) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
