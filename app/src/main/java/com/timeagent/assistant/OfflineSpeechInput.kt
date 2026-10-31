package com.timeagent.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.vosk.Model
import org.vosk.Recognizer
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal interface PcmInput : Closeable {
    fun onCaptureStopped(listener: (limitReached: Boolean) -> Unit) {}
    fun start()
    fun read(buffer: ShortArray): Int
    fun stop()
}
internal interface SpeechDecoder : Closeable {
    fun accept(buffer: ShortArray, length: Int): Boolean
    fun result(): String
    fun partial(): String
    fun finalResult(): String
    fun finalTranscript(samples: ShortArray): String? = null
    /** N-best candidates after the first one; empty when the SDK only produced a single transcript. */
    fun alternatives(): List<String> = emptyList()
    fun suggestion(fullText: String): String? = null
    fun suggestion(fullText: String, alternatives: List<String>): String? = suggestion(fullText)
}
internal interface SpeechModel : Closeable { fun decoder(): SpeechDecoder }

/** Owns microphone + offline recognizer; system RecognitionService and Google apps are unnecessary. */
class OfflineSpeechInput internal constructor(
    private val context: Context,
    private val callbacks: SpeechCallbacks,
    private val loadModel: () -> SpeechModel,
    private val microphone: () -> PcmInput,
    private val worker: ExecutorService,
) : SpeechInput {
    constructor(context: Context, callbacks: SpeechCallbacks) : this(context, callbacks,
        { VoskSpeechModel(OfflineModelFiles.prepare(context).absolutePath) { samples ->
            SenseVoiceSpeech.transcribe(context, samples)
        } }, { AndroidPcmInput() },
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "offline-voice").apply { isDaemon = true } })

    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    @Volatile private var closed = false
    @Volatile private var model: SpeechModel? = null
    private var state = SpeechInputStatus.PREPARING
    private var capture: Capture? = null
    override val available get() = !closed && model != null

    private class Capture(val token: Int) {
        val finish = AtomicBoolean()
        val cancelled = AtomicBoolean()
        @Volatile var audio: PcmInput? = null
        fun stop(cancel: Boolean) {
            if (cancel) cancelled.set(true)
            finish.set(true)
            runCatching { audio?.stop() }
        }
    }

    init {
        callbacks.onState(SpeechInputStatus.PREPARING, null)
        worker.execute {
            try {
                val loaded = loadModel()
                if (closed) loaded.close() else {
                    model = loaded
                    main.post { if (!closed) update(SpeechInputStatus.IDLE) }
                }
            } catch (_: Exception) { preparationFailed() }
            catch (_: LinkageError) { preparationFailed() }
        }
    }

    private fun preparationFailed() = main.post {
        if (!closed) fail("离线中文识别未能初始化，请重新打开语音入口；也可以先用文字输入。")
    }

    override fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed || state != SpeechInputStatus.IDLE) return
        if (!hasPermission()) { fail("请允许时间 Agent 使用麦克风：应用权限 → 麦克风 → 仅使用时允许。"); return }
        if (microphoneBlocked(context)) { fail("系统的麦克风总开关已关闭，请在控制中心打开麦克风访问后重试。"); return }
        if (model == null) { fail("中文语音识别尚未准备好，请重新打开语音入口。"); return }
        val item = Capture(generation.incrementAndGet())
        capture = item
        callbacks.onPartial("")
        update(SpeechInputStatus.STARTING)
        worker.execute { recognize(item) }
    }

    private fun valid(item: Capture) = !closed && generation.get() == item.token && !item.cancelled.get()
    private fun post(item: Capture, action: () -> Unit) { main.post { if (valid(item)) action() } }

    private fun recognize(item: Capture) {
        try {
            if (!valid(item)) return
            val transcript = VoiceTranscript()
            var peak = 0
            var samples = 0
            val recorded = ShortArray(16000 * 60)
            var finalText: String? = null
            var suggestion: String? = null
            val alternatives = mutableListOf<String>()
            model!!.decoder().use { decoder ->
                microphone().use { audio ->
                    item.audio = audio
                    audio.onCaptureStopped { limitReached -> post(item) {
                        if (capture === item) update(SpeechInputStatus.PROCESSING, if (limitReached) "录音已达到 60 秒，正在整理文字。" else null)
                    } }
                    if (!valid(item) || item.finish.get()) return@use
                    audio.start()
                    post(item) { if (!item.finish.get()) update(SpeechInputStatus.LISTENING) }
                    val buffer = ShortArray(1600)
                    var previous = ""
                    // stop() ends capture; still decode every PCM frame captured before release.
                    while (valid(item) && samples < 16000 * 60) {
                        val length = audio.read(buffer)
                        if (!valid(item) || length == -1) break
                        check(length > 0) { "audio_read" }
                        buffer.copyInto(recorded, samples, 0, length)
                        samples += length
                        for (index in 0 until length) peak = maxOf(peak, kotlin.math.abs(buffer[index].toInt()))
                        val text = if (decoder.accept(buffer, length)) {
                            alternatives += decoder.alternatives()
                            transcript.acceptResult(decoder.result())
                        } else transcript.acceptPartial(decoder.partial())
                        if (text != previous) { previous = text; post(item) { callbacks.onPartial(text) } }
                    }
                    runCatching { audio.stop() }
                    post(item) { update(SpeechInputStatus.PROCESSING, if (samples >= 16000 * 60) "录音已达到 60 秒，正在整理文字。" else null) }
                    transcript.acceptResult(decoder.finalResult())
                    finalText = decoder.finalTranscript(recorded.copyOf(samples))
                    suggestion = decoder.suggestion(finalText ?: transcript.text(), alternatives.toList())
                }
            }
            val text = finalText ?: transcript.text()
            post(item) {
                capture = null
                if (text.isBlank()) fail(if (peak < 3 && samples > 8000) "没有收到麦克风声音，请检查系统麦克风总开关、应用权限或是否被其他应用占用。" else "没有听清，请靠近手机，再按住说一句完整的话。")
                else {
                    update(SpeechInputStatus.IDLE)
                    callbacks.onPartial(text)
                    callbacks.onSuggestion(suggestion)
                    callbacks.onText(text)
                }
            }
        } catch (_: SecurityException) {
            post(item) { fail("麦克风访问被系统拒绝，请检查应用的麦克风权限和控制中心的麦克风开关。") }
        } catch (_: Exception) {
            post(item) { fail("离线语音识别未能完成，请重新准备后重试；也可以使用文字输入。") }
        } catch (_: LinkageError) {
            post(item) { fail("这台设备暂时无法启动离线识别，请使用文字输入并反馈设备型号。") }
        } finally { item.audio = null }
    }

    override fun stop() {
        if (closed) return
        capture?.let { item -> update(SpeechInputStatus.PROCESSING); item.stop(false) }
    }
    override fun cancel() {
        generation.incrementAndGet()
        capture?.stop(true); capture = null
        if (!closed && state != SpeechInputStatus.PREPARING) update(SpeechInputStatus.IDLE)
    }
    override fun close() {
        if (closed) return
        closed = true
        cancel()
        worker.execute { model?.close(); model = null }
        worker.shutdown()
    }
    private fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun update(value: SpeechInputStatus, message: String? = null) { state = value; if (!closed) callbacks.onState(value, message) }
    private fun fail(message: String) { capture?.stop(true); capture = null; generation.incrementAndGet(); update(SpeechInputStatus.IDLE, message); callbacks.onFailure() }

    companion object {
        fun microphoneBlocked(context: Context) = runCatching {
            context.getSystemService(AudioManager::class.java)?.isMicrophoneMute == true
        }.getOrDefault(false)
    }
}

