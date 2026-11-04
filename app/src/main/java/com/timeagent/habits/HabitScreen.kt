package com.timeagent.habits

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import com.timeagent.domain.command.HabitMutation
import com.timeagent.domain.command.WriteHabit
import com.timeagent.ui.components.UndoMessage
import com.timeagent.ui.components.UndoSnackbar
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Habits screen. One row per habit with this week's cells, current streak and a 30-day rate; the
 * detail page adds a 12-week heatmap, a 12-week trend and the source rows behind each day.
 *
 * [onOpen] is the integration hook to the rest of the app: (entityType, entityId) where entityType
 * is RECORD, FOCUS, WORKOUT or HABIT. The default is intentionally empty so the screen can be
 * mounted before navigation wiring lands.
 */
@Composable
fun HabitScreen(
    model: HabitViewModel,
    onClose: () -> Unit,
    onOpen: (String, String) -> Unit = { _, _ -> },
) {
    val board by model.board.collectAsStateWithLifecycle()
    val detail by model.detail.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val undo by model.undoMessage.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    UndoSnackbar(snackbar, undo, onUndo = model::undo, onConsumed = model::consumeUndo)

    var screen by rememberSaveable { mutableStateOf("list") }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var showArchived by rememberSaveable { mutableStateOf(false) }

    fun openDetail(id: String) {
        detailId = id
        model.openDetail(id)
        screen = "detail"
    }

    // Restores the detail after process death or when the flow has not emitted yet.
    LaunchedEffect(screen, detailId, detail?.habit?.id) {
        val id = detailId
        if (id != null && (screen == "detail" || screen == "edit") && detail?.habit?.id != id) {
            model.openDetail(id)
        }
    }

    fun back() {
        screen = when (screen) {
            "edit" -> if (detailId != null) "detail" else "list"
            "detail" -> "list"
            else -> "list"
        }
    }

    BackHandler(screen != "list") { back() }
    BackHandler(screen == "list") { onClose() }

    Box(Modifier.fillMaxSize()) {
    when (screen) {
        "edit" -> {
            val habit = detail?.takeIf { it.habit.id == detailId }?.habit
                ?: detailId?.let { id -> board.active.firstOrNull { it.habit.id == id }?.habit }
            if (detailId != null && habit == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                HabitEditor(
                    existing = habit,
                    options = board.options,
                    busy = busy,
                    onBack = ::back,
                    onSave = { draft ->
                        val converted = draft.toEntity()
                        if (converted == null) {
                            model.report("请检查目标值和时刻格式（例如 23:30）")
                        } else {
                            model.save(converted, habit?.version) { saved ->
                                detailId = saved.id
                                screen = "detail"
                            }
                        }
                    },
                )
            }
        }
        "detail" -> {
            val current = detail
            if (current == null || current.habit.id != detailId) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                HabitDetailScreen(
                    detail = current,
                    options = board.options,
                    busy = busy,
                    canEdit = !current.habit.archived,
                    onBack = ::back,
                    onEdit = { screen = "edit" },
                    onArchive = { archived ->
                        model.archive(current.habit.id, archived, current.habit.version) {
                            if (archived) {
                                detailId = null
                                screen = "list"
                            }
                        }
                    },
                    onCheckin = { value, note ->
                        model.checkin(current.habit.id, board.today, value, note) {}
                    },
                    onClearCheckin = {
                        model.clearCheckin(current.habit.id, board.today) {}
                    },
                    onOpen = onOpen,
                )
            }
        }
        else -> HabitList(
            board = board,
            showArchived = showArchived,
            busy = busy,
            onToggleArchived = { showArchived = !showArchived },
            onOpen = ::openDetail,
            onAdd = { detailId = null; screen = "edit" },
            onClose = onClose,
        )
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = model::clearError,
            title = { Text("操作未完成") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = model::clearError) { Text("知道了") } },
        )
    }
}

// -------------------------------------------------------------------------------------------------
// View model
// -------------------------------------------------------------------------------------------------

class HabitViewModel(private val repository: HabitRepository) : ViewModel() {
    private val _board = MutableStateFlow(
        HabitBoard(LocalDate.now(), LocalDate.now().with(DayOfWeek.MONDAY)),
    )
    val board = _board.asStateFlow()

    private val _detail = MutableStateFlow<HabitDetail?>(null)
    val detail = _detail.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _undoMessage = MutableStateFlow<UndoMessage?>(null)
    val undoMessage = _undoMessage.asStateFlow()

