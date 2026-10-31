package com.timeagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.timeagent.data.NoteEntity
import com.timeagent.history.*
import java.time.*
import com.timeagent.core.WeekState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(model: TimeViewModel, onClose: () -> Unit, onOpen: (String, String) -> Unit) {
    val data by model.data.collectAsState()
    val busy by model.busy.collectAsState()
    val answer by model.historyAnswer.collectAsState()
    val today = model.repository.now().atZone(model.zone).toLocalDate()
    var from by rememberSaveable { mutableStateOf(answer?.filter?.from?.toString() ?: today.minusDays(6).toString()) }
    var through by rememberSaveable { mutableStateOf(answer?.filter?.through?.toString() ?: today.toString()) }
    var keyword by rememberSaveable { mutableStateOf(answer?.filter?.keyword.orEmpty()) }
    var question by rememberSaveable { mutableStateOf("") }
    var domain by remember { mutableStateOf(answer?.filter?.domain ?: HistoryDomain.ALL) }
    var taskId by remember { mutableStateOf(answer?.filter?.taskId) }
    var activityId by remember { mutableStateOf(answer?.filter?.activityId) }
    var choose by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<NoteEntity?>(null) }
    BackHandler { if (!busy) onClose() }
    Scaffold(topBar = { TopAppBar(title = { Text("历史问答 · 本地查询") }, navigationIcon = { IconButton(onClick = onClose, enabled = !busy) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("只查询本机的实际记录、已完成训练和笔记；结果与记忆不会作为后续云端对话的上下文。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(question, { question = it }, label = { Text("直接问历史，或用下方筛选") }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("history-question"))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { from = ""; through = ""; question = "" }, enabled = !busy) { Text("全部历史") }
                TextButton(onClick = { from = today.minusDays(6).toString(); through = today.toString(); question = "" }, enabled = !busy) { Text("近 7 天") }
                TextButton(onClick = { val monday = WeekState.startOfWeek(today); from = monday.minusWeeks(1).toString(); through = monday.minusDays(1).toString(); question = "" }, enabled = !busy) { Text("上周") }
            }
            OutlinedTextField(from, { from = it }, label = { Text("起始日期 yyyy-MM-dd，空表示不限") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("history-from"))
            OutlinedTextField(through, { through = it }, label = { Text("结束日期，含当天；空表示截至当前") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("history-through"))
            OutlinedTextField(keyword, { keyword = it }, label = { Text("关键词：标题、活动、备注、笔记") }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("history-keyword"))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { HistoryDomain.entries.forEach { value -> FilterChip(domain == value, { domain = value }, label = { Text(when(value) { HistoryDomain.ALL -> "全部"; HistoryDomain.RECORDS -> "实际"; HistoryDomain.WORKOUTS -> "训练"; HistoryDomain.NOTES -> "笔记" }) }, enabled = !busy) } }
            Row { TextButton(onClick = { choose = "task" }, enabled = !busy) { Text(data.tasks.find { it.id == taskId }?.title ?: "选择任务（可选）") }; TextButton(onClick = { choose = "activity" }, enabled = !busy) { Text(data.activities.find { it.id == activityId }?.name ?: "选择活动（可选）") } }
            Button(onClick = {
                try {
                    val filter = if (question.isNotBlank()) requireNotNull(HistoryQuestionParser.parse(question, model.repository.now(), model.zone)) { "请询问历史实际记录、训练次数或笔记；也可直接使用日期和关键词筛选。" }
                        else HistoryFilter(from.takeIf(String::isNotBlank)?.let(LocalDate::parse), through.takeIf(String::isNotBlank)?.let(LocalDate::parse), keyword.trim(), taskId, activityId, domain)
                    require(filter.from == null || filter.through == null || filter.from <= filter.through) { "起始日期不能晚于结束日期" }
                    model.queryHistory(filter, question.takeIf(String::isNotBlank))
                } catch (failure: Exception) { model.report(failure.message ?: "请检查日期和筛选条件") }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("history-search")) { Text(if (busy) "查询中…" else "查询本机记录") }
            answer?.let { result ->
                HorizontalDivider()
                Text("查询结果", style = MaterialTheme.typography.titleLarge)
                Text(result.text, Modifier.testTag("history-answer"))
                result.lastAt?.let { Text("最近匹配记录：${Instant.ofEpochMilli(it).label(model.zone)}", style = MaterialTheme.typography.bodySmall) }
                Text("实际 ${result.records.size} 条 · 已完成训练 ${result.workouts.size} 次 · 笔记 ${result.notes.size} 条；每类展示前 50 条，统计覆盖全部匹配项。", style = MaterialTheme.typography.bodySmall)
                result.records.sortedByDescending { it.startAt }.take(50).forEach { item -> TextButton(onClick = { onOpen("RECORD", item.id) }, modifier = Modifier.testTag("history-record-${item.id}")) { Text("${Instant.ofEpochMilli(item.startAt).label(model.zone)} · ${item.title.ifBlank { data.tasks.find { task -> task.id == item.taskId }?.title ?: "实际记录" }}") } }
                result.workouts.sortedByDescending { it.startedAt }.take(50).forEach { item -> TextButton(onClick = { onOpen("WORKOUT", item.id) }) { Text("${Instant.ofEpochMilli(item.startedAt).label(model.zone)} · ${item.title}") } }
                result.notes.sortedByDescending { it.updatedAt }.take(50).forEach { item -> TextButton(onClick = { note = item }) { Text("笔记 · ${item.title.ifBlank { item.body.take(30) }}") } }
            }
        }
    }
    choose?.let { kind -> AlertDialog(onDismissRequest = { choose = null }, title = { Text(if (kind == "task") "筛选任务（含历史）" else "筛选活动") }, text = {
        Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
            TextButton(onClick = { if (kind == "task") taskId = null else activityId = null; choose = null }) { Text("不限") }
            if (kind == "task") data.tasks.forEach { task -> TextButton(onClick = { taskId = task.id; choose = null }) { Text(task.title + if (task.deletedAt != null) "（已删除的历史任务）" else "") } }
            else data.activities.forEach { item -> TextButton(onClick = { activityId = item.id; choose = null }) { Text(item.name) } }
        }
    }, confirmButton = { TextButton(onClick = { choose = null }) { Text("关闭") } }) }
    note?.let { item -> AlertDialog(onDismissRequest = { note = null }, title = { Text(item.title.ifBlank { "相关笔记" }) }, text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) { Text(item.body) } }, confirmButton = { TextButton(onClick = { note = null; onOpen(item.targetType, item.targetId) }) { Text("打开所属事项") } }, dismissButton = { TextButton(onClick = { note = null }) { Text("关闭") } }) }
}
