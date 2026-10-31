package com.timeagent.imports

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.timeagent.core.ImportTerm
import com.timeagent.data.ImportPreview
import com.timeagent.ui.TimeViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Standalone entry point; all writes still pass through the existing repository preview/transaction. */
@Composable
fun CourseImportScreen(model: TimeViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<CourseImportDraft?>(null) }
    var official by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var requestId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var termId by rememberSaveable { mutableStateOf("") }
    var termName by rememberSaveable { mutableStateOf("导入学期") }
    var monday by rememberSaveable { mutableStateOf("") }
    var teachingWeeks by rememberSaveable { mutableStateOf("") }
    var zone by rememberSaveable { mutableStateOf("Asia/Shanghai") }
    var timings by rememberSaveable { mutableStateOf(ScutImportPresets.WUSHAN_PERIODS) }
    var reviewed by rememberSaveable { mutableStateOf(false) }

    fun invalidatePreview() {
        preview = null
        requestId = UUID.randomUUID().toString()
        reviewed = false
        error = null
        result = null
    }
    fun dismissPreview() { preview = null; error = null }
    fun accept(value: CourseImportDraft) {
        draft = value
        termId = value.termIdHint
        termName = value.termNameHint
        monday = ScutImportPresets.firstMonday(value.termIdHint)?.toString().orEmpty()
        teachingWeeks = value.suggestedTeachingWeeks.toString()
        invalidatePreview()
    }
    fun edit(value: CourseImportDraft) { draft = value; invalidatePreview() }
    fun work(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { action() }
            catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "导入失败，修改内容仍保留，请重试。" }
            finally { busy = false }
        }
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work {
            val parsed = withContext(Dispatchers.IO) { ScutPdfTimetableParser.parse(PdfTimetableExtractor.extract(context, uri)) }
            accept(parsed)
        }
    }
    BackHandler(enabled = !official) { if (!busy) onClose() }
    if (official) {
        OfficialSchoolImport(onExtracted = { accept(it); official = false }, onClose = { official = false })
        return
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("导入学校课表", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClose, enabled = !busy) { Text("关闭") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (preview == null) error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        result?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp)) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize().testTag("course-import-list")) {
            item {
                Text("先选择来源，再核对学期、节次时间和课程。确认预览后才保存。", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { pdfPicker.launch(arrayOf("application/pdf")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("选择课表 PDF") }
                OutlinedButton(onClick = { official = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("华工官方教务导入（需登录）") }
                TextButton(onClick = { accept(CourseImportDraft(listOf(EditableCourseRow()))) }, enabled = !busy) { Text("手工补录") }
            }
            val current = draft
            if (current != null) {
                current.warnings.forEach { warning -> item { Text(warning, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall) } }
                item {
                    Text("学期与节次", style = MaterialTheme.typography.titleMedium)
                    Text("本次所有课程统一使用你选择的五山作息，可在下方修改。第一教学周和每节时间仍须核对确认。", style = MaterialTheme.typography.bodySmall)
                    if (current.termIdHint == "2026-2027-1") Text("2026–2027 学年第 1 学期按学校开课新闻预填 2026-08-31，仍可修改。", style = MaterialTheme.typography.bodySmall)
                    ImportField("学期标识（同一学期保持一致）", termId, busy) { termId = it; invalidatePreview() }
                    ImportField("学期名称", termName, busy) { termName = it; invalidatePreview() }
                    ImportField("第一教学周周一（YYYY-MM-DD）", monday, busy) { monday = it; invalidatePreview() }
                    ImportField("学期教学周数", teachingWeeks, busy) { teachingWeeks = it; invalidatePreview() }
                    ImportField("时区", zone, busy) { zone = it; invalidatePreview() }
                    OutlinedButton(onClick = { timings = ScutImportPresets.WUSHAN_PERIODS; invalidatePreview() }, enabled = !busy) { Text("使用五山作息") }
                    Text("第 1–10 节按你的截图；第 11 节参考学校公开作息索引 20:50–21:35，请核对后确认。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = timings, onValueChange = { timings = it; invalidatePreview() }, enabled = !busy,
                        label = { Text("学校节次时间（每行一节）") },
                        placeholder = { Text("格式示例，请替换为学校实际时间：\n1=08:00-08:45\n2=08:55-09:40") },
                        supportingText = { Text("课程用到的每一节都要填写；预填作息可以逐行修改。") },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp), minLines = 4,
                    )
                }
                item { Text("课程行（${current.rows.size} 行，可修正）", style = MaterialTheme.typography.titleMedium) }
                itemsIndexed(current.rows) { index, row ->
                    CourseRowEditor(index, row, busy,
                        onChange = { updated -> edit(current.copy(rows = current.rows.mapIndexed { i, existing -> if (i == index) updated else existing })) },
                        onDelete = { edit(current.copy(rows = current.rows.filterIndexed { i, _ -> i != index })) },
                    )
                }
                item {
                    OutlinedButton(onClick = { edit(current.copy(rows = current.rows + EditableCourseRow())) }, enabled = !busy) { Text("添加课程行") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Checkbox(checked = reviewed, onCheckedChange = { reviewed = it }, enabled = !busy)
                        Text("我已核对第一教学周、每节时间和课程行", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth(), enabled = !busy && current.rows.isNotEmpty(),
                        onClick = {
                            work {
                                require(reviewed) { "请先核对学期日期、节次时间和课程行，并勾选确认。" }
                                val firstMonday = runCatching { LocalDate.parse(monday.trim()) }.getOrElse { throw IllegalArgumentException("请按 YYYY-MM-DD 填写第一教学周周一。") }
                                val numberOfWeeks = teachingWeeks.trim().toIntOrNull() ?: throw IllegalArgumentException("教学周数须为 1–60 之间的整数。")
                                val timeZone = runCatching { ZoneId.of(zone.trim()) }.getOrElse { throw IllegalArgumentException("时区无效，例如 Asia/Shanghai。") }
                                val term = ImportTerm(termId.trim(), firstMonday, numberOfWeeks, timeZone, termName.trim())
                                val bundle = withContext(Dispatchers.Default) { current.normalize(term, TimetableFields.periodTimes(timings)) }
                                preview = model.repository.previewImport(bundle)
                                requestId = UUID.randomUUID().toString()
                            }
                        },
                    ) { Text(if (busy) "处理中…" else "生成导入预览") }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
    preview?.let { value ->
        AlertDialog(
            onDismissRequest = { if (!busy) dismissPreview() },
            title = { Text("课表导入预览") },
            text = {
                Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text("${value.bundle.courses.size} 门课程 · ${value.bundle.occurrences.size} 次上课")
                    Text("新增 ${value.newCount} 次，重复跳过 ${value.duplicateCount} 次；其中手工修改保护 ${value.protectedCount} 次。")
                    Text("第一教学周：${value.bundle.term.firstWeekStart}，时区：${value.bundle.term.zone.id}")
                    Text("核对前 ${minOf(12, value.bundle.occurrences.size)} 次上课：", style = MaterialTheme.typography.labelMedium)
                    val definitions = value.bundle.courses.associateBy { it.key }
                    value.bundle.occurrences.take(12).forEach { occurrence ->
                        val local = occurrence.span.start.atZone(value.bundle.term.zone)
                        val end = occurrence.span.end.atZone(value.bundle.term.zone)
                        Text("${local.format(DateTimeFormatter.ofPattern("MM-dd E HH:mm"))}–${end.format(DateTimeFormatter.ofPattern("HH:mm"))}\n${definitions[occurrence.courseKey]?.name.orEmpty()} · ${occurrence.locationOverride ?: definitions[occurrence.courseKey]?.location.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = { work {
                    val saved = model.repository.commitImport(value, requestId)
                    result = "已导入 ${saved.importedCount} 次上课，跳过 ${saved.skippedCount} 次重复课程。"
                    preview = null
                } }) { Text(if (busy) "正在导入…" else "确认导入") }
            },
            dismissButton = { TextButton(onClick = ::dismissPreview, enabled = !busy) { Text("返回修改") } },
        )
    }
}

@Composable
private fun ImportField(label: String, value: String, busy: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
}

@Composable
private fun CourseRowEditor(index: Int, row: EditableCourseRow, busy: Boolean, onChange: (EditableCourseRow) -> Unit, onDelete: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("第 ${index + 1} 行", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                TextButton(onClick = onDelete, enabled = !busy) { Text("删除此行") }
            }
            ImportField("课程名称", row.name, busy) { onChange(row.copy(name = it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(row.dayOfWeek, { onChange(row.copy(dayOfWeek = it)) }, label = { Text("星期 1–7") }, enabled = !busy, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(row.periods, { onChange(row.copy(periods = it)) }, label = { Text("节次，如 1-2") }, enabled = !busy, singleLine = true, modifier = Modifier.weight(1f))
            }
            ImportField("周次，如 1-16周(单),18周", row.weeks, busy) { onChange(row.copy(weeks = it)) }
            ImportField("教师", row.teacher, busy) { onChange(row.copy(teacher = it)) }
            ImportField("地点", row.location, busy) { onChange(row.copy(location = it)) }
            ImportField("校区（可选）", row.campus, busy) { onChange(row.copy(campus = it)) }
        }
    }
}
