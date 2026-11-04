package com.timeagent.onboarding

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.timeagent.domain.command.CommandStatus
import com.timeagent.ui.components.UndoMessage
import com.timeagent.ui.components.UndoSnackbar
import com.timeagent.ui.ThemePreference
import com.timeagent.ui.design.PresentationMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Persisted onboarding progress. The step is written on every transition so a process kill resumes
 * at the same card; `completedVersion` prevents a completed guide from reopening after upgrade.
 */
class OnboardingPreference(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun currentStep(): Int = preferences.getInt(KEY_STEP, 0)

    fun saveStep(step: Int) {
        preferences.edit().putInt(KEY_STEP, step).apply()
    }

    fun completedVersion(): Int = preferences.getInt(KEY_COMPLETED_VERSION, 0)

    fun shouldShow(existingUser: Boolean): Boolean {
        if (isReplaying()) return true
        if (completedVersion() >= CURRENT_VERSION) return false
        // An existing install is never forced through onboarding on upgrade. "重新查看引导" is the
        // only path that opens it for an existing user, and that path still omits the sample step.
        return !existingUser
    }

    fun isReplaying(): Boolean = preferences.getBoolean(KEY_REPLAYING, false)

    fun startReplay() {
        preferences.edit()
            .putBoolean(KEY_REPLAYING, true)
            .putInt(KEY_STEP, 0)
            .putString(KEY_SESSION, UUID.randomUUID().toString())
            .apply()
    }

    fun abandonReplay() {
        preferences.edit().putBoolean(KEY_REPLAYING, false).apply()
    }

    fun sampleChoice(): String? = preferences.getString(KEY_SAMPLE_CHOICE, null)

    fun saveSampleChoice(choice: String) {
        preferences.edit().putString(KEY_SAMPLE_CHOICE, choice).apply()
    }

    /** Stable across process death so a half-finished sample load can be replayed idempotently. */
    fun sessionId(): String {
        preferences.getString(KEY_SESSION, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_SESSION, id).apply()
        return id
    }

    fun markCompleted(version: Int = CURRENT_VERSION) {
        preferences.edit()
            .putInt(KEY_COMPLETED_VERSION, version)
            .putInt(KEY_STEP, 0)
            .putBoolean(KEY_REPLAYING, false)
            .apply()
    }

    companion object {
        const val CURRENT_VERSION = 1
        private const val PREFERENCES_NAME = "onboarding"
        private const val KEY_STEP = "step"
        private const val KEY_COMPLETED_VERSION = "completedVersion"
        private const val KEY_REPLAYING = "replaying"
        private const val KEY_SAMPLE_CHOICE = "sampleChoice"
        private const val KEY_SESSION = "sessionId"
    }
}

