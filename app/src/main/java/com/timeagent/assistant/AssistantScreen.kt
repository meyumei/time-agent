package com.timeagent.assistant

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.JsonParser
import com.timeagent.ui.TimeViewModel
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(model: TimeViewModel, onClose: () -> Unit, initialInput: String = "", autoInterpret: Boolean = false, initialSuggestion: String? = null) {
    val androidContext = LocalContext.current
    val snapshot by model.data.collectAsStateWithLifecycle()
    val selectedWeek by model.visibleWeekStart.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val parser = remember { LocalAssistantParser() }
    val planner = remember { AssistantPlanner() }
    val settings = remember(androidContext) { AssistantSettings(androidContext) }
    val gate = remember(model.repository) { AssistantConfirmation(RepositoryAssistantWriter(model.repository)) }
    var input by rememberSaveable { mutableStateOf(initialInput.take(2000)) }
    var reply by remember { mutableStateOf<AssistantReply?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val requests = remember(scope) { AssistantRequestController(scope) }
    val parsing by requests.busy.collectAsStateWithLifecycle()
    var futureApproved by remember { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var configBusy by remember { mutableStateOf(false) }
    var keyConfigured by remember { mutableStateOf(false) }
    var modelName by rememberSaveable { mutableStateOf(DeepSeekProtocol.DEFAULT_MODEL) }
    var keyDraft by remember { mutableStateOf("") }
    var cloudConsent by remember { mutableStateOf(false) }
    var sharedContext by remember { mutableStateOf<AssistantContext?>(null) }
    var showContext by rememberSaveable { mutableStateOf(false) }
    val busy = parsing || saving || configBusy

    fun currentContext() = AssistantContext(model.repository.now(), model.zone, model.visibleWeekStart.value, model.data.value)
    fun updateInput(text: String) {
        input = text.take(2000)
        gate.cancel(); reply = null; futureApproved = false
        cloudConsent = false; sharedContext = null
    }
    fun showInterpretation(interpretation: Interpretation, context: AssistantContext) {
        gate.cancel(); futureApproved = false
        reply = when (interpretation) {
            is Interpretation.Parsed -> planner.prepare(interpretation.command, context)
            is Interpretation.Clarify -> AssistantReply.Clarification(interpretation.message)
            is Interpretation.Unsupported -> AssistantReply.Clarification(interpretation.message)
        }
        (reply as? AssistantReply.Preview)?.let { gate.stage(it.value) }
    }
    var initialParsed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoInterpret, snapshot.loading) {
        if (autoInterpret && !initialParsed && !snapshot.loading && input.isNotBlank()) {
            initialParsed = true
            showInterpretation(parser.parse(input, currentContext()), currentContext())
        }
    }
    DisposableEffect(requests) { onDispose { requests.cancel(); gate.cancel() } }
    LaunchedEffect(settings) {
        withContext(Dispatchers.IO) { settings.model() to settings.hasApiKey() }.let { (name, configured) -> modelName = name; keyConfigured = configured }
    }
    fun close() {
        if (saving || configBusy) { message = "正在保存，请稍候完成后关闭。"; return }
        requests.cancel(); gate.cancel(); onClose()
    }
    BackHandler(onBack = ::close)

    Scaffold(
        topBar = { TopAppBar(title = { Text("时间助手") }, navigationIcon = { IconButton(onClick = ::close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "关闭时间助手") } },
            actions = { IconButton(onClick = { settingsOpen = !settingsOpen }, enabled = !busy) { Icon(Icons.Outlined.Settings, "DeepSeek设置") } }) },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("说一句，先预览再确认", style = MaterialTheme.typography.titleLarge)
            Text("本地常用指令可以直接使用，自由表达可选 DeepSeek。可以记录想法、设置单点提醒、记录睡眠，也可以撤销或恢复刚才撤销的操作。", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(value = input, onValueChange = ::updateInput, label = { Text("输入内容 · 可修改") }, placeholder = { Text("例如：记个想法 周末去骑车") },
                enabled = !busy, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth().testTag("assistant-input"))
            if (initialSuggestion != null && input == initialInput && input != initialSuggestion) {
                OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("voice-command-suggestion"), onClick = {
                    updateInput(initialSuggestion)
                    showInterpretation(parser.parse(initialSuggestion, currentContext()), currentContext())
                    message = "已采用你选择的指令，请核对结果；尚未修改数据。"
                }) { Text("可能想说：$initialSuggestion") }
            }
            if (settingsOpen) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("DeepSeek 设置", style = MaterialTheme.typography.titleMedium)
                        Text(if (keyConfigured) "API Key 已保存在本机；联通情况以实际请求结果为准。" else "尚未配置 API Key。文字输入与本地解析仍可使用。", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = keyDraft, onValueChange = { keyDraft = it }, label = { Text(if (keyConfigured) "替换 API Key（留空则保留）" else "DeepSeek API Key") },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = modelName, onValueChange = { modelName = it }, label = { Text("模型名") }, supportingText = { Text("当前官方默认：${DeepSeekProtocol.DEFAULT_MODEL}；仅使用官方 HTTPS 接口。") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                        Text("Key 由本机 Android Keystore 加密保存，不进入备份。应用不会显示已保存的 Key。", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !busy && DeepSeekProtocol.validModel(modelName.trim()), onClick = {
                                configBusy = true
                                scope.launch {
                                    try {
                                        settings.saveModel(modelName.trim())
                                        if (keyDraft.isNotBlank()) settings.saveApiKey(keyDraft)
                                        keyDraft = ""; keyConfigured = settings.hasApiKey(); message = "配置已保存。"
                                    } catch (e: CancellationException) { throw e }
                                    catch (_: Exception) { message = "配置保存失败，请检查模型名和设备密钥存储后重试。" }
                                    finally { configBusy = false }
                                }
                            }) { Text("保存配置") }
                            TextButton(enabled = !busy && keyConfigured, onClick = {
                                configBusy = true
                                scope.launch {
                                    try { settings.clearApiKey(); keyDraft = ""; keyConfigured = false; cloudConsent = false; sharedContext = null; message = "已删除本机 API Key。" }
                                    catch (e: CancellationException) { throw e }
                                    catch (_: Exception) { message = "删除失败，请重试。" }
                                    finally { configBusy = false }
                                }
                            }) { Text("删除密钥") }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(modifier = Modifier.weight(1f), enabled = input.isNotBlank() && !busy && !snapshot.loading, onClick = {
                    message = null
                    showInterpretation(parser.parse(input, currentContext()), currentContext())
                }) { Text("本地解析") }
            }
            Text("首页底部按住说话，顶部实时显示文字；松开后可在上方修改。中文识别在本机完成，音频不保存也不上传。", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Row {
                Checkbox(checked = cloudConsent, enabled = !busy && keyConfigured && input.isNotBlank(), onCheckedChange = {
                    cloudConsent = it; sharedContext = if (it) currentContext() else null
                })
                Text("同意将本次文字和有限清单发送给 DeepSeek 理解", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Text("仅发送当前输入、时间、所选周、最多 50 项任务信息、50 条记录活动名与时间、70 项计划时间、70 节课程时间、30 条提醒标题与时间、30 条想法标题、内置示例课程数量及运行计时起点。任务描述、记录备注、想法正文、笔记正文和操作历史不发送。", style = MaterialTheme.typography.bodySmall)
            if (!keyConfigured) TextButton(onClick = { settingsOpen = true }) { Text("配置 DeepSeek API Key") }
            if (cloudConsent && sharedContext != null) {
                TextButton(onClick = { showContext = !showContext }) { Text(if (showContext) "收起发送内容" else "查看本次发送内容") }
                if (showContext) CloudDisclosure(input, sharedContext!!)
            }
            OutlinedButton(enabled = !busy && keyConfigured && cloudConsent && input.isNotBlank() && !snapshot.loading, onClick = {
                val contextToSend = sharedContext ?: return@OutlinedButton
                val textToSend = input
                message = null; gate.cancel(); reply = null; futureApproved = false
                requests.start(action = {
                    try {
                        DeepSeekClient().interpret(textToSend, contextToSend, settings.readApiKey(), settings.model())
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { CloudResult.Failure("本机密钥无法读取，请删除后重新配置；输入仍保留。") }
                }) { result ->
                    when (result) {
                        is CloudResult.Success -> { showInterpretation(result.interpretation, currentContext()); message = "DeepSeek 已解析，请核对以下内容。" }
                        is CloudResult.Failure -> message = result.message
                    }
                }
            }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("用 DeepSeek 理解") }
            if (parsing) TextButton(onClick = { requests.cancel(); message = "请求已取消，没有执行操作；输入仍保留。" }) { Text("取消请求") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
            when (val result = reply) {
                is AssistantReply.Answer -> ElevatedCard(Modifier.fillMaxWidth()) { SelectionContainer { Text(result.text, Modifier.padding(16.dp)) } }
                is AssistantReply.Clarification -> ElevatedCard(Modifier.fillMaxWidth()) { Text(result.text, Modifier.padding(16.dp)) }
                is AssistantReply.Preview -> {
                    val preview = result.value
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(preview.title, style = MaterialTheme.typography.titleMedium)
                            Text("修改前", style = MaterialTheme.typography.labelLarge)
                            SelectionContainer { Text(preview.before) }
                            HorizontalDivider()
                            Text("确认后", style = MaterialTheme.typography.labelLarge)
                            SelectionContainer { Text(preview.after) }
                            preview.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (preview.needsFutureApproval) Row {
                                Checkbox(checked = futureApproved, enabled = !busy, onCheckedChange = { futureApproved = it })
                                Text("我确认将未来时间保存为实际记录", Modifier.padding(top = 12.dp))
                            }
                            Button(enabled = !busy && (!preview.needsFutureApproval || futureApproved), onClick = {
                                saving = true
                                scope.launch {
                                    try {
                                        when (val outcome = gate.confirm(preview.id, futureApproved)) {
                                            is CommitResult.Saved -> { message = outcome.message; reply = null; futureApproved = false }
                                            CommitResult.AlreadySaved -> { message = "此项已保存，没有重复写入。"; reply = null }
                                            is CommitResult.Rejected -> { message = outcome.message; if (outcome.requiresNewPreview) reply = null }
                                        }
                                    } finally { saving = false }
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("确认保存") }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(enabled = !busy, onClick = { gate.cancel(); reply = null; futureApproved = false; message = "预览已取消，没有修改数据。" }) { Text("取消预览") }
                                TextButton(enabled = !busy, onClick = { gate.cancel(); reply = null; futureApproved = false; message = "请修改上方文字，再重新解析。" }) { Text("修改输入") }
                            }
                        }
                    }
                }
                null -> Unit
            }
            HorizontalDivider()
            Text("可以这样说", style = MaterialTheme.typography.titleMedium)
            listOf("今天有什么安排", "明天有什么空闲", "查看任务", "记个想法 周末去骑车", "提醒 明天 15:00 交报告",
                "记录 昨晚 23:30 到 今天 07:00 睡眠", "新建任务 阅读 半小时", "安排 明天 19:00 到 20:00 阅读",
                "记录 今天 14:00 到 15:00 阅读", "开始计时 阅读", "停止计时", "撤销", "恢复刚才撤销的操作").forEach { example ->
                TextButton(enabled = !busy, onClick = { updateInput(example); message = null }, contentPadding = PaddingValues(0.dp)) { Text(example) }
            }
            Text("同名任务或多项计划会要求用 #ID 指定。周几按当前所选周（$selectedWeek）理解；一次确认一项操作。撤销后可用“恢复刚才撤销的操作”还原；说“先预览”会先给你看结果再保存。", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun CloudDisclosure(input: String, context: AssistantContext) {
    val text = remember(input, context) {
        val data = JsonParser.parseString(DeepSeekProtocol.contextJson(context)).asJsonObject
        fun com.google.gson.JsonObject.value(name: String) = get(name)?.takeUnless { it.isJsonNull }?.asString ?: "未设置"
        buildString {
            appendLine("当前文字：$input")
            appendLine("时间：${data.value("now")}\n时区：${data.value("timezone")}\n所选周起点：${data.value("selectedWeekStart")}")
            appendLine("\n任务清单：")
            data.getAsJsonArray("tasks").forEach { element -> val item = element.asJsonObject; appendLine("${item.value("title")} · ${item.value("estimatedMinutes")}分钟 · ${item.value("status")}\n截止 ${item.value("dueAt")}\n#${item.value("id")}") }
            appendLine("\n计划清单：")
            data.getAsJsonArray("plans").forEach { element -> val item = element.asJsonObject; appendLine("任务 #${item.value("taskId")}\n${item.value("startAt")} 至 ${item.value("endAt")}\n计划 #${item.value("id")}") }
            appendLine("\n课程清单：")
            data.getAsJsonArray("courses").forEach { element -> val item = element.asJsonObject; appendLine("${item.value("title")}\n${item.value("startAt")} 至 ${item.value("endAt")}") }
            val sample = data.getAsJsonObject("builtInSampleCourses")
            appendLine("\n提醒清单：")
            data.getAsJsonArray("reminders").forEach { element -> val item = element.asJsonObject; appendLine("${item.value("title")}\n${item.value("remindAt")}\n#${item.value("id")}") }
            appendLine("\n想法清单（仅标题，不发送正文）：")
            data.getAsJsonArray("ideas").forEach { element -> val item = element.asJsonObject; appendLine("${item.value("title")} · ${item.value("status")}\n#${item.value("id")}") }
            appendLine("\n内置示例课程：${sample.value("courseCount")} 门，${sample.value("occurrenceCount")} 次；范围为整个内置示例学期。")
            val timer = data["runningTimer"]
            appendLine("\n运行计时：" + if (timer == null || timer.isJsonNull) "无" else "任务 #${timer.asJsonObject.value("taskId")}，开始 ${timer.asJsonObject.value("startAt")}")
            if (data["inventoryMayBeTruncated"].asBoolean) appendLine("清单有数量上限，可能省略部分条目。")
            append("清单已固定，重新勾选同意可更新。")
        }
    }
    OutlinedCard(Modifier.fillMaxWidth()) { SelectionContainer { Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) } }
}
