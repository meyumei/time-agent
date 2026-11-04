package com.timeagent.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timeagent.core.*
import com.timeagent.data.*
import com.timeagent.assistant.*
import com.timeagent.assistant.SpeechInputStatus
import com.timeagent.assistant.SpeechInputFactory
import com.timeagent.assistant.OfflineSpeechInputFactory
import com.timeagent.imports.CourseImportScreen
import com.timeagent.health.*
import com.timeagent.workout.WorkoutNavScreen
import com.timeagent.core.workout.WorkoutSessionStatus
import com.timeagent.core.workout.WorkoutRules
import com.timeagent.suggestions.SuggestionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import com.timeagent.workout.data.WorkoutRepository
import com.timeagent.ledger.LedgerRepository
import com.timeagent.ledger.LedgerScreen
import com.timeagent.ledger.PendingPaymentCard
import kotlinx.coroutines.withContext
import java.time.*
import java.util.UUID
import com.timeagent.ui.navigation.*

private data class WorkoutTimerSwitch(
    val session: com.timeagent.core.workout.WorkoutSession,
    val taskId: String?,
    val taskVersion: Long?,
    val requestId: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TimeAgentApp(
    model: TimeViewModel,
    speechFactory: SpeechInputFactory = OfflineSpeechInputFactory,
    openReminderId: String? = null,
    onOpenReminderConsumed: () -> Unit = {},
    sharedText: String? = null,
    onSharedTextConsumed: () -> Unit = {},
    appDestination: String? = null,
    onAppDestinationConsumed: () -> Unit = {},
    fishDestination: String? = null,
    onFishDestinationConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val appearance = remember(context) { ThemePreference(context) }
    val appScope = rememberCoroutineScope()
    val suggestionCoordinator = remember(context, model) { SuggestionCoordinator(context.applicationContext, model.repository) }
    LaunchedEffect(suggestionCoordinator) { suggestionCoordinator.start(appScope) }
    TimeAgentTheme {
        val themeMode = LocalAppearance.current.theme
        TimelineColorEnvironment {
            TimeAgentContent(model, themeMode, speechFactory, openReminderId, onOpenReminderConsumed, sharedText, onSharedTextConsumed,
                fishDestination, onFishDestinationConsumed, appDestination, onAppDestinationConsumed) { selected -> appearance.save(selected) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TimeAgentContent(
    model: TimeViewModel,
    themeMode: ThemeMode,
    speechFactory: SpeechInputFactory,
    openReminderId: String?,
    onOpenReminderConsumed: () -> Unit,
    sharedText: String?,
    onSharedTextConsumed: () -> Unit,
    fishDestination: String?,
    onFishDestinationConsumed: () -> Unit,
    appDestination: String?,
    onAppDestinationConsumed: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
) {
    val data by model.data.collectAsStateWithLifecycle()
    val week by model.week.collectAsStateWithLifecycle()
    val visibleWeekStart by model.visibleWeekStart.collectAsStateWithLifecycle()
    val timelineNavigationRevision by model.timelineNavigationRevision.collectAsStateWithLifecycle()
    val reviewWeek = week.copy(selectedWeekStart = visibleWeekStart)
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val zone = model.zone
    val context = LocalContext.current
    val healthSettings = remember(context) { HealthSyncSettings(context.applicationContext) }
    val healthReader = remember(context) { HealthConnectReader(context.applicationContext) }
    val ledgerRepository = remember(model.repository) {
        LedgerRepository(model.repository.database, core = model.repository)
    }
    var ledgerReadError by remember { mutableStateOf<String?>(null) }
    var ledgerReadRevision by remember { mutableIntStateOf(0) }
    val pendingLedgerCount by remember(ledgerRepository, ledgerReadRevision) {
        ledgerRepository.observePendingCount().onEach { ledgerReadError = null }.catch {
            ledgerReadError = "支付草稿读取失败，请打开记账重试"
            emit(0)
        }
    }.collectAsStateWithLifecycle(0)
    val workoutsRepository = remember(context, model) { WorkoutRepository(context.applicationContext, model.repository) }
    var workoutReadError by remember { mutableStateOf<String?>(null) }
    val workouts by remember(workoutsRepository) { workoutsRepository.observeSessions().catch { workoutReadError = "训练读取失败：${it.message}"; emit(emptyList()) } }.collectAsStateWithLifecycle(emptyList())
    val activeWorkout = workouts.firstOrNull { it.status == WorkoutSessionStatus.ACTIVE || it.status == WorkoutSessionStatus.PAUSED }
    LaunchedEffect(activeWorkout) { com.timeagent.workout.WorkoutNotifications.sync(context, activeWorkout) }
    var workoutSwitch by remember { mutableStateOf<WorkoutTimerSwitch?>(null) }
    val healthScope = rememberCoroutineScope()
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (healthSettings.enabled()) healthScope.launch {
            model.data.first { !it.loading }
            model.runHealthSync(healthReader, healthSettings, quiet = true)
        }
    }
    val navigator = rememberAppNavigator()
    val tab = navigator.currentTab.index
    val extraScreen = navigator.page
    val shellDrafts = androidx.lifecycle.viewmodel.compose.viewModel<ShellDraftViewModel>()
    var editor by shellDrafts::editor
    var quickTaskTitle by rememberSaveable { mutableStateOf("") }
    val editorState = rememberSaveableStateHolder()
    val editorDrafts = shellDrafts.editorDrafts
    var selected by remember { mutableStateOf<Any?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    val screenState = rememberSaveableStateHolder()
    fun openScreen(destination: String) {
        if (destination == "ledger") ledgerReadRevision++
        when (destination) {
            "organization" -> context.startActivity(android.content.Intent(context, com.timeagent.organization.OrganizationActivity::class.java))
            else -> navigator.open(destination)
        }
    }
    fun closeScreen() { navigator.close() }
    var snoozeReminderId by rememberSaveable { mutableStateOf<String?>(null) }
    var repeatReminderId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(appDestination) {
        if (appDestination != null) {
            navigator.awaitReady()
            if (appDestination.startsWith("reminder-snooze:")) {
                snoozeReminderId = appDestination.removePrefix("reminder-snooze:")
            } else if (appDestination == "butler") navigator.returnToRoot(3) else navigator.openDeepLink(appDestination)
            onAppDestinationConsumed()
        }
    }
    var extractionText by rememberSaveable { mutableStateOf("") }
    var inboxConversionJson by rememberSaveable { mutableStateOf<String?>(null) }
    var inboxConversionTarget by rememberSaveable { mutableStateOf("") }
    var calendarTitle by rememberSaveable { mutableStateOf("") }
    var calendarStart by rememberSaveable { mutableLongStateOf(0L) }
    var calendarEnd by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            navigator.awaitReady()
            if (sharedText.length > 20_000) model.report("分享文字超过20000字，请分段粘贴")
            else { extractionText = sharedText; navigator.openDeepLink("extract"); editor = null; selected = null }
            onSharedTextConsumed()
        }
    }
    var localVoiceText by rememberSaveable { mutableStateOf("") }
    var voiceMessage by remember { mutableStateOf<String?>(null) }
    var liveVoiceText by remember { mutableStateOf("") }
    var voiceStatus by remember { mutableStateOf(SpeechInputStatus.IDLE) }
    var voiceSuggestion by remember { mutableStateOf<String?>(null) }
    val fish = model.fish
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        model.refreshClock()
        fish?.appForeground = true
        fish?.let {
            try { com.timeagent.fish.FishOverlayService.sync(context, it) }
            catch (error: RuntimeException) {
                it.companion.save(it.companion.settings.value.copy(globalVisible = false))
                model.report(error.message ?: "全局悬浮未能开启，请到陪伴设置重试")
            }
        }
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
        fish?.appForeground = false
        fish?.voice?.stop()
    }
    val companionConfig by (fish?.companion?.settings ?: remember { kotlinx.coroutines.flow.MutableStateFlow(com.timeagent.fish.FishCompanionSettings()) }).collectAsStateWithLifecycle()
    val fishDraft by (fish?.draft ?: remember { kotlinx.coroutines.flow.MutableStateFlow("") }).collectAsStateWithLifecycle()
    val voiceText = if (fish != null) fishDraft else localVoiceText
    fun editAssistantDraft(text: String) { if (fish != null) fish.editDraft(text) else localVoiceText = text }
    val chat by (fish?.chat ?: model.assistant.state).collectAsStateWithLifecycle()
    val fishMessage by (fish?.message ?: kotlinx.coroutines.flow.MutableStateFlow<String?>(null)).collectAsStateWithLifecycle()
    var assistantExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(fishDestination) {
        if (fishDestination != null) {
            navigator.awaitReady()
            if (fishDestination in setOf("chat", "appearance")) {
                editor = null; selected = null
                navigator.openDeepLink(if (fishDestination == "appearance") "fish-appearance" else "fish-chat")
                assistantExpanded = false
            }
            onFishDestinationConsumed()
        }
    }
    var consumedCompletionRevision by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(chat.completionRevision) {
        if (chat.completionRevision > consumedCompletionRevision) {
            assistantExpanded = false
            liveVoiceText = ""
            voiceSuggestion = null
        }
        consumedCompletionRevision = chat.completionRevision
    }
    var assistantSettingsOpen by remember { mutableStateOf(false) }
    val assistantSettings = remember(context) { AssistantSettings(context.applicationContext) }
    val cloud = remember(assistantSettings) { ConversationCloud { text, requestContext, turns ->
        if (!assistantSettings.cloudEnabled()) CloudResult.Failure("智能理解尚未开启，请在助手设置中开启，或使用本地常用表达。")
        else DeepSeekClient().interpret(text, requestContext, assistantSettings.readApiKey(), assistantSettings.model(), turns)
    } }
    fun sendAssistant(text: String) {
        if (text.isBlank()) return
        editAssistantDraft(text); assistantExpanded = true; voiceMessage = null
        if (fish != null) { fish.send(text) }
        else model.sendToAssistant(text, if (assistantSettings.cloudEnabled()) cloud else null)
    }
    LaunchedEffect(voiceMessage) { if (voiceMessage != null) { delay(6000); voiceMessage = null } }
    var confirmation by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var deletingTask by remember { mutableStateOf<TaskEntity?>(null) }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var switchTask by remember { mutableStateOf<Pair<String?, String>?>(null) }
    var reminderDetail by remember { mutableStateOf<ReminderEntity?>(null) }
    var noteType by rememberSaveable { mutableStateOf<String?>(null) }
    var noteId by rememberSaveable { mutableStateOf<String?>(null) }
    var noteTitle by rememberSaveable { mutableStateOf("") }
    val noteState = rememberSaveableStateHolder()
    val noteDrafts = shellDrafts.noteDrafts
    fun closeNote() { noteType = null; noteId = null; noteTitle = "" }
    var notice by remember { mutableStateOf<String?>(null) }
    fun openNote(type: String, id: String, title: String) {
        noteType = type
        noteId = id
        noteTitle = title
        selected = null
    }
    fun openOriginal(type: String, id: String) {
        when (type) {
            "WORKOUT" -> openScreen("workout:$id")
            "TASK" -> data.tasks.find { it.id == id }?.let { selected = it } ?: run { notice = "原任务已被删除" }
            "REMINDER" -> data.reminders.find { it.id == id }?.let { reminderDetail = it } ?: run { notice = "原提醒已被删除" }
            "PROJECT" -> openScreen("project:$id")
            "ACTIVITY" -> openScreen("activities")
            "IDEA" -> openScreen("inbox")
            else -> data.timeline(model.clock.now()).find { it.id == id }?.let { selected = it } ?: run { notice = "原记录已被删除，笔记原文仍保留" }
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) notice = "已保存，通知未开启"
    }
    fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val preferences = context.getSharedPreferences("reminder_permission", android.content.Context.MODE_PRIVATE)
            if (!preferences.getBoolean("asked", false)) {
                preferences.edit().putBoolean("asked", true).apply()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else notice = "已保存，通知未开启"
        }
    }
    LaunchedEffect(openReminderId, data.reminders, data.loading) {
        val id = openReminderId ?: return@LaunchedEffect
        if (data.loading) return@LaunchedEffect
        navigator.awaitReady()
        val found = data.reminders.firstOrNull { it.id == id }
        if (found != null) {
            runCatching {
                com.timeagent.reminders.ReminderInstanceRepository(model.repository).act(
                    found.id, found.version, requestId = "reminder-open:${found.id}:${found.version}",
                )
            }.onFailure { model.report(it.message ?: "提醒处理状态未保存，请重试") }
        }
        if (found != null) { navigator.openDeepLink("inbox"); reminderDetail = found; notice = null } else notice = "这条提醒已被删除"
        onOpenReminderConsumed()
    }
    val running = data.records.firstOrNull { it.endAt == null }
    fun startTimer(taskId: String?) {
        val requestId = UUID.randomUUID().toString()
        if (activeWorkout != null) workoutSwitch = WorkoutTimerSwitch(activeWorkout, taskId,
            taskId?.let { id -> data.tasks.firstOrNull { it.id == id }?.version }, requestId)
        else if (running != null) switchTask = taskId to requestId
        else model.perform { model.repository.startTimer(taskId, requestId) }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.perform {
            val raw = withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openInputStream(uri)).bufferedReader(Charsets.UTF_8).use { reader ->
                    val result = StringBuilder(); val buffer = CharArray(4096)
                    while (true) { val count = reader.read(buffer); if (count < 0) break; result.append(buffer, 0, count); require(result.length <= 512000) { "课表文件超过 500 KB" } }
                    result.toString()
                }
            }
            preview = model.repository.previewImport(JsonCourseAdapter().parse(raw))
        }
    }

    val briefingContent: @Composable () -> Unit = {
        MinuteClockContent(model) { now ->
            val sync = briefingHealthStatus(healthSettings.enabled() && (healthSettings.sleepEnabled() || healthSettings.exerciseEnabled()), healthSettings.readReport(), zone, now)
            DailyBriefingCard(data, now, zone, sync, workouts,
                onOpen = { type, id -> if (type == "SUGGESTIONS") openScreen("inbox") else openOriginal(type, id) },
                onHealth = { openScreen("health") })
        }
    }
    var voiceSheetOpen by rememberSaveable { mutableStateOf(false) }
    val voiceControl = rememberHomeVoiceState(!busy && !data.loading && !chat.busy, ::sendAssistant, { voiceMessage = it },
        { state, text -> voiceStatus = state; liveVoiceText = text }, { voiceSuggestion = it }, speechFactory)
    Scaffold(modifier = Modifier.imePadding(), topBar = {
        if (extraScreen == null && editor == null) Column(Modifier.statusBarsPadding()) {
            if (LocalAppearance.current.modeHintPending) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("现在可在外观中切换到工具模式", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { ThemePreference(context).acknowledgeModeHint() }) { Text("知道了") }
                }
            }
            if (tab != 0 && tab != 3) Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(listOf("今天", "计划", "复盘", "管家")[tab], style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                IconButton(onClick = { model.perform { model.repository.undoLastAction() } }, enabled = !busy && data.history.any { !it.undone }) { Icon(Icons.AutoMirrored.Rounded.Undo, "撤销最近操作") }
                IconButton(onClick = { model.perform { model.repository.redoLastAction() } }, enabled = !busy && data.history.any { it.undone && it.redoable }) { Icon(Icons.AutoMirrored.Rounded.Redo, "重做") }
                ThemeSwitch(themeMode, onThemeMode)
            }
            voiceMessage?.let { Text(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            notice?.let { Text(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            if (tab != 3) fishMessage?.let { Text(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.error) }
            if (!voiceSheetOpen) VoiceInputBanner(voiceStatus, liveVoiceText)
            chat.localDestination?.let { destination -> TextButton(onClick = { openScreen(destination) }, modifier = Modifier.testTag("assistant-local-result")) { Text(if (destination == "history") "查看历史结果与原记录" else "查看个人记忆") } }
            if (tab == 2) WeekHeader(reviewWeek, data, { model.changeReviewWeek(-1) }, { model.changeReviewWeek(1) }, model::currentWeek)
            activeWorkout?.let { session ->
                RunningWorkoutBar(session, model.clock, busy, onOpen = { openScreen("workout:${session.id}") })
            }
            running?.let { item ->
                RunningTimerBar(item, data.tasks.firstOrNull { it.id == item.taskId }?.title ?: "自由计时",
                    model.clock, busy, onStop = { model.perform { model.repository.stopTimer(item.id, item.version, requestId = "timer-stop:${item.id}:${item.version}") } })
            }
        }
    }, bottomBar = {
        if (editor == null && WindowInsets.ime.getBottom(LocalDensity.current) == 0) TimeAgentNavigation(tab, onSelect = {
            if (it == tab && extraScreen == null && it == 0) model.currentWeek()
            navigator.selectTab(it)
        }) {
            HomeVoiceDock(enabled = !busy && !data.loading && !chat.busy,
                onRecognized = ::sendAssistant, onMessage = { voiceMessage = it },
                onTextEntry = { voiceSheetOpen = true }, onOpenPanel = { voiceSheetOpen = true },
                state = voiceControl.copy(finish = { voiceControl.finish(); voiceSheetOpen = true }))
        }
    }, floatingActionButton = {
        if (editor == null && extraScreen == null && tab == 2) com.timeagent.ui.components.PrimaryFab("新建", { addMenu = true })
    }) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            AppNavHost(navigator, motionMillis = com.timeagent.ui.design.LocalPresentation.current.motionMillis, rootContent = { rootTab, reselectRevision ->
            when (rootTab) {
                0 -> screenState.SaveableStateProvider("timeline") {
                    TimelineQueryContent(model, week, !data.loading) { timelineData, reportRange ->
                        MinuteClockContent(model) { now ->
                            val briefingSync = briefingHealthStatus(healthSettings.enabled() && (healthSettings.sleepEnabled() || healthSettings.exerciseEnabled()), healthSettings.readReport(), zone, now)
                            TimelineScreen(timelineData, week, now, zone, { selected = it }, model::changeWeek,
                                onCreate = { addMenu = true }, onAssistant = { voiceSheetOpen = true },
                                onUndo = { model.perform { model.repository.undoLastAction() } },
                                canUndo = data.history.any { !it.undone }, busy = busy || data.loading || timelineData.loading,
                                onVisibleRange = reportRange,
                                onVisibleDate = model::reportVisibleDate, navigationRevision = timelineNavigationRevision,
                                navigationOwner = model.timelineNavigationOwner,
                                onCreateAt = { model.dismissError(); editor = EditorRequest(EditorKind.PLAN, selection = it, scheduleRange = true) }, themeMode = themeMode, onThemeMode = onThemeMode,
                                onRedo = { model.perform { model.repository.redoLastAction() } },
                                canRedo = data.history.any { it.undone && it.redoable },
                                onReminder = { reminderDetail = it },
                                onDragCommit = { item, result ->
                                    model.perform {
                                        when (val entity = item.entity) {
                                            is ScheduleBlockEntity -> model.repository.savePlan(
                                                entity.copy(startAt = result.startAt, endAt = result.endAt),
                                                expectedVersion = entity.version,
                                                requestId = "timeline-drag:${entity.id}:${entity.version}:${result.startAt}:${result.endAt}",
                                            )
                                            is TimeRecordEntity -> model.repository.saveRecord(
                                                entity.copy(startAt = result.startAt, endAt = result.endAt),
                                                expectedVersion = entity.version,
                                                requestId = "timeline-drag:${entity.id}:${entity.version}:${result.startAt}:${result.endAt}",
                                            )
                                        }
                                    }
                                },
                                briefing = {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.weight(1f)) {
                                            if (fish != null) {
                                                val daily = remember(data, now.epochSecond / 60, zone, briefingSync, workouts) {
                                                    buildDailyBriefing(data, now, zone, briefingSync, workouts)
                                                }
                                                FishButlerSummary(fish, if (data.loading) "正在读取今天的安排" else collapsedSummary(daily, zone), onOpen = { openScreen("fish-chat") })
                                            } else briefingContent()
                                        }
                                        TodayQuickActions(
                                            onRecord = { editor = EditorRequest(EditorKind.RECORD) },
                                            onRecurring = { openScreen("recurring") },
                                            onDeadline = { openScreen("deadlines") },
                                            onWorkout = { openScreen("workout") },
                                        )
                                    }
                                    PendingPaymentCard(pendingLedgerCount, onOpen = { openScreen("ledger") })
                                    ledgerReadError?.let {
                                        TextButton(onClick = { openScreen("ledger") }) { Text(it) }
                                    }
                                    workoutReadError?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
                                })
                        }
                    }
                }
                1 -> PlanHubScreen(data, zone, { selected = it }, {
                    editor = EditorRequest(EditorKind.TASK, title = quickTaskTitle.trim().takeIf(String::isNotBlank))
                    quickTaskTitle = ""
                }, ::startTimer,
                    onToggle = { task ->
                        model.perform {
                            model.repository.saveTask(
                                task.copy(status = if (task.status == "COMPLETED") "TODO" else "COMPLETED"),
                                task.version,
                            )
                        }
                    },
                    onSchedule = { task -> editor = EditorRequest(EditorKind.PLAN, taskId = task.id, scheduleRange = true) },
                    onDelete = { task -> deletingTask = task },
                    onQuickPreview = { text -> quickTaskTitle = text },
                    onOpen = { openScreen(it) }, reselectRevision = reselectRevision,
                    scheduleContent = { PlanScheduleOverview(data, model.repository.now(), zone) { selected = it } })
                2 -> Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { openScreen("health") }) { Text("健康与训练") }
                        TextButton(onClick = { openScreen("history") }) { Text("历史明细") }
                    }
                    MinuteClockContent(model) { now ->
                        ReviewScreen(data, reviewWeek, now, zone, ::openOriginal, { openScreen("goals") }, reselectRevision)
                    }
                }
                3 -> ButlerHub(::openScreen, reselectRevision, pendingLedgerCount, ledgerReadError)
            }
            }, pageContent = { destination ->
            when (destination) {
        "fish-chat" -> if (fish != null) com.timeagent.fish.FishChatScreen(fish,
            onSettings = { assistantSettingsOpen = true }, onCompanion = { openScreen("fish-companion") },
            requestInput = assistantExpanded, onInputFocused = { assistantExpanded = false }, briefing = briefingContent)
            else TextButton(onClick = { assistantSettingsOpen = true }) { Text("AI 与本地输入设置") }
        "notification-settings" -> NotificationSettingsScreen(::closeScreen)
        "calendar-settings" -> CalendarSettingsScreen(::closeScreen)
        "about" -> AboutScreen(::closeScreen)
        "ai-models" -> Column { TextButton(onClick = ::closeScreen) { Text("返回设置") }; TextButton(onClick = { assistantSettingsOpen = true }) { Text("配置模型与自带 Key") } }
        "fish-memory" -> fish?.let { com.timeagent.fish.FishMemoryScreen(it,onClose=::closeScreen) }
        "fish-platforms" -> fish?.let { com.timeagent.fish.FishPlatformScreen(it,onClose=::closeScreen) }
        "fish-proactive" -> fish?.let { com.timeagent.fish.FishProactiveScreen(it,onClose=::closeScreen) }
        "fish-companion" -> fish?.let { com.timeagent.fish.FishCharacterHub(it,onOpen=::openScreen,onClose=::closeScreen) }
        "fish-profile" -> fish?.let { com.timeagent.fish.FishProfileScreen(it,onClose=::closeScreen) }
        "fish-wardrobe" -> fish?.let { com.timeagent.fish.FishWardrobeScreen(it,onClose=::closeScreen) }
        "fish-actions" -> fish?.let { com.timeagent.fish.FishActionsScreen(it,onClose=::closeScreen) }
        "fish-voice" -> fish?.let { com.timeagent.fish.FishVoiceScreen(it,onClose=::closeScreen) }
        "fish-appearance" -> fish?.let { com.timeagent.fish.FishCompanionSettingsScreen(it,onClose=::closeScreen) }
        "fish-runtime" -> fish?.let { com.timeagent.fish.FishRuntimeSettingsScreen(it,onModel={assistantSettingsOpen=true},onClose=::closeScreen) }
        "appearance" -> AppearanceScreen(onClose = ::closeScreen)
        "settings" -> SettingsHub(::openScreen, ::closeScreen)
        "tools" -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TextButton(onClick = ::closeScreen) { Text("返回管家") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { context.startActivity(android.content.Intent(context, com.timeagent.organization.OrganizationActivity::class.java)) }) { Text("分类、标签与记录整理") }
                TextButton(onClick = { openScreen("ledger") }) { Text("支付记账") }
            }
            SettingsScreen(data, busy,
                { filePicker.launch(arrayOf("application/json", "text/*")) }, { editor = EditorRequest(EditorKind.COURSE, it) },
                onPdfImport = { openScreen("import") }, onActivities = { openScreen("activities") }, onAssistant = { assistantSettingsOpen = true },
                onHealthSync = { openScreen("health") }, onWorkout = { openScreen("workout") }, onBackup = { openScreen("backup") },
                onMemory = { openScreen("memory") }, onHistory = { openScreen("history") }, onExtract = { extractionText = ""; openScreen("extract") })
        }
        "tasks" -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TextButton(onClick = ::closeScreen) { Text("返回计划") }
            TaskScreen(data, zone, { selected = it }, { editor = EditorRequest(EditorKind.TASK) }, ::startTimer,
                onInbox = { openScreen("inbox") }, onProjects = { openScreen("projects") }, onRecurring = { openScreen("recurring") },
                onDeadlines = { openScreen("deadlines") }, onAttachments = { openScreen("attachments") },
                onReply = { extractionText = ""; openScreen("reply") }, onWorkout = { openScreen("workout") })
        }
        "attachments" -> AttachmentScreen(model, onClose = ::closeScreen)
        "calendar" -> CalendarHandoffScreen(calendarTitle, calendarStart, calendarEnd, onClose = ::closeScreen)
        "deadlines" -> DeadlineScreen(data,model,onTask={selected=it},onClose=::closeScreen)
        "recurring" -> RecurringScreen(data,model,onTask={selected=it},onClose=::closeScreen)
        "projects" -> MinuteClockContent(model) { now ->
            ProjectScreen(data, model, now, zone, onTask = { selected = it }, onClose = ::closeScreen,
                onSchedule = { editor = EditorRequest(EditorKind.PLAN, taskId = it.id, scheduleRange = true) })
        }
        "goals" -> MinuteClockContent(model) { now -> WeeklyGoalsScreen(data,model,now,zone,onClose=::closeScreen) }
        "courses" -> CourseScheduleScreen(
            data = data,
            zone = zone,
            onImport = { openScreen("import") },
            onSelect = { occurrence ->
                selected = data.timeline(model.clock.now()).firstOrNull { it.id == occurrence.id }
            },
            onClose = ::closeScreen,
        )
        "import" -> CourseImportScreen(model, onClose = ::closeScreen)
        "activities" -> ActivityTemplatesScreen(
            model,
            onUse = { activity -> editor = EditorRequest(EditorKind.TASK, activityId = activity.id) },
            onClose = ::closeScreen,
            onNote = { activity -> openNote("ACTIVITY", activity.id, activity.fullName(data.activities)) },
        )
        "inbox" -> InboxScreen(
            model, data, zone, busy,
            onScheduleIdea = { idea ->
                editor = EditorRequest(EditorKind.REMINDER, ideaId = idea.id, ideaVersion = idea.version, title = idea.title)
            },
            onEditReminder = { reminder -> editor = EditorRequest(EditorKind.REMINDER, entity = reminder) },
            onNewReminder = { editor = EditorRequest(EditorKind.REMINDER) },
            onClose = ::closeScreen,
            onOpenTask = { id -> selected = data.tasks.firstOrNull { it.id == id } },
            onConvertIdea = { idea, target ->
                inboxConversionJson = com.google.gson.Gson().toJson(idea)
                inboxConversionTarget = target
            },
        )
        "health" -> HealthSyncScreen(model, healthReader, healthSettings, zone, onClose = ::closeScreen)
        "workout" -> WorkoutNavScreen(workoutsRepository, onClose = ::closeScreen)
        "ledger" -> LedgerScreen(
            repository = ledgerRepository,
            initialCaptureId = null,
            initialIgnore = false,
            onClose = ::closeScreen,
        )
        "backup" -> BackupScreen(model, onClose = ::closeScreen)
        "memory" -> PersonalMemoryScreen(model, onClose = ::closeScreen, onOpenRecord = { openOriginal("RECORD", it) })
        "history" -> HistoryScreen(model, onClose = ::closeScreen, onOpen = ::openOriginal,
            onAskFish = { text -> openScreen("fish-chat"); editAssistantDraft(text) })
        "extract" -> ChatExtractionScreen(model, extractionText, onReply = { extractionText = it; openScreen("reply") }, onClose = ::closeScreen, onOpenSettings = { assistantSettingsOpen = true })
        "reply" -> ReplyCopilotScreen(model, extractionText, onClose = ::closeScreen, onOpenSettings = { assistantSettingsOpen = true })
        else -> if (destination.startsWith("workout:")) WorkoutNavScreen(workoutsRepository,
            initialSessionId = destination.removePrefix("workout:"), onClose = ::closeScreen)
        else if (destination.startsWith("project:")) MinuteClockContent(model) { now -> ProjectScreen(data, model, now, zone,
            onTask = { selected = it }, onClose = ::closeScreen, initialProjectId = destination.removePrefix("project:"),
            onSchedule = { editor = EditorRequest(EditorKind.PLAN, taskId = it.id, scheduleRange = true) }) }
            }
            })
            if (data.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (fish != null && editor == null && WindowInsets.ime.getBottom(LocalDensity.current) == 0) {
                com.timeagent.fish.FishCompanionOverlay(fish, onOpenChat = { openScreen("fish-chat"); assistantExpanded = true },
                    onSettings = { openScreen("fish-companion") })
            }
        }
    }
    BackHandler(enabled = !voiceSheetOpen && editor == null && selected == null && extraScreen == null && tab != 0) {
        navigator.resetToToday()
    }
    if (voiceSheetOpen) VoiceSheet(chat, voiceText, ::editAssistantDraft, ::sendAssistant, voiceControl,
        liveVoiceText, voiceSuggestion, { voiceSuggestion?.let(::sendAssistant); voiceSuggestion = null },
        onClose = { voiceControl.cancel(); voiceSheetOpen = false }, onSettings = { assistantSettingsOpen = true }, fish = fish)
    if (assistantSettingsOpen) ConversationSettingsDialog(assistantSettings, runtimeMode = fish != null, onSaved = { fish?.settingsSaved() }) { assistantSettingsOpen = false }

    if (addMenu) AlertDialog(onDismissRequest = { addMenu = false }, title = { Text("留下一段时间") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text("常用活动", style = MaterialTheme.typography.labelLarge)
            data.activities.filter { it.parentId == null && it.archivedAt == null }.forEach { parent ->
                TextButton(onClick = { addMenu = false; editor = EditorRequest(EditorKind.TASK, activityId = parent.id) }) { Text(parent.name) }
                data.activities.filter { it.parentId == parent.id && it.archivedAt == null }.chunked(2).forEach { children ->
                    Row { children.forEach { child -> TextButton(onClick = { addMenu = false; editor = EditorRequest(EditorKind.TASK, activityId = child.id) }) { Text(child.name) } } }
                }
            }
            TextButton(onClick = { addMenu = false; openScreen("activities") }) { Text("管理活动模板") }
            HorizontalDivider()
            TextButton(onClick = { addMenu = false; editor = EditorRequest(EditorKind.TASK) }) { Text("新建任务") }
            TextButton(onClick = { addMenu = false; editor = EditorRequest(EditorKind.PLAN) }) { Text("安排计划") }
            TextButton(onClick = { addMenu = false; editor = EditorRequest(EditorKind.RECORD) }) { Text("补录时间") }
            TextButton(onClick = { addMenu = false; startTimer(null) }) { Text("开始自由计时") }
            TextButton(onClick = { addMenu = false; openScreen("workout") }, modifier = Modifier.testTag("start-workout-entry")) { Text("开始健身训练") }
        }
    }, confirmButton = { TextButton(onClick = { addMenu = false }) { Text("关闭") } })

    val pendingEditors = editorDrafts.values.filter { it.first == tab }.map { it.second }
    if (editor == null && pendingEditors.isNotEmpty()) androidx.compose.ui.window.Popup(alignment = Alignment.BottomCenter) {
        Surface(Modifier.padding(bottom = 100.dp), shadowElevation = 6.dp) {
            Column { pendingEditors.forEachIndexed { index, draft ->
                TextButton(onClick = { editor = draft }) {
                    Text(if (pendingEditors.size == 1) "继续编辑草稿" else "继续编辑${draft.kind.title}草稿 ${index + 1}")
                }
            } }
        }
    }
    inboxConversionJson?.let { raw ->
        InboxConversionScreen(
            model, com.google.gson.Gson().fromJson(raw, IdeaEntity::class.java),
            inboxConversionTarget, ledgerRepository,
            onClose = { inboxConversionJson = null },
            onManageLedger = { inboxConversionJson = null; openScreen("ledger") },
        )
    }
    editor?.let { request ->
        editorState.SaveableStateProvider(request.id) {
        MinuteClockContent(model) { now ->
            EditorScreen(request, data, now, zone, busy, { editorDrafts.remove(request.id); editorState.removeState(request.id); editor = null }, onSave = { values ->
                model.perform(onSuccess = { editorDrafts.remove(request.id); editorState.removeState(request.id); editor = null; selected = null }) {
                    when (values.kind ?: request.kind) {
                        EditorKind.TASK -> {
                            val before = request.entity as? TaskEntity
                            val candidate = (before ?: TaskEntity(request.id, values.title)).copy(title = values.title, description = values.notes, estimatedMinutes = values.estimatedMinutes, priority = values.priority, dueAt = values.dueAt, categoryId = values.categoryId, activityId = values.activityId)
                            if (request.scheduleNewTask) model.repository.saveTaskAndPlan(candidate, "${request.id}:plan", TimeSpan(Instant.ofEpochMilli(values.start), Instant.ofEpochMilli(values.end)))
                            else model.repository.saveTask(candidate, before?.version)
                        }
                        EditorKind.PLAN -> {
                            if (values.scheduleNewTask) {
                                val task = TaskEntity(request.id, values.title, description = values.notes, estimatedMinutes = values.estimatedMinutes,
                                    priority = values.priority, dueAt = values.dueAt, categoryId = values.categoryId, activityId = values.activityId)
                                model.repository.saveTaskAndPlan(task, "${request.id}:plan", TimeSpan(Instant.ofEpochMilli(values.start), Instant.ofEpochMilli(values.end)))
                            } else {
                                val before = request.entity as? ScheduleBlockEntity
                                val title = if (before?.title == "" && values.taskId != null && values.taskId == before.taskId && values.title == data.tasks.firstOrNull { it.id == before.taskId }?.title) "" else values.title
                                model.repository.savePlan((before ?: ScheduleBlockEntity(request.id, values.taskId, values.start, values.end)).copy(taskId = values.taskId, title = title, notes = values.notes, startAt = values.start, endAt = values.end,
                                    allDayStartDate = values.allDayStartDate, allDayEndDate = values.allDayEndDate), before?.version, requestId = "editor:${request.id}")
                            }
                        }
                        EditorKind.RECORD -> {
                            val before = request.entity as? TimeRecordEntity
                            val notes = if (request.selection != null && values.title.isNotBlank() && values.notes.lineSequence().firstOrNull()?.trim() != values.title)
                                listOf(values.title, values.notes.trim()).filter { it.isNotBlank() }.joinToString("\n") else values.notes
                            model.repository.saveRecord((before ?: TimeRecordEntity(request.id, values.taskId, values.start, values.end)).copy(taskId = values.taskId, startAt = values.start, endAt = values.end, title = values.title, notes = if (request.selection == null) values.notes else notes, categoryId = values.categoryId), values.allowFuture, before?.version, requestId = "editor:${request.id}")
                        }
                        EditorKind.COURSE -> {
                            val before = request.entity as CourseOccurrenceEntity
                            model.repository.saveOccurrence(before.copy(date = Instant.ofEpochMilli(values.start).atZone(zone).toLocalDate().toString(), startAt = values.start, endAt = values.end, locationOverride = values.location, teacherOverride = values.teacher), before.version)
                        }
                        EditorKind.REMINDER -> {
                            val before = request.entity as? ReminderEntity
                            model.repository.saveReminder((before ?: ReminderEntity(request.id, values.title, values.start)).copy(title = values.title, remindAt = values.start), before?.version)
                        }
                    }
                }
            }, onSaveReminder = { before, title, at ->
                val newReminder = before == null
                model.perform(onSuccess = {
                    editorDrafts.remove(request.id)
                    editorState.removeState(request.id)
                    editor = null
                    selected = null
                    reminderDetail = null
                    if (newReminder) askNotificationPermission()
                }) {
                    val ideaId = request.ideaId
                    if (ideaId != null) {
                        model.repository.scheduleIdeaAsReminder(
                            ideaId,
                            requireNotNull(request.ideaVersion) { "想法版本已失效，请重新打开" },
                            ReminderEntity(request.id, title, at),
                        )
                    } else {
                        model.repository.saveReminder(
                            (before ?: ReminderEntity(request.id, title, at)).copy(title = title, remindAt = at),
                            before?.version,
                        )
                    }
                }
            }, saveError = error, onKeepDraft = { editorDrafts[request.id] = tab to request; editor = null })
        }
        }
    }

    selected?.let { selection ->
        MinuteClockContent(model) { now ->
            ModalBottomSheet(onDismissRequest = { if (!busy) selected = null }) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (selection) {
                        is TaskEntity -> {
                            Text(selection.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("task-detail-title"))
                            Text("预计 ${selection.estimatedMinutes.toLong().minutesLabel()} · ${listOf("低", "中", "高")[selection.priority]}优先级 · ${if (selection.status == "COMPLETED") "已完成" else "待完成"}")
                            TimelineColorChoice(selection.timelineColorSubject(), selection.title)
                            selection.dueAt?.let { Text("截止 ${Instant.ofEpochMilli(it).label(zone)}") }
                            if (selection.description.isNotBlank()) Text(selection.description)
                            TextButton(onClick = { selected = null; openScreen("attachments") }) { Text("附件收件箱 · 查看或关联资料") }
                            Text("${data.plans.count { it.taskId == selection.id }} 个计划块 · ${data.records.count { it.taskId == selection.id }} 条实际记录", style = MaterialTheme.typography.bodySmall)
                            data.plans.filter { it.taskId == selection.id }.forEach { item -> TextButton(onClick = { selected = data.timeline(now).first { it.id == item.id } }) { Text("计划 ${Instant.ofEpochMilli(item.startAt).label(zone)}") } }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { startTimer(selection.id); selected = null }, enabled = !busy) { Text("开始计时") }
                                OutlinedButton(onClick = { editor = EditorRequest(EditorKind.PLAN, taskId = selection.id, scheduleRange = true); selected = null }) { Text("安排时间") }
                            }
                            Row {
                                TextButton(onClick = { editor = EditorRequest(EditorKind.TASK, selection); selected = null }) { Text("编辑任务") }
                                TextButton(onClick = { openNote("TASK", selection.id, selection.title) }, modifier = Modifier.testTag("task-note-entry")) { Text("记笔记") }
                                TextButton(onClick = { model.perform(onSuccess = { selected = null }) { model.repository.saveTask(selection.copy(status = if (selection.status == "COMPLETED") "TODO" else "COMPLETED"), selection.version) } }, enabled = !busy) { Text(if (selection.status == "COMPLETED") "重新打开" else "标记完成") }
                                TextButton(onClick = { deletingTask = selection }) { Text("删除") }
                            }
                        }
                        is TimelineItem -> {
                            Text(selection.kind.label, color = selection.kind.color(), style = MaterialTheme.typography.labelLarge)
                            Text(selection.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("${selection.span.start.label(zone)} — ${selection.span.end.label(zone)}")
                            TimelineColorChoice(selection.colorSubject, selection.title)
                            Text("时长 ${java.time.Duration.between(selection.span.start, selection.span.end).toMinutes().minutesLabel()}")
                            if (selection.subtitle.isNotBlank()) Text(selection.subtitle)
                            if (selection.kind == ItemKind.PLAN || selection.kind == ItemKind.COURSE) OutlinedButton(onClick = {
                                calendarTitle = selection.title; calendarStart = selection.span.start.toEpochMilli(); calendarEnd = selection.span.end.toEpochMilli()
                                selected = null; openScreen("calendar")
                            }) { Text("交给系统日历确认添加") }
                            (selection.entity as? TimeRecordEntity)?.takeIf { it.source == "WORKOUT" && it.id.startsWith("workout:") }?.let { record ->
                                Button(onClick = { selected = null; openScreen("workout:${record.id.removePrefix("workout:")}") }) { Text("查看完整训练记录") }
                            }
                            val overlaps = data.timeline(now).filter { it.id != selection.id && (it.kind == ItemKind.RECORD) == (selection.kind == ItemKind.RECORD) && selection.span.overlaps(it.span) }
                            if (overlaps.isNotEmpty()) Text((if (selection.kind == ItemKind.RECORD) "与实际记录重叠：" else "时间冲突：") + overlaps.joinToString("、") { it.title }, color = MaterialTheme.colorScheme.error)
                            if (selection.entity is CourseOccurrenceEntity) {
                                val item = selection.entity
                                val definition = data.courses.firstOrNull { it.id == item.courseId }
                                Text("教师：${item.teacherOverride ?: definition?.teacher.orEmpty()}")
                                Text("来源：${definition?.sourceId.orEmpty()}${if (item.isManuallyModified) " · 已手工修改" else ""}", style = MaterialTheme.typography.bodySmall)
                            }
                            Row {
                                val editable = (selection.entity as? TimeRecordEntity)?.endAt != null || selection.kind != ItemKind.RECORD
                                val noteType = when (selection.entity) { is ScheduleBlockEntity -> "PLAN"; is TimeRecordEntity -> "RECORD"; is CourseOccurrenceEntity -> "OCCURRENCE"; else -> null }
                                if (noteType != null) TextButton(onClick = { openNote(noteType, selection.id, selection.title) }) { Text("记笔记") }
                                TextButton(onClick = { editor = EditorRequest(when (selection.kind) { ItemKind.COURSE -> EditorKind.COURSE; ItemKind.PLAN -> EditorKind.PLAN; ItemKind.RECORD -> EditorKind.RECORD }, selection.entity); selected = null }, enabled = editable) { Text("修改") }
                                TextButton(onClick = { val requestId = UUID.randomUUID().toString(); confirmation = "确认${if (selection.kind == ItemKind.COURSE) "取消这一次课程" else "删除此时间块"}？可以撤销恢复。" to {
                                    model.perform(onSuccess = { selected = null }) { when (val item = selection.entity) { is ScheduleBlockEntity -> model.repository.deletePlan(item.id, item.version, requestId); is TimeRecordEntity -> model.repository.deleteRecord(item.id, item.version, requestId); is CourseOccurrenceEntity -> model.repository.saveOccurrence(item.copy(status = "CANCELLED"), item.version) } }
                                } }, enabled = editable && !busy) { Text(if (selection.kind == ItemKind.COURSE) "取消本次课程" else "删除") }
                            }
                        }
                    }
                }
            }
        }
    }
    preview?.let { value ->
        val requestId = remember(value) { UUID.randomUUID().toString() }
        AlertDialog(onDismissRequest = { if (!busy) preview = null }, title = { Text("课表导入预览") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(value.bundle.term.name); Text("${value.bundle.term.firstWeekStart} 起 · ${value.bundle.term.teachingWeeks} 周 · ${value.bundle.term.zone.id}", style = MaterialTheme.typography.bodySmall)
                Text("新增 ${value.newCount} 次课程 · 已存在 ${value.duplicateCount} 次 · 手工修改保护 ${value.protectedCount} 次", fontWeight = FontWeight.SemiBold)
                value.bundle.courses.forEach { Text("${it.name} · ${it.location}", style = MaterialTheme.typography.bodySmall) }
                Text("确认后写入本机。重复实例跳过，保留手工修改。", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { model.perform(onSuccess = { preview = null; navigator.resetToToday() }) { model.repository.commitImport(value, requestId) } }, enabled = !busy) { Text("确认导入") } }, dismissButton = { TextButton(onClick = { preview = null }, enabled = !busy) { Text("取消") } })
    }
    workoutSwitch?.let { target ->
        val hasSets = WorkoutRules.summary(target.session, model.clock.now().toEpochMilli()).completedSets > 0
        AlertDialog(onDismissRequest = { workoutSwitch = null },
            title = { Text("切换主计时？") },
            text = { Text(if (hasSets) "结束当前训练并保存实际记录，再开始计时。" else "当前训练还没有完成的组，放弃本次空训练后再开始计时。") },
            confirmButton = { TextButton(onClick = {
                model.perform(onSuccess = { workoutSwitch = null }) {
                    workoutsRepository.switchToTimer(target.session.id, target.session.updatedAt, target.taskId,
                        target.taskVersion, target.requestId)
                }
            }, enabled = !busy) { Text(if (hasSets) "结束并开始" else "放弃空训练并开始") } },
            dismissButton = { TextButton(onClick = { workoutSwitch = null }) { Text("取消") } })
    }
    switchTask?.let { target -> AlertDialog(onDismissRequest = { switchTask = null }, title = { Text("切换计时任务？") }, text = { Text("先结束当前计时，再开始新任务。已有记录会保留。") }, confirmButton = { TextButton(onClick = { model.perform(onSuccess = { switchTask = null }) { model.repository.startTimer(target.first, target.second, switchExisting = true) } }, enabled = !busy) { Text("结束并开始") } }, dismissButton = { TextButton(onClick = { switchTask = null }) { Text("取消") } }) }
    deletingTask?.let { task ->
        fun delete(action: LinkedScheduleAction) {
            model.perform(onSuccess = { deletingTask = null; selected = null }) { model.repository.deleteTask(task.id, action, task.version) }
        }
        AlertDialog(onDismissRequest = { if (!busy) deletingTask = null }, title = { Text("删除任务") },
            text = { Text("关联日程如何处理？实际记录始终保留，删除操作可撤销。") },
            confirmButton = { Column {
                TextButton(onClick = { delete(LinkedScheduleAction.KEEP_INDEPENDENT) }, enabled = !busy) { Text("保留为独立日程") }
                TextButton(onClick = { delete(LinkedScheduleAction.DELETE) }, enabled = !busy) { Text("同时删除关联日程") }
            } }, dismissButton = { TextButton(onClick = { deletingTask = null }, enabled = !busy) { Text("取消") } })
    }
    confirmation?.let { (message, action) -> AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("确认操作") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { confirmation = null; action() }, enabled = !busy) { Text("确认") } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } }) }
    error?.let { message -> AlertDialog(onDismissRequest = model::dismissError, title = { Text("操作未完成") }, text = { Text(message) }, confirmButton = { TextButton(onClick = model::dismissError) { Text("知道了") } }) }

    val targetType = noteType
    val targetId = noteId
    if (targetType != null && targetId != null) {
        val key = "$targetType:$targetId"
        val target = NoteTarget(targetType, targetId, noteTitle)
        noteState.SaveableStateProvider(key) {
            NoteEditorScreen(model, data, target, onClose = {
                noteDrafts.remove(key); noteState.removeState(key); closeNote()
            }, onKeepDraft = { noteDrafts[key] = tab to target; closeNote() })
        }
    } else if (noteDrafts.values.any { it.first == tab }) {
        androidx.compose.ui.window.Popup(alignment = Alignment.BottomCenter) {
            Surface(Modifier.padding(bottom = 160.dp), shadowElevation = 6.dp) {
                Column { noteDrafts.values.filter { it.first == tab }.forEach { (_, target) ->
                    TextButton(onClick = { openNote(target.type, target.id, target.title) }) { Text("继续编辑笔记草稿 · ${target.title}") }
                } }
            }
        }
    }
    reminderDetail?.let { reminder ->
        ReminderDetailSheet(
            reminder = reminder,
            zone = zone,
            onEdit = { reminderDetail = null; editor = EditorRequest(EditorKind.REMINDER, entity = reminder) },
            onToggle = {
                reminderDetail = null
                model.perform { model.repository.saveReminder(reminder.copy(enabled = !reminder.enabled), reminder.version) }
            },
            onDelete = {
                reminderDetail = null
                confirmation = "删除后可以撤销恢复。" to { model.perform { model.repository.deleteReminder(reminder.id, reminder.version) } }
            },
            onClose = { reminderDetail = null },
            onSnooze = { snoozeReminderId = reminder.id; reminderDetail = null },
            onRepeat = { repeatReminderId = reminder.id; reminderDetail = null },
        )
    }
    snoozeReminderId?.let { id ->
        data.reminders.firstOrNull { it.id == id }?.let { reminder ->
            com.timeagent.reminders.ReminderSnoozeSheet(
                reminder.title, onDismiss = { snoozeReminderId = null },
                now = model.repository.now(), zone = zone,
                onSnooze = { at -> model.perform(onSuccess = { snoozeReminderId = null }) {
                    com.timeagent.reminders.ReminderInstanceRepository(model.repository).snooze(id, at, reminder.version)
                } },
            )
        }
    }
    repeatReminderId?.let { id ->
        data.reminders.firstOrNull { it.id == id }?.let { reminder ->
            ReminderRepeatDialog(model, reminder, onClose = { repeatReminderId = null })
        }
    }
}

