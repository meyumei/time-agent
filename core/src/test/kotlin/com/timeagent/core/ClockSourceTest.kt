package com.timeagent.core

import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClockSourceTest {

    private val base = Instant.parse("2026-10-31T12:00:00Z")

    @Test
    fun constructionAndIdleTimeReadNoClocks() = runTest {
        var wallReads = 0
        var monotonicReads = 0
        SystemClockSource(
            wallNow = {
                wallReads++
                base.plusMillis(testScheduler.currentTime)
            },
            monotonicMillis = {
                monotonicReads++
                testScheduler.currentTime
            },
        )

        advanceTimeBy(10 * 60_000L)
        runCurrent()

        assertEquals(0, wallReads)
        assertEquals(0, monotonicReads)
    }

    @Test
    fun secondTicksEmitImmediatelyOnCollection() = runTest {
        val source = fakeSource()
        advanceTimeBy(400)
        val emissions = mutableListOf<Instant>()

        launchCollector(source.secondTicks, emissions)
        runCurrent()

        assertEquals(listOf(base.plusMillis(400)), emissions)
    }

    @Test
    fun secondTicksAlignToNextWallSecondFromFractionalStart() = runTest {
        val source = fakeSource()
        advanceTimeBy(400)
        val emissions = mutableListOf<Instant>()
        launchCollector(source.secondTicks, emissions)
        runCurrent()
        assertEquals(listOf(base.plusMillis(400)), emissions)

        advanceTimeBy(599)
        runCurrent()
        assertEquals(1, emissions.size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(base.plusSeconds(1), emissions[1])

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(base.plusSeconds(2), emissions[2])
    }

    @Test
    fun minuteTicksAlignToMinuteBoundaryWithoutSecondEmissions() = runTest {
        val source = fakeSource()
        advanceTimeBy(30_000)
        val emissions = mutableListOf<Instant>()
        launchCollector(source.minuteTicks, emissions)
        runCurrent()
        assertEquals(listOf(base.plusSeconds(30)), emissions)

        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(1, emissions.size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(base.plusSeconds(60), emissions.last())

        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(
            listOf(
                base.plusSeconds(30),
                base.plusSeconds(60),
                base.plusSeconds(120),
                base.plusSeconds(180),
            ),
            emissions,
        )
        assertTrue(emissions.drop(1).all { it.epochSecond % 60 == 0L })
    }

    @Test
    fun cancellingCollectorStopsTicking() = runTest {
        var wallReads = 0
        val source = fakeSource(onWallRead = { wallReads++ })
        val emissions = mutableListOf<Instant>()
        val job = launchCollector(source.secondTicks, emissions)
        runCurrent()
        assertEquals(1, emissions.size)
        val readsAfterFirst = wallReads

        job.cancel()
        runCurrent()
        advanceTimeBy(5 * 60_000L)
        runCurrent()

        assertEquals(1, emissions.size)
        assertEquals(readsAfterFirst, wallReads)
    }

    @Test
    fun elapsedRealtimeReadsOnlyMonotonicSourceAcrossWallJump() = runTest {
        var wallReads = 0
        var wallJumpMillis = 0L
        val source = fakeSource(
            offsetMillis = { wallJumpMillis },
            monotonic = { 7_000L + testScheduler.currentTime },
            onWallRead = { wallReads++ },
        )

        advanceTimeBy(1_000)
        assertEquals(8_000L, source.elapsedRealtime())
        assertEquals(0, wallReads)

        wallJumpMillis = 3_600_000L
        assertEquals(8_000L, source.elapsedRealtime())
        assertEquals(0, wallReads)
    }

    @Test
    fun ticksReportActualWallClockAfterManualJumpAndRealign() = runTest {
        var wallJumpMillis = 0L
        val source = fakeSource(offsetMillis = { wallJumpMillis })
        advanceTimeBy(400)
        val emissions = mutableListOf<Instant>()
        launchCollector(source.secondTicks, emissions)
        runCurrent()
        assertEquals(listOf(base.plusMillis(400)), emissions)

        wallJumpMillis = 3_600_300L
        advanceTimeBy(600)
        runCurrent()
        assertEquals(base.plusSeconds(3_600).plusMillis(1_300), emissions[1])

        advanceTimeBy(699)
        runCurrent()
        assertEquals(2, emissions.size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(base.plusSeconds(3_602), emissions[2])
    }

    @Test
    fun independentCollectorsKeepTheirOwnCadence() = runTest {
        val source = fakeSource()
        val seconds = mutableListOf<Instant>()
        val minutes = mutableListOf<Instant>()
        launchCollector(source.secondTicks, seconds)
        launchCollector(source.minuteTicks, minutes)
        runCurrent()

        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(61, seconds.size)
        assertEquals(2, minutes.size)
    }

    private fun TestScope.fakeSource(
        offsetMillis: () -> Long = { 0L },
        monotonic: () -> Long = { testScheduler.currentTime },
        onWallRead: () -> Unit = {},
    ): SystemClockSource = SystemClockSource(
        wallNow = {
            onWallRead()
            Instant.ofEpochMilli(base.toEpochMilli() + testScheduler.currentTime + offsetMillis())
        },
        monotonicMillis = monotonic,
    )

    private fun TestScope.launchCollector(
        flow: Flow<Instant>,
        emissions: MutableList<Instant>,
    ): Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        flow.collect { emissions += it }
    }
}
