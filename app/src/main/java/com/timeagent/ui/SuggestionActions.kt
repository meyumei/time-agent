package com.timeagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.timeagent.core.TimeSpan
import com.timeagent.data.AgentSuggestionEntity
import com.timeagent.suggestions.SuggestionAction
import com.timeagent.domain.command.SetSuggestionPolicy
import com.timeagent.domain.command.throwIfRejected
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
fun SuggestionActions(model: TimeViewModel, card: AgentSuggestionEntity, busy: Boolean, onOpenTask: (String) -> Unit = {}) {
    var slots by remember(card.id) { mutableStateOf<List<TimeSpan>?>(null) }
    var editingDue by remember(card.id) { mutableStateOf(false) }
    var dueDate by remember(card.id) { mutableStateOf(model.repository.now().atZone(model.zone).toLocalDate().plusDays(1).toString()) }
    var dueTime by remember(card.id) { mutableStateOf("18:00") }
    var validation by remember(card.id) { mutableStateOf<String?>(null) }
    var draft by remember(card.id, card.version) { mutableStateOf<SuggestionAction?>(null) }
    fun execute(action: SuggestionAction) { draft = action }
    val bound = card.taskId != null && card.taskVersion != null
    if (bound) {
        if (card.proposedStartAt != null && card.proposedEndAt != null) {
            Text("建议安排 ${Instant.ofEpochMilli(card.proposedStartAt).label(model.zone)}–${Instant.ofEpochMilli(card.proposedEndAt).atZone(model.zone).format(DateTimeFormatter.ofPattern("HH:mm"))}", style = MaterialTheme.typography.labelMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (card.proposedStartAt != null && card.proposedEndAt != null) TextButton(
                onClick = { execute(SuggestionAction.Schedule(card.proposedStartAt, card.proposedEndAt)) },
                enabled = !busy, modifier = Modifier.testTag("suggestion-schedule"),
            ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_suggestion_actions_001)) }
            TextButton(onClick = { model.perform { slots = model.repository.suggestionSlots(card.id, card.version) } }, enabled = !busy, modifier = Modifier.testTag("suggestion-other-slot")) { Text(if (card.proposedStartAt != null) "换一个时段" else "选择空档") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { editingDue = true }, enabled = !busy, modifier = Modifier.testTag("suggestion-due")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_suggestion_actions_002)) }
            TextButton(onClick = { execute(SuggestionAction.StartTimer) }, enabled = !busy, modifier = Modifier.testTag("suggestion-start-timer")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_suggestion_actions_003)) }
            TextButton(onClick = { onOpenTask(card.taskId!!) }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_suggestion_actions_004)) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val until = model.repository.now().plusSeconds(3600).toEpochMilli()
        TextButton(onClick = { execute(SuggestionAction.Snooze(until)) }, enabled = !busy && until < (card.expiresAt ?: Long.MAX_VALUE), modifier = Modifier.testTag("suggestion-snooze")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_suggestion_actions_005)) }
        TextButton(onClick = { model.perform { model.repository.dismissSuggestion(card.id) } }, enabled = !busy, modifier = Modifier.testTag("suggestion-dismiss")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_ledger_screen_007)) }
        TextButton(onClick = { model.perform {
            model.repository.commands.execute(SetSuggestionPolicy(
                "suggestion-policy:${card.id}:${card.version}", card.type, true,
            )).throwIfRejected()
        } }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_suggestion_actions_006)) }
    }
    slots?.let { choices ->
        AlertDialog(onDismissRequest = { if (!busy) slots = null }, title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_suggestion_actions_007)) }, text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_suggestion_actions_008), style = MaterialTheme.typography.bodySmall)
                if (choices.isEmpty()) Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_suggestion_actions_009))
                choices.forEach { span -> TextButton(enabled = !busy, onClick = { execute(SuggestionAction.Schedule(span.start.toEpochMilli(), span.end.toEpochMilli())) }) {
                    Text("${span.start.label(model.zone)}–${span.end.atZone(model.zone).format(DateTimeFormatter.ofPattern("HH:mm"))}")
                } }
            }
        }, confirmButton = { TextButton(onClick = { slots = null }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_back)) } })
    }
    if (editingDue) AlertDialog(onDismissRequest = { if (!busy) editingDue = false }, title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_suggestion_actions_010)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(dueDate, { dueDate = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_028)) }, singleLine = true, modifier = Modifier.testTag("suggestion-due-date"))
            OutlinedTextField(dueTime, { dueTime = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_deadline_screen_029)) }, singleLine = true, modifier = Modifier.testTag("suggestion-due-time"))
            validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        val instant = runCatching { LocalDate.parse(dueDate).atTime(LocalTime.parse(dueTime)).atZone(model.zone).toInstant() }.getOrNull()
        if (instant == null || instant <= model.repository.now()) validation = "请填写有效的未来日期和时间"
        else execute(SuggestionAction.Postpone(instant.toEpochMilli()))
    }, modifier = Modifier.testTag("suggestion-due-save")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_suggestion_actions_011)) } }, dismissButton = { TextButton(onClick = { editingDue = false }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } })
    draft?.let { action ->
        val after = when (action) {
            is SuggestionAction.Schedule -> "创建关联日程：${Instant.ofEpochMilli(action.startAt).label(model.zone)}–${Instant.ofEpochMilli(action.endAt).label(model.zone)}"
            is SuggestionAction.Postpone -> "截止改为：${Instant.ofEpochMilli(action.dueAt).label(model.zone)}"
            is SuggestionAction.Snooze -> "暂缓此建议至：${Instant.ofEpochMilli(action.until).label(model.zone)}"
            SuggestionAction.StartTimer -> "开始这项任务的计时"
        }
        AlertDialog(
            onDismissRequest = { if (!busy) draft = null },
            title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_suggestion_actions_012)) },
            text = { StructuredDraftPreview(card.title, card.detail, after) },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                model.perform(onSuccess = { draft = null; slots = null; editingDue = false }) {
                    model.repository.executeSuggestion(card.id, card.version, action)
                }
            }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_suggestion_actions_013)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { draft = null }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } },
        )
    }
}
