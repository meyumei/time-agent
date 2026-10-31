package com.timeagent.fish

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class FishVoiceSettings(val provider: String = "edge", val voice: String = "zh-CN-XiaoyiNeural",
    val rate: Int = 8, val pitch: Int = 0, val autoRead: Boolean = false)
data class FishVoiceStatus(val message: String = "", val speaking: Boolean = false, val preparing: Boolean = false)

class FishVoice(private val context: Context, private val request: (JSONObject) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("fish_voice", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(FishVoiceSettings(prefs.getString("provider", "edge")!!,
        prefs.getString("voice", "zh-CN-XiaoyiNeural")!!, prefs.getInt("rate", 8), prefs.getInt("pitch", 0),
        prefs.getBoolean("autoRead", false)))
    val settings = _settings.asStateFlow()
    private val _status = MutableStateFlow(FishVoiceStatus())
    val status = _status.asStateFlow()
    private var player: MediaPlayer? = null
    private var system: TextToSpeech? = null
    private var systemReady = false
    private var pendingSystem: (() -> Unit)? = null
    private var current: String? = null
    private var audioFile: File? = null

    fun save(value: FishVoiceSettings) {
        _settings.value = value
        prefs.edit().putString("provider", value.provider).putString("voice", value.voice)
            .putInt("rate", value.rate).putInt("pitch", value.pitch).putBoolean("autoRead", value.autoRead).apply()
    }
    fun speak(text: String) = main.post {
        stop()
        val id = UUID.randomUUID().toString()
        current = id
        _status.value = FishVoiceStatus("正在生成声音", preparing = true)
        val config = _settings.value
        if (config.provider == "system") {
            fun play() {
                if (current != id) return
                val engine = system ?: return
                val language = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (language < 0) { failed("手机未安装中文朗读语音，可在系统语音设置中下载"); return }
                engine.setSpeechRate(1f + config.rate / 100f)
                engine.setPitch(1f + config.pitch / 100f)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { if (current == id) _status.value = FishVoiceStatus("正在说话", speaking = true) }
                    override fun onDone(utteranceId: String?) { if (current == id) _status.value = FishVoiceStatus("声音已播放完成") }
                    @Deprecated("Platform callback") override fun onError(utteranceId: String?) { if (current == id) failed("系统语音未能播放") }
                })
                if (engine.speak(fishPlainText(text), TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) failed("系统语音未能启动")
            }
            if (systemReady) play() else {
                pendingSystem = ::play
                if (system == null) system = TextToSpeech(context) { code ->
                    main.post {
                        if (code == TextToSpeech.SUCCESS) {
                            systemReady = true
                            pendingSystem?.invoke(); pendingSystem = null
                        } else {
                            system?.shutdown(); system = null
                            pendingSystem = null
                            failed("系统语音初始化失败")
                        }
                    }
                }
            }
        } else request(JSONObject().put("action", "speak").put("id", id).put("text", text)
            .put("voice", config.voice).put("rate", config.rate).put("pitch", config.pitch))
    }
    fun receive(event: JSONObject) = main.post {
        if (event.optString("id") != current) return@post
        if (event.has("error")) { failed(event.optString("error")); return@post }
        try {
            val file = File(context.cacheDir, "fish-voice.mp3")
            file.writeBytes(Base64.decode(event.getString("audioBase64"), Base64.DEFAULT))
            audioFile = file
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build())
                setDataSource(file.absolutePath)
                setOnPreparedListener { start(); _status.value = FishVoiceStatus("正在说话", speaking = true) }
                setOnCompletionListener { this@FishVoice.stop(); _status.value = FishVoiceStatus("声音已播放完成") }
                setOnErrorListener { _, _, _ -> failed("声音播放失败，请重试"); true }
                prepareAsync()
            }
        } catch (error: Exception) { failed(error.message ?: "声音未能播放") }
    }
    fun failed(message: String) = main.post { stop(); _status.value = FishVoiceStatus(message) }
    fun stop() {
        current = null
        pendingSystem = null
        player?.release(); player = null
        system?.stop()
        audioFile?.delete(); audioFile = null
        _status.value = FishVoiceStatus()
    }
}
