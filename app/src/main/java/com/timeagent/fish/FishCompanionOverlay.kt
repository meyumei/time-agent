package com.timeagent.fish

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timeagent.R
import com.timeagent.core.*
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun FishSprite(
    appearance: FishAppearance, presence: FishPresence, modifier: Modifier = Modifier,
    animate: Boolean = true, fixedFrame: Int? = null,
) {
    val context = LocalContext.current
    val resource = if (appearance == FishAppearance.CHIBI) R.drawable.fish_chibi_atlas else R.drawable.fish_elegant_atlas
    val atlas = remember(resource) { ImageBitmap.imageResource(context.resources, resource) }
    val frames = remember(presence.activity, presence.mood) { FishCompanion.frames(presence) }
    var position by remember(frames, appearance) { mutableIntStateOf(0) }
    LaunchedEffect(frames, appearance, animate) {
        if (animate) while (true) {
            val index = frames[position]
            delay(when (index) { 1, 2 -> 100L; 4, 5, 6 -> 280L; else -> 700L })
            position = (position + 1) % frames.size
        }
    }
    val target = fixedFrame ?: frames[position % frames.size]
    var previous by remember { mutableIntStateOf(target) }
    var current by remember { mutableIntStateOf(target) }
    val mix = remember { Animatable(1f) }
    LaunchedEffect(target, appearance) {
        if (current != target) {
            previous = current; current = target
            mix.snapTo(0f)
            mix.animateTo(1f, tween(if (target in 1..2) 70 else 180))
        }
    }
    // Add premultiplied layers in one surface so the character never dims mid-transition.
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val cell = atlas.width / 4
        fun pose(frame: Int, alpha: Float) = drawImage(atlas,
            srcOffset = IntOffset(frame % 4 * cell, frame / 4 * cell), srcSize = IntSize(cell, cell),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), alpha = alpha, blendMode = BlendMode.Plus)
        if (mix.value < 1f) pose(previous, 1f - mix.value)
        pose(current, mix.value)
    }
}

