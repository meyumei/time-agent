package com.timeagent.calendar

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.timeagent.domain.command.CalendarConflictChoice
import com.timeagent.domain.command.CommandResult
import com.timeagent.domain.command.UndoCommand
import com.timeagent.domain.command.throwIfRejected
import com.timeagent.ui.TimeViewModel
import com.timeagent.ui.components.AppTopBar
import com.timeagent.ui.components.UndoMessage
import com.timeagent.ui.components.UndoSnackbar
import kotlinx.coroutines.launch

@Composable
fun CalendarSyncScreen(model: TimeViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(model, context) { CalendarSyncRepository(model.repository, context.applicationContext) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<CalendarSyncSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var disconnect by remember { mutableStateOf<CalendarChoice?>(null) }
    var keepImported by remember { mutableStateOf(true) }
    var deleteExported by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf("365") }
    var pastRange by remember { mutableStateOf("30") }
    val snackbar = remember { SnackbarHostState() }
    var undoMessage by remember { mutableStateOf<UndoMessage?>(null) }
    fun accepted(result: CommandResult) {
        result.throwIfRejected()
        result.undoToken?.let { token ->
            undoMessage = UndoMessage(java.util.UUID.randomUUID().toString(), "日历设置或日程已更新", token)
        }
    }
    suspend fun refresh() {
        state = repo.snapshot()
        range = state!!.rangeDays.toString()
        pastRange = state!!.pastDays.toString()
    }
    fun act(block: suspend () -> Unit) {
        scope.launch {
            busy = true; error = null
            try { block(); refresh() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
            finally { busy = false }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        (context.applicationContext as com.timeagent.TimeAgentApplication).refreshCalendarPermission()
        act { refresh(); CalendarSyncWorker.request(context, false) }
    }
    LaunchedEffect(repo) { refresh() }
    UndoSnackbar(snackbar, undoMessage, onUndo = { token ->
        undoMessage = null
        act { model.repository.commands.execute(UndoCommand(java.util.UUID.randomUUID().toString(), token)).throwIfRejected() }
    }, onConsumed = { id -> if (undoMessage?.id == id) undoMessage = null })
    Scaffold(topBar = { AppTopBar("系统日历同步", onClose) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_calendar_sync_screen_001))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val snapshot = state
            if (snapshot == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else {
                if (!snapshot.canRead || !snapshot.canWrite) {
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_calendar_sync_screen_002))
                    Button(onClick = { permissions.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_calendar_sync_screen_003)) }
                } else {
                    Button(onClick = { act { repo.syncNow().let(::accepted) } }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_calendar_sync_screen_004)) }
                    OutlinedButton(onClick = { act { repo.createLocalCalendar("时间 Agent").let(::accepted) } }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_calendar_sync_screen_005)) }
                    OutlinedTextField(pastRange, { pastRange = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_calendar_sync_screen_006)) })
                    OutlinedTextField(range, { range = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_calendar_sync_screen_007)) })
                    OutlinedButton(onClick = { act {
                        repo.saveSettings(snapshot.exportCalendarId,
                            requireNotNull(range.toIntOrNull()) { "请填写未来天数" },
                            snapshot.actualCalendarId, snapshot.settingsVersion,
                            requireNotNull(pastRange.toIntOrNull()) { "请填写过去天数" }).let(::accepted)
                    } }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_calendar_sync_screen_008)) }
                    snapshot.calendars.forEach { calendar ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(calendar.name, style = MaterialTheme.typography.titleMedium)
                                Text(calendar.account)
                                listOf(CalendarSyncDirection.OFF to "关闭", CalendarSyncDirection.IMPORT to "仅导入",
                                    CalendarSyncDirection.EXPORT to "仅导出", CalendarSyncDirection.BIDIRECTIONAL to "双向").forEach { (direction, label) ->
                                    Row {
                                        RadioButton(calendar.direction == direction, onClick = {
                                            act { repo.configure(calendar.id, calendar.name, calendar.account, direction,
                                                expectedVersion = snapshot.states.firstOrNull { it.calendarId == calendar.id }?.version).let(::accepted) }
                                        }, enabled = !busy && (calendar.writable || direction in listOf(CalendarSyncDirection.OFF, CalendarSyncDirection.IMPORT)))
                                        Text(label, Modifier.padding(top = 12.dp))
                                    }
                                }
                                if (calendar.writable) TextButton(onClick = {
                                    act { repo.saveSettings(calendar.id, requireNotNull(range.toIntOrNull()) { "请填写天数" },
                                        snapshot.actualCalendarId, snapshot.settingsVersion,
                                        requireNotNull(pastRange.toIntOrNull()) { "请填写过去天数" }).let(::accepted) }
                                }, enabled = !busy) { Text(if (snapshot.exportCalendarId == calendar.id) "已为导出目标 · 保存范围" else "设为导出目标") }
                                if (calendar.writable && calendar.id != snapshot.exportCalendarId) TextButton(onClick = {
                                    act { repo.saveSettings(snapshot.exportCalendarId, snapshot.rangeDays, calendar.id,
                                        snapshot.settingsVersion).let(::accepted) }
                                }, enabled = !busy) { Text(if (snapshot.actualCalendarId == calendar.id) "实际记录独立日历 · 已开启" else "将实际记录导出到此独立日历") }
                                TextButton(onClick = { disconnect = calendar }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_calendar_sync_screen_009)) }
                            }
                        }
                    }
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_calendar_sync_screen_010))
                    OutlinedButton(onClick = { act { repo.createActualCalendar().let(::accepted) } }, enabled = !busy) {
                        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_calendar_sync_screen_011))
                    }
                    if (snapshot.actualCalendarId != 0L) TextButton(onClick = {
                        act { repo.saveSettings(snapshot.exportCalendarId, snapshot.rangeDays, 0L, snapshot.settingsVersion).let(::accepted) }
                    }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_calendar_sync_screen_012)) }
                }
                snapshot.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                snapshot.readOnlyReasons.forEach { Text(it) }
                snapshot.series.forEach { series ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("系统日历周期 · ${series.title}")
                            Text(series.rawRule, style = MaterialTheme.typography.bodySmall)
                            Text(series.readOnlyReason ?: "周期原文与单次例外均保留；可在时间轴调整单次日程，周期整体由系统日历管理。")
                            TextButton(onClick = {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                                    android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, series.eventId))
                                try { context.startActivity(intent) }
                                catch (_: android.content.ActivityNotFoundException) { error = "没有可打开此周期的系统日历应用，请在原日历应用中修改" }
                            }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_calendar_sync_screen_013)) }
                        }
                    }
                }
                snapshot.conflicts.forEach { conflict ->
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            Text("同步冲突 · ${conflict.localTitle}")
                            Text("本地：${conflict.localSummary}\n系统日历：${conflict.remoteSummary}\n当前保留：${conflict.kept.orEmpty()}")
                            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_calendar_sync_screen_014),
                                style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { act { repo.resolveConflict(conflict.linkId, CalendarConflictChoice.LOCAL, conflict.version).let(::accepted) } }, enabled = !busy && conflict.canUseLocal) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_calendar_sync_screen_015)) }
                                TextButton(onClick = { act { repo.resolveConflict(conflict.linkId, CalendarConflictChoice.REMOTE, conflict.version).let(::accepted) } }, enabled = !busy && conflict.canUseRemote) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_calendar_sync_screen_016)) }
                            }
                            if (conflict.kind == CalendarConflictKind.LOCAL_DELETED_REMOTE_ORIGIN) {
                                TextButton(onClick = { act { repo.resolveLocalDeletion(conflict.linkId, true, conflict.version).let(::accepted) } }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_calendar_sync_screen_017)) }
                                TextButton(onClick = { act { repo.resolveLocalDeletion(conflict.linkId, false, conflict.version).let(::accepted) } }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_calendar_sync_screen_018)) }
                            }
                        }
                    }
                }
            }
        }
    }
    disconnect?.let { calendar ->
        AlertDialog(onDismissRequest = { disconnect = null }, title = { Text("断开${calendar.name}") },
            text = { Column {
                Row { Checkbox(keepImported, { keepImported = it }); Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_calendar_sync_screen_019)) }
                Row { Checkbox(deleteExported, { deleteExported = it }); Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_calendar_sync_screen_020)) }
            } }, confirmButton = { TextButton(onClick = {
                act { repo.disconnect(calendar.id, keepImported, deleteExported, calendar.version).let(::accepted); disconnect = null }
            }, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_calendar_sync_screen_021)) } }, dismissButton = { TextButton(onClick = { disconnect = null }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } })
    }
}
