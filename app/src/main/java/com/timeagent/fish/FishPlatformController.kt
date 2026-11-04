package com.timeagent.fish

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * QQ + Bilibili connection state, proactive topics and the presence side effects of a
 * platform turn. Authorization stays exactly as configured in the plugin; nothing here
 * approves individual messages.
 */
class FishPlatformController(
    private val scope: CoroutineScope,
    private val rpc: FishRpcClient,
    private val isReady: () -> Boolean,
    private val sessionBusy: () -> Boolean,
    private val onNotice: (String?) -> Unit,
    private val onPresence: (kind: String, text: String) -> Unit,
    private val onEnsureStarted: () -> Unit,
) {
    private val _platform = MutableStateFlow(JSONObject())
    val platform: StateFlow<JSONObject> = _platform.asStateFlow()

    private val _platformBusy = MutableStateFlow(false)
    val platformBusy: StateFlow<Boolean> = _platformBusy.asStateFlow()

    private val _proactive = MutableStateFlow(JSONObject())
    val proactive: StateFlow<JSONObject> = _proactive.asStateFlow()

    private val _proactiveLoading = MutableStateFlow(false)
    val proactiveLoading: StateFlow<Boolean> = _proactiveLoading.asStateFlow()

    private val _qq = MutableStateFlow(JSONObject())
    val qq: StateFlow<JSONObject> = _qq.asStateFlow()

    private val _qqBusy = MutableStateFlow(false)
    val qqBusy: StateFlow<Boolean> = _qqBusy.asStateFlow()

    private val _qqVoice = MutableStateFlow(JSONObject())
    val qqVoice: StateFlow<JSONObject> = _qqVoice.asStateFlow()

    @Volatile private var qqRunning = false

    /**
     * The app and QQ share one fish, but only one turn may run at a time. If a QQ turn
     * is running while the app replies (or the other way round) the character is shown
     * as busy again after the visible turn finishes.
     */
    fun companionEvent(kind: String, text: String = "", source: String = "app") {
        if (source == "qq") {
            if (kind == "send") qqRunning = true
            else if (kind in QQ_TURN_END) qqRunning = false
        }
        onPresence(kind, text)
        val otherTurnRunning = if (source == "qq") sessionBusy() else qqRunning
        if (otherTurnRunning && kind in TURN_END) onPresence("send", "")
    }

    fun handlePresence(event: JSONObject) {
        companionEvent(
            event.optString("kind"),
            event.optString("text"),
            event.optString("source", "app"),
        )
    }

    /** Returns true when the event belonged to platform/QQ/proactive state. */
    fun handle(event: JSONObject): Boolean = when (event.optString("type")) {
        "platform" -> {
            _platform.value = event
            _platformBusy.value = false
            true
        }
        "qq" -> {
            _qq.value = event
            _qqBusy.value = false
            true
        }
        "qq_voice" -> {
            _qqVoice.value = event
            true
        }
        "proactive" -> {
            _proactive.value = event
            _proactiveLoading.value = false
            true
        }
        else -> false
    }

    fun platformAction(operation: String, key: String = "", enabled: Boolean = false): Job {
        if (!requireRuntime("请先启动内置角色，再管理平台连接。")) return scope.launch { }
        if (_platformBusy.value) return scope.launch { }
        _platformBusy.value = true
        onNotice(null)
        return scope.launch {
            try {
                rpc.platform(operation, key, enabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _platformBusy.value = false
                onNotice(error.message ?: "平台连接操作失败")
            }
        }
    }

    fun proactiveAction(operation: String = "get", config: JSONObject = JSONObject()): Job {
        if (!requireRuntime("请先启动内置角色，再管理主动话题。")) return scope.launch { }
        if (_proactiveLoading.value) return scope.launch { }
        _proactiveLoading.value = true
        onNotice(null)
        return scope.launch {
            try {
                rpc.proactive(operation, config)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _proactiveLoading.value = false
                onNotice(error.message ?: "主动话题操作失败")
            }
        }
    }

    fun qqAction(operation: String = "get", config: JSONObject = JSONObject()): Job {
        if (!requireRuntime("请先启动内置角色，再管理QQ连接。")) return scope.launch { }
        if (_qqBusy.value) return scope.launch { }
        _qqBusy.value = true
        return scope.launch {
            try {
                rpc.qq(operation, config)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _qqBusy.value = false
                onNotice(error.message ?: "QQ 连接操作失败")
            }
        }
    }

    fun clearLoading() {
        _platformBusy.value = false
        _proactiveLoading.value = false
        _qqBusy.value = false
    }

    fun onRuntimeLost() {
        qqRunning = false
        clearLoading()
    }

    private fun requireRuntime(message: String): Boolean {
        if (isReady()) return true
        onNotice(message)
        onEnsureStarted()
        return false
    }

    private companion object {
        val QQ_TURN_END = setOf("reply", "cancel", "error")
        val TURN_END = setOf("reply", "done", "cancel", "error")
    }
}
