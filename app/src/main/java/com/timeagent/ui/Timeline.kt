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
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

enum class ItemKind(val label: String) { COURSE("课程"), PLAN("计划"), RECORD("实际") }
data class TimelineItem(val id: String, val kind: ItemKind, val title: String, val span: TimeSpan, val subtitle: String = "", val entity: Any, val colorKey: String = "")
fun LocalSnapshot.timeline(now: Instant): List<TimelineItem> {
    val taskNames = tasks.associateBy { it.id }
    val courseNames = courses.associateBy { it.id }
    return plans.map { TimelineItem(it.id, ItemKind.PLAN, taskNames[it.taskId]?.title ?: "计划", TimeSpan(Instant.ofEpochMilli(it.startAt), Instant.ofEpochMilli(it.endAt)), entity = it, colorKey = taskNames[it.taskId]?.timelineColorSubject() ?: "task:${it.taskId}") } +
        records.map { TimelineItem(it.id, ItemKind.RECORD, it.title.takeIf(String::isNotBlank) ?: taskNames[it.taskId]?.title ?: it.notes.takeIf(String::isNotBlank)?.lineSequence()?.firstOrNull() ?: "自由记录", TimeSpan(Instant.ofEpochMilli(it.startAt), maxOf(Instant.ofEpochMilli(it.startAt).plusMillis(1), it.endAt?.let(Instant::ofEpochMilli) ?: now)), if (it.endAt == null) "计时中" else it.notes, it, colorKey = taskNames[it.taskId]?.timelineColorSubject().orEmpty()) } +
        occurrences.filter { it.status != "CANCELLED" }.map {
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
            TextButton(onClick = onCurrent) { Text("本周") }
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
    save = { listOf(it.firstDay, it.zoom, it.firstMinute) },
    restore = { TimelineViewport(it[0], it[1], it[2]) },
)
private val DayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

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
    onCreateAt: (TimelineSelection) -> Unit = {},
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeMode: (ThemeMode) -> Unit = {},
    onRedo: () -> Unit = {},
    canRedo: Boolean = false,
    onReminder: (ReminderEntity) -> Unit = {},
    briefing: @Composable () -> Unit = {},
) {
    var showConflicts by remember { mutableStateOf(false) }
    var conflictGroup by remember { mutableStateOf<List<TimelineItem>?>(null) }
    var listMode by rememberSaveable { mutableStateOf(false) }
    var viewport by rememberSaveable(stateSaver = ViewportSaver) { mutableStateOf(TimelineViewport.fit(week.selectedWeekStart)) }
    var requestedWeek by rememberSaveable { mutableLongStateOf(week.selectedWeekStart.toEpochDay()) }
    var appliedNavigationRevision by rememberSaveable { mutableLongStateOf(navigationRevision) }
    var navigationPending by remember { mutableStateOf(false) }
    LaunchedEffect(week.selectedWeekStart, navigationRevision) {
        if (appliedNavigationRevision != navigationRevision) {
            appliedNavigationRevision = navigationRevision
            requestedWeek = week.selectedWeekStart.toEpochDay()
            navigationPending = false
            viewport = TimelineViewport.fit(week.selectedWeekStart)
        } else if (navigationPending) {
            if (requestedWeek == week.selectedWeekStart.toEpochDay()) navigationPending = false
        } else if (requestedWeek != week.selectedWeekStart.toEpochDay()) {
            requestedWeek = week.selectedWeekStart.toEpochDay()
            viewport = TimelineViewport.fit(week.selectedWeekStart)
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
        viewport = viewport.shiftDays(delta * 7)
        syncReviewWeek(LocalDate.ofEpochDay(kotlin.math.floor(viewport.firstDay).toLong()))
    }
    val renderCache = remember { TimelineRenderCache() }
    val rendered = renderCache.window(data, now, viewport, zone)
    val dates = rendered.dates
    val reportDate by rememberUpdatedState(onVisibleDate)
    LaunchedEffect(dates.first()) { reportDate(dates.first()) }
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
                val current = WeekState.startOfWeek(week.today)
                viewport = TimelineViewport.fit(current)
                syncReviewWeek(current)
            },
            onFit = { viewport = TimelineViewport.fit(LocalDate.ofEpochDay(kotlin.math.floor(viewport.firstDay).toLong())) },
            onToggleList = { listMode = !listMode },
            themeMode = themeMode, onThemeMode = onThemeMode,
            conflicts = conflicts.size, onConflicts = { showConflicts = true },
        )
        if (listMode) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (items.isEmpty()) EmptyHint("这几天，还留着很多可能", "创建一个任务，为它安排时间，或开始记录此刻。")
                items.forEach { item -> key(item.kind, item.id) {
                    TimelineAgendaCard(item, zone, item.layoutKey in rendered.conflictingKeys, onClick = { onSelect(item) })
                } }
            }
        } else {
            val configuration = LocalViewConfiguration.current
            val gridConfiguration = remember(configuration) { object : ViewConfiguration by configuration {
                // Expanded touch targets would intercept neighboring empty time cells; list mode retains normal targets.
                override val minimumTouchTargetSize = DpSize.Zero
            } }
            CompositionLocalProvider(LocalViewConfiguration provides gridConfiguration) {
            TimelineGrid(
                rendered, viewport, week.today, now, zone, onSelect, { transform -> viewport = transform(viewport) },
                Modifier.weight(1f), if (busy) null else onCreateAt,
                resolve = rendered::currentItem, remindersByDate = remindersByDate, onReminder = onReminder,
                onConflictGroup = { conflictGroup = it },
            )
            }
        }
    }
    if (showConflicts) TimelineConflictsSheet(conflicts, zone, onSelect, onClose = { showConflicts = false })
    conflictGroup?.let { group ->
        TimelineConflictGroupSheet(group, zone, onSelect = onSelect, onClose = { conflictGroup = null })
    }
}

