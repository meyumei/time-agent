package com.timeagent.ui

import androidx.test.runner.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.timeagent.core.ClockSource
import com.timeagent.core.SystemClockSource
import com.timeagent.di.AppEntryPoint
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoundationDeviceTest {

    @Test
    fun appEntryPointKeepsDatabaseOwnersStableWithoutClosingThem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = AppEntryPoint.from(context)
        val second = AppEntryPoint.from(context.applicationContext)

        val repository = first.timeRepository()
        val repeatedRepository = second.timeRepository()

        assertSame(repository, repeatedRepository)
        assertSame(repository.database, repeatedRepository.database)
        assertSame(repository.commands, first.commandExecutor())
        assertSame(first.commandExecutor(), second.commandExecutor())
        assertSame(first.workoutDatabase(), second.workoutDatabase())
    }

    @Test
    fun systemClockSourceUsesInjectedWallAndMonotonicTime() {
        val wall = Instant.parse("2026-10-19T08:00:10Z")
        var elapsed = 7_000L
        val source = SystemClockSource(wallNow = { wall }, monotonicMillis = { elapsed })

        assertEquals(wall, source.now())
        assertEquals(7_000L, source.elapsedRealtime())

        elapsed = 7_250L
        assertEquals(7_250L, source.elapsedRealtime())
    }

    @Test
    fun secondTicksDispatchAfterRealWallTime() = runBlocking {
        val source = SystemClockSource()
        val startedAt = System.nanoTime()

        val ticks = withTimeout(5_000) { source.secondTicks.take(3).toList() }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertEquals(3, ticks.size)
        assertTrue(
            "Second ticks must span real wall time",
            Duration.between(ticks.first(), ticks.last()).toMillis() >= 500,
        )
        assertTrue("Collecting three second ticks must not be immediate", elapsedMillis >= 500)
    }

    @Test
    fun minuteTicksUseTheRealAlignedDelayAndCanBeCancelled() = runBlocking {
        // 500 ms before a minute boundary exercises the real alignment delay without a 60 s wait.
        val nearBoundary = Instant.ofEpochMilli(59_500L)
        val source = SystemClockSource(wallNow = { nearBoundary })
        val ticks = Channel<Instant>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Default) {
            source.minuteTicks.collect { ticks.send(it) }
        }
        val startedAt = System.nanoTime()

        try {
            assertEquals(nearBoundary, withTimeout(2_000) { ticks.receive() })
            assertEquals(nearBoundary, withTimeout(3_000) { ticks.receive() })
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue("Minute ticks must wait for the aligned boundary", elapsedMillis >= 200)
        } finally {
            job.cancelAndJoin()
            ticks.close()
        }

        assertTrue(job.isCancelled)
    }

    @Test
    fun cancellingOneSecondCollectorDoesNotStopAnother() = runBlocking {
        val source = TrackingClockSource(SystemClockSource())
        val firstTicks = AtomicInteger()
        val siblingTicks = AtomicInteger()

        val firstJob = launch(Dispatchers.Default) {
            source.secondTicks.collect { firstTicks.incrementAndGet() }
        }
        val siblingJob = launch(Dispatchers.Default) {
            source.secondTicks.collect { siblingTicks.incrementAndGet() }
        }

        try {
            awaitUntil(2_000) {
                source.activeSecondCollectors.get() == 2 &&
                    firstTicks.get() > 0 &&
                    siblingTicks.get() > 0
            }

            firstJob.cancelAndJoin()
            awaitUntil(2_000) { source.activeSecondCollectors.get() == 1 }

            val ticksBefore = siblingTicks.get()
            awaitUntil(3_000) { siblingTicks.get() > ticksBefore }

            assertFalse(firstJob.isActive)
            assertTrue(siblingJob.isActive)
            assertEquals(1, source.activeSecondCollectors.get())
        } finally {
            firstJob.cancel()
            siblingJob.cancelAndJoin()
        }

        assertEquals(0, source.activeSecondCollectors.get())
    }

    private suspend fun awaitUntil(timeoutMillis: Long, condition: () -> Boolean) {
        withTimeout(timeoutMillis) {
            while (!condition()) delay(10)
        }
    }

    private class TrackingClockSource(
        private val delegate: ClockSource,
    ) : ClockSource by delegate {
        val activeSecondCollectors = AtomicInteger()

        override val secondTicks = flow {
            activeSecondCollectors.incrementAndGet()
            try {
                emitAll(delegate.secondTicks)
            } finally {
                activeSecondCollectors.decrementAndGet()
            }
        }
    }
}
