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
    val taskClassification by model.taskClassification.collectAsStateWithLifecycle()
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
    var focusSwitch by remember { mutableStateOf<Triple<com.timeagent.focus.FocusSessionEntity, String?, String>?>(null) }
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
    val quickModel = remember(model, context) { com.timeagent.quickentry.QuickEntryModel(model.repository, context.applicationContext) }
    var captureTemplateJson by rememberSaveable { mutableStateOf<String?>(null) }
    fun openQuickDraft(draft: com.timeagent.quickentry.QuickEntryDraft) {
        editor = EditorRequest(
            if (draft.action == com.timeagent.quickentry.QuickEntryAction.CREATE_SCHEDULE) EditorKind.PLAN else EditorKind.RECORD,
            taskId = draft.taskId, activityId = draft.activityId, title = draft.title, notes = draft.notes,
            scheduleRange = true, initialStartAt = draft.startAt, initialEndAt = draft.endAt,
            quickEntryId = draft.entryId, quickEntryVersion = draft.entryVersion,
        )
    }
    val onboardingPreference = remember(context) { com.timeagent.onboarding.OnboardingPreference(context) }
    val installedAsUpgrade = remember(context) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.lastUpdateTime > info.firstInstallTime + 1000L
    }
    val existingUser = installedAsUpgrade || data.tasks.isNotEmpty() || data.records.isNotEmpty() || data.projects.isNotEmpty()
    LaunchedEffect(data.loading) {
        if (!data.loading && onboardingPreference.shouldShow(existingUser)) {
            navigator.awaitReady(); navigator.openDeepLink("onboarding")
        }
    }

    var voiceSheetOpen by rememberSaveable { mutableStateOf(false) }
    var snoozeReminderId by rememberSaveable { mutableStateOf<String?>(null) }
    var repeatReminderId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(appDestination) {
        if (appDestination != null) {
            navigator.awaitReady()
            if (appDestination == "voice") {
                voiceSheetOpen = true
            } else if (appDestination == "new-task") {
                editor = EditorRequest(EditorKind.TASK)
            } else if (appDestination == "new-schedule") {
                editor = EditorRequest(EditorKind.PLAN)
            } else if (appDestination.startsWith("quick-entry:")) {
                when (val result = com.timeagent.quickentry.QuickEntryRepository(model.repository, context).activate(appDestination.removePrefix("quick-entry:"))) {
                    is com.timeagent.quickentry.QuickEntryLaunch.OpenEditor -> openQuickDraft(result.draft)
                    is com.timeagent.quickentry.QuickEntryLaunch.StartedFocus -> navigator.open("focus")
                    is com.timeagent.quickentry.QuickEntryLaunch.NeedsConfirmation -> navigator.open("quick-entry-confirm:${result.entry.id}")
                    is com.timeagent.quickentry.QuickEntryLaunch.Failed -> model.report(result.message)
                    else -> navigator.resetToToday()
                }
            } else if (appDestination == "widget-action") {
                model.report(context.getSharedPreferences("widget_status", 0).getString("error", null) ?: "请确认当前计时状态")
                val entryId = context.getSharedPreferences("widget_status", 0).getString("entryId", null)
                navigator.open(if (entryId != null) "quick-entry-confirm:$entryId" else "quick-entries")
            } else if (appDestination == "timer") {
                navigator.resetToToday()
            } else if (appDestination.startsWith("item:")) {
                val parts = appDestination.split(':', limit = 3)
                if (parts.size == 3) selected = when (parts[1]) {
                    "TASK" -> data.tasks.firstOrNull { it.id == parts[2] }
                    "PLAN" -> data.timeline(model.clock.now()).firstOrNull { (it.entity as? ScheduleBlockEntity)?.id == parts[2] }
                    else -> null
                }
            } else if (appDestination.startsWith("reminder-snooze:")) {
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
        healthScope.launch { com.timeagent.timer.TimerNotifications.restoreOnForeground(context)?.let { model.report(it) } }
        (context.applicationContext as com.timeagent.TimeAgentApplication).refreshCalendarPermission()
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
    var timelineDrag by remember { mutableStateOf<TimelineDragRequest?>(null) }
    val timelineSnackbar = remember { SnackbarHostState() }
    var timelineUndo by remember { mutableStateOf<com.timeagent.ui.components.UndoMessage?>(null) }
    fun toggleTask(task: TaskEntity, onSaved: () -> Unit = {}) {
        val completed = task.status == "COMPLETED" || task.status == "DONE"
        val requestId = UUID.randomUUID().toString()
        model.perform(onSuccess = onSaved) {
            val result = model.repository.commands.execute(com.timeagent.domain.command.WriteTaskDetails(
                requestId, task.copy(status = if (completed) "TODO" else "COMPLETED"), task.version))
            check(result.status in setOf(com.timeagent.domain.command.CommandStatus.APPLIED,
                com.timeagent.domain.command.CommandStatus.DUPLICATE)) { result.message }
            result.undoToken?.let { token ->
                timelineUndo = com.timeagent.ui.components.UndoMessage(result.requestId,
                    context.getString(if (completed) com.timeagent.R.string.task_reopened_undo
                        else com.timeagent.R.string.task_completed_undo), token)
            }
        }
    }
    com.timeagent.ui.components.UndoSnackbar(timelineSnackbar, timelineUndo,
        onUndo = { token -> model.perform {
            model.repository.commands.execute(com.timeagent.domain.command.UndoCommand(UUID.randomUUID().toString(), token))
                .also { if (it.status !in setOf(com.timeagent.domain.command.CommandStatus.APPLIED, com.timeagent.domain.command.CommandStatus.DUPLICATE)) error(it.message) }
        } },
        onConsumed = { if (timelineUndo?.id == it) timelineUndo = null })
    var deletingTask by remember { mutableStateOf<TaskEntity?>(null) }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var switchTask by remember { mutableStateOf<Pair<String?, String>?>(null) }
    var reminderDetail by remember { mutableStateOf<ReminderEntity?>(null) }
    LaunchedEffect(reminderDetail?.id, reminderDetail?.version) {
        val reminder = reminderDetail ?: return@LaunchedEffect
        try {
            com.timeagent.reminders.ReminderInstanceRepository(model.repository).act(reminder.id, reminder.version,
                requestId = "reminder-open:${reminder.id}:${reminder.version}")
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) { model.report(error.message ?: "提醒处理状态未保存，请重新打开提醒") }
    }
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
            "FOCUS" -> openScreen("focus-session:$id")
            "HABIT" -> openScreen("habit:$id")
            "TASK" -> data.tasks.find { it.id == id }?.let { selected = it } ?: run { notice = "原任务已被删除" }
            "REMINDER" -> data.reminders.find { it.id == id }?.let { reminderDetail = it } ?: run { notice = "原提醒已被删除" }
            "PROJECT" -> openScreen("project:$id")
            "ACTIVITY" -> openScreen("activities")
            "IDEA" -> openScreen("inbox")
            else -> data.timeline(model.clock.now()).find {
                it.id == id || (it.entity as? TimeRecordEntity)?.id == id
            }?.let { selected = it } ?: run { notice = "原记录已被删除，笔记原文仍保留" }
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
        val activeFocus = data.focusSessions.firstOrNull { it.isActive }
        if (activeFocus != null) focusSwitch = Triple(activeFocus, taskId, requestId)
        else if (activeWorkout != null) workoutSwitch = WorkoutTimerSwitch(activeWorkout, taskId,
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
    val voiceControl = rememberHomeVoiceState(!busy && !data.loading && !chat.busy, ::sendAssistant, { voiceMessage = it },
        { state, text -> voiceStatus = state; liveVoiceText = text }, { voiceSuggestion = it }, speechFactory)
    Scaffold(modifier = Modifier.imePadding(), snackbarHost = { SnackbarHost(timelineSnackbar) }, topBar = {
        if (extraScreen == null && editor == null) Column(Modifier.statusBarsPadding()) {
            if (LocalAppearance.current.modeHintPending) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_001), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { ThemePreference(context).acknowledgeModeHint() }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_acknowledge)) }
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
            chat.localDestination?.let { destination -> TextButton(onClick = { openScreen(destination) }, modifier = Modifier.testTag("assistant-local-result")) {
                Text(when { destination == "history" -> "查看历史结果与原记录"; destination.startsWith("quick-entry") -> "打开快捷记录"; destination == "focus" -> "查看专注"; else -> "查看结果" })
            } }
            if (tab == 2) WeekHeader(reviewWeek, data, { model.changeReviewWeek(-1) }, { model.changeReviewWeek(1) }, model::currentWeek)
            activeWorkout?.let { session ->
                RunningWorkoutBar(session, model.clock, busy, onOpen = { openScreen("workout:${session.id}") })
            }
            com.timeagent.focus.RunningFocusBar(model, onOpen = { openScreen("focus") })
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
                                canUndo = data.history.any { !it.undone }, busy = busy || timelineDrag != null || data.loading || timelineData.loading,
                                onVisibleRange = reportRange,
                                onVisibleDate = model::reportVisibleDate, navigationRevision = timelineNavigationRevision,
                                navigationOwner = model.timelineNavigationOwner,
                                onCreateAt = { model.dismissError(); editor = EditorRequest(EditorKind.PLAN, selection = it, scheduleRange = true) }, themeMode = themeMode, onThemeMode = onThemeMode,
                                onRedo = { model.perform { model.repository.redoLastAction() } },
                                canRedo = data.history.any { it.undone && it.redoable },
                                onReminder = { reminderDetail = it },
                                onSaveTemplate = { captureTemplateJson = com.google.gson.Gson().toJson(it) },
                                onDragCommit = { item, result ->
                                    timelineDrag = TimelineDragRequest.capture(item, result, data)
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
                                    if (data.loading.not()) {
                                        val entries by remember(model) { model.repository.database.quickEntryDao().observeEntries() }.collectAsStateWithLifecycle(emptyList())
                                        if (entries.isNotEmpty()) com.timeagent.quickentry.QuickEntryChips(quickModel,
                                            onOpenManager = { openScreen("quick-entries") }, onOpenFocus = { openScreen("focus") }, onOpenDraft = ::openQuickDraft)
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
                1 -> MinuteClockContent(model) { taskNow -> PlanHubScreen(data, zone, { selected = it }, {
                    editor = EditorRequest(EditorKind.TASK, title = quickTaskTitle.trim().takeIf(String::isNotBlank))
                    quickTaskTitle = ""
                }, ::startTimer,
                    onToggle = { task -> toggleTask(task) },
                    onSchedule = { task -> editor = EditorRequest(EditorKind.PLAN, taskId = task.id, scheduleRange = true) },
                    onDelete = { task -> deletingTask = task },
                    onQuickPreview = { text -> quickTaskTitle = text },
                    tags = taskClassification.tags, taskTagIds = taskClassification.taskTagIds, now = taskNow,
                    onQuickDraft = { draft -> editor = EditorRequest(EditorKind.TASK,
                        title = draft.title, initialDueAt = draft.dueAt, initialProjectId = draft.projectId,
                        initialTagIds = draft.tagIds) },
                    onOpen = { openScreen(it) }, reselectRevision = reselectRevision,
                    scheduleContent = {
                        PlanScheduleContent(model, data, busy || timelineDrag != null, themeMode, onThemeMode,
                            onSelect = { selected = it }, onCreate = { addMenu = true },
                            onCreateAt = { editor = EditorRequest(EditorKind.PLAN, selection = it, scheduleRange = true) },
                            onAssistant = { voiceSheetOpen = true }, onReminder = { reminderDetail = it },
                            onDragCommit = { item, result -> timelineDrag = TimelineDragRequest.capture(item, result, data) },
                            onSaveTemplate = { captureTemplateJson = com.google.gson.Gson().toJson(it) })
                    },
                    moduleContent = { index, onBack ->
                        when (index) {
                            2 -> DeadlineScreen(data, model, onTask = { selected = it }, onClose = onBack,
                                onSchedule = { editor = EditorRequest(EditorKind.PLAN, taskId = it.id, scheduleRange = true) },
                                onToggleTask = { toggleTask(it) })
                            3 -> MinuteClockContent(model) { now ->
                                ProjectScreen(data, model, now, zone, onTask = { selected = it }, onClose = onBack,
                                    onSchedule = { editor = EditorRequest(EditorKind.PLAN, taskId = it.id, scheduleRange = true) })
                            }
                            4 -> RecurringScreen(data, model, onTask = { selected = it }, onClose = onBack)
                        }
                    }) }
                2 -> Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { openScreen("health") }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_002)) }
                        TextButton(onClick = { openScreen("history") }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_003)) }
                        TextButton(onClick = { openScreen("habits") }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_004)) }
                        TextButton(onClick = { openScreen("reports") }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_005)) }
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
            requestInput = assistantExpanded, onInputFocused = { assistantExpanded = false }, briefing = briefingContent,
            onOpenLocalResult = ::openScreen)
            else TextButton(onClick = { assistantSettingsOpen = true }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_006)) }
        "notification-settings" -> NotificationSettingsScreen(::closeScreen)
        "calendar-settings" -> CalendarSettingsScreen(::closeScreen)
        "about" -> AboutScreen(::closeScreen)
        "ai-models" -> Column { TextButton(onClick = ::closeScreen) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_007)) }; TextButton(onClick = { assistantSettingsOpen = true }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_008)) } }
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
        "quick-entries", "quick-recent" -> com.timeagent.quickentry.QuickEntryScreen(quickModel, ::closeScreen, ::openQuickDraft)
        "plan-templates" -> com.timeagent.quickentry.PlanTemplateScreen(quickModel, ::closeScreen)
        "habits" -> HabitPage(model, ::closeScreen, ::openOriginal)
        "reports" -> ReportPage(model, ::closeScreen) { text -> fish?.editDraft(text); openScreen("fish-chat") }
        "export" -> ExportPage(::closeScreen)
        "timer-settings" -> com.timeagent.timer.TimerNotificationSettings(::closeScreen)
        "calendar-sync" -> com.timeagent.calendar.CalendarSyncScreen(model, ::closeScreen)
        "sample-data" -> SamplePage(model, ::closeScreen) { openScreen("onboarding") }
        "onboarding" -> OnboardingPage(model, existingUser, ::closeScreen) { assistantSettingsOpen = true }
        "appearance" -> AppearanceScreen(onClose = ::closeScreen)
        "settings" -> SettingsHub(::openScreen, ::closeScreen)
        "tools" -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TextButton(onClick = ::closeScreen) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_memory_screen_001)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { context.startActivity(android.content.Intent(context, com.timeagent.organization.OrganizationActivity::class.java)) }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_009)) }
                TextButton(onClick = { openScreen("ledger") }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_010)) }
            }
            SettingsScreen(data, busy,
                { filePicker.launch(arrayOf("application/json", "text/*")) }, { editor = EditorRequest(EditorKind.COURSE, it) },
                onPdfImport = { openScreen("import") }, onActivities = { openScreen("activities") }, onAssistant = { assistantSettingsOpen = true },
                onHealthSync = { openScreen("health") }, onWorkout = { openScreen("workout") }, onBackup = { openScreen("backup") },
                onMemory = { openScreen("memory") }, onHistory = { openScreen("history") }, onExtract = { extractionText = ""; openScreen("extract") })
        }
        "tasks" -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TextButton(onClick = ::closeScreen) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_011)) }
            TaskScreen(data, zone, { selected = it }, { editor = EditorRequest(EditorKind.TASK) }, ::startTimer,
                onInbox = { openScreen("inbox") }, onProjects = { openScreen("projects") }, onRecurring = { openScreen("recurring") },
                onDeadlines = { openScreen("deadlines") }, onAttachments = { openScreen("attachments") },
                onReply = { extractionText = ""; openScreen("reply") }, onWorkout = { openScreen("workout") })
        }
        "attachments" -> AttachmentScreen(model, onClose = ::closeScreen)
        "calendar" -> CalendarHandoffScreen(calendarTitle, calendarStart, calendarEnd, onClose = ::closeScreen)
        "deadlines" -> DeadlineScreen(data,model,onTask={selected=it},onClose=::closeScreen,
            onSchedule = { editor = EditorRequest(EditorKind.PLAN, taskId = it.id, scheduleRange = true) },
            onToggleTask = { toggleTask(it) })
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
        "focus" -> com.timeagent.focus.FocusScreen(model, onClose = ::closeScreen)
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
        else -> if (destination.startsWith("report-period:")) ReportPage(model, ::closeScreen,
            destination.removePrefix("report-period:")) { text -> fish?.editDraft(text); openScreen("fish-chat") }
        else if (destination.startsWith("quick-entry-confirm:")) com.timeagent.quickentry.QuickEntryScreen(
            quickModel, ::closeScreen, ::openQuickDraft, initialEntryId = destination.removePrefix("quick-entry-confirm:"))
        else if (destination.startsWith("habit:")) HabitPage(model, ::closeScreen, ::openOriginal,
            initialHabitId = destination.removePrefix("habit:"))
        else if (destination.startsWith("focus-session:")) com.timeagent.focus.FocusSessionDetails(
            data.focusSessions.firstOrNull { it.id == destination.removePrefix("focus-session:") },
            data.loading, zone, ::closeScreen,
            onOpenActive = { openScreen("focus") },
            onOpenActual = { id -> openOriginal("RECORD", id) })
        else if (destination.startsWith("workout:")) WorkoutNavScreen(workoutsRepository,
            initialSessionId = destination.removePrefix("workout:"), onClose = ::closeScreen)
        else if (destination.startsWith("focus-task:")) com.timeagent.focus.FocusScreen(model, onClose = ::closeScreen,
            initialTaskId = destination.removePrefix("focus-task:"))
        else if (destination.startsWith("focus-plan:")) com.timeagent.focus.FocusScreen(model, onClose = ::closeScreen,
            initialScheduleId = destination.removePrefix("focus-plan:"))
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
        onClose = { voiceControl.cancel(); voiceSheetOpen = false }, onSettings = { assistantSettingsOpen = true }, fish = fish,
        onOpenLocalResult = { voiceControl.cancel(); voiceSheetOpen = false; openScreen(it) })
    if (assistantSettingsOpen) ConversationSettingsDialog(assistantSettings, runtimeMode = fish != null, onSaved = { fish?.settingsSaved() }) { assistantSettingsOpen = false }

    if (addMenu) AlertDialog(onDismissRequest = { addMenu = false }, title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_012)) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_013), style = MaterialTheme.typography.labelLarge)
            data.activities.filter { it.parentId == null && it.archivedAt == null }.forEach { parent ->
                TextButton(onClick = { addMenu = false; editor = EditorRequest(EditorKind.TASK, activityId = parent.id) }) { Text(parent.name) }
                data.activities.filter { it.parentId == parent.id && it.archivedAt == null }.chunked(2).forEach { children ->
                    Row { children.forEach { child -> TextButton(onClick = { addMenu = false; editor = EditorRequest(EditorKind.TASK, activityId = child.id) }) { Text(child.name) } } }
                }
            }
            TextButton(onClick = { addMenu = false; openScreen("activities") }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_014)) }
            HorizontalDivider()
            TextButton(onClick = { addMenu = false; editor = EditorRequest(EditorKind.TASK) }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_new_task)) }
            TextButton(onClick = { addMenu = false; editor = EditorRequest(EditorKind.PLAN) }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_015)) }
            TextButton(onClick = { addMenu = false; editor = EditorRequest(EditorKind.RECORD) }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_016)) }
            TextButton(onClick = { addMenu = false; startTimer(null) }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_017)) }
            TextButton(onClick = { addMenu = false; openScreen("focus") }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_focus_screen_006)) }
            TextButton(onClick = { addMenu = false; openScreen("workout") }, modifier = Modifier.testTag("start-workout-entry")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_018)) }
        }
    }, confirmButton = { TextButton(onClick = { addMenu = false }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_close)) } })

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
    captureTemplateJson?.let { raw ->
        val plans = com.google.gson.Gson().fromJson(raw, Array<ScheduleBlockEntity>::class.java).toList()
        com.timeagent.quickentry.CaptureTemplateDialog(model.repository, plans) { captureTemplateJson = null }
    }
    editor?.let { request ->
        editorState.SaveableStateProvider(request.id) {
        MinuteClockContent(model) { now ->
            EditorScreen(request, data, now, zone, busy, { editorDrafts.remove(request.id); editorState.removeState(request.id); editor = null }, onSave = { values ->
                model.perform(onSuccess = { editorDrafts.remove(request.id); editorState.removeState(request.id); editor = null; selected = null }) {
                    if (request.quickEntryId != null) {
                        val plan = if (request.kind == EditorKind.PLAN) ScheduleBlockEntity(request.id,
                            values.taskId, values.start, values.end, title = values.title, notes = values.notes,
                            timezoneId = zone.id) else null
                        val record = if (request.kind == EditorKind.RECORD) TimeRecordEntity(request.id,
                            values.taskId, values.start, values.end, categoryId = values.categoryId,
                            title = values.title, notes = values.notes) else null
                        val result = model.repository.commands.execute(com.timeagent.domain.command.WriteQuickEntry(
                            "editor:${request.id}", com.timeagent.domain.command.QuickEntryMutation.CommitDraft(
                                request.quickEntryId, requireNotNull(request.quickEntryVersion), plan, record, values.allowFuture)))
                        check(result.status in setOf(com.timeagent.domain.command.CommandStatus.APPLIED,
                            com.timeagent.domain.command.CommandStatus.DUPLICATE)) { result.message }
                    } else {
                    when (values.kind ?: request.kind) {
                        EditorKind.TASK -> {
                            val before = request.entity as? TaskEntity
                            val candidate = (before ?: TaskEntity(request.id, values.title)).copy(title = values.title, description = values.notes, estimatedMinutes = values.estimatedMinutes, priority = values.priority, dueAt = values.dueAt, categoryId = values.categoryId, activityId = values.activityId)
                            val result = model.repository.commands.execute(com.timeagent.domain.command.WriteTaskDetails(
                                "editor:${request.id}", candidate, before?.version, updateOrganization = true,
                                projectId = values.projectId, expectedPlacementVersion = values.expectedPlacementVersion,
                                tagIds = values.tagIds, expectedTagIds = values.expectedTagIds,
                                linkedDetails = values.linkedDetails, newSubtasks = values.newSubtasks,
                                newPlan = if (request.scheduleNewTask) ScheduleBlockEntity("${request.id}:plan",
                                    candidate.id, values.start, values.end, timezoneId = zone.id) else null))
                            check(result.status in setOf(com.timeagent.domain.command.CommandStatus.APPLIED,
                                com.timeagent.domain.command.CommandStatus.DUPLICATE)) { result.message }
                            TaskListPreferences(context).saveSubtaskOrder(candidate.id, values.subtaskOrder)
                            result.undoToken?.let { timelineUndo = com.timeagent.ui.components.UndoMessage(
                                result.requestId, context.getString(com.timeagent.R.string.task_saved_undo), it) }
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
                            if (request.focusSessionId != null) {
                                model.repository.commands.execute(com.timeagent.domain.command.WriteFocus("editor:${request.id}",
                                    com.timeagent.domain.command.FocusMutation.AdjustSegment(request.focusSessionId,
                                        requireNotNull(request.focusVersion), requireNotNull(request.focusSegmentIndex), values.start, values.end, values.title, values.notes)
                                )).also { if (it.status !in setOf(com.timeagent.domain.command.CommandStatus.APPLIED, com.timeagent.domain.command.CommandStatus.DUPLICATE)) error(it.message) }
                            } else model.repository.saveRecord((before ?: TimeRecordEntity(request.id, values.taskId, values.start, values.end)).copy(taskId = values.taskId, startAt = values.start, endAt = values.end, title = values.title, notes = if (request.selection == null) values.notes else notes, categoryId = values.categoryId), values.allowFuture, before?.version, requestId = "editor:${request.id}")
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
            }, saveError = error, onKeepDraft = { editorDrafts[request.id] = tab to request; editor = null },
                classification = taskClassification,
                onOpenTask = { id ->
                    data.tasks.firstOrNull { it.id == id && it.deletedAt == null && it.status != "CANCELLED" }?.let { child ->
                        editorDrafts[request.id] = tab to request
                        editor = EditorRequest(EditorKind.TASK, child)
                    }
                },
                onArrangeTask = { editor = request.copy(scheduleNewTask = true) },
                attachmentThumbnail = { item -> TaskEditorAttachmentPreview(item, model.repository) })
        }
        }
    }

    timelineDrag?.let { request ->
        key(request.requestId) {
            TimelineDragSubmission(request, model.repository, onDismiss = { timelineDrag = null },
                onSaved = { result ->
                    timelineDrag = null
                    val message = if (result.warnings.isEmpty()) context.getString(com.timeagent.R.string.timeline_drag_saved)
                        else context.getString(com.timeagent.R.string.timeline_drag_saved_conflicts, result.warnings.size)
                    result.undoToken?.let { timelineUndo = com.timeagent.ui.components.UndoMessage(result.requestId, message, it) }
                },
                onShowLatest = {
                    timelineDrag = null
                    selected = data.timeline(model.clock.now()).firstOrNull { it.layoutKey == request.item.layoutKey }
                })
        }
    }
    selected?.let { selection ->
        MinuteClockContent(model) { now ->
            ModalBottomSheet(onDismissRequest = { if (!busy) selected = null }) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (selection) {
                        is TaskEntity -> {
                            Text(selection.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("task-detail-title"))
                            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.task_detail_summary,
                                selection.estimatedMinutes.toLong().minutesLabel(),
                                androidx.compose.ui.res.stringResource(taskPriorityLabelResource(selection.priority)),
                                androidx.compose.ui.res.stringResource(if (selection.status == "COMPLETED" || selection.status == "DONE")
                                    com.timeagent.R.string.task_status_done else com.timeagent.R.string.task_status_open)))
                            TimelineColorChoice(selection.timelineColorSubject(), selection.title)
                            selection.dueAt?.let { Text("截止 ${Instant.ofEpochMilli(it).label(zone)}") }
                            if (selection.description.isNotBlank()) Text(selection.description)
                            TextButton(onClick = { selected = null; openScreen("attachments") }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_019)) }
                            Text("${data.plans.count { it.taskId == selection.id }} 个计划块 · ${data.records.count { it.taskId == selection.id }} 条实际记录", style = MaterialTheme.typography.bodySmall)
                            data.plans.filter { it.taskId == selection.id }.forEach { item -> TextButton(onClick = { selected = data.timeline(now).first { it.id == item.id } }) { Text("计划 ${Instant.ofEpochMilli(item.startAt).label(zone)}") } }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { startTimer(selection.id); selected = null }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_suggestion_actions_003)) }
                                OutlinedButton(onClick = { editor = EditorRequest(EditorKind.PLAN, taskId = selection.id, scheduleRange = true); selected = null }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_020)) }
                                TextButton(onClick = { selected = null; openScreen("focus-task:${selection.id}") }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_021)) }
                            }
                            Row {
                                TextButton(onClick = { editor = EditorRequest(EditorKind.TASK, selection); selected = null }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_022)) }
                                TextButton(onClick = { openNote("TASK", selection.id, selection.title) }, modifier = Modifier.testTag("task-note-entry")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_activities_007)) }
                                TextButton(onClick = { toggleTask(selection) { selected = null } }, enabled = !busy) { Text(if (selection.status == "COMPLETED") "重新打开" else "标记完成") }
                                TextButton(onClick = { deletingTask = selection }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_delete)) }
                            }
                        }
                        is TimelineItem -> {
                            Text(selection.kind.label, color = selection.kind.color(), style = MaterialTheme.typography.labelLarge)
                            Text(selection.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("${selection.span.start.label(zone)} — ${selection.span.end.label(zone)}")
                            TimelineColorChoice(selection.colorSubject, selection.title)
                            Text("时长 ${java.time.Duration.between(selection.span.start, selection.span.end).toMinutes().minutesLabel()}")
                            if (selection.subtitle.isNotBlank()) Text(selection.subtitle)
                            selection.dragReadOnlyReason?.let { Text("已锁定拖动 · $it", style = MaterialTheme.typography.bodySmall) }
                            if (selection.kind == ItemKind.PLAN || selection.kind == ItemKind.COURSE) OutlinedButton(onClick = {
                                calendarTitle = selection.title; calendarStart = selection.span.start.toEpochMilli(); calendarEnd = selection.span.end.toEpochMilli()
                                selected = null; openScreen("calendar")
                            }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_023)) }
                            (selection.entity as? TimeRecordEntity)?.takeIf { it.source == "WORKOUT" && it.id.startsWith("workout:") }?.let { record ->
                                Button(onClick = { selected = null; openScreen("workout:${record.id.removePrefix("workout:")}") }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_024)) }
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
                                val editable = selection.readOnlyReason == null &&
                                    ((selection.entity as? TimeRecordEntity)?.endAt != null || selection.kind != ItemKind.RECORD)
                                val noteType = when (selection.entity) { is ScheduleBlockEntity -> "PLAN"; is TimeRecordEntity -> "RECORD"; is CourseOccurrenceEntity -> "OCCURRENCE"; else -> null }
                                if (noteType != null) TextButton(onClick = { openNote(noteType, (selection.entity as? TimeRecordEntity)?.id ?: selection.id, selection.title) }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_activities_007)) }
                                TextButton(onClick = { val record = selection.entity as? TimeRecordEntity
                                    val focus = record?.takeIf { it.source == "FOCUS" }?.let { actual -> data.focusSessions.firstOrNull { "focus:${it.id}" == actual.id } }
                                    editor = EditorRequest(when (selection.kind) { ItemKind.COURSE -> EditorKind.COURSE; ItemKind.PLAN -> EditorKind.PLAN; ItemKind.RECORD -> EditorKind.RECORD }, selection.entity,
                                        initialStartAt = selection.span.start.toEpochMilli(), initialEndAt = selection.span.end.toEpochMilli(),
                                        focusSessionId = focus?.id, focusVersion = focus?.version,
                                        focusSegmentIndex = selection.id.substringAfter("#segment:", "").toIntOrNull()); selected = null }, enabled = editable) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_chat_extraction_screen_006)) }
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
        AlertDialog(onDismissRequest = { if (!busy) preview = null }, title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_course_import_screen_016)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(value.bundle.term.name); Text("${value.bundle.term.firstWeekStart} 起 · ${value.bundle.term.teachingWeeks} 周 · ${value.bundle.term.zone.id}", style = MaterialTheme.typography.bodySmall)
                Text("新增 ${value.newCount} 次课程 · 已存在 ${value.duplicateCount} 次 · 手工修改保护 ${value.protectedCount} 次", fontWeight = FontWeight.SemiBold)
                value.bundle.courses.forEach { Text("${it.name} · ${it.location}", style = MaterialTheme.typography.bodySmall) }
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_025), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { model.perform(onSuccess = { preview = null; navigator.resetToToday() }) { model.repository.commitImport(value, requestId) } }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_026)) } }, dismissButton = { TextButton(onClick = { preview = null }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } })
    }
    focusSwitch?.let { target ->
        AlertDialog(onDismissRequest = { focusSwitch = null }, title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_027)) },
            text = { Text("${target.first.label} 已专注的部分会保存为实际记录。") },
            confirmButton = { TextButton(onClick = { model.perform(onSuccess = { focusSwitch = null }) {
                model.repository.commands.execute(com.timeagent.domain.command.SwitchFocusToTimer(target.third,
                    target.first.id, target.first.version, target.second,
                    target.second?.let { id -> data.tasks.firstOrNull { it.id == id }?.version })).also {
                    if (it.status !in setOf(com.timeagent.domain.command.CommandStatus.APPLIED, com.timeagent.domain.command.CommandStatus.DUPLICATE)) error(it.message)
                }
            } }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_focus_screen_007)) } },
            dismissButton = { TextButton(onClick = { focusSwitch = null }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } })
    }
    workoutSwitch?.let { target ->
        val hasSets = WorkoutRules.summary(target.session, model.clock.now().toEpochMilli()).completedSets > 0
        AlertDialog(onDismissRequest = { workoutSwitch = null },
            title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_028)) },
            text = { Text(if (hasSets) "结束当前训练并保存实际记录，再开始计时。" else "当前训练还没有完成的组，放弃本次空训练后再开始计时。") },
            confirmButton = { TextButton(onClick = {
                model.perform(onSuccess = { workoutSwitch = null }) {
                    workoutsRepository.switchToTimer(target.session.id, target.session.updatedAt, target.taskId,
                        target.taskVersion, target.requestId)
                }
            }, enabled = !busy) { Text(if (hasSets) "结束并开始" else "放弃空训练并开始") } },
            dismissButton = { TextButton(onClick = { workoutSwitch = null }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } })
    }
    switchTask?.let { target -> AlertDialog(onDismissRequest = { switchTask = null }, title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_029)) }, text = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_030)) }, confirmButton = { TextButton(onClick = { model.perform(onSuccess = { switchTask = null }) { model.repository.startTimer(target.first, target.second, switchExisting = true) } }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_focus_screen_007)) } }, dismissButton = { TextButton(onClick = { switchTask = null }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } }) }
    deletingTask?.let { task ->
        val deleteRequestId = rememberSaveable(task.id, task.version) { UUID.randomUUID().toString() }
        var deletePreviewJson by rememberSaveable(task.id, task.version) { mutableStateOf<String?>(null) }
        val deletePreview = remember(deletePreviewJson) { deletePreviewJson?.let {
            com.google.gson.Gson().fromJson(it, com.timeagent.domain.command.TaskDeletionPreview::class.java)
        } }
        LaunchedEffect(task.id, task.version) {
            if (deletePreviewJson == null) {
                try {
                    deletePreviewJson = com.google.gson.Gson().toJson(model.repository.previewTaskDeletion(task.id, task.version))
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    model.report(failure.message ?: context.getString(com.timeagent.R.string.task_delete_preview_failed))
                }
            }
        }
        fun delete(action: LinkedScheduleAction) {
            val preview = deletePreview ?: return
            model.perform(onSuccess = { deletingTask = null; selected = null }) {
                val result = model.repository.commands.execute(com.timeagent.domain.command.DeleteTask(deleteRequestId, preview, action))
                check(result.status in setOf(com.timeagent.domain.command.CommandStatus.APPLIED,
                    com.timeagent.domain.command.CommandStatus.DUPLICATE)) { result.message }
                result.undoToken?.let { timelineUndo = com.timeagent.ui.components.UndoMessage(
                    result.requestId, context.getString(com.timeagent.R.string.task_deleted_undo), it) }
            }
        }
        AlertDialog(onDismissRequest = { if (!busy) deletingTask = null }, title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_031)) },
            text = { Column {
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_032))
                Text(if (deletePreview == null) context.getString(com.timeagent.R.string.task_delete_preview_loading)
                    else context.getString(com.timeagent.R.string.task_delete_preview_counts,
                        deletePreview.planVersions.size, deletePreview.reminderVersions.size))
            } },
            confirmButton = { Column {
                TextButton(onClick = { delete(LinkedScheduleAction.KEEP_INDEPENDENT) }, enabled = !busy && deletePreview != null) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_033)) }
                TextButton(onClick = { delete(LinkedScheduleAction.DELETE) }, enabled = !busy && deletePreview != null) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_034)) }
            } }, dismissButton = { TextButton(onClick = { deletingTask = null }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } })
    }
    confirmation?.let { (message, action) -> AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_035)) }, text = { Text(message) }, confirmButton = { TextButton(onClick = { confirmation = null; action() }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_confirm)) } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } }) }
    error?.let { message ->
        androidx.compose.ui.window.Popup(alignment = Alignment.BottomCenter) {
            com.timeagent.ui.components.StatusBanner(message,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 100.dp),
                actionLabel = androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_acknowledge),
                onAction = model::dismissError)
        }
    }

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
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_037), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick=onDeadlines,modifier=Modifier.fillMaxWidth().testTag("task-deadline-entry")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_038)) }
        OutlinedButton(onClick=onReply,modifier=Modifier.fillMaxWidth().testTag("task-reply-entry")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_039)) }
        OutlinedButton(onClick=onAttachments,modifier=Modifier.fillMaxWidth().testTag("task-attachments-entry")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_040)) }
        OutlinedButton(onClick = onRecurring,modifier=Modifier.fillMaxWidth().testTag("task-recurring-entry")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_recurring_screen_001)) }
        OutlinedButton(onClick = onProjects, modifier = Modifier.fillMaxWidth().testTag("task-projects-entry")) { Text("项目与子任务 · ${data.projects.count { it.deletedAt == null }} 个项目") }
        Surface(onClick = onInbox, color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("task-inbox-entry")) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_inbox_screens_007), fontWeight = FontWeight.SemiBold)
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
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_041), fontWeight = FontWeight.SemiBold)
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_042), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(">")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(!showCompleted, { showCompleted = false }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_043)) }); FilterChip(showCompleted, { showCompleted = true }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_044)) }) }
        if (tasks.isEmpty()) { EmptyHint(if (showCompleted) "完成的事，会留在这里" else "从一件小事开始", "任务可以关联多个计划块和实际记录。"); OutlinedButton(onClick = onCreate) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_045)) } }
        tasks.forEach { task -> Surface(onClick = { onSelect(task) }, color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(task.title, fontWeight = FontWeight.SemiBold)
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.task_row_summary, task.estimatedMinutes.toLong().minutesLabel(), androidx.compose.ui.res.stringResource(taskPriorityLabelResource(task.priority))), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_046), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        listOf("计划投入" to planned, "累计记录投入" to stats.recordedMinutes, "去重时间跨度" to stats.elapsedMinutes).forEach { (title, value) ->
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) { Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f)); Text(value.minutesLabel(), fontWeight = FontWeight.Bold, color = if (title == "计划投入") Indigo else RecordGreen) } }
        }
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_047), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ProjectReviewSection(data,week.selectedWeekStart,now,zone,onOpen,onGoals)
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_048), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        (data.categories.map { it.id to it.name } + (null to "未分类")).forEach { (id, name) ->
            val spans = records.filter { (it.entity as TimeRecordEntity).categoryId == id }.map { it.span }
            val minutes = TimeStatistics.calculate(spans, window).recordedMinutes
            if (minutes > 0) { Row { Text(name, Modifier.weight(1f)); Text(minutes.minutesLabel()) }; LinearProgressIndicator(progress = { if (stats.recordedMinutes == 0L) 0f else minutes.toFloat() / stats.recordedMinutes }, modifier = Modifier.fillMaxWidth()) }
        }
        if (stats.recordedMinutes == 0L) Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_049), style = MaterialTheme.typography.bodySmall)
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_050), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
        if (maxMinutes == 0L) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_051), style = MaterialTheme.typography.bodySmall); return }
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
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_001), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("开发测试版 · ${LocalContext.current.packageManager.getPackageInfo(LocalContext.current.packageName, 0).versionName}", style = MaterialTheme.typography.labelMedium); Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_052), style = MaterialTheme.typography.bodyMedium) } }
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_053), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Button(onClick = onPdfImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_054)) }
        Button(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_055)) }
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_056), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        data.terms.filterNot { term -> term.id == "example-2026-fall" && data.courses.none { it.termId == term.id && it.id !in sampleIds } }.forEach { Text("${it.name} · ${it.firstWeekStart} 起 · ${it.teachingWeeks} 周", style = MaterialTheme.typography.bodySmall) }
        val cancelled = data.occurrences.filter { it.status == "CANCELLED" && it.courseId !in sampleIds }
        if (cancelled.isNotEmpty()) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_057), style = MaterialTheme.typography.labelLarge); cancelled.forEach { item -> TextButton(onClick = { onCourse(item) }) { Text("${data.courses.firstOrNull { it.id == item.courseId }?.name} · ${item.date}") } } }
        if (data.imports.isNotEmpty()) Text("最近导入：新增 ${data.imports.first().importedCount} 次，跳过重复 ${data.imports.first().skippedCount} 次", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        OutlinedButton(onClick = onActivities, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_014)) }
        Button(onClick = onWorkout, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("settings-workout")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_058)) }
        OutlinedButton(onClick = onHealthSync, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("settings-health-sync")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_059)) }
        OutlinedButton(onClick = onAssistant, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_060)) }
        OutlinedButton(onClick = onBackup, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("settings-backup")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_backup_screen_001)) }
        OutlinedButton(onClick = onMemory, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("settings-memory")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_061)) }
        OutlinedButton(onClick = onHistory, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("settings-history")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_062)) }
        OutlinedButton(onClick = onExtract, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("settings-extract")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_063)) }
        HorizontalDivider()
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_064), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (data.history.isEmpty()) Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_065), style = MaterialTheme.typography.bodySmall)
        data.history.take(12).forEach { item -> Row { Text(item.actionType, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); Text(if (item.undone) "已撤销" else "已完成", style = MaterialTheme.typography.labelSmall) } }
        HorizontalDivider(); Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_time_agent_app_066), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