/** Short date drags retain their initial travel once a deliberate gesture is recognized. */
private suspend fun PointerInputScope.detectTimelineGestures(
    onTap: (Offset) -> Unit,
    onSelectionPress: (Offset) -> Boolean,
    onSelectionStart: (Offset) -> Boolean,
    onSelectionMove: (Offset) -> Unit,
    onSelectionEnd: (Boolean) -> Unit,
    onTransform: (Offset, Offset, Float) -> Unit,
) {
    val threshold = minOf(viewConfiguration.touchSlop, 4.dp.toPx())
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downWasConsumed = down.isConsumed
        var active = false
        var hadMultiplePointers = false
        var selecting = onSelectionPress(down.position)
        if (selecting) down.consume()
        var waited = selecting
        var selectionMoved = false
        var origin: Offset? = null
        var travel = Offset.Zero
        var lastTime = down.uptimeMillis
        try { while (true) {
            val remaining = (down.uptimeMillis + viewConfiguration.longPressTimeoutMillis - lastTime).coerceAtLeast(1)
            val event = if (!active && !selecting && !waited) withTimeoutOrNull(remaining) { awaitPointerEvent() } else awaitPointerEvent()
            if (event == null) {
                waited = true
                selecting = !downWasConsumed && onSelectionStart(down.position)
                continue
            }
            lastTime = event.changes.firstOrNull()?.uptimeMillis ?: lastTime
            if (event.changes.count { it.pressed } > 1) {
                hadMultiplePointers = true
                active = true
                waited = true
                if (selecting) { onSelectionEnd(false); selecting = false }
            }
            // A second down may be consumed by a clickable child; the calendar owns multitouch.
            if (event.changes.any { it.isConsumed } && !hadMultiplePointers && !selecting) return@awaitEachGesture
            if (selecting) {
                if (event.changes.count { it.pressed } > 1) { onSelectionEnd(false); selecting = false; active = true }
                else {
                    event.changes.firstOrNull { it.id == down.id }?.let {
                        if (it.positionChanged() && (selectionMoved || (it.position - down.position).getDistance() > threshold)) {
                            selectionMoved = true
                            onSelectionMove(it.position)
                        }
                    }
                    event.changes.forEach { it.consume() }
                    if (event.changes.none { it.pressed }) { onSelectionEnd(true); selecting = false; break }
                    continue
                }
            }
            val focus = event.calculateCentroid(useCurrent = false)
            val pan = event.calculatePan()
            val scale = event.calculateZoom()
            if (focus != Offset.Unspecified && (pan != Offset.Zero || scale != 1f)) {
                if (active) onTransform(focus, pan, scale)
                else if (event.changes.count { it.pressed && it.previousPressed } >= 2) {
                    active = true
                    onTransform(focus, pan, scale)
                } else {
                    if (origin == null) origin = focus
                    travel += pan
                    if (travel.getDistance() > threshold) {
                        active = true
                        onTransform(requireNotNull(origin), travel, 1f)
                    }
                }
            }
            if (active) event.changes.filter { it.positionChanged() }.forEach { it.consume() }
            if (event.changes.none { it.pressed }) {
                if (!active) onTap(down.position)
                break
            }
        } } finally { if (selecting) onSelectionEnd(false) }
    }
}

