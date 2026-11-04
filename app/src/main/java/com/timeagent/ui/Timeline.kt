package com.timeagent.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import com.timeagent.ui.timeline.TimelinePreference
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import com.timeagent.core.*
import com.timeagent.data.*
import com.timeagent.ui.timeline.TimelineDayCountSwitch
import com.timeagent.ui.timeline.TimelineDragKind
import com.timeagent.ui.timeline.TimelineDragResult
import com.timeagent.ui.timeline.TimelineFilterButton
import com.timeagent.ui.timeline.TimelineFilterState
import com.timeagent.ui.timeline.minuteDelta
import com.timeagent.ui.timeline.preview
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

enum class ItemKind(val label: String) { COURSE("课程"), PLAN("计划"), RECORD("实际") }
data class TimelineItem(val id: String, val kind: ItemKind, val title: String, val span: TimeSpan, val subtitle: String = "", val entity: Any, val colorKey: String = "", val readOnlyReason: String? = null)
val TimelineItem.dragReadOnlyReason: String? get() = readOnlyReason ?: when {
    kind == ItemKind.COURSE -> "课表来源不能拖动，请在课程详情中修改"
    (entity as? TimeRecordEntity)?.source == "HEALTH_SYNC" -> "健康同步记录不能拖动，请查看原始健康来源"
    (entity as? ScheduleBlockEntity)?.isLocked == true -> "此日程已锁定"
    (entity as? TimeRecordEntity)?.endAt == null && kind == ItemKind.RECORD -> "正在计时，结束后可调整"
    else -> null
}
val TimelineItem.isPoint: Boolean get() = (entity as? ScheduleBlockEntity)?.kind == "POINT"
internal data class ActiveTimelineDrag(
    val item: TimelineItem,
    val kind: TimelineDragKind,
    val deltaPixels: Offset,
    val result: TimelineDragResult,
    val scrollMinutes: Double = 0.0,
)
fun LocalSnapshot.timeline(now: Instant, filters: TimelineFilterState = TimelineFilterState()): List<TimelineItem> {
    val taskNames = tasks.associateBy { it.id }
    val courseNames = courses.associateBy { it.id }
    // TimeSpan requires positive duration; POINT uses a display anchor and never enters duration/conflict calculations.
    return plans.filter { filters.plan && it.deletedAt == null }.map { TimelineItem(it.id, ItemKind.PLAN, it.displayTitle(taskNames[it.taskId]), TimeSpan(Instant.ofEpochMilli(it.startAt), Instant.ofEpochMilli(if (it.kind == "POINT") it.startAt + 1 else it.endAt)), subtitle = it.notes, entity = it, colorKey = taskNames[it.taskId]?.timelineColorSubject() ?: "schedule:${it.id}", readOnlyReason = calendarReadOnlyReasons[it.id]) } +
        records.filter { (it.source == "HEALTH_SYNC" && filters.health) || (it.source != "HEALTH_SYNC" && filters.record) }.flatMap { record ->
            actualSpans(record, now).mapIndexed { index, span ->
                TimelineItem(if (record.source == "FOCUS") "${record.id}#segment:$index" else record.id,
                    ItemKind.RECORD, record.title.takeIf(String::isNotBlank) ?: taskNames[record.taskId]?.title ?: "自由记录",
                    span, if (record.endAt == null) "计时中" else record.notes, record,
                    colorKey = taskNames[record.taskId]?.timelineColorSubject().orEmpty())
            }
        } +
        occurrences.filter { filters.course && it.status != "CANCELLED" }.map {
            val definition = courseNames[it.courseId]
            val location = it.locationOverride ?: definition?.location.orEmpty()
            val teacher = it.teacherOverride ?: definition?.teacher.orEmpty()
            TimelineItem(it.id, ItemKind.COURSE, definition?.name ?: "课程", TimeSpan(Instant.ofEpochMilli(it.startAt), Instant.ofEpochMilli(it.endAt)),
                listOf(location, teacher.takeIf(String::isNotBlank)?.let { name -> "教师：$name" }.orEmpty()).filter(String::isNotBlank).joinToString("\n"), it)
        }
}
fun ItemKind.color(): Color = when (this) { ItemKind.COURSE -> CourseBlue; ItemKind.PLAN -> Indigo; ItemKind.RECORD -> RecordGreen }
private fun ItemKind.legendIcon() = when (this) {
    ItemKind.COURSE -> Icons.Rounded.School
    ItemKind.PLAN -> Icons.Rounded.EventNote
    ItemKind.RECORD -> Icons.Rounded.CheckCircleOutline
}
fun Instant.label(zone: ZoneId): String = atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
fun Long.minutesLabel(): String = if (this < 60) "${this} 分钟" else "${this / 60} 小时 ${this % 60} 分"