@Stable
class OnboardingModel(
    context: Context,
    private val sampleData: SampleDataRepository? = null,
    val existingUser: Boolean = false,
    private val onOpenAiSettings: (() -> Unit)? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val appContext = context.applicationContext
    private val preference = OnboardingPreference(appContext)
    private val appearance = ThemePreference(appContext)

    val totalSteps: Int = if (existingUser) 4 else 5
    var step by mutableIntStateOf(preference.currentStep().coerceIn(0, totalSteps - 1))
        private set
    var presentation by mutableStateOf(appearance.readAll().presentation)
        private set
    var sampleChoice by mutableStateOf(preference.sampleChoice())
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var samplesLoaded by mutableStateOf(false)
        private set

    private var finished = false

    fun isLastStep(): Boolean = step == totalSteps - 1

    fun selectPresentation(mode: PresentationMode) {
        presentation = mode
        appearance.savePresentation(mode)
    }

    fun next() {
        if (step < totalSteps - 1) {
            step++
            preference.saveStep(step)
        }
    }

    fun back() {
        if (step > 0) {
            step--
            preference.saveStep(step)
        }
    }

    fun skipCurrent() {
        if (isLastStep()) finishWithoutSamples() else next()
    }

    fun finishWithoutSamples() {
        sampleChoice = "BLANK"
        preference.saveSampleChoice("BLANK")
        complete()
    }

    fun complete() {
        if (finished) return
        finished = true
        preference.markCompleted()
    }

    fun abandon() {
        if (preference.isReplaying()) preference.abandonReplay()
    }

    fun openAiSettings() {
        onOpenAiSettings?.invoke()
    }

    fun hasAiSettingsCallback(): Boolean = onOpenAiSettings != null

    suspend fun refreshSampleStatus() {
        samplesLoaded = try {
            sampleData?.status()?.loaded == true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Loads the sample week through the command executor. The request id is stable per onboarding
     * session; a retry after a process kill replays the first result instead of writing twice.
     */
    suspend fun loadSamples(): Boolean {
        if (busy) return false
        val repository = sampleData
        if (repository == null) {
            error = "示例数据不可用，请稍后重试"
            return false
        }
        busy = true
        error = null
        return try {
            val result = repository.loadWeek(
                requestId = "onboarding-sample:${preference.sessionId()}:${LocalDate.now(clock)}",
            )
            if (result.status == CommandStatus.APPLIED || result.status == CommandStatus.DUPLICATE) {
                sampleChoice = "SAMPLE"
                preference.saveSampleChoice("SAMPLE")
                samplesLoaded = true
                true
            } else {
                error = result.message
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "示例数据加载失败，请重试"
            false
        } finally {
            busy = false
        }
    }
}

@Stable
class SampleDataModel(
    context: Context,
    private val sampleData: SampleDataRepository,
    private val onReplayOnboarding: (() -> Unit)? = null,
) {
    private val preference = OnboardingPreference(context.applicationContext)
    var undoMessage by mutableStateOf<UndoMessage?>(null)
        private set

    fun consumeUndo(id: String) {
        if (undoMessage?.id == id) undoMessage = null
    }

    suspend fun undo(token: String) {
        if (busy) return
        busy = true
        error = null
        try {
            val result = sampleData.undo(token)
            if (result.status == CommandStatus.APPLIED || result.status == CommandStatus.DUPLICATE) {
                notice = "已撤销示例数据操作。"
                preview = null
                undoMessage = null
                status = sampleData.status()
            } else error = result.message
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "撤销失败，请重试"
        } finally {
            busy = false
        }
    }

    var status by mutableStateOf(SampleDataStatus())
        private set
    var preview by mutableStateOf<SampleCleanupPreview?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    fun canReplayOnboarding(): Boolean = onReplayOnboarding != null

    fun replayOnboarding() {
        preference.startReplay()
        onReplayOnboarding?.invoke()
    }

    suspend fun refresh() {
        busy = true
        try {
            status = sampleData.status()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "示例数据读取失败，请重试"
        } finally {
            busy = false
        }
    }

    suspend fun loadWeek(): Boolean {
        if (busy) return false
        busy = true
        error = null
        notice = null
        return try {
            val result = sampleData.loadWeek("settings-sample:${UUID.randomUUID()}")
            if (result.status == CommandStatus.APPLIED || result.status == CommandStatus.DUPLICATE) {
                notice = "示例数据已加载，可随时清除。"
                undoMessage = result.undoToken?.let { UndoMessage(UUID.randomUUID().toString(), "示例数据已加载", it) }
                true
            } else {
                error = result.message
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "示例数据加载失败，请重试"
            false
        } finally {
            busy = false
            status = runCatching { sampleData.status() }.getOrDefault(status)
        }
    }

    suspend fun previewCleanup() {
        busy = true
        error = null
        notice = null
        try {
            preview = sampleData.cleanupPreview()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "读取示例数据失败，请重试"
        } finally {
            busy = false
        }
    }

    fun dismissPreview() {
        preview = null
    }

    suspend fun cleanup(keepEdited: Boolean) {
        if (busy) return
        busy = true
        error = null
        notice = null
        try {
            val result = sampleData.cleanup(keepEdited)
            if (result.status == CommandStatus.APPLIED || result.status == CommandStatus.DUPLICATE) {
                notice = if (keepEdited) "已清除未编辑的示例数据，编辑过的内容已保留为你的数据。" else "示例数据已清除，可用撤销恢复内容。"
                undoMessage = result.undoToken?.let { UndoMessage(UUID.randomUUID().toString(), "示例数据已清除", it) }
                preview = null
            } else {
                error = result.message
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "清除失败，请重试"
        } finally {
            busy = false
            status = runCatching { sampleData.status() }.getOrDefault(status)
        }
    }
}

/** Five cards, every one skippable. Existing installs get four cards (no sample step). */
@Composable
fun OnboardingScreen(model: OnboardingModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    BackHandler {
        if (model.step > 0) {
            model.back()
        } else {
            model.abandon()
            onClose()
        }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_001), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "第 ${model.step + 1} / ${model.totalSteps} 步",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = {
                    if (model.isLastStep()) {
                        model.finishWithoutSamples()
                        onClose()
                    } else {
                        model.skipCurrent()
                    }
                }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_002)) }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                repeat(model.totalSteps) { index ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (index <= model.step) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (model.step) {
                    0 -> WelcomeStep(model)
                    1 -> PlanActualStep()
                    2 -> PermissionStep()
                    3 -> AiStep(model)
                    else -> SampleStep(model, scope, onClose)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (model.step > 0) {
                    OutlinedButton(onClick = { model.back() }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_003)) }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    if (model.isLastStep()) {
                        model.finishWithoutSamples()
                        onClose()
                    } else {
                        model.next()
                    }
                }) { Text(if (model.isLastStep()) "完成" else "下一步") }
            }
        }
    }
}

