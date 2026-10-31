package com.timeagent.ui

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import com.timeagent.data.HealthSample
import com.timeagent.data.HealthSampleKind
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.data.TimeRepository
import com.timeagent.health.HealthConnectState
import com.timeagent.health.HealthDataReader
import com.timeagent.health.HealthSyncSettings
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

private class FakeHealthReader(var state: HealthConnectState = HealthConnectState.Ready) : HealthDataReader {
    var sleepSamples: List<HealthSample> = emptyList()
    var exerciseSamples: List<HealthSample> = emptyList()
    var requestedWindows = mutableListOf<Pair<Instant, Instant>>()
    override suspend fun state(): HealthConnectState = state
    override suspend fun readSleep(since: Instant, until: Instant): List<HealthSample> {
        requestedWindows += since to until
        return sleepSamples
    }
    override suspend fun readExercise(since: Instant, until: Instant): List<HealthSample> {
        requestedWindows += since to until
        return exerciseSamples
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HealthSyncScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: TimeAgentDatabase
    private lateinit var viewModel: TimeViewModel
    private lateinit var settings: HealthSyncSettings
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-19T02:00:00Z") // 10:00 Shanghai

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("health_sync", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, TimeAgentDatabase::class.java).allowMainThreadQueries().build()
        viewModel = TimeViewModel(TimeRepository(db, Clock.fixed(now, zone)))
        settings = HealthSyncSettings(context)
    }

    @After fun cleanup() {
        viewModel.viewModelScope.cancel()
        db.close()
    }

    private fun idle() { Shadows.shadowOf(Looper.getMainLooper()).idle() }

    private fun at(hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, 10, 19, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun yesterday(hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, 10, 18, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun `ready state shows status and performs a sync with outcome summary`() {
        val reader = FakeHealthReader(HealthConnectState.Ready).apply {
            sleepSamples = listOf(HealthSample("s1", HealthSampleKind.SLEEP, "睡眠", at(1, 30), at(8, 30), "com.mi.health"))
            exerciseSamples = listOf(HealthSample("e1", HealthSampleKind.EXERCISE, "练胸", yesterday(18, 0), yesterday(19, 0), "com.trainnote.rn"))
        }
        compose.setContent { TimeAgentTheme { HealthSyncScreen(viewModel, reader, settings, zone) {} } }
        compose.waitUntil(10_000) { idle(); compose.onAllNodesWithText("Health Connect 已就绪，可读取睡眠与健身记录。").fetchSemanticsNodes().isNotEmpty() }
        // The page scrolls: the GB file card pushes the sync button below the 891dp viewport,
        // and a click on an off-screen node is silently dropped.
        compose.onNodeWithTag("health-sync-now").performScrollTo().performClick()
        compose.waitUntil(10_000) { idle(); compose.onAllNodesWithText("新增 2 · 跟随更新 0 · 手工保护 0 · 无变化 0 · 去重 0", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("睡眠 · 小米运动健康", substring = true).assertExists()
        compose.onNodeWithText("练胸 · 训记", substring = true).assertExists()
        assertTrue(settings.lastSyncAt() > 0)
    }

    @Test fun `needs permission state disables sync and shows guidance`() {
        val reader = FakeHealthReader(HealthConnectState.NeedsPermission)
        compose.setContent { TimeAgentTheme { HealthSyncScreen(viewModel, reader, settings, zone) {} } }
        compose.waitUntil(10_000) { idle(); compose.onAllNodesWithText("需要读取授权后才能同步睡眠与健身记录。").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("去授权读取").assertExists()
        compose.onNodeWithText("立即同步").assertIsNotEnabled()
    }

    @Test fun `connected Gadgetbridge file permits manual sync without Health Connect`() {
        settings.setGbExportUri("content://test.gadgetbridge/export.db")
        compose.setContent { TimeAgentTheme { HealthSyncScreen(viewModel, FakeHealthReader(HealthConnectState.Unavailable), settings, zone) {} } }
        compose.waitUntil(10_000) { idle(); compose.onAllNodesWithTag("gb-file-state").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("health-sync-now").performScrollTo().assertIsEnabled()
    }

    @Test fun `toggles persist to settings and window chips change the request range`() {
        val reader = FakeHealthReader(HealthConnectState.Ready).apply {
            sleepSamples = listOf(HealthSample("s1", HealthSampleKind.SLEEP, "睡眠", at(1, 30), at(8, 30), "com.mi.health"))
        }
        compose.setContent { TimeAgentTheme { HealthSyncScreen(viewModel, reader, settings, zone) {} } }
        compose.waitUntil(10_000) { idle(); compose.onAllNodesWithText("Health Connect 已就绪，可读取睡眠与健身记录。").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("health-sync-auto").performClick()
        compose.onNodeWithTag("health-sync-exercise").performClick()
        compose.onNodeWithTag("health-sync-window-30").performScrollTo().performClick()
        assertTrue(settings.enabled())
        assertFalse(settings.exerciseEnabled())
        assertTrue(settings.windowDays() == 30)
        compose.onNodeWithTag("health-sync-now").performScrollTo().performClick()
        compose.waitUntil(10_000) { idle(); compose.onAllNodesWithText("新增 1 · 跟随更新 0 · 手工保护 0 · 无变化 0 · 去重 0", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val (since, until) = reader.requestedWindows.single()
        assertTrue(until.epochSecond - since.epochSecond == 30L * 24 * 3600)
    }
}
