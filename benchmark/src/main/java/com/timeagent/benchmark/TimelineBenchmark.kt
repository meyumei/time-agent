package com.timeagent.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Timeline benchmarks for the benchmark-only variant.
 *
 * Isolated from app tests on purpose: the measured APK is the debug-signed, profileable
 * `benchmark` build whose fixture provider seeds synthetic DBs at process start, so setup
 * launches keep seeding and schema migration out of every measured iteration. These tests
 * only emit per-iteration values; no threshold is asserted and nothing here is comparable
 * to a debug build.
 *
 * Fixture rows include 2026-10-19, but the timeline opens on the device's current
 * week, which may hold none of them. Frame measurements therefore page back to the fixture week in
 * the (unmeasured) setup block, from a known reset point, and each measured swipe is mirrored so
 * the pager cannot drift forward into empty weeks.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class TimelineBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startupCold() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
        setupBlock = {
            // Warm-up launch runs the fixture provider and any pending migration first.
            pressHome()
            awaitReady()
            killProcess()
        },
    ) {
        awaitReady()
    }

    @Test
    fun frameTimelineSwipe() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            awaitReady()
            // Swiping the current week would render no fixture rows; position on 2026-10-19.
            positionFixtureWeek()
        },
    ) {
        val y = (device.displayHeight * GRID_Y_FRACTION).toInt()
        val startX = (device.displayWidth * 0.8f).toInt()
        val endX = (device.displayWidth * 0.2f).toInt()
        repeat(SWIPES) {
            // Lower grid only: no toolbar or action taps, so no new writes are measured.
            device.swipe(startX, y, endX, y, SWIPE_STEPS)
            device.waitForIdle()
            // Mirrored swipe: returns to the fixture week instead of drifting one week forward.
            device.swipe(endX, y, startX, y, SWIPE_STEPS)
            device.waitForIdle()
        }
    }
}

private const val TARGET_PACKAGE = "com.timeagent.benchmark"
private const val ITERATIONS = 5
private const val READY_TIMEOUT_MS = 15_000L
private const val SWIPES = 3
private const val SWIPE_STEPS = 20
private const val GRID_Y_FRACTION = 0.65f

/** Monday of the week holding the seeded fixture rows. */
private val FIXTURE_MONDAY: LocalDate = LocalDate.of(2026, 10, 19)

private fun MacrobenchmarkScope.awaitReady() {
    startActivityAndWait()
    // Generous waits: a fresh install may still be finishing its first-launch seeding.
    check(device.wait(Until.hasObject(By.text("今天")), READY_TIMEOUT_MS)) {
        "Timeline not ready: day label missing after ${READY_TIMEOUT_MS}ms"
    }
    check(device.wait(Until.hasObject(By.desc("新建").enabled(true)), READY_TIMEOUT_MS)) {
        "Timeline not ready: create control not enabled after ${READY_TIMEOUT_MS}ms"
    }
}

/**
 * Pages the timeline to [FIXTURE_MONDAY] without deep links or app hooks.
 *
 * Always resets to the local current week first, so the number of pager steps is identical on
 * every iteration no matter which week the previous measurement left behind. Each step waits for
 * the header of the week just requested: the outgoing header can still be attached during the
 * pager animation, so matching it would be a stale read.
 */
private fun MacrobenchmarkScope.positionFixtureWeek() {
    val currentMonday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    val thisWeek = device.wait(Until.findObject(By.text("本周")), READY_TIMEOUT_MS)
    checkNotNull(thisWeek) { "Timeline not ready: 本周 control missing after ${READY_TIMEOUT_MS}ms" }
    thisWeek.click()
    device.waitForIdle()
    check(device.wait(Until.hasObject(By.desc("$currentMonday 周一")), READY_TIMEOUT_MS)) {
        "Timeline did not reset to $currentMonday after ${READY_TIMEOUT_MS}ms"
    }

    val weeks = ChronoUnit.WEEKS.between(currentMonday, FIXTURE_MONDAY).toInt()
    val arrow = if (weeks < 0) "上一周" else "下一周"
    val step = if (weeks < 0) -1L else 1L
    var monday = currentMonday
    repeat(if (weeks < 0) -weeks else weeks) {
        monday = monday.plusWeeks(step)
        val control = device.wait(Until.findObject(By.desc(arrow)), READY_TIMEOUT_MS)
        checkNotNull(control) { "Timeline control $arrow missing after ${READY_TIMEOUT_MS}ms" }
        control.click()
        check(device.wait(Until.hasObject(By.desc("$monday 周一")), READY_TIMEOUT_MS)) {
            "Timeline did not reach $monday after ${READY_TIMEOUT_MS}ms"
        }
        device.waitForIdle()
    }

    device.waitForIdle()
    // Calendar header and grid must both be settled on the fixture week before anything is timed.
    check(device.wait(Until.hasObject(By.desc("$FIXTURE_MONDAY 周一")), READY_TIMEOUT_MS)) {
        "Fixture week header missing after ${READY_TIMEOUT_MS}ms"
    }
    check(device.wait(Until.hasObject(By.desc("新建").enabled(true)), READY_TIMEOUT_MS)) {
        "Fixture week grid not ready after ${READY_TIMEOUT_MS}ms"
    }
}
