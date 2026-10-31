package com.timeagent.assistant

import android.Manifest
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OfflineSpeechInputTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val states = mutableListOf<SpeechInputStatus>()
    private val partials = mutableListOf<String>()
    private val texts = mutableListOf<String>()
    private val messages = mutableListOf<String>()
    private var failures = 0
    private var speech: OfflineSpeechInput? = null
    private class Audio : PcmInput {
        val chunks = LinkedBlockingQueue<ShortArray>()
        @Volatile var started = false
        @Volatile var stopped = false
        @Volatile var closed = false
        var stoppedListener: (Boolean) -> Unit = {}
        override fun onCaptureStopped(listener: (Boolean) -> Unit) { stoppedListener = listener }
        override fun start() { started = true }
        override fun read(buffer: ShortArray): Int {
            val next = chunks.poll(2, TimeUnit.SECONDS) ?: return -1
            next.copyInto(buffer)
            return if (next.isEmpty()) -1 else next.size
        }
        override fun stop() { stopped = true; chunks.offer(shortArrayOf()) }
        override fun close() { closed = true }
    }
    private class Decoder : SpeechDecoder {
        @Volatile var closed = false
        override fun accept(buffer: ShortArray, length: Int) = false
        override fun partial() = """{"partial":"新建 任务 阅读"}"""
        override fun result() = """{"text":""}"""
        override fun finalResult() = """{"text":"新建 任务 阅读 三十 分钟"}"""
        override fun close() { closed = true }
    }
    private fun input(decoder: SpeechDecoder = Decoder(), microphone: () -> PcmInput): OfflineSpeechInput {
        val result = OfflineSpeechInput(app, SpeechCallbacks(texts::add, { state, message -> states += state; if (message != null) messages += message }, { failures++ }, partials::add),
            { object : SpeechModel { override fun decoder() = decoder; override fun close() {} } }, microphone, Executors.newSingleThreadExecutor())
        speech = result
        await { result.available && states.lastOrNull() == SpeechInputStatus.IDLE }
        return result
    }
    private fun await(check: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < end) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (check()) return
            Thread.sleep(5)
        }
        fail("Speech callback did not arrive; states=$states messages=$messages")
    }
    @Before fun permission() { Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO) }
    @After fun close() { speech?.close() }

    @Test fun `offline capture emits live partials and one complete result after release`() {
        val audio = Audio(); val decoder = Decoder()
        val input = input(decoder) { audio }
        input.start()
        await { audio.started && states.last() == SpeechInputStatus.LISTENING }
        audio.chunks.put(ShortArray(1600) { 200 })
        await { partials.lastOrNull() == "新建任务阅读" }
        assertTrue(texts.isEmpty())
        input.stop()
        await { texts.size == 1 }
        assertEquals("新建任务阅读三十分钟", texts.single())
        assertEquals(SpeechInputStatus.IDLE, states.last())
        assertTrue(audio.closed); assertTrue(decoder.closed); assertEquals(0, failures)
    }
    @Test fun `cancel discards partial and late final result while releasing microphone`() {
        val audio = Audio(); val decoder = Decoder()
        val input = input(decoder) { audio }
        input.start(); await { audio.started }
        audio.chunks.put(ShortArray(1600) { 200 }); await { partials.any { it.isNotBlank() } }
        input.cancel()
        await { audio.closed && decoder.closed }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(texts.isEmpty()); assertEquals(0, failures); assertTrue(audio.stopped)
        assertEquals(SpeechInputStatus.IDLE, states.last())
    }
    @Test fun `runtime permission denial never opens audio hardware`() {
        var opens = 0
        val input = input { opens++; Audio() }
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        input.start()
        assertEquals(0, opens); assertEquals(1, failures)
        assertEquals(SpeechInputStatus.IDLE, states.last())
    }
    @Test fun `asynchronous audio security failure clears starting state without pretending permission was absent`() {
        val input = input { throw SecurityException("vendor rejected audio access") }
        input.start(); assertEquals(SpeechInputStatus.STARTING, states.last())
        await { failures == 1 }
        assertEquals(SpeechInputStatus.IDLE, states.last()); assertTrue(texts.isEmpty())
        assertTrue(messages.single().contains("系统拒绝"))
    }
    @Test fun `release drains captured PCM that was queued while native decoder was busy`() {
        val accepted = AtomicInteger()
        val busy = CountDownLatch(1)
        val continueDecode = CountDownLatch(1)
        val decoder = object : SpeechDecoder {
            override fun accept(buffer: ShortArray, length: Int): Boolean {
                if (accepted.incrementAndGet() == 1) { busy.countDown(); assertTrue(continueDecode.await(3, TimeUnit.SECONDS)) }
                return false
            }
            override fun result() = "{}"
            override fun partial() = "{}"
            override fun finalResult() = """{"text":"阅读 三十 分钟"}"""
            override fun close() {}
        }
        val audio = Audio()
        val input = OfflineSpeechInput(app, SpeechCallbacks(texts::add, { state, _ -> states += state }, { failures++ }),
            { object : SpeechModel { override fun decoder() = decoder; override fun close() {} } }, { audio }, Executors.newSingleThreadExecutor())
        speech = input
        await { input.available && states.last() == SpeechInputStatus.IDLE }
        input.start(); await { audio.started }
        audio.chunks.put(ShortArray(1600) { 100 })
        assertTrue(busy.await(3, TimeUnit.SECONDS))
        audio.chunks.put(ShortArray(1600) { 100 }); audio.chunks.put(ShortArray(1600) { 100 })
        audio.stoppedListener(true)
        await { states.last() == SpeechInputStatus.PROCESSING }
        assertTrue(texts.isEmpty())
        assertTrue(messages.isEmpty()) // This injected callback uses a state-only listener.
        input.stop(); continueDecode.countDown()
        await { texts.isNotEmpty() }
        assertEquals(3, accepted.get()); assertEquals("阅读三十分钟", texts.single())
        assertTrue(audio.closed)
    }
    @Test fun `final offline recognition uses the complete real recording rather than the preview words`() {
        val audio = Audio()
        var finalSamples = shortArrayOf()
        val decoder = object : SpeechDecoder {
            override fun accept(buffer: ShortArray, length: Int) = false
            override fun result() = "{}"
            override fun partial() = """{"partial":"明天 错词"}"""
            override fun finalResult() = """{"text":"明天 错词"}"""
            override fun finalTranscript(samples: ShortArray): String {
                finalSamples = samples
                return "明天下午3点提醒我交报告。"
            }
            override fun close() {}
        }
        val input = input(decoder) { audio }
        input.start(); await { audio.started }
        audio.chunks.put(ShortArray(1600) { 200 })
        await { partials.any { it.isNotBlank() } }
        audio.chunks.put(ShortArray(800) { 300 })
        input.stop()
        await { texts.isNotEmpty() }
        assertEquals(2400, finalSamples.size)
        assertEquals(300.toShort(), finalSamples.last())
        assertEquals("明天下午3点提醒我交报告。", texts.single())
    }
}