@Composable fun WeekHeader(week: WeekState, data: LocalSnapshot, onPrevious: () -> Unit, onNext: () -> Unit, onCurrent: () -> Unit) {
    val start = week.selectedWeekStart
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${start.monthValue}月${start.dayOfMonth}日 — ${start.plusDays(6).monthValue}月${start.plusDays(6).dayOfMonth}日", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${start.year} · 把每一段时间留给重要的事", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onCurrent) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_habit_screen_006)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            ItemKind.entries.forEach { kind ->
                Icon(kind.legendIcon(), null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(kind.label, Modifier.padding(start = 5.dp, end = 16.dp), style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onPrevious, Modifier.size(40.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "上一周", Modifier.size(18.dp)) }
            IconButton(onClick = onNext, Modifier.size(40.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "下一周", Modifier.size(18.dp)) }
        }
    }
}

private val ViewportSaver = listSaver<TimelineViewport, Double>(
    save = { listOf(it.firstDay, it.zoom, it.firstMinute, it.dayCount.toDouble(), if (it.overview) 1.0 else 0.0, it.selectedDay) },
    restore = { TimelineViewport(it[0], it[1].coerceIn(.5, 2.5), it[2], it.getOrNull(3)?.toInt() ?: 7, it.getOrNull(4) == 1.0,
        it.getOrNull(5) ?: (it[0] + (it.getOrNull(3)?.toInt() ?: 7) / 2)) },
)
internal val DayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

