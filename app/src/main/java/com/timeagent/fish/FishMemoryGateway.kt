package com.timeagent.fish

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class FishMemory(val id: Long, val tier: String, val text: String)
data class FishDiscovery(val title: String, val url: String)

/**
 * Read access to dsh-remembers, the only authoritative common memory. Nothing is
 * duplicated into a second native store; the in-memory list is a display cache.
 *
 * The pinned stdio protocol exposes `memories` (list/search) but no edit/delete action.
 * Editing therefore remains unsupported until the plugin protocol adds it; this class
 * reports that gap instead of pretending to write.
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
                    .put("id", item.id).put("expectedText", item.text).put("text", text))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _loading.value = false; onNotice(error.message ?: "共同记忆未保存，请刷新后重试") }
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