@Composable private fun TimelineToolbar(
    data: LocalSnapshot, dates: List<LocalDate>, today: LocalDate, zoom: Double, listMode: Boolean,
    busy: Boolean, canUndo: Boolean, onCreate: () -> Unit, onAssistant: () -> Unit, onUndo: () -> Unit,
    onRedo: () -> Unit, canRedo: Boolean,
    onPrevious: () -> Unit, onNext: () -> Unit, onCurrent: () -> Unit, onFit: () -> Unit, onToggleList: () -> Unit,
    themeMode: ThemeMode, onThemeMode: (ThemeMode) -> Unit, conflicts: Int, onConflicts: () -> Unit,
) {
    val first = dates.first()
    val last = dates.last()
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${first.monthValue}月${first.dayOfMonth}日 — ${if (first.month == last.month) "" else "${last.monthValue}月"}${last.dayOfMonth}日", fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${first.year} · 今天 ${today.monthValue}月${today.dayOfMonth}日 ${DayNames[today.dayOfWeek.value - 1]}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            IconButton(onClick = onUndo, enabled = !busy && canUndo, modifier = Modifier.size(34.dp)) { Icon(Icons.AutoMirrored.Rounded.Undo, "撤销最近操作", Modifier.size(20.dp)) }
            IconButton(onClick = onRedo, enabled = !busy && canRedo, modifier = Modifier.size(34.dp)) { Icon(Icons.AutoMirrored.Rounded.Redo, "重做", Modifier.size(20.dp)) }
            ThemeSwitch(themeMode, onThemeMode)
            IconButton(onClick = onAssistant, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.KeyboardAlt, "语音助手", Modifier.size(20.dp)) }
            IconButton(onClick = onCreate, enabled = !busy, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Add, "新建", Modifier.size(25.dp)) }
        }
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(28.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "上一周", Modifier.size(16.dp)) }
                TextButton(onClick = onCurrent, modifier = Modifier.width(34.dp).height(30.dp), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("本周", fontSize = 11.sp, maxLines = 1) }
                IconButton(onClick = onNext, modifier = Modifier.size(28.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "下一周", Modifier.size(16.dp)) }
                Spacer(Modifier.width(2.dp))
                if (conflicts > 0) TextButton(onClick = onConflicts, modifier = Modifier.width(30.dp).height(30.dp).semantics { contentDescription = "查看时间冲突" }, contentPadding = PaddingValues(horizontal = 2.dp)) {
                    Icon(Icons.Rounded.WarningAmber, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.error)
                    Text(conflicts.toString(), fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
                ItemKind.entries.forEach { kind ->
                    Icon(kind.legendIcon(), null, Modifier.size(8.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(kind.label, Modifier.padding(start = 1.dp, end = 1.dp), fontSize = 9.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onFit, modifier = Modifier.width(48.dp).height(30.dp).semantics { contentDescription = "适合屏幕" }, contentPadding = PaddingValues(horizontal = 2.dp)) {
                    Icon(Icons.Rounded.FitScreen, null, Modifier.size(15.dp))
                    Text("${(zoom * 100).roundToInt()}%", fontSize = 10.sp, maxLines = 1)
                }
                TextButton(onClick = onToggleList, modifier = Modifier.width(if (listMode) 40.dp else 32.dp).height(30.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                    Text(if (listMode) "时间轴" else "列表", fontSize = 10.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable private fun TimelineGrid(
    rendered: TimelineRenderWindow, viewport: TimelineViewport, today: LocalDate, now: Instant, zone: ZoneId,
    onSelect: (TimelineItem) -> Unit, onViewport: ((TimelineViewport) -> TimelineViewport) -> Unit, modifier: Modifier = Modifier,
    onCreateAt: ((TimelineSelection) -> Unit)? = null,
    resolve: (TimelineItem) -> TimelineItem = { it },
    remindersByDate: Map<LocalDate, List<ReminderEntity>> = emptyMap(),
    onReminder: (ReminderEntity) -> Unit = {},
    onConflictGroup: (List<TimelineItem>) -> Unit = {},
) {
    val latestOnViewport by rememberUpdatedState(onViewport)
    val latestCreate by rememberUpdatedState(onCreateAt)
    val placementViewport = rememberUpdatedState(viewport)
    var anchor by remember { mutableStateOf<TimelineSelection?>(null) }
    var dragSelection by remember { mutableStateOf<TimelineSelection?>(null) }
    var pendingSelection by remember { mutableStateOf<TimelineSelection?>(null) }
    var pendingDrag by remember { mutableStateOf<PendingSelectionDrag?>(null) }
    BoxWithConstraints(modifier.fillMaxWidth().clipToBounds().testTag("timeline-viewport")) {
        val gutter = 38.dp
        val header = 42.dp
        val labelInset = 7.dp
        val graphWidth = (maxWidth - gutter - 3.dp).coerceAtLeast(1.dp)
        val bodyHeight = (maxHeight - header).coerceAtLeast(16.dp)
        val graphHeight = (bodyHeight - labelInset * 2).coerceAtLeast(1.dp)
        val dayWidth = graphWidth * (viewport.zoom / 7).toFloat()
        val minuteHeight = graphHeight * (viewport.zoom / 1440).toFloat()
        val density = LocalDensity.current
        val widthPx = with(density) { graphWidth.toPx().toDouble() }
        val heightPx = with(density) { graphHeight.toPx().toDouble() }
        val gutterPx = with(density) { gutter.toPx() }
        val topPx = with(density) { (header + labelInset).toPx() }
        val hitEvent by rememberUpdatedState<(Offset) -> Boolean> { point ->
            rendered.isOccupiedAt(
                placementViewport.value, graphWidth.value.toDouble(), graphHeight.value.toDouble(),
                ((point.x - gutterPx) / density.density).toDouble(), ((point.y - topPx) / density.density).toDouble(),
            )
        }
        val pick by rememberUpdatedState<(Offset) -> TimelineSelection?> { point ->
            TimelineSelection.at(placementViewport.value, (point.x - gutterPx).toDouble(), (point.y - topPx).toDouble(), widthPx, heightPx)
        }
        val minuteAt by rememberUpdatedState<(Offset) -> Int> { point ->
            val current = placementViewport.value
            (current.firstMinute + (point.y - topPx) / current.minuteHeight(heightPx)).roundToInt().coerceIn(0, 1440)
        }
        val pressSelection by rememberUpdatedState<(Offset) -> Boolean> { point ->
            val pending = pendingSelection
            val hit = pending?.let {
                selectionUiBounds(it, placementViewport.value, graphWidth.value.toDouble(), graphHeight.value.toDouble())
                    .hit(((point.x - gutterPx) / density.density).toDouble(), ((point.y - topPx) / density.density).toDouble())
            }
            if (latestCreate != null && pending != null && hit != null) {
                pendingDrag = PendingSelectionDrag(pending, minuteAt(point), hit)
                dragSelection = pending
                true
            } else {
                if (pending != null) pendingSelection = null
                false
            }
        }
        fun addPending() {
            val picked = pendingSelection ?: return
            pendingSelection = null; pendingDrag = null; dragSelection = null; anchor = null
            latestCreate?.invoke(picked)
        }
        val dates = rendered.dates
        val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)
        Box(Modifier.fillMaxSize().pointerInput(widthPx, heightPx) {
            detectTimelineGestures(
                onTap = { point -> if (latestCreate != null && !hitEvent(point)) pendingSelection = pick(point) },
                onSelectionPress = { pressSelection(it) },
                onSelectionStart = { point ->
                    val slot = if (latestCreate != null && !hitEvent(point)) pick(point) else null
                    pendingSelection = null; pendingDrag = null
                    anchor = slot; dragSelection = slot
                    slot != null
                },
                onSelectionMove = { point ->
                    val draft = pendingDrag
                    if (draft != null) dragSelection = draft.update(minuteAt(point))
                    else anchor?.let { dragSelection = TimelineSelection.range(it.date, it.startMinute, minuteAt(point)) }
                },
                onSelectionEnd = { commit ->
                    val picked = dragSelection
                    anchor = null; dragSelection = null; pendingDrag = null; pendingSelection = null
                    if (commit && picked != null) latestCreate?.invoke(picked)
                },
            ) { centroid, pan, zoom ->
                pendingSelection = null
                latestOnViewport { current -> current.transform(pan.x.toDouble(), pan.y.toDouble(), zoom.toDouble(), (centroid.x - gutterPx).toDouble(), (centroid.y - topPx).toDouble(), widthPx, heightPx) }
            }
        }.semantics { contentDescription = "连续时间轴"; stateDescription = "缩放${(viewport.zoom * 100).roundToInt()}%，左右滑动日期，双指缩放，点空白选时段，再点加号或拖动松手添加" }) {
            Box(Modifier.offset(x = gutter).width(graphWidth).height(header).clipToBounds()) {
                dates.forEach { date -> key(date) {
                    TimelineDateHeader(date, today, dayWidth, header, placementViewport)
                } }
            }
            Box(Modifier.offset(y = header).fillMaxWidth().height(bodyHeight).clipToBounds()) {
                TimelineHourLabels(gutter, bodyHeight, labelInset, minuteHeight, viewport.firstMinute)
                Box(Modifier.offset(x = gutter, y = labelInset).width(graphWidth).height(graphHeight).clipToBounds()) {
                    Canvas(Modifier.matchParentSize()) {
                        val current = placementViewport.value
                        val dayWidthPx = dayWidth.toPx()
                        val minuteHeightPx = minuteHeight.toPx()
                        dates.forEach { date ->
                            val x = (dayWidthPx * (date.toEpochDay() - current.firstDay)).toFloat()
                            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f)
                        }
                        val right = (dayWidthPx * (dates.last().toEpochDay() + 1 - current.firstDay)).toFloat()
                        drawLine(gridColor, Offset(right, 0f), Offset(right, size.height), 1f)
                        repeat(25) { hour ->
                            val y = (minuteHeightPx * (hour * 60 - current.firstMinute)).toFloat()
                            drawLine(gridColor.copy(alpha = if (hour % 6 == 0) .65f else .3f), Offset(0f, y), Offset(size.width, y), 1f)
                        }
                    }
                    dates.forEach { date -> key(date) {
                        TimelineDay(
                            rendered.days.getValue(date), placementViewport, dayWidth, minuteHeight, graphHeight,
                            viewport.zoom, viewport.firstMinute, today, now, zone, rendered.conflictingKeys, onSelect,
                            resolve = resolve, reminders = remindersByDate[date].orEmpty(), onReminder = onReminder, onConflictGroup = onConflictGroup,
                        )
                    } }
                    (dragSelection ?: pendingSelection)?.takeIf { it.date in dates }?.let { selected ->
                        TimelineSelectionOverlay(
                            selectionUiBounds(selected, viewport, graphWidth.value.toDouble(), graphHeight.value.toDouble()), selected,
                            pending = pendingSelection != null, onAdd = ::addPending,
                        )
                    }
                }
            }
        }
    }
}

/** Read fractional x only during placement so a date drag can skip its text composition. */
@Composable private fun TimelineDateHeader(date: LocalDate, today: LocalDate, dayWidth: Dp, header: Dp, viewport: State<TimelineViewport>) {
    val isToday = date == today
    Column(Modifier.offset {
        IntOffset((dayWidth.toPx() * (date.toEpochDay() - viewport.value.firstDay)).roundToInt(), 0)
    }.width(dayWidth).height(header).semantics { contentDescription = "$date ${DayNames[date.dayOfWeek.value - 1]}" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(DayNames[date.dayOfWeek.value - 1], fontSize = 10.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Surface(color = if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent, shape = RoundedCornerShape(7.dp)) {
            Text(date.dayOfMonth.toString(), Modifier.padding(horizontal = 7.dp, vertical = 2.dp), fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, color = if (isToday) Color.White else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable private fun TimelineHourLabels(gutter: Dp, bodyHeight: Dp, labelInset: Dp, minuteHeight: Dp, firstMinute: Double) {
    repeat(25) { hour ->
        val y = labelInset + minuteHeight * (hour * 60 - firstMinute).toFloat()
        if (y >= 0.dp && y <= bodyHeight) {
            Text("%02d:00".format(hour), Modifier.offset(y = (y - 7.dp).coerceIn(0.dp, bodyHeight - 14.dp)).width(gutter - 5.dp), fontSize = 9.sp, lineHeight = 12.sp, textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A complete day's lane assignment stays fixed while its parent moves horizontally. */
@Composable private fun TimelineDay(
    day: TimelineDayLayout, viewport: State<TimelineViewport>, dayWidth: Dp, minuteHeight: Dp, graphHeight: Dp,
    zoom: Double, firstMinute: Double, today: LocalDate, now: Instant, zone: ZoneId, conflictingKeys: Set<String>,
    onSelect: (TimelineItem) -> Unit,
    resolve: (TimelineItem) -> TimelineItem,
    reminders: List<ReminderEntity>,
    onReminder: (ReminderEntity) -> Unit,
    onConflictGroup: (List<TimelineItem>) -> Unit,
) {
    val visibleMinutes = 1440.0 / zoom
    Box(Modifier.offset {
        IntOffset((dayWidth.toPx() * (day.date.toEpochDay() - viewport.value.firstDay)).roundToInt(), 0)
    }.width(dayWidth).height(graphHeight)) {
        day.conflictGroups.forEach { group ->
            val bounds = day.groupBounds(group, dayWidth.value.toDouble(), minuteHeight.value.toDouble(), firstMinute, visibleMinutes)
            if (bounds != null) {
                TimelineConflictGroupCard(
                    items = group.items,
                    zone = zone,
                    modifier = Modifier
                        .offset(x = bounds.x.dp, y = bounds.y.dp)
                        .width(bounds.width.dp)
                        .height(bounds.height.dp)
                        .testTag("timeline-conflict-group"),
                    cardSize = DpSize(bounds.width.dp, bounds.height.dp),
                    onClick = { onConflictGroup(group.items.map(resolve)) },
                )
            }
        }
        day.events.forEach { event -> key(event.item.kind, event.item.id) {
            val grouped = event.item.kind != ItemKind.RECORD && event.item.layoutKey in day.groupedKeys
            if (!grouped) {
                val bounds = day.bounds(event, dayWidth.value.toDouble(), minuteHeight.value.toDouble(), firstMinute, visibleMinutes)
                if (bounds != null) {
                    val live = resolve(event.item)
                    TimelineEventCard(
                        live, zone, zoom, live.layoutKey in conflictingKeys,
                        Modifier.offset(x = bounds.x.dp, y = bounds.y.dp).width(bounds.width.dp).height(bounds.height.dp),
                        cardSize = DpSize(bounds.width.dp, bounds.height.dp),
                        onClick = { onSelect(live) },
                    )
                }
            }
        } }
        reminders.forEach { reminder ->
            val minute = Instant.ofEpochMilli(reminder.remindAt).atZone(zone).toLocalTime().toSecondOfDay() / 60.0
            val y = minuteHeight * (minute - firstMinute).toFloat()
            if (y in 0.dp..graphHeight) {
                Box(
                    Modifier.offset(x = dayWidth - 15.dp, y = y).size(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) { onReminder(reminder) }
                        .semantics { contentDescription = "提醒 ${reminder.title}" }
                        .testTag("timeline-reminder-${reminder.id}"),
                )
            }
        }
        if (day.date == today) {
            val minute = now.atZone(zone).toLocalTime().toSecondOfDay() / 60.0
            val y = minuteHeight * (minute - firstMinute).toFloat()
            if (y in 0.dp..graphHeight) Box(Modifier.offset(y = y).width(dayWidth).height(1.dp).background(MaterialTheme.colorScheme.error))
        }
    }
}

@Composable fun EmptyHint(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(32.dp), tint = Indigo)
        Spacer(Modifier.height(12.dp)); Text(title, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp)); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

