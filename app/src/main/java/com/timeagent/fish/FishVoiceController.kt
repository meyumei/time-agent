package com.timeagent.fish

import android.content.Context
import android.util.Base64
import com.timeagent.assistant.SenseVoiceSpeech
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Owns speech output and the QQ voice-note transcription callback. Edge TTS is only
 * requested while the runtime is ready; the system TTS provider keeps working offline.
 */
class FishVoiceController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val rpc: FishRpcClient,
    private val isReady: () -> Boolean,
) {
    private val engine: FishVoice = FishVoice(context) { payload -> request(payload) }

    val settings: StateFlow<FishVoiceSettings> = engine.settings
    val status: StateFlow<FishVoiceStatus> = engine.status

    fun save(value: FishVoiceSettings) = engine.save(value)

    fun speak(text: String) {
        if (text.isBlank()) return
        if (settings.value.provider != "system" && !isReady()) {
            engine.failed("先启动管家，再使用在线声音；也可以在声音设置里选择系统语音")
            return
        }
        engine.speak(text)
    }

    fun stop() = engine.stop()

    fun receive(event: JSONObject) = engine.receive(event)

    fun failed(message: String) = engine.failed(message)

    /** Auto-read is gated by the caller (foreground + user setting). */
    fun autoRead(text: String) {
        if (settings.value.autoRead && text.isNotBlank()) speak(text)
    }

    /** Offline transcription of a QQ voice note; the result goes back to the same host id. */
    fun handleSpeech(event: JSONObject) {
        scope.launch {
            val result = try {
                val bytes = Base64.decode(event.getString("audioBase64"), Base64.DEFAULT)
                JSONObject().put("text", SenseVoiceSpeech.transcribeWav(context, bytes))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                JSONObject().put("error", error.message ?: "语音识别失败")
            } catch (_: LinkageError) {
                JSONObject().put("error", "这台设备未能加载离线语音识别")
            }
            try {
                rpc.respondTool(event.getString("id"), result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The host will time out the same request; nothing else to do here.
            }
        }
    }

    private fun request(payload: JSONObject) {
        scope.launch {
            if (!isReady()) {
                engine.failed("先启动管家，再使用在线声音；也可以选择系统语音")
                return@launch
            }
            try {
                rpc.send(payload)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                engine.failed(error.message ?: "声音连接失败")
            }
        }
    }
}