private class VoskSpeechModel(path: String, private val transcribeFinal: (ShortArray) -> String) : SpeechModel {
    private val model = Model(path)
    override fun decoder(): SpeechDecoder {
        val recognizer = Recognizer(model, 16000f)
        val hints = try { Recognizer(model, 16000f, com.google.gson.Gson().toJson(VoiceCommandHints.phrases + "[unk]")) }
            catch (e: Exception) { recognizer.close(); throw e }
        return object : SpeechDecoder {
            private val hintTranscript = VoiceTranscript()
            override fun accept(buffer: ShortArray, length: Int): Boolean {
                if (hints.acceptWaveForm(buffer, length)) hintTranscript.acceptResult(hints.result)
                return recognizer.acceptWaveForm(buffer, length)
            }
            override fun result() = recognizer.result
            override fun partial() = recognizer.partialResult
            override fun finalResult() = recognizer.finalResult
            override fun finalTranscript(samples: ShortArray) = transcribeFinal(samples)
            /** When the SDK emits N-best JSON, only the candidates AFTER the first feed an optional hint. */
            override fun alternatives(): List<String> = runCatching {
                com.google.gson.JsonParser.parseString(recognizer.result).asJsonObject.getAsJsonArray("alternatives")
                    ?.mapNotNull { element -> element.takeIf { it.isJsonObject }?.asJsonObject?.get("text")
                        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotBlank() } }
                    ?.drop(1).orEmpty()
            }.getOrDefault(emptyList())
            override fun suggestion(fullText: String, alternatives: List<String>): String? {
                val hint = hintTranscript.acceptResult(hints.finalResult)
                for (candidate in alternatives + hint) VoiceCommandHints.suggestion(fullText, candidate)?.let { return it }
                return null
            }
            override fun suggestion(fullText: String) = suggestion(fullText, emptyList())
            override fun close() { try { recognizer.close() } finally { hints.close() } }
        }
    }
    override fun close() = model.close()
}