@Composable
private fun WelcomeStep(model: OnboardingModel) {
    StepTitle("欢迎使用时间 Agent", "3 分钟了解计划与实际，选择你喜欢的陪伴方式。")
    Spacer(Modifier.height(20.dp))
    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_004), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        PresentationMode.entries.forEach { mode ->
            val selected = model.presentation == mode
            Card(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { model.selectPresentation(mode) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(mode.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (mode == PresentationMode.TOOL) "信息优先，紧凑安静" else "大肥鱼更常出现，氛围更轻松",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    ModePreview(model.presentation)
    Spacer(Modifier.height(12.dp))
    Text(
        androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_005),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ModePreview(mode: PresentationMode) {
    val companion = mode == PresentationMode.COMPANION
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (companion) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (companion) {
                    Box(
                        modifier = Modifier.size(36.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center,
                    ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_006), style = MaterialTheme.typography.labelLarge) }
                    Spacer(Modifier.width(10.dp))
                }
                Column {
                    Text(if (companion) "大肥鱼陪你安排今天" else "今天", style = MaterialTheme.typography.titleSmall)
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_007), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(12.dp))
            PreviewRow("09:30", "晨间专注", MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            PreviewRow("10:05", "阅读（实际）", MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.height(6.dp))
            PreviewRow("19:30", "散步", MaterialTheme.colorScheme.secondary)
            if (companion) {
                Spacer(Modifier.height(10.dp))
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_008), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PreviewRow(time: String, title: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(time, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PlanActualStep() {
    StepTitle("计划与实际分开记录", "计划是你打算做什么，实际是你真的做了什么；两条轨道可以对比，也可以复盘。")
    Spacer(Modifier.height(16.dp))
    DemoTimeline()
    Spacer(Modifier.height(12.dp))
    Text(
        androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_009),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_010), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_011),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DemoTimeline() {
    val density = LocalDensity.current
    var startMinute by remember { mutableFloatStateOf(9f * 60f) }
    var running by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        seconds = 0
        val started = SystemClock.elapsedRealtime()
        while (true) {
            seconds = ((SystemClock.elapsedRealtime() - started) / 1000).toInt().coerceIn(0, 60)
            delay(250)
        }
    }

    val actualMinutes = if (running || seconds > 0) seconds.coerceAtLeast(1) else 0
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.height(180.dp).width(48.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf("09:00", "10:00", "11:00", "12:00").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(180.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        ) {
            // Plan block: vertical drag, 15-minute snap, clamped so it never leaves the demo day.
            Column(
                modifier = Modifier
                    .offset {
                        IntOffset(0, ((startMinute - 540f) * density.density).roundToInt())
                    }
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val next = (startMinute + drag.y / density.density) / 15f
                            startMinute = (next.roundToInt() * 15f).coerceIn(540f, 660f)
                        }
                    }
                    .padding(10.dp),
            ) {
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_012), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${clockLabel(startMinute.toInt())}–${clockLabel(startMinute.toInt() + 60)}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (actualMinutes > 0) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(0, 60.dp.roundToPx()) }
                        .fillMaxWidth()
                        .height(actualMinutes.dp.coerceAtLeast(6.dp))
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer),
                ) {
                    Text(
                        "实际 · 阅读 ${actualMinutes} 分钟",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(6.dp),
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { running = !running }) {
            Text(if (running) "结束计时演示" else "开始计时演示")
        }
        Spacer(Modifier.width(12.dp))
        Text(
            if (running) "演示计时中，实际块正在变长" else "点一下开始，再点一下结束",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun clockLabel(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

@Composable
private fun PermissionStep() {
    val context = LocalContext.current
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }

    val notificationGranted = remember(resumeTick) { notificationGranted(context) }
    val exactGranted = remember(resumeTick) { exactAlarmGranted(context) }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { resumeTick++ }

    StepTitle("只申请现在需要的权限", "通知和精确闹钟会在这里说明用途；其他权限等你第一次使用对应功能时再申请。")
    Spacer(Modifier.height(16.dp))
    PermissionCard(
        title = "通知",
        detail = "提醒到点时通知你；拒绝也不影响记录和计时。",
        granted = notificationGranted,
        actionLabel = if (Build.VERSION.SDK_INT >= 33) "允许通知" else "系统无需额外授权",
        enabled = Build.VERSION.SDK_INT >= 33 && !notificationGranted,
        onAction = {
            if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
    )
    Spacer(Modifier.height(10.dp))
    PermissionCard(
        title = "精确闹钟",
        detail = "让提醒尽量准时；没有它时提醒可能晚几分钟。",
        granted = exactGranted,
        actionLabel = if (exactGranted) "已允许" else "去开启",
        enabled = Build.VERSION.SDK_INT >= 31 && !exactGranted,
        onAction = {
            if (Build.VERSION.SDK_INT >= 31) {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }
            }
        },
    )
    Spacer(Modifier.height(16.dp))
    Text(
        androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_013),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PermissionCard(
    title: String,
    detail: String,
    granted: Boolean,
    actionLabel: String,
    enabled: Boolean,
    onAction: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(detail, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.width(12.dp))
                if (granted) {
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_014), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                } else {
                    OutlinedButton(onClick = onAction, enabled = enabled) { Text(actionLabel) }
                }
            }
            if (!granted) {
                Spacer(Modifier.height(6.dp))
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_015), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun notificationGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun exactAlarmGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

@Composable
private fun AiStep(model: OnboardingModel) {
    StepTitle(
        "大肥鱼需要你自己的 API Key（可选）",
        "大肥鱼使用你提供的 Key 来聊天、理解语音和操作数据；没有 Key 也能使用全部时间管理功能。",
    )
    Spacer(Modifier.height(20.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_016), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_017),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { model.openAiSettings() }, enabled = model.hasAiSettingsCallback()) {
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_018))
            }
            if (!model.hasAiSettingsCallback()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_019),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(
        androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_020),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SampleStep(
    model: OnboardingModel,
    scope: kotlinx.coroutines.CoroutineScope,
    onClose: () -> Unit,
) {
    LaunchedEffect(Unit) { model.refreshSampleStatus() }
    StepTitle(
        "要加载一周示例吗？",
        "示例会放在今天这一周，展示任务、日程、实际记录和一个项目的配合方式；不会覆盖你已有的数据或模板。",
    )
    Spacer(Modifier.height(20.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (model.samplesLoaded) "示例数据已经加载" else "示例一周",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (model.samplesLoaded) "可以随时在 管家 → 设置 → 示例数据 中清除。"
                else "加载后可随时一键清除；你编辑过的示例内容在清除前会单独询问。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    scope.launch {
                        if (model.loadSamples()) {
                            model.complete()
                            onClose()
                        }
                    }
                },
                enabled = !model.busy && !model.samplesLoaded,
            ) {
                Text(
                    when {
                        model.busy -> "正在加载…"
                        model.samplesLoaded -> "已加载"
                        else -> "加载示例一周"
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    model.finishWithoutSamples()
                    onClose()
                },
                enabled = !model.busy,
            ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_021)) }
            model.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_022),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StepTitle(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Settings page: reopen onboarding, load or clear samples, and preview edited rows before cleanup. */
@Composable
fun SampleDataScreen(model: SampleDataModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { model.refresh() }
    UndoSnackbar(snackbar, model.undoMessage, onUndo = { token -> scope.launch { model.undo(token) } },
        onConsumed = model::consumeUndo)
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_023), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_done)) }
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_025), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_026),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { model.replayOnboarding() }, enabled = model.canReplayOnboarding()) {
                            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_027))
                        }
                        if (!model.canReplayOnboarding()) {
                            Spacer(Modifier.height(4.dp))
                            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_028), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_029), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                if (model.status.loaded) {
                    Text(
                        "已加载 ${model.status.total} 条：任务 ${model.status.tasks}、日程 ${model.status.plans}、实际 ${model.status.records}、项目 ${model.status.projects}。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (model.status.edited > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text("其中 ${model.status.edited} 条已被你编辑。", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { scope.launch { model.previewCleanup() } },
                        enabled = !model.busy,
                    ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_030)) }
                } else {
                    Text(
                        androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_031),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { scope.launch { model.loadWeek() } },
                        enabled = !model.busy,
                    ) { Text(if (model.busy) "处理中…" else "加载示例一周") }
                }
                model.preview?.let { preview ->
                    Spacer(Modifier.height(16.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_032), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text("将清除 ${preview.total} 条示例关联记录。", style = MaterialTheme.typography.bodySmall)
                            if (preview.hasEdited) {
                                Spacer(Modifier.height(10.dp))
                                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_033), style = MaterialTheme.typography.labelLarge)
                                preview.edited.forEach { item ->
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "· ${typeLabel(item.entityType)}：${item.title}（版本 ${item.initialVersion} → ${item.version}）",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = { scope.launch { model.cleanup(keepEdited = false) } },
                                    enabled = !model.busy,
                                ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_034)) }
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { scope.launch { model.cleanup(keepEdited = true) } },
                                    enabled = !model.busy,
                                ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_035)) }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_036),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            } else {
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = { scope.launch { model.cleanup(keepEdited = false) } },
                                    enabled = !model.busy,
                                ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_032)) }
                            }
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = model::dismissPreview) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) }
                        }
                    }
                }
                model.notice?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                model.error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_onboarding_screen_037),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun typeLabel(type: String): String = when (type) {
    SampleEntityTypes.TASK -> "任务"
    SampleEntityTypes.PLAN -> "日程"
    SampleEntityTypes.RECORD -> "实际记录"
    SampleEntityTypes.PROJECT -> "项目"
    else -> type
}
