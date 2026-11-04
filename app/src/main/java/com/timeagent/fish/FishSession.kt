package com.timeagent.fish

import android.content.Context
import android.content.Intent
import com.timeagent.assistant.AssistantChatState
import com.timeagent.core.FishActivity
import com.timeagent.data.TimeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** One application-owned DSH session, shared by the dock and the butler page. */
class FishSession private constructor(private val context: Context, private val repository: TimeRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Character presentation is local; the authoritative conversation and memory stay in DSH.
    val companion = FishCompanionState(context)
    val character = FishCharacter(context)
    private val tools = FishTimeTools(repository)

    private val conversation = FishConversationRepository(context)
    val localAssistant = com.timeagent.assistant.AssistantConversation(repository)
    val chat: StateFlow<AssistantChatState> = kotlinx.coroutines.flow.combine(
        conversation.chat, localAssistant.state,
    ) { remote, local ->
        local.copy(
            turns = (remote.turns + local.turns.map { turn ->
                if (turn.role == "assistant") turn.copy(text = "本地执行 · ${turn.text}", localOnly = true)
                else turn.copy(localOnly = true)
            }).sortedBy { it.at },
            busy = remote.busy || local.busy,
            completionRevision = remote.completionRevision + local.completionRevision,
        )
    }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, conversation.chat.value)
    val draft: StateFlow<String> = conversation.draft
    fun latestRemoteReply(): String = conversation.lastAssistantText()
    val message: StateFlow<String?> = conversation.notice
    val zone = repository.zone

    // Runtime lifecycle and the only stdio client.
    val runtime = FishRuntimeController(context, scope) { apiKey, model ->
        JSONObject()
            .put("apiKey", apiKey)
            .put("model", model)
            .put("timezone", zone.id)
            .put("characterName", character.profile.value.name)
            .put("appearance", companion.settings.value.appearance.name)
    }

    val voice = FishVoiceController(context, scope, runtime.rpc) { runtime.status.value.ready }
    val presence = FishPresenceController(companion, zone, scope) { voice.status.value.speaking }

    private val platformController = FishPlatformController(
        scope = scope,
        rpc = runtime.rpc,
        isReady = { runtime.status.value.ready },
        sessionBusy = { chat.value.busy },
        onNotice = conversation::setNotice,
        onPresence = { kind, text -> presence.event(kind, text) },
        onEnsureStarted = { runtime.ensureRunning("平台连接") },
    )
    val platform: StateFlow<JSONObject> = platformController.platform
    val platformBusy: StateFlow<Boolean> = platformController.platformBusy
    val proactive: StateFlow<JSONObject> = platformController.proactive
    val proactiveLoading: StateFlow<Boolean> = platformController.proactiveLoading
    val qq: StateFlow<JSONObject> = platformController.qq
    val qqBusy: StateFlow<Boolean> = platformController.qqBusy
    val qqVoice: StateFlow<JSONObject> = platformController.qqVoice

    private val memoryGateway = FishMemoryGateway(
        scope = scope,
        rpc = runtime.rpc,
        isReady = { runtime.status.value.ready },
        onEnsureStarted = { runtime.ensureRunning("共同记忆") },
        onNotice = conversation::setNotice,
    )
    val memories: StateFlow<List<FishMemory>> = memoryGateway.memories
    val discoveries: StateFlow<List<FishDiscovery>> = memoryGateway.discoveries
    val memoryLoading: StateFlow<Boolean> = memoryGateway.loading
    val memoryReadOnly: StateFlow<Boolean> = memoryGateway.readOnly
    val memoryHistory = memoryGateway.history
    val memoryLastChange = memoryGateway.lastChange

    private val toolBridge = FishToolBridge(
        scope = scope,
        tools = tools,
        rpc = runtime.rpc,
        presencePayload = presence::toolPayload,
        onToolPresence = { presence.event("tool") },
        onErrorPresence = { presence.event("error") },
        onNotice = conversation::setNotice,
    )
    val tool: StateFlow<String?> = toolBridge.tool

    @Volatile var appForeground = false
    private val _characterStatus = MutableStateFlow("")
    val characterStatus: StateFlow<String> = _characterStatus.asStateFlow()
    private val sendLock = Mutex()
    private val localContextPreferences = context.getSharedPreferences("fish_local_context", Context.MODE_PRIVATE)
    private val localContextEvents = JSONObject(localContextPreferences.getString("pending", "{}") ?: "{}")
    private var sentContextIds: List<String> = emptyList()

    private fun rememberLocalContext(requestId: String, text: String) {
        localContextEvents.put(requestId, text)
        localContextPreferences.edit().putString("pending", localContextEvents.toString()).apply()
    }

    private suspend fun sendRemote(text: String) {
        sentContextIds = localContextEvents.keys().asSequence().toList()
        val events = org.json.JSONArray()
        sentContextIds.forEach { id -> events.put(JSONObject().put("id", id).put("text", localContextEvents.getString(id))) }
        if (sentContextIds.isNotEmpty()) runtime.rpc.send(JSONObject().put("action", "local_context").put("events", events))
        runtime.rpc.sendChat(text)
    }

    private suspend fun flushPending() = sendLock.withLock {
        if (chat.value.busy || !runtime.status.value.ready || conversation.pending.value.isEmpty()) return@withLock
        setChatBusy(true)
        if (conversation.flushPending(::sendRemote) == 0) setChatBusy(false)
    }

    init {
        scope.launch {
            companion.settings.map { it.appearance }.distinctUntilChanged().collect { appearance ->
                if (runtime.status.value.ready) {
                    try {
                        runtime.rpc.setCompanionAppearance(appearance.name)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                    }
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var previous: FishRuntimeStatus? = null
            runtime.status.collect { status ->
                if (status is FishRuntimeStatus.Failed && previous !is FishRuntimeStatus.Failed) {
                    conversation.setNotice(status.detail)
                    platformController.companionEvent("error", "", "app")
                }
                if (status is FishRuntimeStatus.Stopped && previous?.active == true) {
                    platformController.companionEvent("stopped", "", "app")
                }
                if (!status.active || status is FishRuntimeStatus.Failed) {
                    platformController.onRuntimeLost()
                    memoryGateway.onRuntimeLost()
                    setChatBusy(false)
                }
                previous = status
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runtime.rpc.events.collect { event -> handleEvent(event) }
        }
    }

    private suspend fun handleEvent(event: JSONObject) {
        when (event.optString("type")) {
            "history" -> conversation.handleHistory(event) { text ->
                platformController.companionEvent("reply", text, "app")
                if (appForeground) voice.autoRead(text)
            }
            "memories" -> memoryGateway.handle(event)
            "memory_changed" -> memoryGateway.handleChanged(event)
            "memory_history" -> memoryGateway.handleHistory(event)
            "platform", "qq", "qq_voice", "proactive" -> platformController.handle(event)
            "voice" -> voice.receive(event)
            "speech" -> voice.handleSpeech(event)
            "character" -> {
                _characterStatus.value = if (event.optBoolean("saved")) {
                    "已同步到同一个管家和共同记忆"
                } else {
                    "本机档案已保存，同步未完成：${event.optString("error")}"
                }
            }
            "done" -> {
                sentContextIds.forEach { localContextEvents.remove(it) }
                sentContextIds = emptyList()
                localContextPreferences.edit().putString("pending", localContextEvents.toString()).apply()
                conversation.completeTurn()
                platformController.companionEvent("done", conversation.lastAssistantText(), "app")
            }
            "busy" -> {
                val busy = event.optBoolean("busy")
                setChatBusy(busy)
                if (busy) {
                    platformController.companionEvent("send", "", "app")
                } else if (companion.presence.value.activity in
                    setOf(FishActivity.THINKING, FishActivity.WORKING)
                ) {
                    platformController.companionEvent("cancel", "", "app")
                }
                if (!busy) flushPending()
            }
            "session_event" -> when (event.optJSONObject("event")?.optString("type")) {
                "tool/call" -> presence.event("tool")
                "tool/result" -> presence.event("send")
            }
            "presence" -> platformController.handlePresence(event)
            "tool" -> toolBridge.handle(event)
            "error", "turn_error" -> {
                platformController.companionEvent("error", "", "app")
                conversation.setNotice(event.optString("message", "内置运行连接失败"))
                setChatBusy(false)
                memoryGateway.stopLoading()
                platformController.clearLoading()
            }
            "ready" -> {
                conversation.setNotice(null)
                if (character.configured) syncCharacter()
                flushPending()
            }
            "stopped" -> {
                platformController.companionEvent("stopped", "", "app")
                setChatBusy(false)
                memoryGateway.onRuntimeLost()
                platformController.onRuntimeLost()
                context.stopService(Intent(context, FishRuntimeService::class.java))
            }
        }
    }

    private fun setChatBusy(value: Boolean) {
        conversation.setBusy(value)
        runtime.setBusy(value)
    }

    fun saveProfile(profile: FishProfile) {
        character.save(profile)
        syncCharacter()
    }

    fun syncCharacter(): Job = scope.launch {
        _characterStatus.value = "档案已保存；下次启动时同步"
        if (runtime.status.value.ready) {
            try {
                runtime.rpc.syncCharacter(character.card())
                _characterStatus.value = "档案已保存，正在同步"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _characterStatus.value = "档案已保存在本机，同步未完成"
            }
        }
    }

    fun start(): Job = scope.launch {
        conversation.setNotice(null)
        runtime.start()
        when (val status = runtime.status.value) {
            is FishRuntimeStatus.NeedsKey -> conversation.setNotice(status.detail)
            is FishRuntimeStatus.Failed -> conversation.setNotice(status.detail)
            FishRuntimeStatus.UnsupportedDevice -> conversation.setNotice("内置角色运行环境目前只支持 arm64-v8a；全部本地业务仍可使用")
            else -> Unit
        }
    }

    fun settingsSaved(): Job = scope.launch {
        if (runtime.status.value.ready) {
            runtime.stop(userIntent = false)
            conversation.setNotice("模型设置已保存，点击准备并启动后使用新设置。")
        } else {
            runtime.refreshEnvironment()
        }
    }

    fun editDraft(text: String) {
        conversation.editDraft(text)
    }

    fun send(text: String): Job = scope.launch {
        sendLock.withLock {
            if (text.isBlank() || chat.value.busy) return@withLock
            if (text.length > 20000) {
                conversation.setNotice("消息超过 20000 字，原文已保留，请分段发送。")
                return@withLock
            }
            val direct = text.trim().startsWith("@鱼")
            val input = if (direct) text.trim().removePrefix("@鱼").trim() else text.trim()
            if (!direct) {
                val assistantContext = com.timeagent.assistant.AssistantContext(
                    repository.now(), zone,
                    com.timeagent.core.WeekState.startOfWeek(repository.now().atZone(zone).toLocalDate()),
                    repository.snapshot(),
                )
                val parsed = com.timeagent.assistant.LocalAssistantParser().parse(input, assistantContext)
                val continuing = localAssistant.state.value.pending != null ||
                    input in setOf("撤销", "重做", "取消", "确认", "确认保存", "确认执行")
                if (parsed is com.timeagent.assistant.Interpretation.Parsed || continuing ||
                    localAssistant.matchingQuickEntries(input).isNotEmpty()) {
                    localAssistant.submit(input, assistantContext)
                    localAssistant.state.value.commandResult?.takeIf {
                        it.status in setOf(com.timeagent.domain.command.CommandStatus.APPLIED, com.timeagent.domain.command.CommandStatus.DUPLICATE)
                    }?.let { result ->
                        rememberLocalContext(result.requestId, "本地指令：$input；结果：${result.message}")
                    }
                    if (draft.value.trim() == text.trim()) conversation.editDraft("")
                    return@withLock
                }
            }
            if (!runtime.status.value.ready) {
                conversation.enqueue(input)
                conversation.setNotice(
                    when (val status = runtime.status.value) {
                        is FishRuntimeStatus.Installing ->
                            "内置角色正在准备，消息已保留，就绪后会自动发出。"
                        FishRuntimeStatus.Starting ->
                            "内置角色正在准备，消息已保留，就绪后会自动发出。"
                        is FishRuntimeStatus.NeedsKey ->
                            "请先在模型设置中填写 API Key，消息已保留。"
                        FishRuntimeStatus.UnsupportedDevice ->
                            "这台设备不支持内置角色，消息已保留；本地业务仍可使用。"
                        else -> "请先到管家页启动内置角色，消息已保留，就绪后会自动发出。"
                    },
                )
                runtime.ensureRunning("发送消息")
                return@withLock
            }
            conversation.setNotice(null)
            platformController.companionEvent("send", text, "app")
            conversation.appendUser(text)
            setChatBusy(true)
            try {
                sendRemote(input)
                if (draft.value.trim() == text.trim()) conversation.editDraft("")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                platformController.companionEvent("error", "", "app")
                conversation.setNotice(error.message)
                setChatBusy(false)
            }
        }
    }

    fun cancel(): Job = scope.launch {
        try {
            runtime.rpc.cancelTurn()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            conversation.setNotice(error.message)
        }
        platformController.companionEvent("cancel", "", "app")
    }

    fun loadMemories(query: String = ""): Job = scope.launch { memoryGateway.load(query) }
    fun editMemory(item: FishMemory, text: String) = memoryGateway.edit(item, text)
    fun deleteMemory(item: FishMemory) = memoryGateway.delete(item)
    fun restoreMemory(token: String, undo: Boolean) = memoryGateway.restore(token, undo)

    fun platformAction(operation: String, key: String = "", enabled: Boolean = false): Job =
        platformController.platformAction(operation, key, enabled)

    fun proactiveAction(operation: String = "get", config: JSONObject = JSONObject()): Job =
        platformController.proactiveAction(operation, config)

    fun qqAction(operation: String = "get", config: JSONObject = JSONObject()): Job =
        platformController.qqAction(operation, config)

    fun stop(): Job = scope.launch {
        runtime.stop()
        setChatBusy(false)
    }

    companion object {
        @Volatile private var instance: FishSession? = null
        fun get(context: Context, repository: TimeRepository): FishSession = instance ?: synchronized(this) {
            instance ?: FishSession(context.applicationContext, repository).also { instance = it }
        }
        fun current(): FishSession? = instance
    }
}
