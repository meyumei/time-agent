package com.timeagent.fish

import com.timeagent.domain.command.Origin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Receives the host's local `tool` callbacks, turns them into command-originated writes
 * through [FishTimeTools]/CommandExecutor, and returns the structured result to the same
 * tool call id. It never fabricates a success result.
 */
class FishToolBridge(
    private val scope: CoroutineScope,
    private val tools: FishTimeTools,
    private val rpc: FishRpcClient,
    private val presencePayload: () -> JSONObject,
    private val onToolPresence: () -> Unit,
    private val onErrorPresence: () -> Unit,
    private val onNotice: (String?) -> Unit,
) {
    private val _tool = MutableStateFlow<String?>(null)
    val tool: StateFlow<String?> = _tool.asStateFlow()

    fun handle(event: JSONObject) {
        onToolPresence()
        scope.launch {
            _tool.value = statusFor(event.optString("tool"))
            try {
                val result = try {
                    if (event.optString("tool") == "fish_presence") {
                        presencePayload()
                    } else {
                        tools.execute(
                            event.getString("tool"),
                            event.optJSONObject("args") ?: JSONObject(),
                            Origin.FISH(
                                event.getString("sessionId"),
                                event.getString("messageId"),
                                event.getString("toolCallId"),
                            ),
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    JSONObject().put("error", error.message ?: "业务操作失败")
                }

                try {
                    rpc.respondTool(event.getString("id"), result)
                    if (result.has("error")) onErrorPresence()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    onNotice(error.message ?: "工具结果未能送回管家，请重新询问")
                }
            } finally {
                _tool.value = null
            }
        }
    }

    private fun statusFor(tool: String): String = when (tool) {
        "time_today", "time_schedule" -> "正在读取真实日程"
        "time_tasks" -> "正在读取待办"
        "time_projects" -> "正在读取项目"
        else -> "正在处理时间事务"
    }
}
