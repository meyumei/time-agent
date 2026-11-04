package com.timeagent.fish

import android.content.Context
import com.timeagent.runtime.AgentRuntime
import com.timeagent.runtime.RuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class FishRpcException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * What the pinned host advertises in its `ready` frame. The stdio protocol has no
 * separate version handshake, so compatibility is negotiated from these declared fields
 * instead of inventing a method the host does not implement.
 */
data class FishProtocol(
    val runtime: String,
    val dshVersion: String,
    val memory: String,
    val sessionId: String,
    val supported: Boolean,
    val reason: String = "",
)

/**
 * The only adapter to the existing newline-delimited JSON protocol in
 * agent-plugins/time-agent/host.mjs. Every method below corresponds to an action the
 * host actually handles; no remote method is added here.
 */
class FishRpcClient(context: Context, private val scope: CoroutineScope) {
    private val runtime = AgentRuntime(context.applicationContext)

    /** Raw process state; the controller maps it onto [FishRuntimeStatus]. */
    val state: StateFlow<RuntimeState> = runtime.state
    val events: SharedFlow<JSONObject> = runtime.events

    suspend fun start(config: JSONObject) = runtime.start(config)

    suspend fun stop() = runtime.stop()

    suspend fun send(action: JSONObject, timeoutMillis: Long = SEND_TIMEOUT_MS) {
        try {
            withTimeout(timeoutMillis) { runtime.send(action) }
        } catch (timeout: TimeoutCancellationException) {
            throw FishRpcException("运行环境未在 ${timeoutMillis / 1000} 秒内响应，请稍后重试")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalStateException) {
            val message = when (error.message) {
                "RUNTIME_NOT_RUNNING" -> "内置角色尚未就绪"
                "RUNTIME_WRITE_FAILED" -> "运行环境写入失败，请重试"
                else -> error.message ?: "运行环境连接失败"
            }
            throw FishRpcException(message, error)
        }
    }

    /** Fire-and-forget for UI actions that already own their busy flag. */
    fun sendOrReport(action: JSONObject, onError: (String) -> Unit): Job = scope.launch {
        try {
            send(action)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onError(error.message ?: "运行环境连接失败")
        }
    }

    // Only actions handled by host.mjs. Keeping them typed prevents unsupported calls.
    suspend fun sendChat(text: String) = send(action("send").put("text", text.trim()))

    suspend fun cancelTurn() = send(action("cancel"))

    suspend fun requestHistory() = send(action("history"))

    suspend fun loadMemories(query: String = "") = send(action("memories").put("query", query))

    suspend fun platform(operation: String, key: String = "", enabled: Boolean = false) =
        send(action("platform").put("operation", operation).put("key", key).put("enabled", enabled))

    suspend fun proactive(operation: String = "get", config: JSONObject = JSONObject()) =
        send(action("proactive").put("operation", operation).put("config", config))

    suspend fun qq(operation: String = "get", config: JSONObject = JSONObject()) =
        send(action("qq").put("operation", operation).put("config", config))

    suspend fun syncCharacter(card: JSONObject) = send(action("character").put("card", card))

    suspend fun setCompanionAppearance(appearance: String) =
        send(action("companion").put("appearance", appearance))

    suspend fun speak(id: String, text: String, voice: String, rate: Int, pitch: Int) =
        send(
            action("speak")
                .put("id", id)
                .put("text", text)
                .put("voice", voice)
                .put("rate", rate)
                .put("pitch", pitch),
        )

    suspend fun respondTool(id: String, result: JSONObject) =
        send(action("tool_result").put("id", id).put("result", result))

    /** Reads the declared runtime/version/memory identity without assuming extra fields. */
    fun negotiate(event: JSONObject): FishProtocol {
        val declaredRuntime = event.optString("runtime")
        // The current host only advertises dshVersion; prefer an explicit protocol
        // version if a later build adds one, without inventing the field ourselves.
        val version = event.optString("protocolVersion").ifBlank { event.optString("dshVersion") }
        val memory = event.optString("memory")
        val sessionId = event.optString("sessionId")
        val problem = when {
            declaredRuntime.isBlank() -> "运行时没有声明自身标识"
            else -> versionProblem(version) ?: memoryProblem(memory)
        }
        return FishProtocol(
            runtime = declaredRuntime,
            dshVersion = version,
            memory = memory,
            sessionId = sessionId,
            supported = problem == null,
            reason = problem.orEmpty(),
        )
    }

    private fun versionProblem(version: String): String? {
        if (version.isBlank()) return "运行时没有声明协议版本，无法确认兼容性"
        val match = Regex("^(\\d+)\\.(\\d+)").find(version) ?: return "无法识别协议版本 $version"
        val major = match.groupValues[1].toIntOrNull() ?: return "无法识别协议版本 $version"
        val minor = match.groupValues[2].toIntOrNull() ?: return "无法识别协议版本 $version"
        return when {
            major == 0 && minor >= MIN_DSH_MINOR -> null
            major == 0 -> "运行时协议版本 $version 低于当前应用支持的 0.$MIN_DSH_MINOR"
            else -> "运行时协议版本 $version 高于当前应用支持的 0.x，已停止连接以免读写错位"
        }
    }

    private fun memoryProblem(memory: String): String? = when {
        memory.isBlank() -> "运行时没有声明共同记忆服务"
        memory != "dsh-remembers" -> "共同记忆服务是 $memory，不是唯一的 dsh-remembers"
        else -> null
    }

    private fun action(name: String) = JSONObject().put("action", name)

    private companion object {
        const val SEND_TIMEOUT_MS = 15_000L

        /** host.mjs reports dshVersion=0.2.1-rc.2; 0.2 is the protocol family it speaks. */
        const val MIN_DSH_MINOR = 2
    }
}
