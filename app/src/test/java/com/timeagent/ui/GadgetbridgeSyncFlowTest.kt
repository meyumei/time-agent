package com.timeagent.ui

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import com.timeagent.data.*
import com.timeagent.health.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GadgetbridgeSyncFlowTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-19T02:00:00Z")
    private lateinit var db: TimeAgentDatabase
    private lateinit var model: TimeViewModel
    private lateinit var settings: HealthSyncSettings
    private lateinit var export: File

    @Before fun setup() {
        context.getSharedPreferences("health_sync", 0).edit().clear().commit()
        settings = HealthSyncSettings(context)
        db = Room.inMemoryDatabaseBuilder(context, TimeAgentDatabase::class.java).allowMainThreadQueries().build()
        model = TimeViewModel(TimeRepository(db, Clock.fixed(now, zone)), context)
        export = File(context.cacheDir, "gb-flow-${System.nanoTime()}.db")
        SQLiteDatabase.openOrCreateDatabase(export, null).use {
            it.execSQL("CREATE TABLE HUAWEI_ACTIVITY_SAMPLE (TIMESTAMP INTEGER, DEVICE_ID INTEGER, RAW_KIND INTEGER)")
            it.execSQL("INSERT INTO HUAWEI_ACTIVITY_SAMPLE VALUES (?, 1, 6), (?, 1, 7)", arrayOf(now.minusSeconds(8*3600).epochSecond, now.minusSeconds(2*3600).epochSecond))
        }
        settings.setGbExportUri(export.toURI().toString())
    }

    @After fun cleanup() { model.viewModelScope.cancel(); db.close(); export.delete() }

    private fun sync(state: HealthConnectState): HealthSyncOutcome? {
        val done = CompletableDeferred<HealthSyncOutcome?>()
        val reader = object : HealthDataReader {
            override suspend fun state() = state
            override suspend fun readSleep(since: Instant, until: Instant) = emptyList<HealthSample>()
            override suspend fun readExercise(since: Instant, until: Instant) = emptyList<HealthSample>()
        }
        model.runHealthSync(reader, settings) { done.complete(it) }
        val deadline = System.currentTimeMillis()+10_000
        while (!done.isCompleted && System.currentTimeMillis()<deadline) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)); Thread.sleep(20) }
        assertTrue("Sync callback did not finish", done.isCompleted)
        return runBlocking { done.await() }
    }

    @Test fun `Gadgetbridge sleep imports with Health Connect unavailable`() {
        assertEquals(1, sync(HealthConnectState.Unavailable)?.inserted)
        assertEquals(1, runBlocking { db.dao().healthItems().size })
    }

    @Test fun `disabled sleep never imports Gadgetbridge sleep`() {
        settings.setSleepEnabled(false)
        assertEquals(0, sync(HealthConnectState.Ready)?.inserted)
        assertTrue(runBlocking { db.dao().healthItems().isEmpty() })
    }

    @Test fun `broken Gadgetbridge export is reported even when Health Connect returns no data`() {
        settings.setGbExportUri("content://unavailable.gadgetbridge/export.db")
        assertNull(sync(HealthConnectState.Ready))
        assertTrue(model.error.value.orEmpty().contains("Gadgetbridge"))
        assertEquals(0L, settings.lastSyncAt())
    }
}
