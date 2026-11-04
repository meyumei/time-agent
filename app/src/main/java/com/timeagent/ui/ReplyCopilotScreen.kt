package com.timeagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.timeagent.extraction.*
import com.timeagent.reply.*
import java.time.*
import java.time.format.DateTimeFormatter

private class ReplyCopilotViewModel(initialText: String, initialDay: String) : ViewModel() {
    val source = mutableStateOf(initialText)
    val preview = mutableStateOf<ExtractionPreview?>(null)
    val selected = mutableStateOf<String?>(null)
    val day = mutableStateOf(initialDay)
    val from = mutableStateOf("08:00")
    val until = mutableStateOf("22:00")
    val minutes = mutableStateOf("30")
    val check = mutableStateOf<ReplyAvailability?>(null)
    val draft = mutableStateOf<ReplyDraft?>(null)
    val reply = mutableStateOf("")
    val mine = mutableStateOf(false)
    val notice = mutableStateOf<String?>(null)
    val saved = mutableStateOf(false)
}

private class ReplyCopilotViewModelFactory(private val initialText: String, private val initialDay: String) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ReplyCopilotViewModel(initialText, initialDay) as T
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplyCopilotScreen(model: TimeViewModel, initialText: String, onClose: () -> Unit, onOpenSettings: (() -> Unit)? = null) {
    val initialDay = remember { model.repository.now().atZone(model.zone).toLocalDate().toString() }
    val state: ReplyCopilotViewModel = viewModel(factory = ReplyCopilotViewModelFactory(initialText, initialDay))
    val busy by model.busy.collectAsState()
    val error by model.error.collectAsState()
    val clipboard = LocalClipboardManager.current
    var source by state.source
    var preview by state.preview
    var selected by state.selected
    var day by state.day
    var from by state.from
    var until by state.until
    var minutes by state.minutes
    var check by state.check
    var draft by state.draft
    var reply by state.reply
    var mine by state.mine
    var notice by state.notice
    var saved by state.saved
    val aiAvailability = rememberAiAvailability(model)
    val formatter = remember { DateTimeFormatter.ofPattern("M月d日 HH:mm") }
    fun period(slot: com.timeagent.core.TimeSpan) = "${formatter.format(slot.start.atZone(model.zone))}–${slot.end.atZone(model.zone).toLocalTime()}"
    fun invalidate() { check = null; draft = null; mine = false; saved = false }
    fun generate(kind: ReplyKind, index: Int = 0) {
        runCatching { ReplyCopilot.draft(requireNotNull(check), kind, index, model.zone) }
            .onSuccess { draft = it; reply = it.text; mine = false; saved = false; notice = "已生成草稿；尚未保存安排、复制或发送" }
            .onFailure { notice = it.message }
    }
    BackHandler(enabled = !busy, onBack = onClose)
    Scaffold(topBar = { TopAppBar(title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reply_copilot_screen_001)) }, navigationIcon = { TextButton(onClick = onClose, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_back)) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reply_copilot_screen_002))
            AiAvailabilityBanner(
                availability = aiAvailability,
                onOpenSettings = onOpenSettings,
                detail = "空档核对与回复模板仍可本地使用；需要鱼参与生成时请先配置模型。入口不会被隐藏。",
            )
            OutlinedTextField(source, { source = it; preview = null; selected = null; invalidate() }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reply_copilot_screen_003)) }, minLines = 3,
                modifier = Modifier.fillMaxWidth().testTag("reply-source"), enabled = !busy)
            Button(onClick = {
                runCatching { ChatTextExtractor.extract(source, model.repository.now(), model.zone) }.onSuccess {
                    preview = it; selected = it.items.firstOrNull()?.id; invalidate(); notice = if (it.items.isEmpty()) "没有明确候选，可先确认具体时间" else null
                    it.items.firstOrNull()?.startAt?.let { start -> day = Instant.ofEpochMilli(start).atZone(model.zone).toLocalDate().toString() }
                }.onFailure { notice = it.message }
            }, enabled = !busy && source.isNotBlank() && source.length <= 20000, modifier = Modifier.testTag("reply-extract")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reply_copilot_screen_004)) }
            preview?.let { extracted ->
                extracted.items.forEach { item ->
                    Row { RadioButton(selected == item.id, onClick = { selected = item.id; invalidate() }); TextButton(onClick = { selected = item.id; invalidate() }) { Text(item.title) } }
                }
                if (selected != null) Text(extracted.items.firstOrNull { it.id == selected }?.original.orEmpty(), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(day, { day = it; invalidate() }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reply_copilot_screen_005)) }, modifier = Modifier.fillMaxWidth().testTag("reply-day"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(from, { from = it; invalidate() }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reply_copilot_screen_006)) }, modifier = Modifier.weight(1f))
                    OutlinedTextField(until, { until = it; invalidate() }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reply_copilot_screen_007)) }, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(minutes, { minutes = it; invalidate() }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reply_copilot_screen_008)) }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { model.perform {
                    check = ReplyCopilot.check(source, selected, LocalDate.parse(day.trim()), minutes.toInt(), LocalTime.parse(from.trim()), LocalTime.parse(until.trim()),
                        model.repository.snapshot(), model.repository.now(), model.zone)
                    draft = null; mine = false; saved = false; notice = "已核对；生成草稿不会写入日程"
                } }, enabled = !busy, modifier = Modifier.testTag("reply-check")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reply_copilot_screen_009)) }
            }
            check?.let { checked ->
                if (checked.runningTimer) Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reply_copilot_screen_010))
                checked.accepted?.let { slot -> Button(onClick = { generate(ReplyKind.ACCEPT) }, enabled = !busy, modifier = Modifier.testTag("reply-accept")) { Text("接受原时段 ${period(slot)}") } }
                    ?: Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reply_copilot_screen_011))
                checked.alternatives.forEachIndexed { index, slot ->
                    OutlinedButton(onClick = { generate(ReplyKind.ALTERNATE, index) }, enabled = !busy, modifier = Modifier.testTag("reply-alternate-$index")) { Text("另约 ${period(slot)}") }
                }
                if (checked.alternatives.isEmpty()) Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reply_copilot_screen_012))
                OutlinedButton(onClick = { generate(ReplyKind.CLARIFY) }, enabled = !busy, modifier = Modifier.testTag("reply-clarify")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reply_copilot_screen_013)) }
            }
            if (reply.isNotBlank()) {
                OutlinedTextField(reply, { reply = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reply_copilot_screen_014)) }, minLines = 4, modifier = Modifier.fillMaxWidth().testTag("reply-draft"))
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reply_copilot_screen_015))
                Button(onClick = { clipboard.setText(AnnotatedString(reply)); notice = "已复制；实际发送未确认" }, modifier = Modifier.testTag("reply-copy")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reply_copilot_screen_016)) }
            }
            draft?.takeIf { it.slot != null }?.let { current ->
                Text("待保存安排：${current.title} · ${period(current.slot!!)}")
                Row { Checkbox(mine, { mine = it }, enabled = !busy && !saved, modifier = Modifier.testTag("reply-mine")); Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reply_copilot_screen_017)) }
                Button(onClick = { model.perform {
                    saved = ReplyCopilot.save(model.repository, current, mine)
                    notice = if (saved) "安排已保存；回复未发送" else "此安排已处理，不会重复创建；回复未发送"
                } }, enabled = !busy && mine && !saved, modifier = Modifier.testTag("reply-save")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reply_copilot_screen_018)) }
                if (saved) TextButton(onClick = { model.perform { model.repository.undoLastAction(current.requestId); saved = false; mine = false; notice = "已撤销安排；复制过的文字不会撤回" } }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_reply_copilot_screen_019)) }
            }
            notice?.let { Text(it) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