@SuppressLint("MissingPermission") // Checked at every start; SecurityException is handled for revocation races.
private class AndroidPcmInput : PcmInput {
    private sealed interface Frame {
        data class Data(val samples: ShortArray) : Frame
        data class Failure(val cause: Exception) : Frame
        data object End : Frame
    }
    private val frames = LinkedBlockingQueue<Frame>()
    private val stopping = AtomicBoolean()
    private var reader: Thread? = null
    private var stoppedListener: (Boolean) -> Unit = {}
    override fun onCaptureStopped(listener: (Boolean) -> Unit) { stoppedListener = listener }
    private val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        maxOf(6400, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)))
    @Synchronized override fun start() {
        if (stopping.get()) { frames.offer(Frame.End); return }
        check(audio.state == AudioRecord.STATE_INITIALIZED)
        audio.startRecording()
        check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        // Native decoding never blocks microphone collection. At most 60 s (~1.9 MB) PCM stays in RAM.
        reader = Thread({
            val buffer = ShortArray(1600)
            var filled = 0
            var captured = 0
            var drainDeadline = Long.MAX_VALUE
            try {
                while (captured < 16000 * 60) {
                    if (stopping.get() && drainDeadline == Long.MAX_VALUE) drainDeadline = System.nanoTime() + 50_000_000L
                    if (System.nanoTime() >= drainDeadline) break
                    val length = audio.read(buffer, filled, minOf(buffer.size - filled, 16000 * 60 - captured), AudioRecord.READ_NON_BLOCKING)
                    check(length >= 0) { "audio_read_$length" }
                    if (length > 0) {
                        filled += length; captured += length
                        if (filled == buffer.size) { frames.offer(Frame.Data(buffer.copyOf())); filled = 0 }
                    } else if (stopping.get()) break else Thread.sleep(10)
                    // After release, drain only what was already buffered; no waiting for new audio.
                    if (stopping.get() && length == 0) break
                }
                if (filled > 0) frames.offer(Frame.Data(buffer.copyOf(filled)))
            } catch (e: Exception) { frames.offer(Frame.Failure(e)) }
            finally {
                runCatching { audio.stop() }
                runCatching { audio.release() }
                runCatching { stoppedListener(captured >= 16000 * 60) }
                frames.offer(Frame.End)
            }
        }, "voice-pcm-capture").apply { isDaemon = true; start() }
    }
    override fun read(buffer: ShortArray): Int = when (val frame = frames.take()) {
        is Frame.Data -> { frame.samples.copyInto(buffer); frame.samples.size }
        is Frame.Failure -> throw frame.cause
        Frame.End -> -1
    }
    @Synchronized override fun stop() { stopping.set(true) }
    override fun close() {
        stop()
        if (reader == null) { runCatching { audio.stop() }; audio.release() }
        else reader?.join(1500)
        frames.clear()
    }
}
