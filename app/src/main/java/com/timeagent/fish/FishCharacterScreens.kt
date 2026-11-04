package com.timeagent.fish

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timeagent.core.FishAppearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun CharacterPage(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onClose) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_back)) }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun FishCharacterHub(session: FishSession, onOpen: (String) -> Unit, onClose: () -> Unit) {
    val profile by session.character.profile.collectAsStateWithLifecycle()
    CharacterPage("大肥鱼的一切", onClose) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CharacterSprite(session, Modifier.size(112.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.titleLarge)
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_002), style = MaterialTheme.typography.bodyMedium)
            }
        }
        listOf(
            Triple("个人档案", "名字、性格、背景、称呼与问候", "fish-profile"),
            Triple("换装", "Q版、御姐版、红色工作服和自己的形象", "fish-wardrobe"),
            Triple("表情动作", "预览、添加图片或动图，设置触发场景", "fish-actions"),
            Triple("声音", "选音色、试听、语速和自动朗读", "fish-voice"),
            Triple("陪伴与作息", "悬浮显示、位置与休息时间", "fish-appearance"),
            Triple("共同记忆", "一起经历的事和最近见闻", "fish-memory"),
            Triple("平台连接", "B站与QQ，继续同一位管家的会话", "fish-platforms"),
            Triple("主动话题", "什么时候想来和你聊两句", "fish-proactive"),
            Triple("运行设置", "连接、停止与模型设置", "fish-runtime"),
            Triple("应用设置", "时间管理、数据与备份", "settings"),
        ).forEach { (title, subtitle, route) ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) },
                    trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                    modifier = Modifier.clickable { onOpen(route) })
            }
        }
    }
}

@Composable
fun FishProfileScreen(session: FishSession, onClose: () -> Unit) {
    val profile by session.character.profile.collectAsStateWithLifecycle()
    val sync by session.characterStatus.collectAsStateWithLifecycle()
    var edited by remember(profile) { mutableStateOf(profile) }
    var feedback by remember { mutableStateOf("") }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try { session.character.importCard(uri); session.syncCharacter(); feedback = "角色卡已导入" }
        catch (error: Exception) { feedback = error.message ?: "角色卡导入失败" }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try { session.character.exportCard(uri); feedback = "角色卡已导出" }
        catch (error: Exception) { feedback = error.message ?: "角色卡导出失败" }
    }
    CharacterPage("个人档案", onClose) {
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_003))
        OutlinedTextField(edited.name, { edited = edited.copy(name = it) }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_004)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(edited.description, { edited = edited.copy(description = it) }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_005)) }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(edited.personality, { edited = edited.copy(personality = it) }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_006)) }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(edited.scenario, { edited = edited.copy(scenario = it) }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_007)) }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(edited.greeting, { edited = edited.copy(greeting = it) }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_008)) }, maxLines = 3, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(edited.examples, { edited = edited.copy(examples = it) }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_009)) }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            try { session.saveProfile(edited); feedback = "档案已保存在本机" }
            catch (error: Exception) { feedback = error.message ?: "请检查档案内容" }
        }, modifier = Modifier.testTag("fish-profile-save")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_010)) }
        if (feedback.isNotBlank()) Text(feedback)
        if (sync.isNotBlank()) Text(sync, style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { importer.launch(arrayOf("application/json", "text/*")) }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_011)) }
            TextButton(onClick = { exporter.launch("${profile.name}-角色卡.json") }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_012)) }
        }
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_013), style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FishWardrobeScreen(session: FishSession, onClose: () -> Unit) {
    val config by session.companion.settings.collectAsStateWithLifecycle()
    val outfit by session.character.outfit.collectAsStateWithLifecycle()
    val outfits by session.character.outfits.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    CharacterPage("换装", onClose) {
        CharacterSprite(session, Modifier.size(200.dp).align(Alignment.CenterHorizontally))
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_014))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(FishAppearance.CHIBI to "经典Q版", FishAppearance.ELEGANT to "经典御姐版").forEach { (appearance, label) ->
                FilterChip(outfit == "classic" && config.appearance == appearance, {
                    session.character.selectOutfit("classic"); session.companion.save(config.copy(appearance = appearance))
                }, { Text(label) })
            }
        }
        outfits.forEach { asset ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    FishAssetImage(asset.files.first(), Modifier.size(84.dp))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(asset.name, style = MaterialTheme.typography.titleSmall)
                        Row {
                            TextButton(onClick = {
                                session.character.selectOutfit(asset.id)
                                session.companion.save(config.copy(appearance = FishAppearance.valueOf(asset.appearance)))
                            }) { Text(if (outfit == asset.id && config.appearance.name == asset.appearance) "正在穿" else "穿上") }
                            if (asset.id != "red-work") TextButton(onClick = { session.character.removeAsset(asset, false) }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_015)) }
                        }
                    }
                }
            }
        }
        Button(onClick = { adding = true }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_016)) }
    }
    if (adding) AssetImportDialog(session, false, onClose = { adding = false })
}

