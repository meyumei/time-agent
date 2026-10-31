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
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
fun SuggestionActions(model: TimeViewModel, card: AgentSuggestionEntity, busy: Boolean, onOpenTask: (String) -> Unit = {}) {
    var slots by remember(card.id) { mutableStateOf<List<TimeSpan>?>(null) }
    var editingDue by remember(card.id) { mutableStateOf(false) }
    var dueDate by remember(card.id) { mutableStateOf(model.repository.now().atZone(model.zone).toLocalDate().plusDays(1).toString()) }
    var dueTime by remember(card.id) { mutableStateOf("18:00") }
    var validation by remember(card.id) { mutableStateOf<String?>(null) }
    fun execute(action: SuggestionAction) { model.perform(onSuccess = { slots = null; editingDue = false }) { model.repository.executeSuggestion(card.id, card.version, action) } }
    val bound = card.taskId != null && card.taskVersion != null
    if (bound) {
        if (card.proposedStartAt != null && card.proposedEndAt != null) {
            Text("建议安排 ${Instant.ofEpochMilli(card.proposedStartAt).label(model.zone)}–${Instant.ofEpochMilli(card.proposedEndAt).atZone(model.zone).format(DateTimeFormatter.ofPattern("HH:mm"))}", style = MaterialTheme.typography.labelMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (card.proposedStartAt != null && card.proposedEndAt != null) TextButton(
                onClick = { execute(SuggestionAction.Schedule(card.proposedStartAt, card.proposedEndAt)) },
                enabled = !busy, modifier = Modifier.testTag("suggestion-schedule"),
            ) { Text("安排到这个时段") }
            TextButton(onClick = { model.perform { slots = model.repository.suggestionSlots(card.id, card.version) } }, enabled = !busy, modifier = Modifier.testTag("suggestion-other-slot")) { Text(if (card.proposedStartAt != null) "换一个时段" else "选择空档") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { editingDue = true }, enabled = !busy, modifier = Modifier.testTag("suggestion-due")) { Text("调整截止 / 延后") }
            TextButton(onClick = { execute(SuggestionAction.StartTimer) }, enabled = !busy, modifier = Modifier.testTag("suggestion-start-timer")) { Text("开始计时") }
            TextButton(onClick = { onOpenTask(card.taskId!!) }, enabled = !busy) { Text("事项") }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val until = model.repository.now().plusSeconds(3600).toEpochMilli()
        TextButton(onClick = { execute(SuggestionAction.Snooze(until)) }, enabled = !busy && until < (card.expiresAt ?: Long.MAX_VALUE), modifier = Modifier.testTag("suggestion-snooze")) { Text("暂缓 1 小时") }
        TextButton(onClick = { model.perform { model.repository.dismissSuggestion(card.id) } }, enabled = !busy, modifier = Modifier.testTag("suggestion-dismiss")) { Text("忽略") }
    }
    slots?.let { choices ->
        AlertDialog(onDismissRequest = { if (!busy) slots = null }, title = { Text("选择可安排时段") }, text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text("按任务预计时长计算，保存时再次检查冲突。", style = MaterialTheme.typography.bodySmall)
                if (choices.isEmpty()) Text("今天未查到足够空档，可返回事项调整预计时长或截止。")
                choices.forEach { span -> TextButton(enabled = !busy, onClick = { execute(SuggestionAction.Schedule(span.start.toEpochMilli(), span.end.toEpochMilli())) }) {
                    Text("${span.start.label(model.zone)}–${span.end.atZone(model.zone).format(DateTimeFormatter.ofPattern("HH:mm"))}")
                } }
            }
        }, confirmButton = { TextButton(onClick = { slots = null }, enabled = !busy) { Text("返回") } })
    }
    if (editingDue) AlertDialog(onDismissRequest = { if (!busy) editingDue = false }, title = { Text("调整截止时间") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(dueDate, { dueDate = it }, label = { Text("日期 yyyy-MM-dd") }, singleLine = true, modifier = Modifier.testTag("suggestion-due-date"))
            OutlinedTextField(dueTime, { dueTime = it }, label = { Text("时间 HH:mm") }, singleLine = true, modifier = Modifier.testTag("suggestion-due-time"))
            validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        val instant = runCatching { LocalDate.parse(dueDate).atTime(LocalTime.parse(dueTime)).atZone(model.zone).toInstant() }.getOrNull()
        if (instant == null || instant <= model.repository.now()) validation = "请填写有效的未来日期和时间"
        else execute(SuggestionAction.Postpone(instant.toEpochMilli()))
    }, modifier = Modifier.testTag("suggestion-due-save")) { Text("保存截止") } }, dismissButton = { TextButton(onClick = { editingDue = false }, enabled = !busy) { Text("取消") } })
}