    fun consumeUndo(id: String) {
        if (_undoMessage.value?.id == id) _undoMessage.value = null
    }

    fun undo(token: String) = perform {
        repository.undo(token)
        _undoMessage.value = null
        _detail.value?.habit?.id?.let { _detail.value = repository.detail(it) }
    }

    private suspend fun mutate(mutation: HabitMutation, message: String) {
        val result = repository.apply(WriteHabit(java.util.UUID.randomUUID().toString(), mutation))
        _undoMessage.value = result.undoToken?.let { UndoMessage(java.util.UUID.randomUUID().toString(), message, it) }
    }

    init {
        viewModelScope.launch {
            repository.observeBoard()
                .catch { _error.value = it.message ?: "习惯加载失败" }
                .collect {
                    _board.value = it
                    _detail.value?.habit?.id?.let { id -> _detail.value = repository.detail(id) }
                }
        }
    }

    fun openDetail(id: String) {
        viewModelScope.launch {
            _detail.value = runCatching { repository.detail(id) }.getOrElse {
                _error.value = it.message ?: "习惯详情加载失败"
                null
            }
        }
    }

    fun save(habit: HabitEntity, expectedVersion: Long?, onSaved: (HabitEntity) -> Unit = {}) = perform {
        val saved = repository.save(habit, expectedVersion)
        _detail.value = repository.detail(saved.id)
        onSaved(saved)
    }

    fun archive(id: String, archived: Boolean, expectedVersion: Long?, onDone: () -> Unit = {}) = perform {
        mutate(HabitMutation.Archive(id, archived, expectedVersion), if (archived) "已归档习惯" else "已恢复习惯")
        _detail.value = if (archived) null else repository.detail(id)
        onDone()
    }

    fun checkin(habitId: String, date: LocalDate, value: Int, note: String, onDone: () -> Unit = {}) = perform {
        mutate(HabitMutation.Checkin(habitId, date.toString(), value, note,
            _detail.value?.takeIf { it.habit.id == habitId }?.todayCheckin?.version), "已保存打卡")
        _detail.value = repository.detail(habitId)
        onDone()
    }

    fun clearCheckin(habitId: String, date: LocalDate, onDone: () -> Unit = {}) = perform {
        mutate(HabitMutation.ClearCheckin(habitId, date.toString(),
            _detail.value?.takeIf { it.habit.id == habitId }?.todayCheckin?.version), "已清除打卡")
        _detail.value = repository.detail(habitId)
        onDone()
    }

    fun report(message: String) {
        _error.value = message
    }

    fun clearError() {
        _error.value = null
    }

    private fun perform(action: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                action()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _error.value = error.message ?: "操作未保存，原数据保持不变"
            } finally {
                _busy.value = false
            }
        }
    }

    companion object {
        fun factory(repository: HabitRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = HabitViewModel(repository) as T
        }
    }
}

