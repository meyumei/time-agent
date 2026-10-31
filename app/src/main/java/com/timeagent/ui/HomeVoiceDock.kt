package com.timeagent.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.timeagent.assistant.*

@Composable
fun HomeVoiceDock(
    enabled: Boolean, onRecognized: (String) -> Unit, onMessage: (String) -> Unit, onTextEntry: () -> Unit = {},
    onLiveInput: (SpeechInputStatus, String) -> Unit = { _, _ -> },
    onSuggestion: (String?) -> Unit = {},
    speechFactory: SpeechInputFactory = OfflineSpeechInputFactory,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val deliver by rememberUpdatedState(onRecognized)
    val showMessage by rememberUpdatedState(onMessage)
    var status by remember { mutableStateOf(SpeechInputStatus.IDLE) }
    var liveText by remember { mutableStateOf("") }
    var resultReady by remember { mutableStateOf(false) }
    var resetSignal by remember { mutableIntStateOf(0) }
    var engineRevision by remember { mutableIntStateOf(0) }
    var failureMessage by remember { mutableStateOf<String?>(null) }
    var helpOpen by remember { mutableStateOf(false) }
    val liveInput by rememberUpdatedState(onLiveInput)
    val suggest by rememberUpdatedState(onSuggestion)
    val sessionHolder = remember { arrayOfNulls<HoldToTalkSession>(1) }
    val speech = remember(context, speechFactory, engineRevision) {
        speechFactory(context.applicationContext, SpeechCallbacks(
            onText = { resultReady = true; sessionHolder[0]?.receive(it) },
            onState = { state, message -> status = state; if (message != null) { failureMessage = message; showMessage(message) } },
            onFailure = { resetSignal++; sessionHolder[0]?.cancel(); if (failureMessage?.contains("没有听清") != true) helpOpen = true },
            onPartial = { liveText = it },
            onSuggestion = { suggest(it) },
        ))
    }
    val session = remember(speech) { HoldToTalkSession(speech::start, speech::stop, speech::cancel) { deliver(it) } }
    SideEffect { sessionHolder[0] = session }
    LaunchedEffect(status, liveText) { liveInput(status, liveText) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        session.cancel()
        resetSignal++
        if (!granted) { failureMessage = "请允许时间 Agent 使用麦克风后再试。"; helpOpen = true }
        showMessage(if (granted) "麦克风已授权，请重新按住说话。" else "未获得麦克风权限，可用右上角键盘输入。")
    }
    DisposableEffect(lifecycle, speech, session) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { session.cancel(); resetSignal++; liveText = "" } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); session.cancel(); speech.close(); sessionHolder[0] = null }
    }
    HoldToTalkButton(enabled, status == SpeechInputStatus.PROCESSING,
        onStart = {
            when {
                status == SpeechInputStatus.PREPARING -> { showMessage("首次使用正在准备离线中文识别，准备好后请重新按住。"); false }
                status != SpeechInputStatus.IDLE -> { showMessage("正在识别上一段语音，请稍候。"); false }
                !speech.available -> { helpOpen = true; false }
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> {
                    permission.launch(Manifest.permission.RECORD_AUDIO); false
                }
                else -> { resultReady = false; liveText = ""; suggest(null); failureMessage = null; session.begin(); status == SpeechInputStatus.LISTENING || status == SpeechInputStatus.STARTING }
            }
        }, onFinish = session::release, onCancel = { session.cancel(); liveText = ""; suggest(null) },
        onTap = { if (failureMessage != null) helpOpen = true else showMessage("按住下方圆圈说话，文字会在屏幕上方显示；松开识别，上滑取消。") }, onTextEntry = onTextEntry,
        resetSignal = resetSignal, preparing = status == SpeechInputStatus.PREPARING, starting = status == SpeechInputStatus.STARTING, resultReady = resultReady)
    if (helpOpen) AlertDialog(onDismissRequest = { helpOpen = false }, title = { Text("检查语音输入") },
        text = { Text((failureMessage ?: "离线中文识别尚未就绪，可重新准备后再试。") + "\n\n小米／澎湃 OS：应用管理 → 时间 Agent → 权限管理 → 录音或麦克风，允许使用。也请检查控制中心的麦克风开关。中文识别已内置，无需安装系统语音服务。") },
        confirmButton = { TextButton(onClick = {
            helpOpen = false
            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                .onFailure { showMessage("请在手机设置的应用管理中打开时间 Agent 的麦克风权限。") }
        }) { Text("打开应用权限") } },
        dismissButton = { TextButton(onClick = { helpOpen = false; failureMessage = null; session.cancel(); resetSignal++; engineRevision++ }) { Text("重新准备") } })
}
