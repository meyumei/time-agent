package com.timeagent.core

import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface ClockSource {
    fun now(): Instant
    val minuteTicks: Flow<Instant>
    val secondTicks: Flow<Instant>
    fun elapsedRealtime(): Long
}

class SystemClockSource(
    private val wallNow: () -> Instant = Instant::now,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : ClockSource {

    override fun now(): Instant = wallNow()

    override val minuteTicks: Flow<Instant> = ticks(60_000L)

    override val secondTicks: Flow<Instant> = ticks(1_000L)

    override fun elapsedRealtime(): Long = monotonicMillis()

    // Cold: each collector gets its own independent, cancellable loop.
    private fun ticks(periodMillis: Long): Flow<Instant> = flow {
        while (true) {
            val wall = wallNow()
            emit(wall)
            // floorMod keeps alignment correct for instants before the epoch.
            delay(periodMillis - Math.floorMod(wall.toEpochMilli(), periodMillis))
        }
    }
}