@Composable private fun TaskScreen(data: LocalSnapshot, zone: ZoneId, onSelect: (TaskEntity) -> Unit, onCreate: () -> Unit, onStart: (String?) -> Unit, onInbox: () -> Unit, onProjects: () -> Unit, onRecurring: () -> Unit, onDeadlines: () -> Unit, onAttachments: () -> Unit, onReply: () -> Unit, onWorkout: () -> Unit) {
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    val tasks = data.tasks.filter { it.deletedAt == null && (if (showCompleted) it.status == "COMPLETED" else it.status != "COMPLETED" && it.status != "CANCELLED") }.sortedWith(compareByDescending<TaskEntity> { it.priority }.thenBy { it.dueAt ?: Long.MAX_VALUE })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("先决定要做什么，再为它留出时间。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick=onDeadlines,modifier=Modifier.fillMaxWidth().testTag("task-deadline-entry")) { Text("生活期限 · 续费 / 归还 / 生日") }
        OutlinedButton(onClick=onReply,modifier=Modifier.fillMaxWidth().testTag("task-reply-entry")) { Text("回复副驾 · 结合空档起草") }
        OutlinedButton(onClick=onAttachments,modifier=Modifier.fillMaxWidth().testTag("task-attachments-entry")) { Text("附件收件箱 · 文件与文字") }
        OutlinedButton(onClick = onRecurring,modifier=Modifier.fillMaxWidth().testTag("task-recurring-entry")) { Text("周期任务与习惯") }
        OutlinedButton(onClick = onProjects, modifier = Modifier.fillMaxWidth().testTag("task-projects-entry")) { Text("项目与子任务 · ${data.projects.count { it.deletedAt == null }} 个项目") }
        Surface(onClick = onInbox, color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("task-inbox-entry")) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("未安排", fontWeight = FontWeight.SemiBold)
                    Text(
                        "${data.ideas.count { it.deletedAt == null && it.status == "UNSCHEDULED" }} 个想法 · ${data.reminders.count { it.enabled }} 个任务点提醒",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(">")
            }
        }
        Surface(onClick = onWorkout, color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("task-workout-entry")) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("健身训练", fontWeight = FontWeight.SemiBold)
                    Text("动作库、训练记录与 3D 肌群", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(">")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(!showCompleted, { showCompleted = false }, label = { Text("待完成") }); FilterChip(showCompleted, { showCompleted = true }, label = { Text("已完成") }) }
        if (tasks.isEmpty()) { EmptyHint(if (showCompleted) "完成的事，会留在这里" else "从一件小事开始", "任务可以关联多个计划块和实际记录。"); OutlinedButton(onClick = onCreate) { Text("创建第一个任务") } }
        tasks.forEach { task -> Surface(onClick = { onSelect(task) }, color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(task.title, fontWeight = FontWeight.SemiBold)
                    Text("${task.estimatedMinutes.toLong().minutesLabel()} · ${listOf("低", "中", "高")[task.priority]}优先级", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    task.dueAt?.let { Text("截止 ${Instant.ofEpochMilli(it).label(zone)}", style = MaterialTheme.typography.labelSmall) }
                }
                IconButton(onClick = { onStart(task.id) }) { Icon(Icons.Rounded.PlayCircleOutline, "开始计时 ${task.title}", tint = RecordGreen) }
            }
        } }
        Spacer(Modifier.height(70.dp))
    }
}