@Composable
fun FishCompanionOverlay(session: FishSession, onOpenChat: () -> Unit, onSettings: () -> Unit) {
    val config by session.companion.settings.collectAsStateWithLifecycle()
    val presence by session.companion.presence.collectAsStateWithLifecycle()
    val profile by session.character.profile.collectAsStateWithLifecycle()
    val voice by session.voice.status.collectAsStateWithLifecycle()
    if (!config.visible) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val side = if (config.appearance == FishAppearance.CHIBI) 116.dp else 142.dp
        val travelX = with(density) { (maxWidth - side).toPx().coerceAtLeast(0f) }
        val travelY = with(density) { (maxHeight - side - 22.dp).toPx().coerceAtLeast(0f) }
        var location by remember { mutableStateOf(Offset(config.positionX * travelX, config.positionY * travelY)) }
        LaunchedEffect(travelX, travelY, config.positionX, config.positionY) {
            location = Offset(config.positionX * travelX, config.positionY * travelY)
        }
        Column(
            Modifier.offset { IntOffset(location.x.roundToInt(), location.y.roundToInt()) }
                .width(side).testTag("fish-companion")
                .semantics {
                    contentDescription = "${profile.name}，${fishStatus(presence)}。点击继续原对话，长按设置，可拖动"
                    onClick { session.companion.event("tap"); onOpenChat(); true }
                    onLongClick { onSettings(); true }
                }
                .pointerInput(travelX, travelY) {
                    detectDragGestures(onDragEnd = {
                        session.companion.save(session.companion.settings.value.copy(
                            positionX = if (travelX > 0) location.x / travelX else 0f,
                            positionY = if (travelY > 0) location.y / travelY else 0f))
                    }) { change, delta ->
                        change.consume()
                        location = Offset((location.x + delta.x).coerceIn(0f, travelX),
                            (location.y + delta.y).coerceIn(0f, travelY))
                    }
                }
                .pointerInput(Unit) { detectTapGestures(
                    onTap = { session.companion.event("tap"); onOpenChat() },
                    onLongPress = { onSettings() }) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CharacterSprite(session, Modifier.size(side))
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .93f)) {
                Text(if (voice.speaking) "正在说话" else fishStatus(presence), Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

internal fun fishStatus(presence: FishPresence): String = when (presence.activity) {
    FishActivity.THINKING -> "在想呢"
    FishActivity.WORKING -> "忙碌中"
    FishActivity.LISTENING -> "听着呢"
    FishActivity.SLEEPY -> "有点困"
    FishActivity.SLEEPING -> "睡觉中"
    FishActivity.ERROR -> "出了点状况"
    FishActivity.GREETING -> "嗨，在这里"
    FishActivity.IDLE -> when (presence.mood) {
        FishMood.HAPPY -> "心情不错"
        FishMood.CURIOUS -> "好奇中"
        FishMood.CONCERNED -> "陪着你"
        FishMood.CALM -> "在这里"
    }
}

@Composable
fun FishAppearancePicker(session: FishSession, onSettings: (() -> Unit)? = null) {
    val config by session.companion.settings.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(config.appearance == FishAppearance.CHIBI,
            { session.companion.save(config.copy(appearance = FishAppearance.CHIBI)) }, { Text("Q版") })
        FilterChip(config.appearance == FishAppearance.ELEGANT,
            { session.companion.save(config.copy(appearance = FishAppearance.ELEGANT)) }, { Text("御姐版") })
        if (onSettings != null) TextButton(onClick = onSettings) { Text("陪伴设置") }
    }
}

@Composable
fun FishCompanionSettingsScreen(session: FishSession, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val config by session.companion.settings.collectAsStateWithLifecycle()
    val presence by session.companion.presence.collectAsStateWithLifecycle()
    var wake by remember(config.wakeHour) { mutableStateOf(config.wakeHour.toString()) }
    var sleep by remember(config.sleepHour) { mutableStateOf(config.sleepHour.toString()) }
    var feedback by remember { mutableStateOf("") }
    fun enableGlobal() {
        session.companion.save(session.companion.settings.value.copy(globalVisible = true))
        try {
            FishOverlayService.sync(context, session)
            feedback = "全局悬浮陪伴已开启，离开主界面即可看到"
        } catch (error: Exception) {
            session.companion.save(session.companion.settings.value.copy(globalVisible = false))
            feedback = error.message ?: "全局悬浮未能开启，请重试"
        }
    }
    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(context)) enableGlobal()
        else feedback = "尚未授权悬浮窗，全局陪伴未开启"
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onClose) { Text("返回") }
        Text("陪伴与作息", style = MaterialTheme.typography.headlineSmall)
        CharacterSprite(session, Modifier.size(200.dp).align(Alignment.CenterHorizontally))
        Text("拖动可调整位置，点击进入管家对话，长按打开「大肥鱼的一切」。")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("应用内显示大肥鱼", Modifier.weight(1f))
            Switch(config.visible, { session.companion.save(config.copy(visible = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("全局悬浮陪伴")
                Text("在桌面和其他应用上陪着你", style = MaterialTheme.typography.bodySmall)
            }
            Switch(config.globalVisible, { enabled ->
                if (!enabled) {
                    session.companion.save(session.companion.settings.value.copy(globalVisible = false))
                    context.stopService(Intent(context, FishOverlayService::class.java))
                    feedback = "全局悬浮陪伴已关闭"
                } else if (Settings.canDrawOverlays(context)) enableGlobal()
                else overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            }, modifier = Modifier.testTag("fish-global-toggle"))
        }
        Text("首次开启需授权“显示在其他应用上层”。全局角色可拖动，点击继续管家对话，长按返回这里；通知栏也可关闭。锁屏时隐藏，回到主界面使用原来的悬浮鱼。",
            style = MaterialTheme.typography.bodySmall)
        Text("作息 · ${session.zone.id}", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(wake, { wake = it.take(2) }, label = { Text("起床（0–23时）") }, modifier = Modifier.weight(1f))
            OutlinedTextField(sleep, { sleep = it.take(2) }, label = { Text("睡觉（0–23时）") }, modifier = Modifier.weight(1f))
        }
        Text("睡前会犯困，睡眠时安静休息。你发来消息时会醒来；正在执行事务时保持工作。主动聊天的时段与频率在「主动话题」里设置。")
        Button(onClick = {
            val from = wake.toIntOrNull()
            val until = sleep.toIntOrNull()
            if (from == null || until == null || from !in 0..23 || until !in 0..23) feedback = "请输入 0–23 的小时。"
            else {
                session.companion.save(config.copy(wakeHour = from, sleepHour = until))
                session.companion.tick(session.zone)
                feedback = "作息已保存"
            }
        }) { Text("保存作息") }
        if (feedback.isNotBlank()) Text(feedback)
    }
}
