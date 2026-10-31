package com.timeagent.assistant

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.timeagent.core.VoiceAudio

/** One offline final decoder shared by microphone recordings and received QQ voice messages. */
object SenseVoiceSpeech {
    private var recognizer: OfflineRecognizer? = null

    fun transcribeWav(context: Context, bytes: ByteArray): String = transcribe(context, VoiceAudio.wavTo16k(bytes))

    @Synchronized
    fun transcribe(context: Context, samples: ShortArray): String {
        if (samples.isEmpty()) return ""
        val engine = recognizer ?: OfflineRecognizer(
            assetManager = context.applicationContext.assets,
            config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = "asr/sensevoice/model.int8.onnx", language = "auto", useInverseTextNormalization = true),
                tokens = "asr/sensevoice/tokens.txt", numThreads = 2, provider = "cpu",
            )),
        ).also { recognizer = it }
        val stream = engine.createStream()
        return try {
            stream.acceptWaveform(FloatArray(samples.size) { samples[it] / 32768f }, 16000)
            engine.decode(stream)
            engine.getResult(stream).text.trim()
        } finally { stream.release() }
    }
}
