package com.timeagent.ui

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.timeagent.core.ClockSource
import com.timeagent.core.SystemClockSource
import com.timeagent.core.RecurringPattern
import com.timeagent.data.RecurringRuleEntity
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.data.TimeRepository
import com.timeagent.recurring.RecurringService
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

@OptIn(InternalComposeTracingApi::class)
class ClockRecompositionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var database: TimeAgentDatabase
    private lateinit var model: TimeViewModel
    private val wallClock = MutableClock(Instant.parse("2026-10-19T08:00:10Z"))
    private val trace = CompositionCounts()

    @After fun close() {
        Composer.setTracer(null)
        if (::model.isInitialized) model.viewModelScope.cancel()
        if (::database.isInitialized) database.close()
    }

    @Test fun fiveTimerSecondsCauseZeroTimelineOrRootRecompositions() {
        showRunningTimer()
        val timelineBefore = trace.timeline
        val rootBefore = trace.root

        repeat(5) { advanceWallBy(1) }

        compose.onNodeWithText("计时中 · 00:00:05").assertExists()
        println("CLOCK_FIVE_SECONDS timeline=${trace.timeline - timelineBefore} root=${trace.root - rootBefore}")
        assertEquals("Second ticks must not recompose the timeline", 0, trace.timeline - timelineBefore)
        assertEquals("The root must not collect wall-clock ticks", 0, trace.root - rootBefore)
    }

    @Test fun aMinuteBoundaryUpdatesTheTimelineWhileTheTimerKeepsItsDuration() {
        showRunningTimer()
        val before = trace.timeline

        advanceWallBy(50)

        compose.onNodeWithText("计时中 · 00:00:50").assertExists()
        assertTrue("A new minute must update the timeline", trace.timeline > before)
    }

    @Test fun midnightUpdatesTodayAndKeepsTheHourlyRecurringRefresh() {
        wallClock.current = Instant.parse("2026-10-19T15:59:58Z")
        showRunningTimer()
        val pattern = RecurringPattern("clock-daily", "DAILY", "2026-10-19")
        runBlocking {
            RecurringService(model.repository).saveRule(
                RecurringRuleEntity(pattern.id, "CLOCK_DAILY", Gson().toJson(pattern)),
                null, LocalDate.parse(pattern.startDate), "clock-daily-seed",
            )
        }
        val lastDate = runBlocking {
            model.repository.snapshot().recurringInstances.maxOf { LocalDate.parse(it.originalDate) }
        }
        val expectedLastDate = lastDate.plusDays(1).toString()

        advanceWallBy(2)

        compose.waitUntil(10_000) {

            model.week.value.today == LocalDate.of(2026, 10, 20) &&
                model.data.value.recurringInstances.any { it.originalDate == expectedLastDate }
        }
        compose.onNodeWithText("计时中 · 00:00:02").assertExists()
        assertEquals(LocalDate.of(2026, 10, 19), model.week.value.selectedWeekStart)
        assertEquals(1, model.data.value.records.count { it.endAt == null })
    }

    @Test fun theInjectedClockDefinesInitialAndCurrentWeekNavigation() {
        database = TimeAgentDatabase.openInMemory(ApplicationProvider.getApplicationContext<Context>())
        val repositoryClock = Clock.fixed(Instant.parse("2026-10-05T08:00:00Z"), wallClock.zone)
        val ticks = MutableStateFlow(Instant.parse("2026-11-02T08:00:00Z"))
        val source = object : ClockSource {
            override fun now() = ticks.value
            override val minuteTicks = ticks
            override val secondTicks = emptyFlow<Instant>()
            override fun elapsedRealtime() = 0L
        }
        model = TimeViewModel(TimeRepository(database, repositoryClock), clock = source)
        compose.setContent {}
        compose.waitUntil(10_000) {

            !model.data.value.loading
        }
        val expectedWeek = ticks.value.atZone(wallClock.zone).toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        assertEquals(expectedWeek, model.week.value.selectedWeekStart)
        compose.runOnIdle { model.changeWeek(-1) }
        compose.runOnIdle { model.currentWeek() }
        assertEquals(expectedWeek, model.week.value.selectedWeekStart)
    }

    @Test fun resumeRefreshesMinuteTimeAfterSleepWithoutWaitingForThePendingTick() {
        val owner = TestLifecycleOwner()
        showRunningTimer(owner = owner)
        compose.runOnIdle {
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            wallClock.current = wallClock.current.plus(Duration.ofDays(1))
        }

        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()

        assertEquals("Resume must not replay yesterday until the old minute delay wakes",
            wallClock.instant(), model.now.value)
        assertEquals(LocalDate.of(2026, 10, 20), model.week.value.today)
        compose.onNodeWithText("计时中 · 24:00:00").assertExists()
    }

    @Test fun onlyTheVisibleTimerCollectsSecondsAcrossTabsEditorsAndStop() {
        val source = CountingClockSource(SystemClockSource(wallNow = wallClock::instant))
        showRunningTimer(source)
        assertEquals(1, source.secondCollectors)
        compose.onNodeWithContentDescription("计划页面").performClick()
        assertEquals("The global timer remains visible on plan", 1, source.secondCollectors)
        compose.onNodeWithContentDescription("复盘页面").performClick()
        assertEquals(1, source.secondCollectors)
        compose.onNodeWithContentDescription("今天页面").performClick()
        compose.onNodeWithTag("today-record").performClick()
        compose.waitForIdle()
        assertEquals("The hidden timer must release its second flow", 0, source.secondCollectors)
        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()
        assertEquals(1, source.secondCollectors)
        advanceWallBy(1)
        compose.onNodeWithText("结束计时").performClick()
        compose.waitUntil(10_000) {

            model.data.value.records.none { it.endAt == null }
        }
        compose.waitForIdle()
        assertEquals(0, source.secondCollectors)
    }

    private fun showRunningTimer(clock: ClockSource? = null, owner: LifecycleOwner? = null) {
        database = TimeAgentDatabase.openInMemory(ApplicationProvider.getApplicationContext<Context>())
        val repository = TimeRepository(database, wallClock)
        runBlocking {
            repository.initialize()
            repository.startTimer(null, "clock-recomposition")
        }
        val source = clock ?: object : ClockSource by SystemClockSource(wallNow = wallClock::instant) {
            // Compose's test dispatcher controls UI delays; the production ticker needs real elapsed time.
            override val secondTicks = SystemClockSource(wallNow = wallClock::instant).secondTicks.flowOn(Dispatchers.Default)
        }
        model = TimeViewModel(repository, clock = source)
        Composer.setTracer(trace)
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides (owner ?: LocalLifecycleOwner.current)) {
                TimeAgentApp(model, speechFactory = { _, callbacks -> object : com.timeagent.assistant.SpeechInput {
                    override val available = true
                    override fun start() {}
                    override fun stop() {}
                    override fun cancel() {}
                    override fun close() {}
                } })
            }
        }
        compose.waitUntil(10_000) {

            !model.data.value.loading
        }
        compose.waitForIdle()
        assertTrue("The tracer must observe the real TimelineScreen", trace.timeline > 0)
        assertTrue("The tracer must observe the real TimeAgentContent", trace.root > 0)
        compose.onNodeWithText("计时中 · 00:00:00").assertExists()
    }

    private fun advanceWallBy(seconds: Long) {
        compose.runOnIdle { wallClock.current = wallClock.current.plusSeconds(seconds) }
        Thread.sleep(seconds * 1000 + 150)
        compose.waitForIdle()
        val tree = compose.onRoot().printToString()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("clockDevice", "now=${model.clock.now()} wall=${wallClock.instant()} " +
                tree.lineSequence().filter { it.contains("计时中") }.joinToString())
        })
    }

    private class MutableClock(var current: Instant) : Clock() {
        override fun instant(): Instant = current
        override fun getZone(): ZoneId = ZoneId.of("Asia/Shanghai")
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
    }

    private class TestLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
    }

    private class CountingClockSource(private val delegate: ClockSource) : ClockSource by delegate {
        var secondCollectors = 0
        override val secondTicks = flow {
            secondCollectors++
            try { emitAll(delegate.secondTicks.flowOn(Dispatchers.Default)) }
            finally { secondCollectors-- }
        }
    }

    private class CompositionCounts : CompositionTracer {
        var timeline = 0
        var root = 0
        override fun isTraceInProgress() = true
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            if (info.startsWith("com.timeagent.ui.TimelineScreen (")) timeline++
            if (info.startsWith("com.timeagent.ui.TimeAgentContent (")) root++
        }
        override fun traceEventEnd() = Unit
    }
}
