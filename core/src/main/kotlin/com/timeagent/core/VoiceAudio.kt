package com.timeagent.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

object VoiceAudio {
    fun wavTo16k(bytes: ByteArray): ShortArray {
        require(bytes.size in 44..12 * 1024 * 1024) { "语音文件大小无效" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
        require(tag(0) == "RIFF" && tag(8) == "WAVE") { "语音需要转换为 WAV 音频" }
        val declaredEnd = 8L + (buffer.getInt(4).toLong() and 0xffffffffL)
        require(declaredEnd <= bytes.size && declaredEnd >= 12) { "语音下载不完整" }
        val end = declaredEnd.toInt()
        var offset = 12
        var rate = 0
        var channels = 0
        var dataOffset = 0
        var dataBytes = 0
        while (offset + 8 <= end) {
            val length = buffer.getInt(offset + 4).toLong() and 0xffffffffL
            require(length <= end - offset - 8) { "语音下载不完整" }
            val start = offset + 8
            when (tag(offset)) {
                "fmt " -> {
                    require(length >= 16 && buffer.getShort(start).toInt() == 1) { "仅支持 PCM16 WAV 语音" }
                    channels = buffer.getShort(start + 2).toInt()
                    rate = buffer.getInt(start + 4)
                    require(channels in 1..2 && rate in 8000..48000 &&
                        buffer.getShort(start + 14).toInt() == 16 &&
                        buffer.getShort(start + 12).toInt() == channels * 2) { "语音采样格式不支持" }
                }
                "data" -> { dataOffset = start; dataBytes = length.toInt() }
            }
            offset = start + length.toInt() + (length.toInt() and 1)
        }
        require(rate > 0 && dataBytes > 0 && dataBytes % (channels * 2) == 0) { "语音没有有效音频" }
        val frames = dataBytes / channels / 2
        require(frames <= rate * 60) { "语音暂支持最多 60 秒" }
        val mono = ShortArray(frames) { index ->
            val position = dataOffset + index * channels * 2
            if (channels == 1) buffer.getShort(position)
            else ((buffer.getShort(position).toInt() + buffer.getShort(position + 2).toInt()) / 2).toShort()
        }
        if (rate == 16000) return mono
        // Match the upstream mono/16k input contract without adding FFmpeg to Android.
        return ShortArray((frames.toLong() * 16000 / rate).toInt()) { index ->
            val source = index.toDouble() * rate / 16000
            val left = source.toInt().coerceAtMost(frames - 1)
            val right = (left + 1).coerceAtMost(frames - 1)
            (mono[left] + (mono[right].toInt() - mono[left].toInt()) * (source - left)).roundToInt().toShort()
        }
    }
}
