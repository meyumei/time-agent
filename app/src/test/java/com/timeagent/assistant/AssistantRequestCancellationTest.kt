package com.timeagent.assistant

import com.timeagent.data.LocalSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import java.time.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantRequestCancellationTest {
    private val context = AssistantContext(Instant.parse("2026-10-20T08:00:00Z"), ZoneId.of("Asia/Shanghai"), LocalDate.of(2026, 10, 19), LocalSnapshot(loading = false))
    private val response = """{"choices":[{"finish_reason":"stop","message":{"content":"{\"action\":\"query\",\"scope\":\"tasks\"}"}}]}"""

    @Test fun `old cancelled delayed request cannot clear busy state of newer request`() = runTest {
        val releaseA = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        var calls = 0
        val delivered = mutableListOf<CloudResult>()
        val client = DeepSeekClient(AssistantTransport { _, _ ->
            val number = ++calls
            // Models a socket read that returns only later even after caller cancellation.
            withContext(NonCancellable) { if (number == 1) releaseA.await() else releaseB.await() }
            response
        })
        val controller = AssistantRequestController(this)
        try {
            assertTrue(controller.start({ client.interpret("A", context, "test-key", "deepseek-flash") }) { delivered += it })
            runCurrent()
            assertTrue(controller.busy.value)
            assertEquals(1, calls)
            controller.cancel()
            assertTrue(controller.start({ client.interpret("B", context, "test-key", "deepseek-flash") }) { delivered += it })
            runCurrent()
            assertEquals(2, calls)
            releaseA.complete(Unit)
            runCurrent()
            assertTrue("finishing cancelled A must not make active B appear idle", controller.busy.value)
            assertTrue(delivered.isEmpty())
            assertFalse(controller.start({ error("must not start a third request while B is pending") }) { })
            releaseB.complete(Unit)
            runCurrent()
            assertFalse(controller.busy.value)
            assertEquals(1, delivered.size)
        } finally { releaseA.complete(Unit); releaseB.complete(Unit); controller.cancel(); runCurrent() }
    }

    @Test fun `cancelling official transport disconnects blocked connection before response arrives`() = runBlocking {
        val connection = BlockingConnection()
        val transport = OfficialDeepSeekTransport { connection }
        val request = async(Dispatchers.Default) { transport.post("test-key", "{}") }
        try {
            assertTrue("request should enter controlled socket read", withContext(Dispatchers.IO) { connection.entered.await(3, TimeUnit.SECONDS) })
            request.cancel()
            assertTrue("cancel must disconnect without waiting for read timeout", withContext(Dispatchers.IO) { connection.disconnected.await(1, TimeUnit.SECONDS) })
            withTimeout(1000) { request.join() }
        } finally { connection.release.countDown(); request.cancelAndJoin() }
    }

    private class BlockingConnection : HttpsURLConnection(URL(DeepSeekProtocol.ENDPOINT)) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected.countDown(); release.countDown() }
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getResponseCode() = 200
        override fun getInputStream(): InputStream {
            entered.countDown()
            if (!release.await(5, TimeUnit.SECONDS)) throw IOException("controlled read timeout")
            if (disconnected.count == 0L) throw IOException("controlled disconnect")
            return "{}".byteInputStream()
        }
        override fun getCipherSuite() = "TLS_TEST_ONLY"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
}
