package com.timeagent.workout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.Gson
import com.timeagent.core.workout.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable fun WorkoutApp(model: WorkoutViewModel, initialSessionId: String? = null, onClose: () -> Unit) {
    val sessions by model.sessions.collectAsStateWithLifecycle()
    val templates by model.templates.collectAsStateWithLifecycle()
    val previous by model.previous.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val loading by model.loading.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(if (initialSessionId == null) "home" else "session") }
    var sessionId by rememberSaveable { mutableStateOf(initialSessionId) }
    var pickerReturn by rememberSaveable { mutableStateOf("home") }
    var replaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerRevision by rememberSaveable { mutableIntStateOf(0) }
    val screenStates = rememberSaveableStateHolder()
    var anatomyReturn by rememberSaveable { mutableStateOf("home") }
    var muscle by rememberSaveable { mutableStateOf("") }
    val draftJson = model.templateDraftJson
    var leaveTemplate by remember { mutableStateOf(false) }
    val gson = remember { Gson() }
    var settings by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val prefs = remember { context.getSharedPreferences("workout", 0) }
    var pounds by remember { mutableStateOf(prefs.getBoolean("pounds", false)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(screen) { while (screen == "session" || screen == "home") { now = System.currentTimeMillis(); delay(if (screen == "session") 250 else 60_000) } }
    fun openSession(id: String) { sessionId = id; screen = "session" }
    fun openPicker(returnTo: String, replacement: String? = null) {
        focusManager.clearFocus(); screenStates.removeState("picker:$pickerRevision"); pickerRevision++
        pickerReturn = returnTo; replaceId = replacement; screen = "picker"
    }
    fun close() { focusManager.clearFocus(); model.perform(onSuccess = { if (model.error.value == null) onClose() }) {} }
    fun back() {
        focusManager.clearFocus()
        if (screen == "template" && model.templateDraftJson != model.templateBaselineJson) { leaveTemplate = true; return }
        screen = when (screen) { "anatomy" -> anatomyReturn; "picker" -> pickerReturn; else -> "home" }
    }
    BackHandler(screen != "home") { back() }
    BackHandler(screen == "home") { close() }
    val active = sessions.firstOrNull { it.status == WorkoutSessionStatus.ACTIVE || it.status == WorkoutSessionStatus.PAUSED }
    LaunchedEffect(active?.id, active?.restEndsAt, active?.status, active?.intervalTimer, active?.totalPausedMillis) {
        WorkoutNotifications.sync(context, active)
    }
    screenStates.SaveableStateProvider(if (screen == "picker") "picker:$pickerRevision" else screen) {
      when (screen) {
        "session" -> {
            val session = sessions.find { it.id == sessionId }
            if (session != null) WorkoutSessionScreen(model, session, previous, now, busy, pounds,
                onBack = ::back, onMinimize = ::close,
                onAdd = { openPicker("session", it) }, onSettings = { settings = true },
                onAnatomy = { focusManager.clearFocus(); muscle = it; anatomyReturn = "session"; screen = "anatomy" }, onOpenSession = ::openSession)
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (loading) CircularProgressIndicator() else TextButton(onClick = { screen = "home" }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_workout_screens_001)) }
            }
        }
        "picker" -> WorkoutExercisePicker(model, selecting = pickerReturn != "home", onBack = ::back,
            onPicked = { exercises ->
                if (pickerReturn == "template") {
                    val draft = gson.fromJson(draftJson, WorkoutTemplate::class.java)
                    model.templateDraftJson = gson.toJson(draft.copy(movements = draft.movements + exercises.map { WorkoutMovement(exercise = it) }))
                    screen = "template"
                } else if (pickerReturn == "session" && sessionId != null) {
                    model.addExercises(sessionId!!, exercises, replaceId); screen = "session"
                } else if (active != null) { model.addExercises(active.id, exercises); openSession(active.id) }
                else model.startWithExercises(exercises, ::openSession)
            }, onAnatomy = { muscle = it; anatomyReturn = "picker"; screen = "anatomy" })
        "template" -> draftJson?.let { json ->
            WorkoutTemplateEditor(gson.fromJson(json, WorkoutTemplate::class.java), busy, pounds,
                onChange = { transform -> model.templateDraftJson = gson.toJson(transform(gson.fromJson(model.templateDraftJson, WorkoutTemplate::class.java))) }, onBack = ::back,
                onAdd = { openPicker("template") },
                onSave = { model.saveTemplateDefinition(gson.fromJson(model.templateDraftJson, WorkoutTemplate::class.java)) { model.templateDraftJson = null; screen = "home" } })
        }
        "anatomy" -> WorkoutAnatomyScreen(muscle, onBack = ::back)
        else -> WorkoutHome(sessions, templates, busy || loading, now, onBack = ::close,
            onStart = { if (active != null) openSession(active.id) else model.start(::openSession) }, onOpen = ::openSession,
            onLibrary = { openPicker("home") },
            onAnatomy = { muscle = ""; anatomyReturn = "home"; screen = "anatomy" },
            onTemplate = { model.fromTemplate(it, ::openSession) }, onDeleteTemplate = model::deleteTemplate,
            onEditTemplate = { template -> model.templateDraftJson = gson.toJson(template ?: WorkoutTemplate(title = "新训练模板", movements = emptyList())); model.templateBaselineJson = model.templateDraftJson; screen = "template" },
            onSettings = { settings = true })
    }
    }
    if (settings) WorkoutSettingsDialog(pounds, onPounds = { pounds = it; prefs.edit().putBoolean("pounds", it).apply() }, onDismiss = { settings = false })
    if (leaveTemplate) AlertDialog(onDismissRequest = { leaveTemplate = false }, title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_workout_screens_002)) }, text = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_workout_screens_003)) },
        confirmButton = { TextButton(onClick = { model.saveTemplateDefinition(gson.fromJson(model.templateDraftJson, WorkoutTemplate::class.java)) { model.templateDraftJson = null; leaveTemplate = false; screen = "home" } }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_workout_screens_004)) } },
        dismissButton = { Row { TextButton(onClick = { leaveTemplate = false }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_attachment_screen_023)) }; TextButton(onClick = { model.templateDraftJson = null; leaveTemplate = false; screen = "home" }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_workout_screens_005)) } } })
    LaunchedEffect(screen, draftJson) { if (screen == "template" && draftJson == null) screen = "home" }
    error?.let { message -> AlertDialog(onDismissRequest = model::clearError, title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_habit_screen_001)) }, text = { Text(message) }, confirmButton = { TextButton(onClick = model::clearError) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_acknowledge)) } }) }
    if (model.timerConflict) AlertDialog(onDismissRequest = model::cancelStartSwitch, title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_workout_screens_006)) },
        text = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_workout_screens_007)) },
        confirmButton = { TextButton(onClick = model::confirmStartSwitch, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_workout_screens_008)) } },
        dismissButton = { TextButton(onClick = model::cancelStartSwitch) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun WorkoutHome(sessions: List<WorkoutSession>, templates: List<WorkoutTemplate>, busy: Boolean, now: Long,
    onBack: () -> Unit, onStart: () -> Unit, onOpen: (String) -> Unit, onLibrary: () -> Unit, onAnatomy: () -> Unit,
    onTemplate: (String) -> Unit, onDeleteTemplate: (String) -> Unit, onEditTemplate: (WorkoutTemplate?) -> Unit, onSettings: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var delete by remember { mutableStateOf<WorkoutTemplate?>(null) }
    val active = sessions.firstOrNull { it.status == WorkoutSessionStatus.ACTIVE || it.status == WorkoutSessionStatus.PAUSED }
    val completed = sessions.filter { it.status == WorkoutSessionStatus.COMPLETED }
    val week = completed.filter { it.startedAt >= now - 7 * 86_400_000L }
    Scaffold(containerColor = WorkoutBackground, topBar = { TopAppBar(title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_report_screen_016), fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回时间 Agent") } }, actions = { IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "训练设置") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Surface(color = Color(0xFF172C43), shape = RoundedCornerShape(26.dp)) { Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_workout_screens_009), color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("近 7 天 ${week.size} 次 · ${week.sumOf { WorkoutRules.summary(it, now).elapsedMillis } / 60_000} 分钟 · ${number(week.sumOf { WorkoutRules.summary(it, now).volumeKg })} kg", color = Color(0xFFB9CDE0), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onStart, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("workout-start"), colors = ButtonDefaults.buttonColors(containerColor = WorkoutBlue, contentColor = MaterialTheme.colorScheme.onPrimary)) { Text(if (active == null) "开始自由训练" else "继续「${active.title}」") }
                } }
            }
            item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedButton(onClick = onLibrary, modifier = Modifier.weight(1f).testTag("workout-library")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_workout_screens_010)) }; OutlinedButton(onClick = onAnatomy, modifier = Modifier.weight(1f).testTag("workout-anatomy")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_workout_picker_002)) } } }
            item { TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) { listOf("训练模板", "训练记录").forEachIndexed { i, label -> Tab(selected = i == tab, onClick = { tab = i }, text = { Text(label) }) } } }
            if (tab == 0) {
                item { TextButton(onClick = { onEditTemplate(null) }, modifier = Modifier.testTag("workout-new-template")) { Icon(Icons.Rounded.Add, null); Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_workout_screens_011)) } }
                if (templates.isEmpty()) item { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_workout_screens_012), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
                items(templates, key = { it.id }) { template -> WorkoutListCard(template.title, "${template.movements.size} 个动作 · ${template.movements.sumOf { it.sets.size }} 组", onClick = { onEditTemplate(template) }) {
                    TextButton(onClick = { onTemplate(template.id) }, enabled = !busy && active == null) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_workout_screens_013)) }
                    IconButton(onClick = { delete = template }) { Icon(Icons.Rounded.DeleteOutline, "删除模板 ${template.title}") }
                } }
            } else {
                if (completed.isEmpty()) item { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_workout_screens_014), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(completed, key = { it.id }) { session ->
                    val result = WorkoutRules.summary(session, now)
                    WorkoutListCard(session.title, "${workoutDate(session.startedAt)} · ${result.workingSets} 正式组 · ${number(result.volumeKg)} kg", { onOpen(session.id) }) { Icon(Icons.Rounded.ChevronRight, "查看训练详情") }
                }
            }
        }
    }
    delete?.let { template -> AlertDialog(onDismissRequest = { delete = null }, title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_workout_screens_015)) }, text = { Text("删除「${template.title}」，已完成的训练记录会保留。") }, confirmButton = { TextButton(onClick = { onDeleteTemplate(template.id); delete = null }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_delete)) } }, dismissButton = { TextButton(onClick = { delete = null }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } }) }
}

