package com.timeagent.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timeagent.ui.components.AppTopBar
import com.timeagent.ui.components.SegmentedSwitch
import com.timeagent.ui.components.StatusBanner
import com.timeagent.ui.components.TopBarAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * N05 report page. All numbers come from [ReportSnapshot] (local only). The optional fish
 * commentary is rendered in its own block and never replaces or recalculates the local figures.
 */
@Composable
fun ReportScreen(
    model: ReportModel,
    onClose: () -> Unit,
    onAskFish: (suspend (ReportSnapshot) -> String?)? = null,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var sectionMenu by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var weeklyReminder by remember { mutableStateOf(ReportNotifications.weeklyEnabled(context)) }
    var monthlyReminder by remember { mutableStateOf(ReportNotifications.monthlyEnabled(context)) }

    LaunchedEffect(Unit) { model.refresh() }

    fun errorMessage(error: Throwable): String =
        error.message?.takeIf(String::isNotBlank) ?: "操作失败，数据未修改，请重试。"

    Scaffold(
        topBar = {
            AppTopBar(
                title = "报表",
                onBack = onClose,
                actions = listOf(
                    TopBarAction("刷新", Icons.Filled.Refresh) { model.refresh() },
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PeriodControls(model = model, state = state)

            if (state.loading && state.snapshot == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_001), style = MaterialTheme.typography.bodyMedium)
            }
            state.error?.let { StatusBanner(it) }

            state.snapshot?.let { snapshot ->
                if (ReportSection.OVERVIEW in state.visibleSections) OverviewSection(snapshot)
                if (ReportSection.PROJECTS in state.visibleSections) {
                    DistributionSection(
                        title = "按项目",
                        subtitle = "实际记录按任务的所属项目汇总；未归属项目的记录单独列出。",
                        slices = snapshot.projects,
                    )
                }
                if (ReportSection.TAGS in state.visibleSections) {
                    DistributionSection(
                        title = "按标签",
                        subtitle = "一条记录有多个标签时会分别计入，占比之和可能超过 100%。",
                        slices = snapshot.tags,
                    )
                }
                if (ReportSection.DEVIATIONS in state.visibleSections) DeviationSection(snapshot.deviations)
                if (ReportSection.DEADLINES in state.visibleSections) DeadlineSection(snapshot.deadlines)
                if (ReportSection.HABITS in state.visibleSections) HabitSection(snapshot.habits)
                if (ReportSection.WEEKLY_GOALS in state.visibleSections) WeeklyGoalSection(snapshot.weeklyGoals)
                if (ReportSection.WORKOUTS in state.visibleSections) WorkoutSection(snapshot.workout)
                if (ReportSection.LEDGER in state.visibleSections) LedgerSection(snapshot.ledger)

                if (snapshot.notes.isNotEmpty()) {
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_002), style = MaterialTheme.typography.titleMedium)
                    snapshot.notes.forEach { note ->
                        Text("· $note", style = MaterialTheme.typography.bodySmall)
                    }
                }

                FishSection(
                    model = model,
                    state = state,
                    onAskFish = onAskFish,
                    snackbar = snackbar,
                )

                ShareSection(
                    sharing = sharing,
                    onShareImage = {
                        scope.launch {
                            sharing = true
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    ReportDocuments.renderLongImages(
                                        context = context,
                                        snapshot = snapshot,
                                        sections = state.visibleSections,
                                        fishCommentary = state.fishCommentary,
                                    )
                                }
                            }.onSuccess { files ->
                                ReportDocuments.shareImages(context, files)
                            }.onFailure {
                                snackbar.showSnackbar("长图生成失败：${errorMessage(it)}")
                            }
                            sharing = false
                        }
                    },
                    onSharePdf = {
                        scope.launch {
                            sharing = true
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    ReportDocuments.renderPdf(
                                        context = context,
                                        snapshot = snapshot,
                                        sections = state.visibleSections,
                                        fishCommentary = state.fishCommentary,
                                    )
                                }
                            }.onSuccess { file ->
                                ReportDocuments.sharePdf(context, file)
                            }.onFailure {
                                snackbar.showSnackbar("PDF 生成失败：${errorMessage(it)}")
                            }
                            sharing = false
                        }
                    },
                )

                SectionFilter(
                    expanded = sectionMenu,
                    onExpandedChange = { sectionMenu = it },
                    visible = state.visibleSections,
                    onToggle = model::toggleSection,
                )

                ReminderSection(
                    weekly = weeklyReminder,
                    monthly = monthlyReminder,
                    onWeekly = { enabled ->
                        weeklyReminder = enabled
                        runCatching { ReportNotifications.setWeeklyEnabled(context, enabled) }
                            .onFailure { error -> scope.launch { snackbar.showSnackbar(errorMessage(error)) } }
                    },
                    onMonthly = { enabled ->
                        monthlyReminder = enabled
                        runCatching { ReportNotifications.setMonthlyEnabled(context, enabled) }
                            .onFailure { error -> scope.launch { snackbar.showSnackbar(errorMessage(error)) } }
                    },
                )

                Text(
                    androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_003) +
                        "“大肥鱼点评”是可选的 AI 文本，不参与计算。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PeriodControls(model: ReportModel, state: ReportUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SegmentedSwitch(
                options = listOf(ReportKind.WEEK.label, ReportKind.MONTH.label),
                selectedIndex = if (state.kind == ReportKind.WEEK) 0 else 1,
                onSelected = { index -> model.selectKind(if (index == 0) ReportKind.WEEK else ReportKind.MONTH) },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { model.move(-1) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一期")
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        state.snapshot?.let { ReportFormat.windowTitle(it.window) }
                            ?: ReportFormat.windowTitle(modelWindow(model, state)),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        if (state.kind == ReportKind.WEEK) "周一开始，周日结束" else "自然月",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(
                    onClick = { model.move(1) },
                    enabled = model.canGoNext(),
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一期")
                }
            }
        }
    }
}

