package com.timeagent.focus

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timeagent.data.StaleChange
import com.timeagent.domain.command.FocusMode
import com.timeagent.domain.command.Origin
import com.timeagent.fish.CharacterSprite
import com.timeagent.ui.TimeViewModel
import com.timeagent.ui.design.LocalPresentation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Focus configuration and the running focus screen. A one-second ticker is collected only while
 * this screen is composed; it never writes per second. The runtime/alarm and this screen both ask
 * CommandExecutor for a Tick only at a phase boundary.
 */
@Composable
fun FocusScreen(
    model: TimeViewModel,
    onClose: () -> Unit,
    initialTaskId: String? = null,
    initialScheduleId: String? = null,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val repository = remember(model.repository, appContext) { FocusRepository(model.repository, appContext) }
    val defaults = remember(appContext) { appContext.getSharedPreferences("focus_defaults", 0) }
    val scope = rememberCoroutineScope()
    val active by repository.observeActive().collectAsStateWithLifecycle(null)
    val snapshot by model.data.collectAsStateWithLifecycle()

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var slotConflict by remember { mutableStateOf<List<FocusSlot>?>(null) }
    var versionConflict by remember { mutableStateOf(false) }

    var label by rememberSaveable { mutableStateOf("") }
    var labelTouched by rememberSaveable { mutableStateOf(false) }
    var modeName by rememberSaveable { mutableStateOf(FocusMode.POMODORO.name) }
    var focusText by rememberSaveable { mutableStateOf(defaults.getInt("focus", 25).toString()) }
    var breakText by rememberSaveable { mutableStateOf(defaults.getInt("break", 5).toString()) }
    var longBreakText by rememberSaveable { mutableStateOf(defaults.getInt("long_break", 15).toString()) }
    var roundsText by rememberSaveable { mutableStateOf(defaults.getInt("rounds", 4).toString()) }
    var longBreakEveryText by rememberSaveable { mutableStateOf(defaults.getInt("long_break_every", 4).toString()) }

    val mode = runCatching { FocusMode.valueOf(modeName) }.getOrDefault(FocusMode.POMODORO)
    val task = snapshot.tasks.firstOrNull { it.id == initialTaskId }
    val plan = snapshot.plans.firstOrNull { it.id == initialScheduleId }

    LaunchedEffect(initialTaskId, initialScheduleId, snapshot.loading) {
        if (!labelTouched && label.isBlank()) {
            label = plan?.title?.takeIf { it.isNotBlank() } ?: task?.title ?: "专注"
        }
    }

    fun act(action: suspend () -> Unit, success: String? = null) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try {
                action()
                if (success != null) notice = success
            } catch (_: StaleChange) {
                versionConflict = true
            } catch (e: Exception) {
                error = e.message ?: "操作失败，尚未保存"
            } finally {
                busy = false
            }
        }
    }

    fun begin(switches: List<FocusSlot> = emptyList()) {
        if (busy) return
        val focusMinutes = focusText.toIntOrNull()
        val pomodoro = mode == FocusMode.POMODORO
        val breakMinutes = if (pomodoro) breakText.toIntOrNull() else 0
        val longBreakMinutes = if (pomodoro) longBreakText.toIntOrNull() else 0
        val rounds = if (pomodoro) roundsText.toIntOrNull() else 1
        val longBreakEvery = if (pomodoro) longBreakEveryText.toIntOrNull() else 4
        if (label.trim().isEmpty()) {
            error = "请填写专注名称"
            return
        }
        if (focusMinutes == null || focusMinutes <= 0) {
            error = "专注时长须为正整数分钟"
            return
        }
        if (pomodoro && (breakMinutes == null || breakMinutes < 0 ||
                longBreakMinutes == null || longBreakMinutes < 0)
        ) {
            error = "休息时长须为非负整数分钟"
            return
        }
        if (rounds == null || rounds <= 0 || longBreakEvery == null || longBreakEvery <= 0) {
            error = "轮数与长休息间隔须为正整数"
            return
        }
        val finalBreak = breakMinutes ?: 0
        val finalLongBreak = longBreakMinutes ?: 0
        val finalRounds = rounds ?: 1
        val timer = switches.firstOrNull { it.kind == "TIMER" }
        val workout = switches.firstOrNull { it.kind == "WORKOUT" }
        scope.launch {
            busy = true
            error = null
            notice = null
            try {
                repository.start(
                    label = label.trim(),
                    id = UUID.randomUUID().toString(),
                    mode = mode,
                    focusMinutes = focusMinutes,
                    breakMinutes = finalBreak,
                    longBreakMinutes = finalLongBreak,
                    rounds = finalRounds,
                    taskId = initialTaskId?.takeIf { task != null },
                    scheduleBlockId = initialScheduleId?.takeIf { plan != null },
                    switchTimerId = timer?.id,
                    expectedTimerVersion = timer?.version,
                    switchWorkoutId = workout?.id,
                    expectedWorkoutVersion = workout?.version,
                    longBreakEvery = longBreakEvery,
                )
                if (pomodoro) defaults.edit().putInt("focus", focusMinutes).putInt("break", finalBreak)
                    .putInt("long_break", finalLongBreak).putInt("rounds", finalRounds)
                    .putInt("long_break_every", longBreakEvery).apply()
            } catch (_: StaleChange) {
                val slots = repository.slots()
                if (slots.any { it.kind == "FOCUS" }) {
                    error = "已有专注正在进行，请先结束当前专注"
                } else if (slots.any { it.kind == "TIMER" || it.kind == "WORKOUT" }) {
                    slotConflict = slots
                } else {
                    error = "主计时状态已变化，请重新开始"
                }
            } catch (e: Exception) {
                error = e.message ?: "开始专注失败，尚未保存"
            } finally {
                busy = false
            }
        }
    }

    fun finish(save: Boolean) {
        val current = active ?: return
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try {
                repository.finish(current.id, current.version, save)
                notice = if (save) "已结束并保存实际记录" else "已放弃本次专注，不生成实际记录"
            } catch (_: StaleChange) {
                versionConflict = true
            } catch (e: Exception) {
                error = e.message ?: "结束专注失败，状态未改变"
            } finally {
                busy = false
            }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (active == null) "开始专注" else "专注", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                TextButton(onClick = onClose) { Text("关闭") }
            }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { error = null }) { Text("知道了") }
            }

            val current = active
            if (current == null) {
                Text("专注结束后自动生成一条实际记录；暂停和休息不计入实际时长。", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FocusMode.values().forEach { item ->
                        FilterChip(
                            selected = mode == item,
                            onClick = { modeName = item.name },
                            label = { Text(modeLabel(item), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(200); labelTouched = true },
                    label = { Text("专注名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                NumberField(
                    if (mode == FocusMode.COUNT_UP) "目标分钟（仅展示）" else if (mode == FocusMode.COUNT_DOWN) "倒计时分钟" else "专注分钟",
                    focusText, { focusText = it }, Modifier.fillMaxWidth(),
                )
                if (mode == FocusMode.POMODORO) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumberField("短休息", breakText, { breakText = it }, Modifier.weight(1f))
                        NumberField("长休息", longBreakText, { longBreakText = it }, Modifier.weight(1f))
                    }
                    NumberField("轮数", roundsText, { roundsText = it }, Modifier.fillMaxWidth())
                    NumberField("每几轮使用长休息", longBreakEveryText, { longBreakEveryText = it }, Modifier.fillMaxWidth())
                    Text("开始后保存本次配置，供下次使用。默认 25 分钟专注、5 分钟短休息，每 4 轮长休息 15 分钟。",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!FocusClock.exactAlarmsAllowed(context)) {
                    Text("精确闹钟未开启，后台阶段提醒可能延迟；专注时长仍按实际经过时间计算。",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
                if (task != null || plan != null) {
                    Text("关联：${plan?.title?.takeIf { it.isNotBlank() } ?: task?.title ?: "已有条目"}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    onClick = { begin() },
                    enabled = !busy && label.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("开始专注") }
            } else {
                FocusRunningContent(
                    model = model,
                    repository = repository,
                    session = current,
                    busy = busy,
                    onPause = { act(action = { repository.pause(current.id, current.version) }) },
                    onResume = { act(action = { repository.resume(current.id, current.version) }) },
                    onSkipBreak = { act(action = { repository.skipBreak(current.id, current.version) }) },
                    onFinish = { save -> finish(save) },
                )
            }
        }
    }

    slotConflict?.let { slots ->
        val switchable = slots.filter { it.kind != "FOCUS" }
        val hasFocus = slots.any { it.kind == "FOCUS" }
        AlertDialog(
            onDismissRequest = { slotConflict = null },
            title = { Text(if (hasFocus) "已有专注正在进行" else "结束当前主计时？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (hasFocus) {
                        "同一时间只能有一个专注。请先结束当前专注，再开始新的专注。"
                    } else {
                        "同一时间只允许一个主计时。开始前需要先结束下面这些正在运行的计时；不会并行计时。"
                    })
                    slots.forEach { slot ->
                        Text("· ${slotName(slot)}（版本 ${slot.version}）", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                if (hasFocus || switchable.isEmpty()) {
                    TextButton(onClick = { slotConflict = null }) { Text("知道了") }
                } else {
                    TextButton(onClick = {
                        val chosen = switchable
                        slotConflict = null
                        begin(chosen)
                    }) { Text("结束并开始") }
                }
            },
            dismissButton = {
                if (!hasFocus && switchable.isNotEmpty()) TextButton(onClick = { slotConflict = null }) { Text("取消") }
            },
        )
    }

    if (versionConflict) {
        AlertDialog(
            onDismissRequest = { versionConflict = false },
            title = { Text("专注状态已变化") },
            text = { Text("另一个操作已经更新了这次专注，本次操作未保存。当前状态已重新读取，请核对后再操作。") },
            confirmButton = { TextButton(onClick = { versionConflict = false }) { Text("知道了") } },
        )
    }
}

@Composable
private fun FocusRunningContent(
    model: TimeViewModel,
    repository: FocusRepository,
    session: FocusSessionEntity,
    busy: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSkipBreak: () -> Unit,
    onFinish: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var elapsedNow by remember(session.id, session.version) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var finishAsk by remember(session.id) { mutableStateOf(false) }
    val boot = FocusClock.bootCount(context)

    LaunchedEffect(session.id, session.version, session.state) {
        var sent = false
        while (true) {
            elapsedNow = SystemClock.elapsedRealtime()
            val bootNow = FocusClock.bootCount(context)
            if (session.bootCount >= 0 && bootNow != session.bootCount) {
                if (!sent) {
                    sent = true
                    scope.launch { runCatching { repository.reconcile() } }
                }
                break
            }
            val boundary = FocusClock.nextBoundaryInMs(session, elapsedNow, bootNow)
            if (boundary != null && boundary <= 0L) {
                if (!sent) {
                    sent = true
                    val wall = model.clock.now().toEpochMilli()
                    scope.launch {
                        runCatching {
                            repository.tick(
                                session.id, session.version,
                                requestId = "focus-ui:${session.id}:${session.version}:$wall",
                                origin = Origin.UI,
                            )
                        }
                    }
                }
                break
            }
            delay(1000L)
        }
    }

    val mode = runCatching { FocusMode.valueOf(session.mode) }.getOrDefault(FocusMode.POMODORO)
    val focusNow = FocusClock.focusElapsedNow(session, elapsedNow, boot)
    val remaining = FocusClock.remainingRoundMs(session, elapsedNow, boot)
    val breakRemaining = FocusClock.breakRemainingMs(session, elapsedNow, boot)
    val breakTotal = FocusClock.breakDurationMs(session)
    val planned = FocusClock.plannedFocusMs(session)
    val progress = when (session.state) {
        FocusSessionEntity.STATE_FOCUSING -> if (mode == FocusMode.COUNT_UP || planned <= 0L) 0f
        else (1f - remaining.toFloat() / planned.toFloat()).coerceIn(0f, 1f)
        FocusSessionEntity.STATE_BREAK -> if (breakTotal <= 0L) 0f
        else (1f - breakRemaining.toFloat() / breakTotal.toFloat()).coerceIn(0f, 1f)
        FocusSessionEntity.STATE_PAUSED -> if (mode == FocusMode.COUNT_UP || planned <= 0L) 0f
        else (FocusClock.roundElapsedNow(session, elapsedNow, boot).toFloat() / planned.toFloat()).coerceIn(0f, 1f)
        else -> 1f
    }
    val ringText = when (session.state) {
        FocusSessionEntity.STATE_FOCUSING -> if (mode == FocusMode.COUNT_UP) FocusClock.formatClock(focusNow)
        else FocusClock.formatClock(remaining)
        FocusSessionEntity.STATE_BREAK -> FocusClock.formatClock(breakRemaining)
        FocusSessionEntity.STATE_PAUSED -> "已暂停"
        else -> "已结束"
    }
    val caption = when (session.state) {
        FocusSessionEntity.STATE_FOCUSING -> if (session.roundsPlanned > 1) "第 ${session.roundIndex}/${session.roundsPlanned} 轮"
        else if (mode == FocusMode.COUNT_UP) "正计时" else "倒计时"
        FocusSessionEntity.STATE_BREAK -> "休息"
        FocusSessionEntity.STATE_PAUSED -> "已专注 ${FocusClock.formatDuration(focusNow)}"
        else -> "已结束"
    }
    val ringColor = when (session.state) {
        FocusSessionEntity.STATE_BREAK -> MaterialTheme.colorScheme.tertiary
        FocusSessionEntity.STATE_PAUSED -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.primary
    }

    Text(session.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        FocusRing(progress, ringText, caption, ringColor, Modifier.size(190.dp))
        val fish = model.fish
        if (LocalPresentation.current.companion && fish != null) {
            Spacer(Modifier.width(8.dp))
            CharacterSprite(fish, Modifier.size(64.dp))
        }
    }
    if (session.needsReview || session.timeChanged) {
        Text(if (session.needsReview) "设备重启后已暂停，只保留重启前已核实的专注时长。请核对，缺失时段可手动补录。"
            else "检测到系统时间变化，专注时长按实际经过时间计算，请核对展示时间。",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
    }
    Text("已专注 ${FocusClock.formatDuration(focusNow)}；休息和暂停不计入实际。",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when (session.state) {
            FocusSessionEntity.STATE_FOCUSING -> {
                Button(onClick = onPause, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("暂停") }
                OutlinedButton(onClick = { finishAsk = true }, enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("结束") }
            }
            FocusSessionEntity.STATE_PAUSED -> {
                Button(onClick = onResume, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("继续") }
                OutlinedButton(onClick = { finishAsk = true }, enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("结束") }
            }
            FocusSessionEntity.STATE_BREAK -> {
                Button(onClick = onSkipBreak, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("跳过休息") }
                OutlinedButton(onClick = { finishAsk = true }, enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("结束") }
            }
        }
    }

    if (finishAsk) {
        AlertDialog(
            onDismissRequest = { finishAsk = false },
            title = { Text("结束这次专注？") },
            text = {
                Text("已专注 ${FocusClock.formatDuration(focusNow)}。保存会生成一条实际记录；不保存则本次不生成记录，已专注的片段仍保留在专注会话详情中。")
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        finishAsk = false
                        onFinish(false)
                    }) { Text("不保存") }
                    TextButton(onClick = {
                        finishAsk = false
                        onFinish(true)
                    }) { Text("保存并结束") }
                }
            },
            dismissButton = { TextButton(onClick = { finishAsk = false }) { Text("继续专注") } },
        )
    }
}

@Composable
private fun FocusRing(
    progress: Float,
    time: String,
    caption: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier.semantics { contentDescription = "$time $caption" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            drawArc(color = track, startAngle = -90f, sweepAngle = 360f, useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round))
            val safe = if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f)
            drawArc(color = color, startAngle = -90f, sweepAngle = 360f * safe, useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(time, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (caption.isNotBlank()) {
                Text(caption, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter { it.isDigit() }.take(4)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
fun RunningFocusBar(model: TimeViewModel, onOpen: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val repository = remember(model.repository, appContext) { FocusRepository(model.repository, appContext) }
    val active by repository.observeActive().collectAsStateWithLifecycle(null)
    val session = active ?: return
    var now by remember(session.id, session.version) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(session.id, session.version, session.state) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000L)
        }
    }
    val boot = FocusClock.bootCount(context)
    val text = when (session.state) {
        FocusSessionEntity.STATE_FOCUSING -> {
            val mode = runCatching { FocusMode.valueOf(session.mode) }.getOrDefault(FocusMode.POMODORO)
            if (mode == FocusMode.COUNT_UP) FocusClock.formatClock(FocusClock.focusElapsedNow(session, now, boot))
            else FocusClock.formatClock(FocusClock.remainingRoundMs(session, now, boot))
        }
        FocusSessionEntity.STATE_BREAK -> "休息 ${FocusClock.formatClock(FocusClock.breakRemainingMs(session, now, boot))}"
        FocusSessionEntity.STATE_PAUSED -> "已暂停"
        else -> ""
    }
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable(onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("专注 · ${session.label}", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            Text("打开", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun modeLabel(mode: FocusMode): String = when (mode) {
    FocusMode.POMODORO -> "番茄钟"
    FocusMode.COUNT_UP -> "正计时"
    FocusMode.COUNT_DOWN -> "倒计时"
}

private fun slotName(slot: FocusSlot): String = when (slot.kind) {
    "TIMER" -> "自由计时：${slot.label}"
    "WORKOUT" -> "训练：${slot.label}"
    "FOCUS" -> "专注：${slot.label}"
    else -> slot.label
}