@Composable private fun ReviewScreen(data: LocalSnapshot, week: WeekState, now: Instant, zone: ZoneId, onOpen: (String,String)->Unit, onGoals: ()->Unit, reselectRevision: Int = 0) {
    val scroll = rememberScrollState()
    var consumedReselect by rememberSaveable { mutableIntStateOf(reselectRevision) }
    LaunchedEffect(reselectRevision) {
        if (reselectRevision != consumedReselect) { scroll.scrollTo(0); consumedReselect = reselectRevision }
    }
    val window = week.window(zone)
    val records = data.timeline(now).filter { it.kind == ItemKind.RECORD }
    val stats = TimeStatistics.calculate(records.map { it.span }, window)
    val planned = data.plans.filter { it.deletedAt == null && it.kind != "POINT" }.sumOf { TimeSpan(Instant.ofEpochMilli(it.startAt), Instant.ofEpochMilli(it.endAt)).clip(window)?.seconds ?: 0 } / 60
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("时间留下的痕迹", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        listOf("计划投入" to planned, "累计记录投入" to stats.recordedMinutes, "去重时间跨度" to stats.elapsedMinutes).forEach { (title, value) ->
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) { Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f)); Text(value.minutesLabel(), fontWeight = FontWeight.Bold, color = if (title == "计划投入") Indigo else RecordGreen) } }
        }
        Text("累计投入逐条相加；去重跨度合并重叠区间。两者含义不同，投入差异不代表时间被浪费。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ProjectReviewSection(data,week.selectedWeekStart,now,zone,onOpen,onGoals)
        Text("分类投入", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        (data.categories.map { it.id to it.name } + (null to "未分类")).forEach { (id, name) ->
            val spans = records.filter { (it.entity as TimeRecordEntity).categoryId == id }.map { it.span }
            val minutes = TimeStatistics.calculate(spans, window).recordedMinutes
            if (minutes > 0) { Row { Text(name, Modifier.weight(1f)); Text(minutes.minutesLabel()) }; LinearProgressIndicator(progress = { if (stats.recordedMinutes == 0L) 0f else minutes.toFloat() / stats.recordedMinutes }, modifier = Modifier.fillMaxWidth()) }
        }
        if (stats.recordedMinutes == 0L) Text("还没有本周记录，开始一次计时后回来看看。", style = MaterialTheme.typography.bodySmall)
        Text("近 14 天投入", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        TrendBars(data, now, zone)
        Spacer(Modifier.height(70.dp))
    }
}

@Composable private fun TrendBars(data: LocalSnapshot, now: Instant, zone: ZoneId) {
    val today = now.atZone(zone).toLocalDate()
    val spans = data.records.mapNotNull { it.endAt?.let { end -> TimeSpan(Instant.ofEpochMilli(it.startAt), Instant.ofEpochMilli(end)) } }
    val minutesPerDay = (13 downTo 0).map { offset ->
        val date = today.minusDays(offset.toLong())
        val window = TimeSpan(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
        date to (spans.sumOf { it.clip(window)?.seconds ?: 0L } / 60)
    }
    val maxMinutes = minutesPerDay.maxOfOrNull { it.second } ?: 0L
    Column(Modifier.fillMaxWidth().testTag("review-trend")) {
        if (maxMinutes == 0L) { Text("最近两周还没有记录。", style = MaterialTheme.typography.bodySmall); return }
        val average = minutesPerDay.sumOf { it.second } / 14
        Text("日均 ${average.minutesLabel()} · 最高 ${maxMinutes.minutesLabel()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            minutesPerDay.forEach { (date, minutes) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height((72 * (minutes.toFloat() / maxMinutes)).dp.coerceAtLeast(2.dp)).background(if (date == today) Indigo else RecordGreen.copy(alpha = 0.55f), RoundedCornerShape(3.dp)))
                    Text("${date.monthValue}/${date.dayOfMonth}", fontSize = 8.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable private fun SettingsScreen(data: LocalSnapshot, busy: Boolean, onImport: () -> Unit, onCourse: (CourseOccurrenceEntity) -> Unit, onPdfImport: () -> Unit, onActivities: () -> Unit, onAssistant: () -> Unit, onHealthSync: () -> Unit, onWorkout: () -> Unit, onBackup: () -> Unit, onMemory: () -> Unit, onHistory: () -> Unit, onExtract: () -> Unit) {
    val sampleIds = SampleCourseScope.courses(data.courses).map { it.id }.toSet()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("时间 Agent", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("开发测试版 · ${LocalContext.current.packageManager.getPackageInfo(LocalContext.current.packageName, 0).versionName}", style = MaterialTheme.typography.labelMedium); Text("说出安排，让时间就位。", style = MaterialTheme.typography.bodyMedium) } }
        Text("课程管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Button(onClick = onPdfImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("导入 PDF / 官方教务") }
        Button(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("导入 JSON 课表") }
        Text("PDF 先解析预览，可修正学期、节次和课程行后确认。官方教务入口由你自行登录，读取课表后走同一预览流程。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        data.terms.filterNot { term -> term.id == "example-2026-fall" && data.courses.none { it.termId == term.id && it.id !in sampleIds } }.forEach { Text("${it.name} · ${it.firstWeekStart} 起 · ${it.teachingWeeks} 周", style = MaterialTheme.typography.bodySmall) }
        val cancelled = data.occurrences.filter { it.status == "CANCELLED" && it.courseId !in sampleIds }
        if (cancelled.isNotEmpty()) { Text("已取消课程（可编辑或撤销恢复）", style = MaterialTheme.typography.labelLarge); cancelled.forEach { item -> TextButton(onClick = { onCourse(item) }) { Text("${data.courses.firstOrNull { it.id == item.courseId }?.name} · ${item.date}") } } }
        if (data.imports.isNotEmpty()) Text("最近导入：新增 ${data.imports.first().importedCount} 次，跳过重复 ${data.imports.first().skippedCount} 次", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        OutlinedButton(onClick = onActivities, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("管理活动模板") }
        Button(onClick = onWorkout, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("settings-workout")) { Text("健身训练 · 动作库 / 3D 肌群") }
        OutlinedButton(onClick = onHealthSync, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("settings-health-sync")) { Text("健康数据同步（睡眠 / 健身）") }
        OutlinedButton(onClick = onAssistant, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("语音助手与 DeepSeek 设置") }
        OutlinedButton(onClick = onBackup, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("settings-backup")) { Text("完整本地备份 / 恢复") }
        OutlinedButton(onClick = onMemory, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("settings-memory")) { Text("个人记忆与排程约定") }
        OutlinedButton(onClick = onHistory, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("settings-history")) { Text("历史问答 · 实际 / 训练 / 笔记") }
        OutlinedButton(onClick = onExtract, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("settings-extract")) { Text("从聊天文字提取事项") }
        HorizontalDivider()
        Text("操作历史", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (data.history.isEmpty()) Text("保存、删除与计时操作会留下记录，可用右上角撤销或重做。", style = MaterialTheme.typography.bodySmall)
        data.history.take(12).forEach { item -> Row { Text(item.actionType, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); Text(if (item.undone) "已撤销" else "已完成", style = MaterialTheme.typography.labelSmall) } }
        HorizontalDivider(); Text("数据保存在本机。内置离线中文语音识别，音频不保存也不上传；DeepSeek 仅在你选择发送后理解文字。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