// -------------------------------------------------------------------------------------------------
// List
// -------------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HabitList(
    board: HabitBoard,
    showArchived: Boolean,
    busy: Boolean,
    onToggleArchived: () -> Unit,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onClose: () -> Unit,
) {
    val rows = if (showArchived) board.archived else board.active
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showArchived) "已归档习惯" else "习惯", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { if (showArchived) onToggleArchived() else onClose() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (board.archived.isNotEmpty() || showArchived) {
                        IconButton(onClick = onToggleArchived) {
                            Icon(if (showArchived) Icons.Rounded.Unarchive else Icons.Rounded.Archive, "已归档习惯")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!showArchived) {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text("新建习惯") },
                    modifier = Modifier.testTag("habit-add"),
                )
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!showArchived) {
                item {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("把想坚持的事交给已发生过的时间", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(
                                "自动习惯从实际记录、训练、睡眠与专注推导，不重复存储结果；手动习惯只记录打卡。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
            if (rows.isEmpty()) {
                item {
                    Text(
                        if (showArchived) "没有已归档的习惯。" else "还没有习惯。新建后，本周的格子会随着数据自动变化。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(rows, key = { it.habit.id }) { summary ->
                HabitRow(summary, board.options, onClick = { onOpen(summary.habit.id) })
            }
            if (!showArchived && board.archived.isNotEmpty()) {
                item {
                    TextButton(onClick = onToggleArchived) { Text("查看已归档（${board.archived.size}）") }
                }
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun HabitRow(summary: HabitSummary, options: HabitOptions, onClick: () -> Unit) {
    val habit = summary.habit
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HabitBadge(habit)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(habit.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(targetLabel(habit, options), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${summary.rate}%", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            WeekStrip(summary.weekDays)
            Text("本周 ${summary.weekLabel}", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (habit.periodKind == HabitPeriod.WEEKLY) "连续 ${summary.streak} 周" else "连续 ${summary.streak} 天",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${summary.rate}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HabitBadge(habit: HabitEntity) {
    val glyph = habit.icon.trim().take(2).ifBlank { habit.name.take(1) }
    Box(
        Modifier.size(36.dp).background(Color(habit.color), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WeekStrip(days: List<HabitDay>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        days.forEach { day ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(weekdayLabel(day.date.dayOfWeek), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(
                    Modifier.fillMaxWidth().height(20.dp)
                        .background(stateColor(day.state), RoundedCornerShape(6.dp)),
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Detail
// -------------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HabitDetailScreen(
    detail: HabitDetail,
    options: HabitOptions,
    busy: Boolean,
    canEdit: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onArchive: (Boolean) -> Unit,
    onCheckin: (Int, String) -> Unit,
    onClearCheckin: () -> Unit,
    onOpen: (String, String) -> Unit,
) {
    val habit = detail.habit
    val evaluation = detail.evaluation
    var confirmArchive by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(habit.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回习惯列表") }
                },
                actions = {
                    if (canEdit) {
                        IconButton(onClick = onEdit, enabled = !busy) { Icon(Icons.Rounded.Edit, "编辑习惯") }
                        IconButton(onClick = { confirmArchive = true }, enabled = !busy) { Icon(Icons.Rounded.Archive, "归档习惯") }
                    } else {
                        TextButton(onClick = { onArchive(false) }, enabled = !busy) { Text("恢复") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HabitBadge(habit)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(targetLabel(habit, options), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "连续 ${evaluation.streak} ${if (habit.periodKind == HabitPeriod.WEEKLY) "周" else "天"} · 近${if (evaluation.days.size <= 31) "30天" else "12周"}达成率 ${evaluation.rate}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("本周", fontWeight = FontWeight.SemiBold)
                    WeekStrip(evaluation.weekDays)
                    Text(evaluation.weekLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionTitle("近 12 周热力图")
            Heatmap(evaluation.days)

            SectionTitle("12 周趋势")
            TrendChart(evaluation.weeks, habit.metricKind)

            if (habit.sourceKind == HabitSource.MANUAL_CHECKIN && canEdit) {
                SectionTitle("手动打卡")
                ManualCheckin(detail, busy, onCheckin = onCheckin, onClear = onClearCheckin)
            }

            SectionTitle("历史与明细")
            val withEvents = evaluation.days.filter { it.events.isNotEmpty() }.sortedByDescending { it.date }
            if (withEvents.isEmpty()) {
                Text("所选时间范围内没有源数据。没有数据不等于未达成。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            withEvents.forEach { day ->
                Text(
                    "${formatDate(day.date)} · ${stateLabel(day.state)}${valueSuffix(habit, day)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                day.events.forEach { event ->
                    Surface(
                        Modifier.fillMaxWidth().clickable { onOpen(openType(event), openId(event)) },
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(event.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(eventSpanText(event), eventDetail(habit, event)).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(Icons.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (habit.sourceKind == HabitSource.MANUAL_CHECKIN && detail.checkins.isNotEmpty()) {
                SectionTitle("打卡历史")
                detail.checkins.take(60).forEach { item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatDate(item.day), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(checkinLabel(habit, item.value), style = MaterialTheme.typography.bodySmall)
                    }
                    if (item.note.isNotBlank()) {
                        Text(item.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    if (confirmArchive) {
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text("归档习惯") },
            text = { Text("归档后不再出现在列表，历史与打卡仍可查看，可随时恢复。") },
            confirmButton = { TextButton(onClick = { confirmArchive = false; onArchive(true) }) { Text("归档") } },
            dismissButton = { TextButton(onClick = { confirmArchive = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ManualCheckin(
    detail: HabitDetail,
    busy: Boolean,
    onCheckin: (Int, String) -> Unit,
    onClear: () -> Unit,
) {
    val habit = detail.habit
    val existing = detail.todayCheckin
    var value by remember(habit.id, existing?.value) {
        mutableStateOf(
            existing?.value?.let { checkinInput(habit, it) }
                ?: if (habit.metricKind == HabitMetric.BEFORE_TIME || habit.metricKind == HabitMetric.AFTER_TIME) {
                    formatMinuteOfDay(habit.target)
                } else habit.target.toString(),
        )
    }
    var note by remember(habit.id, existing?.note) { mutableStateOf(existing?.note.orEmpty()) }
    var invalid by remember(habit.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (habit.metricKind == HabitMetric.BEFORE_TIME || habit.metricKind == HabitMetric.AFTER_TIME) "今天实际时间（HH:mm）" else "今天实际完成",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = value,
            onValueChange = { value = it; invalid = false },
            singleLine = true,
            label = { Text(if (habit.metricKind == HabitMetric.COUNT) "次数" else if (habit.metricKind == HabitMetric.DURATION) "分钟" else "时刻") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = if (habit.metricKind == HabitMetric.BEFORE_TIME || habit.metricKind == HabitMetric.AFTER_TIME) KeyboardType.Text else KeyboardType.Number,
            ),
            modifier = Modifier.fillMaxWidth().testTag("habit-checkin-value"),
        )
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("备注（可选）") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val parsed = parseCheckin(habit, value)
                    if (parsed == null) {
                        invalid = true
                    } else {
                        onCheckin(parsed, note)
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f).testTag("habit-checkin-save"),
            ) { Text(if (existing == null) "打卡" else "更新打卡") }
            if (existing != null) {
                OutlinedButton(onClick = onClear, enabled = !busy) { Text("取消打卡") }
            }
        }
        if (invalid) {
            Text(
                if (habit.metricKind == HabitMetric.BEFORE_TIME || habit.metricKind == HabitMetric.AFTER_TIME) "请按 HH:mm 填写时刻" else "请填写有效数字",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun Heatmap(days: List<HabitDay>) {
    val byWeek = days.groupBy { it.date.with(DayOfWeek.MONDAY) }.toSortedMap()
    val weekCount = byWeek.size
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (dow in 1..7) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    weekdayLabel(DayOfWeek.of(dow)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(20.dp),
                )
                byWeek.values.forEach { week ->
                    val day = week.firstOrNull { it.date.dayOfWeek.value == dow }
                    Box(
                        Modifier.size(16.dp).background(stateColor(day?.state ?: HabitDayState.BEFORE_START), RoundedCornerShape(3.dp)),
                    )
                }
            }
        }
    }
    Text(
        if (weekCount == 0) "暂无数据" else "每列一周，共 $weekCount 周；浅色为无数据或未纳入统计。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TrendChart(weeks: List<HabitWeek>, metric: HabitMetric) {
    val values = weeks.map { it.value }
    val max = values.filterNotNull().maxOrNull()?.coerceAtLeast(1) ?: 1
    Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        weeks.forEach { week ->
            val value = week.value
            val ratio = if (value == null) 0f else (value.toFloat() / max).coerceIn(0f, 1f)
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Box(
                    Modifier.fillMaxWidth().height(if (value == null) 3.dp else (ratio * 74f + 4f).dp)
                        .background(
                            if (value == null) MaterialTheme.colorScheme.surfaceVariant else stateColor(week.state),
                            RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                        ),
                )
                Text(
                    when {
                        value == null -> "—"
                        metric == HabitMetric.DURATION -> "${(value / 60f).roundToInt()}h"
                        metric == HabitMetric.COUNT -> "$value"
                        else -> "$value"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
    Text("横轴为最近 12 周（含本周）。无数据的周显示为浅色短柱。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// -------------------------------------------------------------------------------------------------
// Editor
// -------------------------------------------------------------------------------------------------

private data class HabitDraft(
    val id: String?,
    val name: String,
    val icon: String,
    val color: Long,
    val metric: HabitMetric,
    val source: HabitSource,
    val sourceRef: String?,
    val targetText: String,
    val period: HabitPeriod,
    val weekdays: Set<DayOfWeek>,
    val startDate: String,
    val reminderEnabled: Boolean,
) {
    fun toEntity(): HabitEntity? {
        val target = if (metric == HabitMetric.BEFORE_TIME || metric == HabitMetric.AFTER_TIME) {
            parseMinuteOfDay(targetText)
        } else {
            targetText.trim().toIntOrNull()
        } ?: return null
        val start = runCatching { LocalDate.parse(startDate.trim()) }.getOrNull() ?: return null
        return HabitEntity(
            id = id ?: "habit:${java.util.UUID.randomUUID()}",
            name = name,
            icon = icon,
            color = color,
            metric = metric.name,
            source = source.name,
            sourceRef = sourceRef,
            target = target,
            period = period.name,
            activeWeekdays = HabitEntity.weekdaysOf(weekdays),
            startDate = start.toString(),
            reminderEnabled = reminderEnabled,
        )
    }

    companion object {
        fun from(habit: HabitEntity?): HabitDraft = if (habit == null) HabitDraft(
            id = null,
            name = "",
            icon = "check",
            color = 0xFF425F76,
            metric = HabitMetric.DURATION,
            source = HabitSource.MANUAL_CHECKIN,
            sourceRef = null,
            targetText = "30",
            period = HabitPeriod.DAILY,
            weekdays = DayOfWeek.values().toSet(),
            startDate = LocalDate.now().toString(),
            reminderEnabled = false,
        ) else HabitDraft(
            id = habit.id,
            name = habit.name,
            icon = habit.icon,
            color = habit.color,
            metric = habit.metricKind,
            source = habit.sourceKind,
            sourceRef = habit.sourceRef,
            targetText = if (habit.metricKind == HabitMetric.BEFORE_TIME || habit.metricKind == HabitMetric.AFTER_TIME) {
                formatMinuteOfDay(habit.target)
            } else habit.target.toString(),
            period = habit.periodKind,
            weekdays = habit.weekdays,
            startDate = habit.startDate,
            reminderEnabled = habit.reminderEnabled,
        )

        val Saver = listSaver<HabitDraft, Any?>(
            save = {
                listOf(it.id, it.name, it.icon, it.color, it.metric.name, it.source.name, it.sourceRef, it.targetText,
                    it.period.name, it.weekdays.joinToString(",") { day -> day.value.toString() }, it.startDate, it.reminderEnabled)
            },
            restore = {
                HabitDraft(
                    id = it[0] as String?,
                    name = it[1] as String,
                    icon = it[2] as String,
                    color = it[3] as Long,
                    metric = HabitMetric.parse(it[4] as String),
                    source = HabitSource.parse(it[5] as String),
                    sourceRef = it[6] as String?,
                    targetText = it[7] as String,
                    period = HabitPeriod.parse(it[8] as String),
                    weekdays = (it[9] as String).split(',')
                        .mapNotNull { value -> value.toIntOrNull()?.let { day -> DayOfWeek.of(day) } }
                        .toSet(),
                    startDate = it[10] as String,
                    reminderEnabled = it[11] as Boolean,
                )
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HabitEditor(
    existing: HabitEntity?,
    options: HabitOptions,
    busy: Boolean,
    onBack: () -> Unit,
    onSave: (HabitDraft) -> Unit,
) {
    var draft by rememberSaveable(existing?.id, stateSaver = HabitDraft.Saver) { mutableStateOf(HabitDraft.from(existing)) }
    val refOptions = options.forSource(draft.source)
    BackHandler { onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "新建习惯" else "编辑习惯", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } },
                actions = { TextButton(onClick = { onSave(draft) }, enabled = !busy) { Text("保存") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it.take(60)) },
                label = { Text("习惯名称") },
                placeholder = { Text("例如：每天学习 2 小时") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("habit-name"),
            )

            SectionTitle("统计方式")
            ChipRow(
                options = HabitMetric.values().map { it to metricLabel(it) },
                selected = draft.metric,
                onSelect = { metric ->
                    val target = when (metric) {
                        HabitMetric.BEFORE_TIME, HabitMetric.AFTER_TIME -> formatMinuteOfDay(22 * 60)
                        else -> "30"
                    }
                    draft = draft.copy(metric = metric, targetText = target)
                },
            )

            SectionTitle("数据来源")
            ChipRow(
                options = HabitSource.values().map { it to sourceLabel(it) },
                selected = draft.source,
                onSelect = { source -> draft = draft.copy(source = source, sourceRef = null) },
            )
            if (draft.source.needsSourceRef) {
                if (refOptions.isEmpty()) {
                    Text("还没有可选的${sourceLabel(draft.source)}，请先在对应模块创建。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        refOptions.forEach { option ->
                            FilterChip(
                                selected = draft.sourceRef == option.id,
                                onClick = { draft = draft.copy(sourceRef = option.id) },
                                label = { Text(option.label) },
                            )
                        }
                    }
                }
            }

            SectionTitle("目标")
            OutlinedTextField(
                value = draft.targetText,
                onValueChange = { draft = draft.copy(targetText = it.take(8)) },
                label = {
                    Text(
                        when (draft.metric) {
                            HabitMetric.DURATION -> "每天 / 每周分钟数"
                            HabitMetric.COUNT -> "每天 / 每周次数"
                            HabitMetric.BEFORE_TIME -> "不晚于（HH:mm）"
                            HabitMetric.AFTER_TIME -> "不早于（HH:mm）"
                        },
                    )
                },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (draft.metric == HabitMetric.BEFORE_TIME || draft.metric == HabitMetric.AFTER_TIME) KeyboardType.Text else KeyboardType.Number,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle("周期")
            ChipRow(
                options = listOf(HabitPeriod.DAILY to "每日", HabitPeriod.WEEKLY to "每周"),
                selected = draft.period,
                onSelect = { draft = draft.copy(period = it) },
            )

            SectionTitle("统计星期（不计入的日期不算未达成）")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DayOfWeek.values().forEach { day ->
                    FilterChip(
                        selected = day in draft.weekdays,
                        onClick = {
                            val next = if (day in draft.weekdays) draft.weekdays - day else draft.weekdays + day
                            if (next.isNotEmpty()) draft = draft.copy(weekdays = next)
                        },
                        label = { Text(weekdayLabel(day)) },
                    )
                }
            }

            SectionTitle("开始日期")
            OutlinedTextField(
                value = draft.startDate,
                onValueChange = { draft = draft.copy(startDate = it.take(10)) },
                label = { Text("YYYY-MM-DD；此日期之前不计入") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("未达成时 21:00 提醒")
                    Text("可选。已达成或非统计日不提醒。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = draft.reminderEnabled, onCheckedChange = { draft = draft.copy(reminderEnabled = it) })
            }

            SectionTitle("颜色")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(0xFF425F76, 0xFF1E8268, 0xFFA7362A, 0xFF785626, 0xFF5756B7, 0xFF3978B5).forEach { color ->
                    Box(
                        Modifier.size(32.dp)
                            .background(Color(color), CircleShape)
                            .clickable { draft = draft.copy(color = color) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (draft.color == color) Icon(Icons.Rounded.Check, null, tint = Color.White)
                    }
                }
            }

            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                "自动习惯不需要手动打卡，统计始终来自源数据；修改历史记录后数字立即更新。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Small helpers
// -------------------------------------------------------------------------------------------------

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun stateColor(state: HabitDayState): Color {
    val scheme = MaterialTheme.colorScheme
    return when (state) {
        HabitDayState.ACHIEVED -> scheme.secondary
        HabitDayState.MISSED -> scheme.error.copy(alpha = 0.75f)
        HabitDayState.NO_DATA -> scheme.surfaceVariant
        HabitDayState.INACTIVE -> scheme.surfaceContainerHighest
        HabitDayState.BEFORE_START -> scheme.surfaceContainerLow
        HabitDayState.UPCOMING -> scheme.outlineVariant.copy(alpha = 0.55f)
    }
}

private fun stateLabel(state: HabitDayState): String = when (state) {
    HabitDayState.ACHIEVED -> "达成"
    HabitDayState.MISSED -> "未达成"
    HabitDayState.NO_DATA -> "无数据"
    HabitDayState.INACTIVE -> "非统计日"
    HabitDayState.BEFORE_START -> "习惯开始前"
    HabitDayState.UPCOMING -> "未到"
}

private fun weekdayLabel(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "一"
    DayOfWeek.TUESDAY -> "二"
    DayOfWeek.WEDNESDAY -> "三"
    DayOfWeek.THURSDAY -> "四"
    DayOfWeek.FRIDAY -> "五"
    DayOfWeek.SATURDAY -> "六"
    DayOfWeek.SUNDAY -> "日"
}

private fun metricLabel(metric: HabitMetric): String = when (metric) {
    HabitMetric.DURATION -> "时长"
    HabitMetric.COUNT -> "次数"
    HabitMetric.BEFORE_TIME -> "不晚于"
    HabitMetric.AFTER_TIME -> "不早于"
}

private fun sourceLabel(source: HabitSource): String = when (source) {
    HabitSource.ACTUAL_BY_PROJECT -> "按项目"
    HabitSource.ACTUAL_BY_TAG -> "按标签"
    HabitSource.ACTUAL_BY_ACTIVITY -> "按活动"
    HabitSource.ACTUAL_BY_CATEGORY -> "按分类"
    HabitSource.WORKOUT -> "训练"
    HabitSource.SLEEP -> "睡眠"
    HabitSource.FOCUS -> "专注"
    HabitSource.MANUAL_CHECKIN -> "手动打卡"
}

private fun targetLabel(habit: HabitEntity, options: HabitOptions): String {
    val period = if (habit.periodKind == HabitPeriod.WEEKLY) "每周" else "每天"
    val scope = when (habit.sourceKind) {
        HabitSource.ACTUAL_BY_PROJECT, HabitSource.ACTUAL_BY_TAG,
        HabitSource.ACTUAL_BY_ACTIVITY, HabitSource.ACTUAL_BY_CATEGORY ->
            options.label(habit.sourceKind, habit.sourceRef)?.let { "按${sourceLabel(habit.sourceKind).removePrefix("按")} · $it" }
                ?: sourceLabel(habit.sourceKind)
        else -> sourceLabel(habit.sourceKind)
    }
    val target = when (habit.metricKind) {
        HabitMetric.DURATION -> if (habit.target % 60 == 0) "${habit.target / 60} 小时" else "${habit.target} 分钟"
        HabitMetric.COUNT -> "${habit.target} 次"
        HabitMetric.BEFORE_TIME -> "${formatMinuteOfDay(habit.target)} 前"
        HabitMetric.AFTER_TIME -> "${formatMinuteOfDay(habit.target)} 后"
    }
    return "$period · $scope · $target"
}

private fun valueSuffix(habit: HabitEntity, day: HabitDay): String {
    val value = day.value ?: return ""
    return when (habit.metricKind) {
        HabitMetric.DURATION -> " · ${value / 60}h${value % 60}m"
        HabitMetric.COUNT -> " · $value 次"
        else -> " · ${formatMinuteOfDay(value.toInt())}"
    }
}

private fun eventSpanText(event: HabitEvent): String? {
    val start = event.startAt ?: return null
    val zone = java.time.ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    val end = event.endAt
    return if (end == null) {
        Instant.ofEpochMilli(start).atZone(zone).format(formatter)
    } else {
        "${Instant.ofEpochMilli(start).atZone(zone).format(formatter)}–${Instant.ofEpochMilli(end).atZone(zone).format(formatter)}"
    }
}

private fun eventDetail(habit: HabitEntity, event: HabitEvent): String? = when (habit.metricKind) {
    HabitMetric.DURATION -> if (event.minutes > 0) "${event.minutes} 分钟" else null
    HabitMetric.COUNT -> if (event.count > 0) "${event.count} 次" else null
    HabitMetric.BEFORE_TIME -> event.minuteOfDay?.let { formatMinuteOfDay(it) }
    HabitMetric.AFTER_TIME -> (event.endMinuteOfDay ?: event.minuteOfDay)?.let { formatMinuteOfDay(it) }
}

private fun openType(event: HabitEvent): String = when (event.kind) {
    HabitStatistics.KIND_WORKOUT -> "WORKOUT"
    HabitStatistics.KIND_FOCUS -> "FOCUS"
    HabitStatistics.KIND_CHECKIN -> "HABIT"
    else -> "RECORD"
}

private fun openId(event: HabitEvent): String =
    if (event.kind == HabitStatistics.KIND_CHECKIN) event.habitId ?: event.id else event.id

private fun checkinInput(habit: HabitEntity, value: Int): String = when (habit.metricKind) {
    HabitMetric.BEFORE_TIME, HabitMetric.AFTER_TIME -> formatMinuteOfDay(value)
    else -> value.toString()
}

private fun checkinLabel(habit: HabitEntity, value: Int): String = when (habit.metricKind) {
    HabitMetric.DURATION -> "${value} 分钟"
    HabitMetric.COUNT -> "$value 次"
    else -> formatMinuteOfDay(value)
}

private fun parseCheckin(habit: HabitEntity, text: String): Int? =
    if (habit.metricKind == HabitMetric.BEFORE_TIME || habit.metricKind == HabitMetric.AFTER_TIME) {
        parseMinuteOfDay(text)
    } else {
        text.trim().toIntOrNull()?.takeIf { it >= 0 }
    }

private fun formatDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("M月d日 E", Locale.CHINA))
