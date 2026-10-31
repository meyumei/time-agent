package com.timeagent.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class VoiceAudioTest {
    private fun wav(rate: Int, channels: Int, samples: ShortArray): ByteArray {
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { pcm.putShort(it) }
        val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * 2)
            .putShort((channels * 2).toShort()).putShort(16).array()
        val chunks = ByteArrayOutputStream()
        fun chunk(name: String, data: ByteArray) {
            chunks.write(name.toByteArray(Charsets.US_ASCII))
            chunks.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array())
            chunks.write(data)
            if (data.size % 2 != 0) chunks.write(0)
        }
        chunk("JUNK", byteArrayOf(7))
        chunk("fmt ", fmt)
        chunk("data", pcm.array())
        return "RIFF".toByteArray() +
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(4 + chunks.size()).array() +
            "WAVE".toByteArray() + chunks.toByteArray()
    }

    @Test fun `converted stereo wav is mixed and resampled to real mono pcm`() {
        val input = ShortArray(4410 * 2) { if (it % 2 == 0) 1000 else 3000 }
        val output = VoiceAudio.wavTo16k(wav(44100, 2, input))
        assertEquals(1600, output.size)
        assertTrue(output.all { it == 2000.toShort() })
        val mono = shortArrayOf(-2000, 0, 2000, 1000)
        assertArrayEquals(mono, VoiceAudio.wavTo16k(wav(16000, 1, mono)))
    }

    @Test fun `silk truncated and overlong recordings are not treated as valid speech`() {
        assertThrows(IllegalArgumentException::class.java) { VoiceAudio.wavTo16k("#!SILK".toByteArray()) }
        val complete = wav(16000, 1, ShortArray(160))
        assertThrows(IllegalArgumentException::class.java) { VoiceAudio.wavTo16k(complete.copyOf(complete.size - 2)) }
        assertThrows(IllegalArgumentException::class.java) { VoiceAudio.wavTo16k(wav(16000, 1, ShortArray(16000 * 61))) }
    }
}
