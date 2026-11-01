package com.timeagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.timeagent.core.TimeSpan
import com.timeagent.core.TimeInput
import com.timeagent.core.TimelineSelection
import com.timeagent.data.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class EditorKind(val title: String) { TASK("任务"), PLAN("计划"), RECORD("实际记录"), COURSE("课程实例"), REMINDER("提醒") }
data class EditorRequest(
    val kind: EditorKind,
    val entity: Any? = null,
    val taskId: String? = null,
    val id: String = UUID.randomUUID().toString(),
    val activityId: String? = null,
    val selection: TimelineSelection? = null,
    val scheduleNewTask: Boolean = false,
    /** A plan editor opened from a task/selection must stay a time range, not the new reminder default. */
    val scheduleRange: Boolean = false,
    val ideaId: String? = null,
    val ideaVersion: Long? = null,
    val title: String? = null,
)
data class EditValues(val title: String, val notes: String, val estimatedMinutes: Int, val priority: Int, val dueAt: Long?, val taskId: String?, val categoryId: String?, val start: Long, val end: Long, val allowFuture: Boolean, val location: String, val teacher: String, val activityId: String? = null, val kind: EditorKind? = null, val scheduleNewTask: Boolean = false, val allDayStartDate: String? = null, val allDayEndDate: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun EditorScreen(
    request: EditorRequest,
    data: LocalSnapshot,
    now: Instant,
    zone: ZoneId,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (EditValues) -> Unit,
    onSaveReminder: (ReminderEntity?, String, Long) -> Unit = { _, _, _ -> },
    saveError: String? = null,
) {
    if (request.selection != null && request.entity == null) {
        GridBlockEditorScreen(request, data, now, zone, busy, saveError, onDismiss, onSave)
        return
    }
    val task = request.entity as? TaskEntity
    val plan = request.entity as? ScheduleBlockEntity
    val schedulePoint = plan?.kind == "POINT"
    val scheduleAllDay = plan?.kind == "ALL_DAY"
    val record = request.entity as? TimeRecordEntity
    val course = request.entity as? CourseOccurrenceEntity
    val reminder = request.entity as? ReminderEntity
    val activity = data.activities.firstOrNull { it.id == (task?.activityId ?: request.activityId) }
    val selectedSpan = request.selection?.span(zone)
    val hasTime = request.kind != EditorKind.TASK || request.scheduleNewTask
    val newPlan = request.kind == EditorKind.PLAN && request.entity == null && request.selection == null && !request.scheduleRange
    val showModeSwitch = newPlan
    var pointMode by rememberSaveable(request.id) { mutableStateOf(request.kind == EditorKind.REMINDER || newPlan) }
    val initialStart = plan?.startAt ?: record?.startAt ?: course?.startAt ?: reminder?.remindAt
        ?: selectedSpan?.start?.toEpochMilli()
        ?: if (newPlan || request.kind == EditorKind.REMINDER) now.plusSeconds(3600).toEpochMilli()
        else now.minusSeconds(if (request.kind == EditorKind.RECORD) 1800 else 0).toEpochMilli()
    val initialEnd = plan?.endAt ?: record?.endAt ?: course?.endAt ?: reminder?.remindAt?.plus(1_800_000L)
        ?: selectedSpan?.end?.toEpochMilli() ?: if (request.kind == EditorKind.RECORD) now.toEpochMilli() else now.plusSeconds(1800).toEpochMilli()
    val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    val initialTitle = plan?.displayTitle(data.tasks.firstOrNull { it.id == plan.taskId }) ?: record?.title?.takeIf(String::isNotBlank) ?: task?.title ?: activity?.fullName(data.activities).orEmpty().takeIf { it.isNotBlank() }
        ?: reminder?.title ?: request.title.orEmpty()
    var title by rememberSaveable(request.id) { mutableStateOf(initialTitle) }
    var notes by rememberSaveable(request.id) { mutableStateOf(plan?.notes ?: task?.description ?: record?.notes.orEmpty()) }
    var estimated by rememberSaveable(request.id) { mutableStateOf((task?.estimatedMinutes ?: activity?.defaultMinutes ?: if (request.scheduleNewTask) ((initialEnd - initialStart) / 60000).toInt() else 30).toString()) }
    var priority by rememberSaveable(request.id) { mutableIntStateOf(task?.priority ?: 1) }
    var dueDate by rememberSaveable(request.id) { mutableStateOf(task?.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() }.orEmpty()) }
    var dueTime by rememberSaveable(request.id) { mutableStateOf(task?.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).format(timeFormat) } ?: "23:59") }
    var date by rememberSaveable(request.id) { mutableStateOf(plan?.allDayStartDate ?: Instant.ofEpochMilli(initialStart).atZone(zone).toLocalDate().toString()) }
    var endDate by rememberSaveable(request.id) { mutableStateOf(plan?.allDayEndDate ?: Instant.ofEpochMilli(initialEnd).atZone(zone).toLocalDate().toString()) }
    var startTime by rememberSaveable(request.id) { mutableStateOf(Instant.ofEpochMilli(initialStart).atZone(zone).format(timeFormat)) }
    var endTime by rememberSaveable(request.id) { mutableStateOf(Instant.ofEpochMilli(initialEnd).atZone(zone).format(timeFormat)) }
    var selectedTask by rememberSaveable(request.id) { mutableStateOf(plan?.taskId ?: record?.taskId ?: request.taskId) }
    var category by rememberSaveable(request.id) { mutableStateOf(task?.categoryId ?: record?.categoryId ?: activity?.categoryId) }
    var activityId by rememberSaveable(request.id) { mutableStateOf(task?.activityId ?: request.activityId) }
    var location by rememberSaveable(request.id) { mutableStateOf(course?.locationOverride ?: data.courses.firstOrNull { it.id == course?.courseId }?.location.orEmpty()) }
    var teacher by rememberSaveable(request.id) { mutableStateOf(course?.teacherOverride ?: data.courses.firstOrNull { it.id == course?.courseId }?.teacher.orEmpty()) }
    var futureConfirmed by rememberSaveable(request.id) { mutableStateOf(false) }
    var validation by remember { mutableStateOf<String?>(null) }
    fun point(day: String, time: String, original: Long? = null) = TimeInput.parse(day, time, zone, original?.let(Instant::ofEpochMilli)).toEpochMilli()
    val originalStart = initialStart.takeIf { request.entity != null }
    val originalEnd = initialEnd.takeIf { request.entity != null }
    val candidate = if (pointMode || schedulePoint) null else runCatching {
        if (scheduleAllDay) TimeSpan(LocalDate.parse(date).atStartOfDay(ZoneId.of(plan!!.timezoneId)).toInstant(), LocalDate.parse(endDate).plusDays(1).atStartOfDay(ZoneId.of(plan.timezoneId)).toInstant())
        else TimeSpan(Instant.ofEpochMilli(point(date, startTime, originalStart)), Instant.ofEpochMilli(point(endDate, endTime, originalEnd)))
    }.getOrNull()
    val entityId = plan?.id ?: record?.id ?: course?.id
    val overlaps = candidate?.let { span -> data.timeline(now).filter { it.id != entityId && !it.isPoint && (if (request.kind == EditorKind.RECORD) it.kind == ItemKind.RECORD else it.kind != ItemKind.RECORD) && span.overlaps(it.span) } }.orEmpty()
    val future = request.kind == EditorKind.RECORD && candidate?.end?.isAfter(now) == true
    val save: () -> Unit = {
        try {
            val start = if (!hasTime) 0 else point(date, startTime, originalStart)
            val end = if (!hasTime) 0 else if (schedulePoint) start else point(endDate, endTime, originalEnd)
            if (hasTime && !schedulePoint && !scheduleAllDay) TimeSpan(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end))
            if (scheduleAllDay) require(LocalDate.parse(endDate) >= LocalDate.parse(date)) { "结束日期早于开始日期" }
            if (request.kind == EditorKind.PLAN) require(title.isNotBlank() || selectedTask != null) { "日程名称不能为空" }
            if (request.kind == EditorKind.TASK) require(title.isNotBlank()) { "任务名称不能为空" }
            onSave(EditValues(title.trim(), notes, if (request.kind == EditorKind.TASK) estimated.toIntOrNull() ?: error("预计耗时须为整数") else 30, priority, if (dueDate.isBlank()) null else point(dueDate, dueTime, task?.dueAt), selectedTask, category, start, end, futureConfirmed, location, teacher, activityId,
                allDayStartDate = if (scheduleAllDay) date else null, allDayEndDate = if (scheduleAllDay) endDate else null))
        } catch (e: Exception) { validation = e.message?.takeIf { it.contains("必须") || it.contains("请选择") || it.contains("名称") || it.contains("整数") } ?: "请检查日期、时间和时长格式" }
    }
    val savePoint: () -> Unit = {
        try {
            require(title.isNotBlank()) { "请填写提醒内容" }
            val at = point(date, startTime, reminder?.remindAt)
            require(at > now.toEpochMilli() || (reminder != null && at == reminder.remindAt)) { "提醒时间已经过去，请选择未来时间" }
            onSaveReminder(reminder, title.trim(), at)
        } catch (e: Exception) {
            validation = e.message?.takeIf { it.contains("提醒") || it.contains("内容") } ?: "请检查提醒日期和时间格式"
        }
    }
    BackHandler { if (!busy) onDismiss() }
    val pointScreen = pointMode
    val titleText = when {
        request.kind == EditorKind.REMINDER -> if (reminder == null) "新建提醒" else "编辑提醒"
        pointScreen -> "新建提醒"
        else -> (if (request.entity == null) "新建" else "编辑") + request.kind.title + if (request.scheduleNewTask) "并安排" else ""
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(titleText, fontWeight = FontWeight.Bold) }, navigationIcon = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } }, actions = {
            TextButton(
                onClick = if (pointScreen) savePoint else save,
                enabled = !busy && (pointScreen || !future || futureConfirmed),
                modifier = if (pointScreen) Modifier.testTag("reminder-save") else Modifier,
            ) { Text(if (busy) "保存中…" else if (!pointScreen && overlaps.isNotEmpty() && request.kind == EditorKind.PLAN) "仍然保存" else "保存") }
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showModeSwitch) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(pointMode, { pointMode = true }, label = { Text("单时点") }, modifier = Modifier.testTag("plan-mode-reminder"))
                    FilterChip(!pointMode, { pointMode = false }, label = { Text("时段") }, modifier = Modifier.testTag("plan-mode-range"))
                }
                Text(
                    if (pointMode) "到点发一次提醒，不占用日历时间。" else "占用日历时间，可关联任务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (pointMode) {
                OutlinedTextField(title, { title = it }, label = { Text("提醒内容") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("reminder-title"))
                Text("时间以 ${zone.id} 显示", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(date, { date = it }, label = { Text("提醒日期 YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(startTime, { startTime = it }, label = { Text("提醒时间") }, placeholder = { Text("HH:mm") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("只发送一次系统通知；声音、锁屏显示和省电策略由系统设置决定。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (reminder?.deliveredAt != null) Text("这条提醒已经向系统提交过通知。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (!pointMode) {
                if (request.kind == EditorKind.PLAN) {
                    OutlinedTextField(title, { title = it }, label = { Text("日程名称") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("plan-title"))
                    OutlinedTextField(notes, { notes = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth())
                }
                if (request.kind == EditorKind.TASK) {
                    if (activityId != null) {
                        Text("活动：${data.activities.firstOrNull { it.id == activityId }?.fullName(data.activities).orEmpty()}", style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { activityId = null }) { Text("取消活动关联") }
                    }
                    OutlinedTextField(title, { title = it }, label = { Text("任务名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(estimated, { estimated = it }, label = { Text("预计耗时（分钟）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("低", "中", "高").forEachIndexed { i, name -> FilterChip(selected = priority == i, onClick = { priority = i }, label = { Text("${name}优先级") }) } }
                    OutlinedTextField(dueDate, { dueDate = it }, label = { Text("截止日期（可选）") }, placeholder = { Text("YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (dueDate.isNotBlank()) OutlinedTextField(dueTime, { dueTime = it }, label = { Text("截止时间 HH:mm") }, singleLine = true)
                }
                if (hasTime) {
                    Text("时间以 ${zone.id} 显示", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(date, { date = it }, label = { Text("开始日期 YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (!scheduleAllDay) OutlinedTextField(startTime, { startTime = it }, label = { Text("开始时间 HH:mm") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (!schedulePoint) OutlinedTextField(endDate, { endDate = it }, label = { Text("结束日期 YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (!schedulePoint && !scheduleAllDay) OutlinedTextField(endTime, { endTime = it }, label = { Text("结束时间 HH:mm") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (request.kind == EditorKind.PLAN || request.kind == EditorKind.RECORD) {
                        Text("关联任务（可选）", style = MaterialTheme.typography.labelLarge)
                        Column {
                            FilterChip(selected = selectedTask == null, onClick = { selectedTask = null }, label = { Text("不关联任务") })
                            if (request.kind == EditorKind.RECORD) data.tasks.firstOrNull { it.id == record?.taskId && (it.deletedAt != null || it.status == "CANCELLED") }?.let { historical ->
                                FilterChip(selected = selectedTask == historical.id, onClick = { selectedTask = historical.id }, label = { Text("${historical.title}（历史任务）") })
                            }
                            data.tasks.filter { it.deletedAt == null && it.status != "CANCELLED" }.forEach { item -> FilterChip(selected = selectedTask == item.id, onClick = { selectedTask = item.id }, label = { Text(item.title) }) }
                        }
                    }
                    if (request.kind == EditorKind.COURSE) {
                        OutlinedTextField(location, { location = it }, label = { Text("教室") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(teacher, { teacher = it }, label = { Text("教师") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("只修改这一次课程，重新导入会保留本次修改。", style = MaterialTheme.typography.bodySmall)
                    }
                    if (overlaps.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                        Text((if (request.kind == EditorKind.RECORD) "与已有实际记录重叠，仍可保存：" else "时间冲突，仍可保存：") + overlaps.take(4).joinToString("、") { it.title }, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    if (future) {
                        Text("这条记录包含未来时间；如尚未发生，建议改建计划。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Row { Checkbox(futureConfirmed, { futureConfirmed = it }); Text("我确认仍作为实际记录保存", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (request.kind == EditorKind.TASK || request.kind == EditorKind.RECORD) {
                    if (request.kind == EditorKind.RECORD) OutlinedTextField(title, { title = it }, label = { Text("活动名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("分类", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = category == null, onClick = { category = null }, label = { Text("未分类") })
                        data.categories.forEach { item -> FilterChip(selected = category == item.id, onClick = { category = item.id }, label = { Text(item.name) }) }
                    }
                    OutlinedTextField(notes, { notes = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                }
            }
            validation?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
