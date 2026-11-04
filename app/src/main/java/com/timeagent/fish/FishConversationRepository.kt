package com.timeagent.fish

import android.content.Context
import com.timeagent.assistant.AssistantChatState
import com.timeagent.assistant.ConversationTurn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Display cache for the one DSH conversation: recent turns, the draft and messages that
 * were typed while the runtime was unavailable. The DSH session remains authoritative;
 * every `history` frame replaces this cache.
 */
class FishConversationRepository(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences("fish_conversation", Context.MODE_PRIVATE)
    private val queueLock = Mutex()

    private val _chat = MutableStateFlow(AssistantChatState(turns = readTurns()))
    val chat: StateFlow<AssistantChatState> = _chat.asStateFlow()

    private val _draft = MutableStateFlow(preferences.getString(KEY_DRAFT, "").orEmpty())
    val draft: StateFlow<String> = _draft.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _pending = MutableStateFlow(readQueue())
    val pending: StateFlow<List<String>> = _pending.asStateFlow()

    private var historyHydrated = false
    private var lastAssistantText = ""

    fun setNotice(value: String?) {
        _notice.value = value
    }

    fun editDraft(text: String) {
        _draft.value = text
        preferences.edit().putString(KEY_DRAFT, text).apply()
    }

    fun setBusy(value: Boolean) {
        _chat.value = _chat.value.copy(busy = value)
    }

    fun lastAssistantText(): String = lastAssistantText

    fun appendUser(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        val turns = (_chat.value.turns + ConversationTurn("user", trimmed)).takeLast(MAX_TURNS)
        _chat.value = _chat.value.copy(turns = turns)
        persistTurns(turns)
    }

    /**
     * Replaces the display cache with the authoritative DSH history. Only a genuinely
     * new final assistant message is reported, so reconnecting never re-reads old text.
     */
    fun handleHistory(event: JSONObject, onNewAssistantReply: (String) -> Unit) {
        val messages = event.optJSONArray("messages") ?: return
        val turns = (0 until messages.length()).mapNotNull { index ->
            val message = messages.optJSONObject(index) ?: return@mapNotNull null
            val role = message.optString("role")
            val text = message.optString("text")
            if (role.isBlank() || text.isBlank()) null else ConversationTurn(role, text)
        }
        _chat.value = _chat.value.copy(turns = turns)
        val text = turns.lastOrNull { it.role == "assistant" }?.text.orEmpty()
        if (historyHydrated && text.isNotBlank() && text != lastAssistantText) {
            onNewAssistantReply(text)
        }
        lastAssistantText = text
        historyHydrated = true
        persistTurns(turns)
    }

    /** Queues a message that arrived while the runtime was not ready. */
    fun enqueue(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        _pending.value = _pending.value + trimmed
        persistQueue(_pending.value)
    }

    /**
     * Sends the oldest queued message. The host accepts one turn at a time, so the next
     * message is sent after the following `busy=false` frame. A rejected message stays
     * queued for the next attempt.
     */
    suspend fun flushPending(send: suspend (String) -> Unit): Int = queueLock.withLock {
        var sent = 0
        val next = _pending.value.firstOrNull()
        if (next != null) {
            try {
                send(next)
                _pending.value = _pending.value.drop(1)
                persistQueue(_pending.value)
                appendUser(next)
                sent = 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                setNotice(error.message ?: "保留的消息未能发出，已继续保留")
            }
        }
        sent
    }

    fun clearQueue() {
        _pending.value = emptyList()
        persistQueue(emptyList())
    }

    private fun readTurns(): List<ConversationTurn> {
        val raw = preferences.getString(KEY_TURNS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val role = item.optString("role")
                val text = item.optString("text")
                if (role.isBlank() || text.isBlank()) null
                else ConversationTurn(role, text, item.optBoolean("localOnly", false))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistTurns(turns: List<ConversationTurn>) {
        try {
            val array = JSONArray()
            turns.takeLast(MAX_TURNS).forEach { turn ->
                array.put(
                    JSONObject()
                        .put("role", turn.role)
                        .put("text", turn.text)
                        .put("localOnly", turn.localOnly),
                )
            }
            preferences.edit().putString(KEY_TURNS, array.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun readQueue(): List<String> {
        val raw = preferences.getString(KEY_PENDING, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistQueue(queue: List<String>) {
        try {
            preferences.edit().putString(KEY_PENDING, JSONArray(queue).toString()).apply()
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val KEY_TURNS = "turns"
        const val KEY_DRAFT = "draft"
        const val KEY_PENDING = "pending"
        const val MAX_TURNS = 60
    }
}