@Composable fun TimelineScreen(
    data: LocalSnapshot,
    week: WeekState,
    now: Instant,
    zone: ZoneId,
    onSelect: (TimelineItem) -> Unit,
    onWeek: (Long) -> Unit,
    onCreate: () -> Unit = {},
    onAssistant: () -> Unit = {},
    onUndo: () -> Unit = {},
    canUndo: Boolean = false,
    busy: Boolean = false,
    onVisibleDate: (LocalDate) -> Unit = {},
    navigationRevision: Long = 0L,
    navigationOwner: String = "",
    onCreateAt: (TimelineSelection) -> Unit = {},
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeMode: (ThemeMode) -> Unit = {},
    onRedo: () -> Unit = {},
    canRedo: Boolean = false,
    onReminder: (ReminderEntity) -> Unit = {},
    briefing: @Composable () -> Unit = {},
    onVisibleRange: (LocalDate, LocalDate) -> Unit = { _, _ -> },
    defaultDayCount: Int = 3,
    onDragCommit: (TimelineItem, TimelineDragResult) -> Unit = { _, _ -> },
    onSaveTemplate: ((List<ScheduleBlockEntity>) -> Unit)? = null,
    preferenceKey: String = "today",
) {
    val context = LocalContext.current
    val preference = remember(context, preferenceKey) { TimelinePreference(context, preferenceKey) }
    fun selectedDate(): LocalDate = if (week.selectedWeekStart == WeekState.startOfWeek(week.today)) week.today else week.selectedWeekStart
    fun initialViewport(): TimelineViewport = TimelineViewport.centered(selectedDate(), preference.dayCount(defaultDayCount)).copy(
        zoom = preference.density() / 64.0,
        firstMinute = if (selectedDate() == week.today) (now.atZone(zone).hour * 60 + now.atZone(zone).minute - 60).coerceAtLeast(0).toDouble() else 480.0,
    )
    var showConflicts by remember { mutableStateOf(false) }
    var selectingTemplate by rememberSaveable { mutableStateOf(false) }
    var selectedPlansJson by rememberSaveable { mutableStateOf("[]") }
    val selectedPlans = remember(selectedPlansJson) {
        com.google.gson.Gson().fromJson(selectedPlansJson, Array<ScheduleBlockEntity>::class.java).toList()
    }
    fun updateSelectedPlans(plans: List<ScheduleBlockEntity>) {
        selectedPlansJson = com.google.gson.Gson().toJson(plans)
    }
    val selectItem: (TimelineItem) -> Unit = { item ->
        if (selectingTemplate) {
            if (item.entity is ScheduleBlockEntity) updateSelectedPlans(
                if (selectedPlans.any { it.id == item.entity.id }) selectedPlans.filterNot { it.id == item.entity.id }
                else selectedPlans + item.entity)
        } else onSelect(item)
    }
    var conflictGroup by remember { mutableStateOf<List<TimelineItem>?>(null) }
    var listMode by rememberSaveable { mutableStateOf(preference.listMode()) }
    var snapMinutes by rememberSaveable { mutableIntStateOf(preference.snapMinutes()) }
    var viewport by rememberSaveable(stateSaver = ViewportSaver) { mutableStateOf(initialViewport()) }
    var filterState by rememberSaveable(stateSaver = listSaver<TimelineFilterState, Boolean>(
        save = { listOf(it.plan, it.record, it.course, it.health) },
        restore = { TimelineFilterState(it[0], it[1], it[2], it[3]) },
    )) { mutableStateOf(preference.filters()) }
    LaunchedEffect(viewport.dayCount, viewport.zoom, filterState, listMode) {
        preference.save(viewport.dayCount, viewport.vertical.hourHeightDp, filterState, listMode)
    }
    var requestedWeek by rememberSaveable { mutableLongStateOf(week.selectedWeekStart.toEpochDay()) }
    var appliedNavigationRevision by rememberSaveable { mutableLongStateOf(navigationRevision) }
    var navigationPending by remember { mutableStateOf(false) }
    var savedNavigationOwner by rememberSaveable { mutableStateOf(navigationOwner) }
    LaunchedEffect(week.selectedWeekStart, navigationRevision, navigationOwner) {
        if (savedNavigationOwner != navigationOwner) {
            savedNavigationOwner = navigationOwner
            requestedWeek = week.selectedWeekStart.toEpochDay()
            appliedNavigationRevision = navigationRevision
            return@LaunchedEffect
        }
        if (appliedNavigationRevision != navigationRevision) {
            appliedNavigationRevision = navigationRevision
            requestedWeek = week.selectedWeekStart.toEpochDay()
            navigationPending = false
            viewport = TimelineViewport.centered(selectedDate(), viewport.dayCount).copy(zoom = viewport.zoom, firstMinute = viewport.firstMinute)
        } else if (navigationPending) {
            if (requestedWeek == week.selectedWeekStart.toEpochDay()) navigationPending = false
        } else if (requestedWeek != week.selectedWeekStart.toEpochDay()) {
            requestedWeek = week.selectedWeekStart.toEpochDay()
            viewport = TimelineViewport.centered(selectedDate(), viewport.dayCount).copy(zoom = viewport.zoom, firstMinute = viewport.firstMinute)
        }
    }
    fun syncReviewWeek(firstDate: LocalDate) {
        val expected = LocalDate.ofEpochDay(requestedWeek)
        val delta = ChronoUnit.DAYS.between(WeekState.startOfWeek(expected), WeekState.startOfWeek(firstDate)) / 7
        if (delta != 0L) {
            requestedWeek = expected.plusWeeks(delta).toEpochDay()
            // The model acknowledgement must not snap our fractional viewport to Monday.
            navigationPending = true
            onWeek(delta)
        }
    }
    fun moveWeek(delta: Long) {
        val step = delta * if (viewport.dayCount == 7) 7 else 1
        viewport = viewport.shiftDays(step)
        syncReviewWeek(LocalDate.ofEpochDay(kotlin.math.floor(viewport.firstDay).toLong()))
    }
    val renderCache = remember { TimelineRenderCache() }
    val rendered = renderCache.window(data, now, viewport, zone, filterState)
    val dates = rendered.dates
    val reportDate by rememberUpdatedState(onVisibleDate)
    LaunchedEffect(dates.first()) { reportDate(dates.first()) }
    val reportRange by rememberUpdatedState(onVisibleRange)
    LaunchedEffect(dates.first(), dates.last()) { reportRange(dates.first(), dates.last()) }
    val items = rendered.items
    val conflicts = rendered.conflicts
    // Reminders depend on their source and time zone, never on pinch frames or date placement.
    val remindersByDate = remember(data.reminders, zone) {
        data.reminders.filter { it.enabled }.groupBy { Instant.ofEpochMilli(it.remindAt).atZone(zone).toLocalDate() }
    }
    Column(Modifier.fillMaxSize().background(timelineBackground())) {
        briefing()
        TimelineToolbar(
            data = data, dates = dates, today = week.today, zoom = viewport.zoom, listMode = listMode,
            busy = busy, canUndo = canUndo, onCreate = onCreate, onAssistant = onAssistant, onUndo = onUndo,
            onRedo = onRedo, canRedo = canRedo,
            onPrevious = { moveWeek(-1) }, onNext = { moveWeek(1) },
            onCurrent = {
                val current = week.today
                viewport = TimelineViewport.centered(current, viewport.dayCount).copy(zoom = viewport.zoom, firstMinute = (now.atZone(zone).hour * 60 - 60).coerceAtLeast(0).toDouble())
                syncReviewWeek(current)
            },
            onFit = { viewport = viewport.copy(overview = true, firstMinute = 0.0) },
            onDensity = { factor -> viewport = viewport.copy(zoom = (viewport.zoom * factor).coerceIn(.5, 2.5), overview = false) },
            dayCount = viewport.dayCount,
            onDayCount = { count -> viewport = TimelineViewport.withDayCount(viewport, count) },
            filterState = filterState,
            onFilterState = { filterState = it },
            onToggleList = { listMode = !listMode },
            themeMode = themeMode, onThemeMode = onThemeMode,
            conflicts = conflicts.size, onConflicts = { showConflicts = true },
            onSelectTemplate = onSaveTemplate?.let { { selectingTemplate = true; updateSelectedPlans(emptyList()) } },
            snapMinutes = snapMinutes,
            onSnapMinutes = { snapMinutes = it; preference.saveSnapMinutes(it) },
        )
        if (selectingTemplate) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("点选日程 · 已选 ${selectedPlans.size}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { selectingTemplate = false; updateSelectedPlans(emptyList()) }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) }
            TextButton(enabled = selectedPlans.isNotEmpty(), onClick = {
                onSaveTemplate?.invoke(selectedPlans)
                selectingTemplate = false
            }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_timeline_001)) }
        }
        if (selectingTemplate && selectedPlans.isNotEmpty()) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            selectedPlans.forEach { plan ->
                TextButton(onClick = { updateSelectedPlans(selectedPlans.filterNot { it.id == plan.id }) }) {
                    Text("已选 ${plan.displayTitle(data.tasks.firstOrNull { it.id == plan.taskId })} ×", maxLines = 1)
                }
            }
        }
        if (listMode) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (items.isEmpty()) EmptyHint("这几天，还留着很多可能", "创建一个任务，为它安排时间，或开始记录此刻。")
                items.groupBy { it.span.start.atZone(zone).toLocalDate() }.forEach { (date, entries) ->
                    Text(date.toString(), style = MaterialTheme.typography.titleSmall)
                    entries.forEach { item -> key(item.kind, item.id) {
                        TimelineAgendaCard(item, zone, item.layoutKey in rendered.conflictingKeys, onClick = { selectItem(item) })
                    } }
                }
            }
        } else {
            TimelineCanvas(
                rendered, viewport, week.today, now, zone, selectItem, { transform -> viewport = transform(viewport) },
                Modifier.weight(1f), if (busy) null else onCreateAt,
                resolve = rendered::currentItem, remindersByDate = remindersByDate, onReminder = onReminder,
                onConflictGroup = { conflictGroup = it }, allDayItems = items.filter(TimelineItem::isAllDay),
                onDragCommit = onDragCommit,
                snapMinutes = snapMinutes,
            )
        }
    }
    if (showConflicts) TimelineConflictsSheet(conflicts, zone, selectItem, onClose = { showConflicts = false })
    conflictGroup?.let { group ->
        TimelineConflictGroupSheet(group, zone, onSelect = selectItem, onClose = { conflictGroup = null })
    }
}

