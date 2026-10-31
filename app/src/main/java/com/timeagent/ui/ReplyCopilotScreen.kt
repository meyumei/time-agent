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
import com.timeagent.extraction.*
import com.timeagent.reply.*
import java.time.*
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplyCopilotScreen(model: TimeViewModel, initialText: String, onClose: () -> Unit) {
    val busy by model.busy.collectAsState()
    val error by model.error.collectAsState()
    val clipboard = LocalClipboardManager.current
    var source by remember { mutableStateOf(initialText) }
    var preview by remember { mutableStateOf<ExtractionPreview?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var day by remember { mutableStateOf(model.repository.now().atZone(model.zone).toLocalDate().toString()) }
    var from by remember { mutableStateOf("08:00") }
    var until by remember { mutableStateOf("22:00") }
    var minutes by remember { mutableStateOf("30") }
    var check by remember { mutableStateOf<ReplyAvailability?>(null) }
    var draft by remember { mutableStateOf<ReplyDraft?>(null) }
    var reply by remember { mutableStateOf("") }
    var mine by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    val formatter = remember { DateTimeFormatter.ofPattern("M月d日 HH:mm") }
    fun period(slot: com.timeagent.core.TimeSpan) = "${formatter.format(slot.start.atZone(model.zone))}–${slot.end.atZone(model.zone).toLocalTime()}"
    fun invalidate() { check = null; draft = null; mine = false; saved = false }
    fun generate(kind: ReplyKind, index: Int = 0) {
        runCatching { ReplyCopilot.draft(requireNotNull(check), kind, index, model.zone) }
            .onSuccess { draft = it; reply = it.text; mine = false; saved = false; notice = "已生成草稿；尚未保存安排、复制或发送" }
            .onFailure { notice = it.message }
    }
    BackHandler(enabled = !busy, onBack = onClose)
    Scaffold(topBar = { TopAppBar(title = { Text("回复副驾") }, navigationIcon = { TextButton(onClick = onClose, enabled = !busy) { Text("返回") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("文字在本机处理。空档仅依据本应用的计划、课程、实际记录和个人约定；外部日历未同步。")
            OutlinedTextField(source, { source = it; preview = null; selected = null; invalidate() }, label = { Text("粘贴或分享的原文") }, minLines = 3,
                modifier = Modifier.fillMaxWidth().testTag("reply-source"), enabled = !busy)
            Button(onClick = {
                runCatching { ChatTextExtractor.extract(source, model.repository.now(), model.zone) }.onSuccess {
                    preview = it; selected = it.items.firstOrNull()?.id; invalidate(); notice = if (it.items.isEmpty()) "没有明确候选，可先确认具体时间" else null
                    it.items.firstOrNull()?.startAt?.let { start -> day = Instant.ofEpochMilli(start).atZone(model.zone).toLocalDate().toString() }
                }.onFailure { notice = it.message }
            }, enabled = !busy && source.isNotBlank() && source.length <= 20000, modifier = Modifier.testTag("reply-extract")) { Text("提取候选") }
            preview?.let { extracted ->
                extracted.items.forEach { item ->
                    Row { RadioButton(selected == item.id, onClick = { selected = item.id; invalidate() }); TextButton(onClick = { selected = item.id; invalidate() }) { Text(item.title) } }
                }
                if (selected != null) Text(extracted.items.firstOrNull { it.id == selected }?.original.orEmpty(), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(day, { day = it; invalidate() }, label = { Text("查询日期 yyyy-MM-dd") }, modifier = Modifier.fillMaxWidth().testTag("reply-day"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(from, { from = it; invalidate() }, label = { Text("从 HH:mm") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(until, { until = it; invalidate() }, label = { Text("至 HH:mm") }, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(minutes, { minutes = it; invalidate() }, label = { Text("另约时长（分钟，请核对）") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { model.perform {
                    check = ReplyCopilot.check(source, selected, LocalDate.parse(day.trim()), minutes.toInt(), LocalTime.parse(from.trim()), LocalTime.parse(until.trim()),
                        model.repository.snapshot(), model.repository.now(), model.zone)
                    draft = null; mine = false; saved = false; notice = "已核对；生成草稿不会写入日程"
                } }, enabled = !busy, modifier = Modifier.testTag("reply-check")) { Text("核对空档") }
            }
            check?.let { checked ->
                if (checked.runningTimer) Text("存在尚未结束的计时，其后的查询时段暂按占用；结束计时后可重新核对。")
                checked.accepted?.let { slot -> Button(onClick = { generate(ReplyKind.ACCEPT) }, enabled = !busy, modifier = Modifier.testTag("reply-accept")) { Text("接受原时段 ${period(slot)}") } }
                    ?: Text("原文无明确起止时间，或原时段不空闲，不能直接接受。")
                checked.alternatives.forEachIndexed { index, slot ->
                    OutlinedButton(onClick = { generate(ReplyKind.ALTERNATE, index) }, enabled = !busy, modifier = Modifier.testTag("reply-alternate-$index")) { Text("另约 ${period(slot)}") }
                }
                if (checked.alternatives.isEmpty()) Text("当前查询范围没有可推荐空档，可换日期或先确认。")
                OutlinedButton(onClick = { generate(ReplyKind.CLARIFY) }, enabled = !busy, modifier = Modifier.testTag("reply-clarify")) { Text("先确认具体时间") }
            }
            if (reply.isNotBlank()) {
                OutlinedTextField(reply, { reply = it }, label = { Text("可编辑回复草稿") }, minLines = 4, modifier = Modifier.fillMaxWidth().testTag("reply-draft"))
                Text("编辑回复不会改变下方安排；复制后请在聊天应用自行核对并发送。")
                Button(onClick = { clipboard.setText(AnnotatedString(reply)); notice = "已复制；实际发送未确认" }, modifier = Modifier.testTag("reply-copy")) { Text("复制回复") }
            }
            draft?.takeIf { it.slot != null }?.let { current ->
                Text("待保存安排：${current.title} · ${period(current.slot!!)}")
                Row { Checkbox(mine, { mine = it }, enabled = !busy && !saved, modifier = Modifier.testTag("reply-mine")); Text("这是我的安排") }
                Button(onClick = { model.perform {
                    saved = ReplyCopilot.save(model.repository, current, mine)
                    notice = if (saved) "安排已保存；回复未发送" else "此安排已处理，不会重复创建；回复未发送"
                } }, enabled = !busy && mine && !saved, modifier = Modifier.testTag("reply-save")) { Text("单独保存这个安排") }
                if (saved) TextButton(onClick = { model.perform { model.repository.undoLastAction(current.requestId); saved = false; mine = false; notice = "已撤销安排；复制过的文字不会撤回" } }, enabled = !busy) { Text("撤销此次安排") }
            }
            notice?.let { Text(it) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