private val actionLabels = linkedMapOf(
    "IDLE" to "待机", "HAPPY" to "开心", "LISTENING" to "聆听", "THINKING" to "思考",
    "WORKING" to "处理事务", "SPEAKING" to "说话", "GREETING" to "打招呼",
    "SLEEPY" to "困倦", "SLEEPING" to "休息", "ERROR" to "遇到问题",
)

@Composable
fun FishActionsScreen(session: FishSession, onClose: () -> Unit) {
    val actions by session.character.actions.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { session.character.preview(null) } }
    CharacterPage("表情动作", onClose) {
        CharacterSprite(session, Modifier.size(160.dp).align(Alignment.CenterHorizontally))
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_017))
        Button(onClick = { adding = true }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_018)) }
        TextButton(onClick = { session.character.preview(null) }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_019)) }
        actions.forEach { asset ->
            ListItem(headlineContent = { Text(asset.name) },
                supportingContent = { Text("${actionLabels[asset.trigger] ?: asset.trigger} · ${asset.files.size} 张 · ${asset.frameMs}ms") },
                leadingContent = { FishAssetImage(asset.files.first(), Modifier.size(56.dp)) },
                trailingContent = {
                    Row {
                        TextButton(onClick = { session.character.preview(asset) }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_020)) }
                        TextButton(onClick = { session.character.removeAsset(asset, true) }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_015)) }
                    }
                })
        }
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_021), style = MaterialTheme.typography.titleMedium)
        listOf(
            Triple("指一指", "pointing.png", "GREETING"),
            Triple("送你一朵花", "rose.jpg", "HAPPY"),
            Triple("有点惊讶", "surprised.jpg", "LISTENING"),
            Triple("准备晚安", "bedtime.jpg", "SLEEPING"),
        ).forEach { (name, file, trigger) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                FishAssetImage("asset:fish/references/$file", Modifier.size(70.dp))
                Text(name, Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = { session.character.addReference(name, file, trigger) }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_022)) }
            }
        }
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_023), style = MaterialTheme.typography.bodySmall)
    }
    if (adding) AssetImportDialog(session, true, onClose = { adding = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssetImportDialog(session: FishSession, action: Boolean, onClose: () -> Unit) {
    val config by session.companion.settings.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var uris by remember { mutableStateOf(emptyList<Uri>()) }
    var trigger by remember { mutableStateOf("IDLE") }
    var frameMs by remember { mutableStateOf("350") }
    var error by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris = it }
    AlertDialog(onDismissRequest = onClose, title = { Text(if (action) "新增表情动作" else "添加形象") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_024)) }, singleLine = true)
                TextButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text(if (uris.isEmpty()) "选择图片或动图" else "已选择 ${uris.size} 张，重新选择") }
                if (action) {
                    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_025))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        actionLabels.forEach { (value, label) -> FilterChip(trigger == value, { trigger = value }, { Text(label) }) }
                    }
                }
                if (uris.size > 1) OutlinedTextField(frameMs, { frameMs = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_026)) }, singleLine = true)
                Text("形象：${if (config.appearance == FishAppearance.CHIBI) "Q版" else "御姐版"}；多图按选择顺序播放。", style = MaterialTheme.typography.bodySmall)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(enabled = !importing, onClick = {
            scope.launch {
                importing = true
                try {
                    val duration = frameMs.toIntOrNull()
                    require(duration != null && duration in 80..3000) { "帧时长请填80–3000毫秒" }
                    withContext(Dispatchers.IO) { session.character.addAsset(name, uris, action, trigger, duration, config.appearance.name) }
                    onClose()
                } catch (failure: Exception) { error = failure.message ?: "导入失败，请重新选择图片" }
                finally { importing = false }
            }
        }) { Text(if (importing) "正在导入…" else "添加") } },
        dismissButton = { TextButton(onClick = onClose) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } })
}