private fun modelWindow(model: ReportModel, state: ReportUiState): ReportWindow =
    if (state.kind == ReportKind.WEEK) {
        val monday = state.anchor.with(java.time.DayOfWeek.MONDAY)
        ReportWindow(monday, monday.plusDays(6))
    } else {
        val month = java.time.YearMonth.from(state.anchor)
        ReportWindow(month.atDay(1), month.atEndOfMonth())
    }

@Composable
private fun OverviewSection(snapshot: ReportSnapshot) {
    val overview = snapshot.overview
    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_004), style = MaterialTheme.typography.titleLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricTile(
            label = "计划",
            value = ReportFormat.minutes(overview.plannedMinutes),
            detail = "${overview.planCount} 个日程",
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            label = "实际",
            value = ReportFormat.minutes(overview.actualMinutes),
            detail = "${overview.actualRecordCount} 条记录",
            modifier = Modifier.weight(1f),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricTile(
            label = "计划覆盖",
            value = ReportFormat.minutes(overview.coveredMinutes),
            detail = "时间覆盖 ${ReportFormat.rate(overview.timeCoverageRate)}",
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            label = "计划完成率",
            value = ReportFormat.rate(overview.planCompletionRate),
            detail = "覆盖 ${overview.coveredPlans}/${overview.planCount} 个日程",
            modifier = Modifier.weight(1f),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricTile(
            label = "专注",
            value = ReportFormat.minutes(overview.focusMinutes),
            detail = if (overview.focusMinutes == null) "表未启用或无数据" else "${overview.focusSessions} 次",
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            label = "睡眠",
            value = ReportFormat.minutes(overview.sleepMinutes),
            detail = if (overview.sleepMinutes == null) "无数据（未同步不按 0）" else "${overview.sleepNights} 晚",
            modifier = Modifier.weight(1f),
        )
    }
    Text(
        androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_005),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun MetricTile(label: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DistributionSection(title: String, subtitle: String, slices: List<DistributionSlice>) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    Text(subtitle, style = MaterialTheme.typography.bodySmall)
    if (slices.isEmpty()) {
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.state_no_data), style = MaterialTheme.typography.bodyMedium)
        return
    }
    slices.forEach { slice ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(slice.name, modifier = Modifier.weight(1f))
                Text("${ReportFormat.minutes(slice.minutes)} · ${slice.sharePercent}%")
            }
            LinearProgressIndicator(
                progress = { (slice.sharePercent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DeviationSection(items: List<DeviationReport>) {
    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_007), style = MaterialTheme.typography.titleLarge)
    Text(
        androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_008),
        style = MaterialTheme.typography.bodySmall,
    )
    if (items.isEmpty()) {
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_009), style = MaterialTheme.typography.bodyMedium)
        return
    }
    items.forEach { item ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.label, modifier = Modifier.weight(1f))
                Text(
                    if (item.deltaMinutes >= 0) "+${ReportFormat.minutes(item.deltaMinutes)}"
                    else "−${ReportFormat.minutes(-item.deltaMinutes)}",
                    color = if (item.deltaMinutes >= 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "计划 ${ReportFormat.minutes(item.plannedMinutes)} · 实际 ${ReportFormat.minutes(item.actualMinutes)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DeadlineSection(report: DeadlineReport?) {
    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_010), style = MaterialTheme.typography.titleLarge)
    if (report == null) {
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_011), style = MaterialTheme.typography.bodyMedium)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricTile("总数", report.total.toString(), "所选区间到期", Modifier.weight(1f))
        MetricTile("完成率", "${report.completionRate}%", "${report.completed}/${report.total}", Modifier.weight(1f))
    }
    Text("待办 ${report.pending} · 已逾期 ${report.overdue}", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun HabitSection(section: HabitReportSection?) {
    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_012), style = MaterialTheme.typography.titleLarge)
    if (section == null) {
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.state_no_data), style = MaterialTheme.typography.bodyMedium)
        return
    }
    if (section.unavailable) {
        StatusBanner("习惯表尚未注册（v19 前），本板块显示为无数据，不按 0 计算。")
        return
    }
    if (section.lines.isEmpty()) {
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_013), style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text(
        "合计达成 ${section.achieved}/${section.evaluated}" +
            if (section.evaluated > 0) " · ${section.achieved * 100 / section.evaluated}%" else "",
        style = MaterialTheme.typography.bodyMedium,
    )
    section.lines.forEach { line ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(line.name, modifier = Modifier.weight(1f))
                Text(if (line.noData || line.rate == null) "无数据" else "${line.rate}%")
            }
            Text(
                "${line.targetText} · ${if (line.evaluated == 0) "无数据" else "达成 ${line.achieved}/${line.evaluated}"}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (line.rate != null && !line.noData) {
                LinearProgressIndicator(
                    progress = { (line.rate / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun WeeklyGoalSection(goals: List<WeeklyGoalReportLine>) {
    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_014), style = MaterialTheme.typography.titleLarge)
    if (goals.isEmpty()) {
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_015), style = MaterialTheme.typography.bodyMedium)
        return
    }
    goals.forEach { goal ->
        val rate = if (goal.targetMinutes <= 0) 0 else (goal.actualMinutes * 100 / goal.targetMinutes).toInt()
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${goal.title} · ${goal.source}", modifier = Modifier.weight(1f))
                Text("${goal.actualMinutes}/${goal.targetMinutes} 分钟")
            }
            Text("周起始 ${goal.weekStart} · 达成 $rate%", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(
                progress = { (rate / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun WorkoutSection(report: WorkoutReport?) {
    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_016), style = MaterialTheme.typography.titleLarge)
    if (report == null) {
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_017), style = MaterialTheme.typography.bodyMedium)
        return
    }
    if (report.sessions == 0) {
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_018), style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text("${report.sessions} 次 · ${ReportFormat.minutes(report.minutes)} · ${report.completedSets} 组")
    Text("总量 ${String.format(java.util.Locale.ROOT, "%.2f", report.volumeKg)} kg", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun LedgerSection(report: LedgerReport?) {
    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_019), style = MaterialTheme.typography.titleLarge)
    if (report == null) {
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_020), style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text("${report.count} 笔 · 净支出 ${ReportFormat.money(report.netExpenseMinor)}")
    Text(
        "支出 ${ReportFormat.money(report.expenseMinor)} · 收入 ${ReportFormat.money(report.incomeMinor)} · 退款 ${ReportFormat.money(report.refundMinor)}",
        style = MaterialTheme.typography.bodySmall,
    )
    report.topCategories.forEach { slice ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(slice.name, modifier = Modifier.weight(1f))
            Text("${ReportFormat.money(slice.amountMinor)} · ${slice.sharePercent}%")
        }
    }
}

