package com.timeagent.ui

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
import com.timeagent.domain.command.CommandResult
import com.timeagent.domain.command.CommandStatus
import com.timeagent.domain.command.SaveRecurringRule
import com.timeagent.recurring.RecurringService
import com.timeagent.recurring.RuleChangeScope
import com.timeagent.ui.components.InlineError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

@Composable
internal fun RecurringPauseDialog(
    initial: RecurringPauseDraft,
    model: TimeViewModel,
    onDraftChange: (RecurringPauseDraft) -> Boolean,
    onRemoveDraft: () -> Boolean,
    onDismiss: () -> Unit,
    onSaved: (CommandResult) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val service = remember(model) { RecurringService(model.repository) }
    val busy by model.busy.collectAsState()
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var submitting by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(initial.saved) }
    var error by remember { mutableStateOf<String?>(null) }
    val enabled = !busy && !submitting

    fun remove() {
        if (onRemoveDraft()) onDismiss()
        else error = context.getString(if (completed) R.string.pause_draft_cleanup_failed else R.string.pause_draft_write_failed)
    }
    fun keep() {
        if (onDraftChange(draft)) onDismiss()
        else error = context.getString(R.string.pause_draft_write_failed)
    }
    suspend fun prepare() {
        if (submitting || completed) return
        val pending = draft
        submitting = true
        try {
            val check = service.previewRuleChange(pending.original.id, LocalDate.parse(pending.from))
            val next = pending.copy(preview = RecurringSubmission.create(RuleChangeScope.INCLUDING_PENDING, check),
                selectedScope = null, requestId = UUID.randomUUID().toString())
            if (onDraftChange(next)) {
                draft = next
                error = null
            } else error = context.getString(R.string.pause_draft_write_failed)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = failure.message ?: context.getString(R.string.pause_draft_preview_failed)
        } finally {
            submitting = false
        }
    }
    fun commit(selectedScope: RuleChangeScope) {
        if (busy || submitting || completed) return
        val pending = draft.copy(selectedScope = draft.selectedScope ?: selectedScope.name)
        val preview = pending.preview?.preview() ?: return
        if (!onDraftChange(pending)) {
            error = context.getString(R.string.pause_draft_write_failed)
            return
        }
        draft = pending
        submitting = true
        coroutineScope.launch {
            try {
                val chosenScope = requireNotNull(pending.selectedScope)
                val result = model.repository.commands.execute(SaveRecurringRule(
                    "${pending.requestId}:$chosenScope", pending.entity(), pending.original.version,
                    LocalDate.parse(pending.from), RuleChangeScope.valueOf(chosenScope), preview))
                if (result.status in setOf(CommandStatus.APPLIED, CommandStatus.DUPLICATE)) {
                    completed = true
                    draft = pending.copy(saved = true)
                    onDraftChange(draft)
                    onSaved(result)
                    remove()
                } else error = result.message
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: context.getString(R.string.pause_draft_save_failed)
            } finally {
                submitting = false
            }
        }
    }
    LaunchedEffect(initial.id) {
        if (draft.preview == null && !completed) prepare()
    }
    val preview = draft.preview
    val selectedScope = draft.selectedScope
    AlertDialog(
        onDismissRequest = { if (enabled) { if (completed) remove() else keep() } },
        title = { Text(stringResource(if (draft.pattern.paused) R.string.pause_draft_pause_title else R.string.pause_draft_resume_title,
            draft.original.title)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (completed) Text(stringResource(R.string.pause_draft_saved))
                else {
                    Text(stringResource(R.string.recurring_draft_scope_title, draft.from))
                    Text(stringResource(R.string.text_recurring_screen_009))
                    if (preview == null) Text(stringResource(R.string.text_recurring_screen_010))
                    else {
                        Text(stringResource(R.string.recurring_draft_scope_future, requireNotNull(preview.firstUnmaterializedDate)))
                        Text(stringResource(R.string.recurring_draft_scope_counts, requireNotNull(preview.editable),
                            requireNotNull(preview.protectedCount)))
                    }
                    Text(stringResource(R.string.text_recurring_screen_011))
                    if (selectedScope != null) Text(stringResource(R.string.pause_draft_scope_locked))
                    if (preview != null || error != null) TextButton(
                        onClick = { coroutineScope.launch { prepare() } }, enabled = enabled,
                    ) { Text(stringResource(R.string.pause_draft_refresh_preview)) }
                }
                InlineError(error)
            }
        },
        confirmButton = {
            if (completed) TextButton(onClick = ::remove, enabled = enabled) { Text(stringResource(R.string.action_close)) }
            else Column {
                if (selectedScope == null || selectedScope == RuleChangeScope.INCLUDING_PENDING.name) TextButton(
                    onClick = { commit(RuleChangeScope.INCLUDING_PENDING) }, enabled = enabled && preview != null,
                    modifier = Modifier.testTag("recurring-pause-including-pending"),
                ) { Text(stringResource(R.string.text_recurring_screen_012)) }
                if (selectedScope == null || selectedScope == RuleChangeScope.FUTURE_ONLY.name) TextButton(
                    onClick = { commit(RuleChangeScope.FUTURE_ONLY) }, enabled = enabled && preview != null,
                    modifier = Modifier.testTag("recurring-pause-future-only"),
                ) { Text(stringResource(R.string.text_recurring_screen_013)) }
            }
        },
        dismissButton = {
            if (!completed) Column {
                TextButton(onClick = ::keep, enabled = enabled) { Text(stringResource(R.string.pause_draft_keep)) }
                TextButton(onClick = ::remove, enabled = enabled) { Text(stringResource(R.string.pause_draft_discard)) }
            }
        },
    )
}
