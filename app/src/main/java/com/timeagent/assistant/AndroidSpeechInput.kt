package com.timeagent.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Foreground, explicit one-shot recognition. The app never reads or retains audio buffers. */
class AndroidSpeechInput(
    private val context: Context,
    private val onText: (String) -> Unit,
    private val onState: (SpeechInputStatus, String?) -> Unit,
    private val onFailure: () -> Unit = {},
) : SpeechInput {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0
    private var closed = false
    private var state = SpeechInputStatus.IDLE
    val onDevice: Boolean = Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
    override val available: Boolean = onDevice || runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    override fun start() = onMain {
        if (closed || state != SpeechInputStatus.IDLE) return@onMain
        if (!available) { onState(SpeechInputStatus.IDLE, "这台设备没有可用的系统语音服务，请使用文字输入。"); return@onMain }
        val token = ++generation
        try {
            val engine = if (Build.VERSION.SDK_INT >= 31 && onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = engine
            engine.setRecognitionListener(object : RecognitionListener {
                private fun valid() = !closed && token == generation
                override fun onReadyForSpeech(params: Bundle?) { if (valid()) update(SpeechInputStatus.LISTENING) }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { if (valid()) update(SpeechInputStatus.PROCESSING) }
                override fun onError(error: Int) {
                    if (!valid()) return
                    release()
                    update(SpeechInputStatus.IDLE, errorMessage(error))
                    onFailure()
                }
                override fun onResults(results: Bundle?) {
                    if (!valid()) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }
                    release()
                    update(SpeechInputStatus.IDLE, if (text == null) "没有识别到文字，请重试或手动输入。" else "语音已转成文字，请检查后再解析。")
                    if (text != null) onText(text)
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            update(SpeechInputStatus.LISTENING)
            engine.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        } catch (_: SecurityException) {
            release(); update(SpeechInputStatus.IDLE, "麦克风权限未授予，请授权后重试，也可以使用文字输入。"); onFailure()
        } catch (_: Exception) {
            release(); update(SpeechInputStatus.IDLE, "语音服务暂时无法启动，请检查系统语音服务或使用文字输入。"); onFailure()
        }
    }
    override fun stop() = onMain {
        if (closed || state != SpeechInputStatus.LISTENING) return@onMain
        try { update(SpeechInputStatus.PROCESSING); recognizer?.stopListening() }
        catch (_: Exception) { release(); update(SpeechInputStatus.IDLE, "语音服务未完成识别，请重试或使用文字输入。"); onFailure() }
    }
    override fun cancel() = onMain {
        ++generation
        runCatching { recognizer?.cancel() }
        release()
        update(SpeechInputStatus.IDLE)
    }
    override fun close() = onMain {
        closed = true
        ++generation
        runCatching { recognizer?.cancel() }
        release()
    }
    private fun release() { runCatching { recognizer?.destroy() }; recognizer = null }
    private fun update(value: SpeechInputStatus, message: String? = null) { state = value; if (!closed) onState(value, message) }
    private fun onMain(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }
    private fun errorMessage(error: Int) = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "麦克风不可用，请检查是否被其他应用占用。"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "麦克风权限未授予，可使用文字输入。"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "系统语音服务连接失败，请重试或手动输入。"
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听清，请再次按住说话或手动输入。"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "系统语音服务正忙，请稍后重试。"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "系统缺少中文语音支持，请在系统语音设置中安装，或手动输入。"
        else -> "语音识别未完成（$error），请重试或使用文字输入。"
    }
}