@Composable private fun WorkoutListCard(title: String, subtitle: String, onClick: () -> Unit, trailing: @Composable RowScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; trailing() } }
}

@Composable private fun WorkoutSettingsDialog(pounds: Boolean, onPounds: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("workout", 0) }
    var sound by remember { mutableStateOf(prefs.getBoolean("sound", true)) }
    var awake by remember { mutableStateOf(prefs.getBoolean("keepAwake", true)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_workout_screens_016)) }, text = { Column {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_workout_screens_017), Modifier.weight(1f)); Switch(pounds, onPounds) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_workout_screens_018), Modifier.weight(1f)); Switch(sound, { sound = it; prefs.edit().putBoolean("sound", it).apply() }) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_workout_screens_019), Modifier.weight(1f)); Switch(awake, { awake = it; prefs.edit().putBoolean("keepAwake", it).apply(); (context as? android.app.Activity)?.window?.let { window -> if (it) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }) }
        TextButton(onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_settings_pages_002)) }
        if (android.os.Build.VERSION.SDK_INT >= 31 && !context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()) {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_workout_screens_020), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${context.packageName}"))) } }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_workout_screens_021)) }
        }
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_workout_screens_022), style = MaterialTheme.typography.bodySmall)
    } }, confirmButton = { TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_done)) } })
}

private fun workoutDate(value: Long): String = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