@Composable
private fun FishSection(
    model: ReportModel,
    state: ReportUiState,
    onAskFish: (suspend (ReportSnapshot) -> String?)?,
    snackbar: SnackbarHostState,
) {
    val snapshot = state.snapshot ?: return
    val scope = rememberCoroutineScope()
    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_021), style = MaterialTheme.typography.titleLarge)
    Text(
        androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_022),
        style = MaterialTheme.typography.bodySmall,
    )
    when {
        state.fishLoading -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_023))
            }
        }
        state.fishCommentary != null -> {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                Text(state.fishCommentary, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge)
            }
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_024), style = MaterialTheme.typography.bodySmall)
            if (onAskFish != null) TextButton(onClick = {
                scope.launch {
                    model.beginFishCommentary()
                    try { model.setFishCommentary(onAskFish(snapshot)) }
                    catch (e: kotlinx.coroutines.CancellationException) { model.failFishCommentary("点评已取消"); throw e }
                    catch (e: Exception) { model.failFishCommentary(e.message ?: "点评未完成，请重试") }
                }
            }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_025)) }
        }
        onAskFish == null -> {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_026), style = MaterialTheme.typography.bodyMedium)
        }
        else -> {
            Button(onClick = {
                scope.launch {
                    model.beginFishCommentary()
                    runCatching { onAskFish.invoke(snapshot) }
                        .onSuccess { model.setFishCommentary(it) }
                        .onFailure {
                            model.failFishCommentary(
                                it.message?.takeIf(String::isNotBlank)
                                    ?: "大肥鱼点评失败，本地数字未受影响。",
                            )
                        }
                }
            }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_027)) }
        }
    }
    state.fishError?.let { StatusBanner(it) }
}

