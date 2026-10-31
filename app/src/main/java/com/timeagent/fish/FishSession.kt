package com.timeagent.fish

import android.content.Context
import android.content.Intent
import com.timeagent.assistant.*
import com.timeagent.data.TimeRepository
import com.timeagent.runtime.AgentRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class FishMemory(val id: Long, val tier: String, val text: String)
data class FishDiscovery(val title: String, val url: String)

/** One application-owned DSH session, shared by the dock and the butler page. */
class FishSession private constructor(private val context: Context, repository: TimeRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val runtime = AgentRuntime(context)
    private val tools = FishTimeTools(repository)
    val companion = FishCompanionState(context)
    val character = FishCharacter(context)
    val voice = FishVoice(context, ::requestVoice)
    @Volatile var appForeground = false
    private val _characterStatus = MutableStateFlow("")
    val characterStatus = _characterStatus.asStateFlow()
    val zone = repository.zone
    private var historyHydrated = false
    private var lastAssistantText = ""
    private val _chat = MutableStateFlow(AssistantChatState())
    val chat = _chat.asStateFlow()
    private val sendLock = Mutex()
    private val _draft = MutableStateFlow("")
    val draft = _draft.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _tool = MutableStateFlow<String?>(null)
    val tool = _tool.asStateFlow()
    private val _memories = MutableStateFlow<List<FishMemory>>(emptyList())
    val memories = _memories.asStateFlow()
    private val _discoveries = MutableStateFlow<List<FishDiscovery>>(emptyList())
    val discoveries = _discoveries.asStateFlow()
    private val _memoryLoading = MutableStateFlow(false)
    val memoryLoading = _memoryLoading.asStateFlow()
    private val _platform = MutableStateFlow(JSONObject())
    val platform = _platform.asStateFlow()
    private val _platformBusy = MutableStateFlow(false)
    val platformBusy = _platformBusy.asStateFlow()
    private val _proactive = MutableStateFlow(JSONObject())
    val proactive = _proactive.asStateFlow()
    private val _proactiveLoading = MutableStateFlow(false)
    val proactiveLoading = _proactiveLoading.asStateFlow()
    private val _qq = MutableStateFlow(JSONObject())
    val qq = _qq.asStateFlow()
    private val _qqBusy = MutableStateFlow(false)
    val qqBusy = _qqBusy.asStateFlow()
    private val _qqVoice = MutableStateFlow(JSONObject())
    val qqVoice = _qqVoice.asStateFlow()
    private var qqRunning = false

    private fun companionEvent(kind: String, text: String = "", source: String = "app") {
        if (source == "qq") {
            if (kind == "send") qqRunning = true
            else if (kind in setOf("reply", "cancel", "error")) qqRunning = false
        }
        companion.event(kind, text)
        val otherTurnRunning = if (source == "qq") _chat.value.busy else qqRunning
        if (otherTurnRunning && kind in setOf("reply", "done", "cancel", "error")) companion.event("send")
    }

    init {
        scope.launch {
            companion.settings.map { it.appearance }.distinctUntilChanged().collect { appearance ->
                if (runtime.state.value.ready) runtime.send(JSONObject().put("action", "companion").put("appearance", appearance.name))
            }
        }
        scope.launch {
            while (isActive) { companion.tick(zone); delay(15_000L) }
        }
        scope.launch(start=CoroutineStart.UNDISPATCHED) {
            runtime.state.collect { state ->
                if (state.error != null || state.stage == "已停止") {
                    qqRunning = false
                    companion.event(if (state.error != null) "error" else "stopped")
                    _chat.value = _chat.value.copy(busy=false)
                    _platformBusy.value = false
                    _proactiveLoading.value = false
                    _memoryLoading.value = false
                    _qqBusy.value = false
                    state.error?.let { _message.value=it }
                }
            }
        }
        scope.launch(start=CoroutineStart.UNDISPATCHED) {
            runtime.events.collect { event ->
                when (event.optString("type")) {
                    "history" -> {
                        val messages = event.optJSONArray("messages") ?: return@collect
                        _chat.value = _chat.value.copy(turns = (0 until messages.length()).map { index ->
                            messages.getJSONObject(index).let { ConversationTurn(it.getString("role"), it.getString("text")) }
                        })
                        val text = _chat.value.turns.lastOrNull { it.role == "assistant" }?.text.orEmpty()
                        if (historyHydrated && text.isNotBlank() && text != lastAssistantText) {
                            companionEvent("reply", text)
                            if (appForeground && voice.settings.value.autoRead) voice.speak(text)
                        }
                        lastAssistantText = text
                        historyHydrated = true
                    }
                    "memories" -> {
                        val items = event.optJSONArray("items")
                        _memories.value = if (items == null) emptyList() else (0 until items.length()).map { index ->
                            items.getJSONObject(index).let { FishMemory(it.getLong("id"),it.optString("tier"),it.optString("text")) }
                        }
                        _memoryLoading.value = false
                        val sources = event.optJSONArray("discoveries")
                        _discoveries.value = if (sources == null) emptyList() else (0 until sources.length()).map { index ->
                            sources.getJSONObject(index).let { FishDiscovery(it.optString("title"), it.optString("url")) }
                        }
                    }
                    "platform" -> { _platform.value=event; _platformBusy.value=false }
                    "qq" -> { _qq.value = event; _qqBusy.value = false }
                    "qq_voice" -> _qqVoice.value = event
                    "voice" -> voice.receive(event)
                    "character" -> _characterStatus.value = if (event.optBoolean("saved")) "已同步到同一个管家和共同记忆"
                        else "本机档案已保存，同步未完成：${event.optString("error")}"
                    "proactive" -> { _proactive.value=event; _proactiveLoading.value=false }
                    "done" -> companionEvent("done", lastAssistantText)
                    "busy" -> {
                        val busy = event.optBoolean("busy")
                        _chat.value = _chat.value.copy(busy = busy)
                        if (busy) companionEvent("send")
                        else if (companion.presence.value.activity in setOf(com.timeagent.core.FishActivity.THINKING, com.timeagent.core.FishActivity.WORKING)) companionEvent("cancel")
                    }
                    "session_event" -> when (event.optJSONObject("event")?.optString("type")) {
                        "tool/call" -> companion.event("tool")
                        "tool/result" -> companion.event("send")
                    }
                    "presence" -> companionEvent(event.optString("kind"), event.optString("text"), event.optString("source", "app"))
                    "speech" -> scope.launch {
                        val result = try {
                            val bytes = android.util.Base64.decode(event.getString("audioBase64"), android.util.Base64.DEFAULT)
                            JSONObject().put("text", SenseVoiceSpeech.transcribeWav(context, bytes))
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            JSONObject().put("error", error.message ?: "语音识别失败")
                        } catch (_: LinkageError) {
                            JSONObject().put("error", "这台设备未能加载离线语音识别")
                        }
                        runtime.send(JSONObject().put("action", "tool_result").put("id", event.getString("id")).put("result", result))
                    }
                    "tool" -> {
                        companion.event("tool")
                        scope.launch {
                            _tool.value = when (event.optString("tool")) {
                                "time_today", "time_schedule" -> "正在读取真实日程"
                                "time_tasks" -> "正在读取待办"
                                "time_projects" -> "正在读取项目"
                                else -> "正在处理时间事务"
                            }
                            val result = try {
                                if (event.optString("tool") == "fish_presence") presencePayload()
                                else tools.execute(event.getString("tool"),event.optJSONObject("args") ?: JSONObject())
                            }
                            catch (error: Exception) {
                                if (error is CancellationException) throw error
                                JSONObject().put("error",error.message ?: "业务操作失败")
                            }
                            runtime.send(JSONObject().put("action","tool_result").put("id",event.getString("id")).put("result",result))
                            if (result.has("error")) companion.event("error")
                            _tool.value = null
                        }
                    }
                    "error", "turn_error" -> { companionEvent("error"); _message.value = event.optString("message","内置运行连接失败"); _chat.value = _chat.value.copy(busy = false); _memoryLoading.value=false; _platformBusy.value=false; _proactiveLoading.value=false }
                    "ready" -> { _message.value = null; if (character.configured) syncCharacter() }
                    "stopped" -> {
                        qqRunning = false
                        companion.event("stopped")
                        _chat.value = _chat.value.copy(busy = false)
                        context.stopService(Intent(context,FishRuntimeService::class.java))
                    }
                }
            }
        }
    }
    private fun voiceUnavailable() { voice.failed("先启动管家，再使用在线声音；也可以选择系统语音") }
    private fun requestVoice(payload: JSONObject) {
        scope.launch {
            try {
                if (!runtime.state.value.ready) voiceUnavailable() else runtime.send(payload)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                voice.failed(error.message ?: "声音连接失败")
            }
        }
    }
    fun saveProfile(profile: FishProfile) { character.save(profile); syncCharacter() }
    fun syncCharacter() = scope.launch {
        _characterStatus.value = "档案已保存；下次启动时同步"
        if (runtime.state.value.ready) {
            try {
                runtime.send(JSONObject().put("action", "character").put("card", character.card()))
                _characterStatus.value = "档案已保存，正在同步"
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _characterStatus.value = "档案已保存在本机，同步未完成"
            }
        }
    }
    private fun presencePayload(): JSONObject {
        val state = companion.presence.value
        val config = companion.settings.value
        return JSONObject().put("mood", state.mood.name).put("activity", state.activity.name)
            .put("localTime", java.time.ZonedDateTime.now(zone).toString()).put("timezone", zone.id)
            .put("wakeHour", config.wakeHour).put("sleepHour", config.sleepHour)
            .put("appearance", config.appearance.name).put("identity", "同一个大肥鱼，外观不改变人格和共同记忆")
    }
    fun start() = scope.launch {
        try {
            _message.value = null
            val settings = AssistantSettings(context)
            val key = settings.readApiKey()
            require(key.isNotBlank()) { "请先在模型设置中填写 API Key，再启动内置角色。" }
            context.startForegroundService(Intent(context,FishRuntimeService::class.java))
            runtime.start(JSONObject().put("apiKey",key).put("model",settings.model()).put("timezone",zone.id)
                .put("characterName", character.profile.value.name)
                .put("appearance", companion.settings.value.appearance.name))
            if (runtime.state.value.error != null) context.stopService(Intent(context,FishRuntimeService::class.java))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _message.value = error.message ?: "运行环境启动失败"
        }
    }
    fun settingsSaved() = scope.launch {
        if (!runtime.state.value.ready) return@launch
        context.stopService(Intent(context,FishRuntimeService::class.java))
        runtime.stop()
        _message.value="模型设置已保存，点击准备并启动后使用新设置。"
    }
    fun editDraft(text: String) { _draft.value = text }
    fun send(text: String) = scope.launch { sendLock.withLock {
        if (text.isBlank() || _chat.value.busy) return@withLock
        if (text.length > 20000) { _message.value="消息超过 20000 字，原文已保留，请分段发送。"; return@withLock }
        if (!runtime.state.value.ready) { _message.value = "请先到管家页启动内置角色，输入已保留。"; return@withLock }
        _message.value = null
        companion.event("send", text)
        _chat.value = _chat.value.copy(busy=true,turns=_chat.value.turns + ConversationTurn("user",text.trim()))
        try {
            runtime.send(JSONObject().put("action","send").put("text",text.trim()))
            if (_draft.value.trim() == text.trim()) _draft.value=""
        }
        catch (error: Exception) { companion.event("error"); _message.value = error.message; _chat.value = _chat.value.copy(busy=false) }
    } }
    fun cancel() = scope.launch { runtime.send(JSONObject().put("action","cancel")); companionEvent("cancel") }
    fun loadMemories(query: String = "") = scope.launch {
        if (!runtime.state.value.ready) { _message.value="请先启动内置角色，再读取共同记忆。"; return@launch }
        _memoryLoading.value=true
        try { runtime.send(JSONObject().put("action","memories").put("query",query)) }
        catch (error: Exception) { _message.value=error.message; _memoryLoading.value=false }
    }
    fun platformAction(operation: String, key: String = "", enabled: Boolean = false) = scope.launch {
        if (!runtime.state.value.ready) { _message.value="请先启动内置角色，再管理平台连接。"; return@launch }
        if (_platformBusy.value) return@launch
        _platformBusy.value=true
        _message.value=null
        try { runtime.send(JSONObject().put("action","platform").put("operation",operation).put("key",key).put("enabled",enabled)) }
        catch (error: Exception) { _message.value=error.message; _platformBusy.value=false }
    }
    fun proactiveAction(operation: String = "get", config: JSONObject = JSONObject()) = scope.launch {
        if (!runtime.state.value.ready) { _message.value="请先启动内置角色，再管理主动话题。"; return@launch }
        if (_proactiveLoading.value) return@launch
        _proactiveLoading.value=true
        _message.value=null
        try { runtime.send(JSONObject().put("action","proactive").put("operation",operation).put("config",config)) }
        catch (error: Exception) { _message.value=error.message; _proactiveLoading.value=false }
    }
    fun qqAction(operation: String = "get", config: JSONObject = JSONObject()) = scope.launch {
        if (!runtime.state.value.ready) { _message.value = "请先启动内置角色，再管理QQ连接。"; return@launch }
        if (_qqBusy.value) return@launch
        _qqBusy.value = true
        try { runtime.send(JSONObject().put("action", "qq").put("operation", operation).put("config", config)) }
        catch (error: Exception) { _message.value = error.message; _qqBusy.value = false }
    }
    fun stop() = scope.launch {
        runtime.stop()
        context.stopService(Intent(context,FishRuntimeService::class.java))
        _chat.value = _chat.value.copy(busy=false)
    }
    companion object {
        @Volatile private var instance: FishSession? = null
        fun get(context: Context, repository: TimeRepository): FishSession = instance ?: synchronized(this) {
            instance ?: FishSession(context.applicationContext,repository).also { instance=it }
        }
        fun current(): FishSession? = instance
    }
}
