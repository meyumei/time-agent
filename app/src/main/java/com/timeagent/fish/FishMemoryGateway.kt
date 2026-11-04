package com.timeagent.fish

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

data class FishMemory(val id: Long, val tier: String, val text: String)
data class FishDiscovery(val title: String, val url: String)
data class FishMemoryChange(val token: String, val text: String, val deleted: Boolean, val undone: Boolean)

/**
 * Read access to dsh-remembers, the only authoritative common memory. Nothing is
 * duplicated into a second native store; the in-memory list is a display cache.
 *
 * Edits and their before/after snapshots stay in the authoritative DSH database.
 */
class FishMemoryGateway(
    private val scope: CoroutineScope,
    private val rpc: FishRpcClient,
    private val isReady: () -> Boolean,
    private val onEnsureStarted: () -> Unit,
    private val onNotice: (String?) -> Unit,
) {
    private val _memories = MutableStateFlow<List<FishMemory>>(emptyList())
    val memories: StateFlow<List<FishMemory>> = _memories.asStateFlow()

    private val _discoveries = MutableStateFlow<List<FishDiscovery>>(emptyList())
    val discoveries: StateFlow<List<FishDiscovery>> = _discoveries.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _readOnly = MutableStateFlow(false)
    val readOnly: StateFlow<Boolean> = _readOnly.asStateFlow()
    private val _history = MutableStateFlow<List<FishMemoryChange>>(emptyList())
    val history = _history.asStateFlow()
    private val _lastChange = MutableStateFlow<Pair<String, Boolean>?>(null)
    val lastChange = _lastChange.asStateFlow()

    val editingSupported: Boolean = true
    val editingUnavailableReason: String =
        "运行时不可用时，缓存仅供查看；启动后可编辑共同记忆。"

    fun edit(item: FishMemory, text: String) = change(item, text)
    fun delete(item: FishMemory) = change(item, null)

    private fun change(item: FishMemory, text: String?) {
        if (!isReady()) { onNotice(editingUnavailableReason); return }
        _loading.value = true
        scope.launch {
            try {
                rpc.send(JSONObject().put("action", if (text == null) "memory_delete" else "memory_edit")
                    .put("id", item.id).put("expectedText", item.text).put("text", text)
                    .put("requestId", UUID.randomUUID().toString()))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _loading.value = false; onNotice(error.message ?: "共同记忆未保存，请刷新后重试") }
        }
    }

    fun restore(token: String, undo: Boolean) {
        if (!isReady()) { onNotice(editingUnavailableReason); return }
        _loading.value = true
        scope.launch {
            try {
                rpc.send(JSONObject().put("action", "memory_restore").put("undoToken", token).put("undo", undo))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _loading.value = false; onNotice(error.message ?: "共同记忆未恢复，请刷新后重试") }
        }
    }

    fun handleChanged(event: JSONObject) {
        _lastChange.value = event.getString("undoToken") to event.optBoolean("undone")
        onNotice(null)
    }

    fun handleHistory(event: JSONObject) {
        val changes = event.optJSONArray("changes") ?: return
        _history.value = (0 until changes.length()).map { index ->
            val item = changes.getJSONObject(index)
            FishMemoryChange(item.getString("undoToken"), item.getString("text"),
                item.optBoolean("deleted"), item.optBoolean("undone"))
        }
    }

    fun load(query: String = "") {
        if (!isReady()) {
            _readOnly.value = _memories.value.isNotEmpty() || _discoveries.value.isNotEmpty()
            onNotice("请先启动内置角色，再读取共同记忆。")
            onEnsureStarted()
            return
        }
        if (_loading.value) return
        _loading.value = true
        _readOnly.value = false
        scope.launch {
            try {
                rpc.loadMemories(query)
                rpc.send(JSONObject().put("action", "memory_history"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _loading.value = false
                _readOnly.value = _memories.value.isNotEmpty() || _discoveries.value.isNotEmpty()
                onNotice(error.message ?: "共同记忆读取失败")
            }
        }
    }

    fun handle(event: JSONObject) {
        val items = event.optJSONArray("items")
        _memories.value = if (items == null) {
            emptyList()
        } else {
            (0 until items.length()).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                FishMemory(item.optLong("id"), item.optString("tier"), item.optString("text"))
            }
        }
        val sources = event.optJSONArray("discoveries")
        _discoveries.value = if (sources == null) {
            emptyList()
        } else {
            (0 until sources.length()).mapNotNull { index ->
                val source = sources.optJSONObject(index) ?: return@mapNotNull null
                FishDiscovery(source.optString("title"), source.optString("url"))
            }
        }
        _loading.value = false
        _readOnly.value = false
    }

    fun stopLoading() {
        _loading.value = false
    }

    fun onRuntimeLost() {
        _loading.value = false
        _readOnly.value = _memories.value.isNotEmpty() || _discoveries.value.isNotEmpty()
    }
}