@Composable
private fun ShareSection(
    sharing: Boolean,
    onShareImage: () -> Unit,
    onSharePdf: () -> Unit,
) {
    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_028), style = MaterialTheme.typography.titleLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onShareImage, enabled = !sharing) {
            Icon(Icons.Filled.Image, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_029))
        }
        OutlinedButton(onClick = onSharePdf, enabled = !sharing) {
            Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_030))
        }
    }
    if (sharing) Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_031), style = MaterialTheme.typography.bodySmall)
    Text(
        androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_032),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun SectionFilter(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    visible: Set<ReportSection>,
    onToggle: (ReportSection) -> Unit,
) {
    Box {
        OutlinedButton(onClick = { onExpandedChange(true) }) {
            Icon(Icons.Filled.Tune, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_033))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            ReportSection.entries.forEach { section ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = section in visible, onCheckedChange = null)
                            Spacer(Modifier.size(8.dp))
                            Text(section.label)
                        }
                    },
                    onClick = { onToggle(section) },
                )
            }
        }
    }
}

@Composable
private fun ReminderSection(
    weekly: Boolean,
    monthly: Boolean,
    onWeekly: (Boolean) -> Unit,
    onMonthly: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_034), style = MaterialTheme.typography.titleMedium)
            Text(
                androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_035),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_036))
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_037), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = weekly, onCheckedChange = onWeekly)
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_038))
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_039), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = monthly, onCheckedChange = onMonthly)
            }
        }
    }
}