@Composable
fun FishVoiceScreen(session: FishSession, onClose: () -> Unit) {
    val config by session.voice.settings.collectAsStateWithLifecycle()
    val status by session.voice.status.collectAsStateWithLifecycle()
    val profile by session.character.profile.collectAsStateWithLifecycle()
    CharacterPage("声音", onClose) {
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_027))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(config.provider == "edge", { session.voice.save(config.copy(provider = "edge")) }, { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_028)) })
            FilterChip(config.provider == "system", { session.voice.save(config.copy(provider = "system")) }, { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_029)) })
        }
        if (config.provider == "edge") listOf(
            "zh-CN-XiaoyiNeural" to "晓伊 · 轻快女声",
            "zh-CN-XiaoxiaoNeural" to "晓晓 · 自然女声",
            "zh-CN-YunxiNeural" to "云希 · 男声",
        ).forEach { (id, label) -> FilterChip(config.voice == id, { session.voice.save(config.copy(voice = id)) }, { Text(label) }) }
        Text("语速 ${config.rate}%")
        Slider(config.rate.toFloat(), { session.voice.save(config.copy(rate = it.toInt())) }, valueRange = -40f..60f)
        Text("音调 ${config.pitch}")
        Slider(config.pitch.toFloat(), { session.voice.save(config.copy(pitch = it.toInt())) }, valueRange = -30f..30f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_030), Modifier.weight(1f))
            Switch(config.autoRead, { session.voice.save(config.copy(autoRead = it)) })
        }
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_031), style = MaterialTheme.typography.bodySmall)
        Row {
            Button(onClick = { session.voice.speak("你好，我是${profile.name}。今天也陪着你，一起把想做的事慢慢做好。") }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_032)) }
            TextButton(onClick = { session.voice.stop() }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_033)) }
        }
        if (status.preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.message.isNotBlank()) Text(status.message)
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_034), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun FishRuntimeSettingsScreen(session: FishSession, onModel: () -> Unit, onClose: () -> Unit) {
    val runtime by session.runtime.state.collectAsStateWithLifecycle()
    val message by session.message.collectAsStateWithLifecycle()
    val chat by session.chat.collectAsStateWithLifecycle()
    val status by session.runtime.status.collectAsStateWithLifecycle()
    val autoStart by session.runtime.autoStart.collectAsStateWithLifecycle()
    val restartSuspended by session.runtime.restartSuspended.collectAsStateWithLifecycle()
    val runtimeLog by session.runtime.log.collectAsStateWithLifecycle()
    var showLog by remember { mutableStateOf(false) }
    CharacterPage("运行设置", onClose) {
        Text(runtime.error ?: message ?: runtime.stage)
        runtime.progress?.let { LinearProgressIndicator(progress = { it.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) }
        Row {
            if (status.primaryAction == FishRuntimeAction.ENTER_KEY) Button(onClick = onModel) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_035)) }
            else if (status.canStart) Button(onClick = { session.start() }, enabled = !chat.busy) { Text(if (status is FishRuntimeStatus.Failed) "重试启动" else "准备并启动") }
            if (runtime.ready) TextButton(onClick = { session.stop() }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_036)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_037), Modifier.weight(1f))
            Switch(checked = autoStart, onCheckedChange = session.runtime::setAutoStart)
        }
        if (restartSuspended) Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_038))
        if (status is FishRuntimeStatus.UnsupportedDevice) Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_039))
        TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "收起运行日志" else "查看运行日志") }
        if (showLog) runtimeLog.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = onModel) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_character_screens_040)) }
    }
}
