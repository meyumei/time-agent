package com.timeagent.health

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.HealthSampleKind
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GadgetbridgeImporterTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-25T10:00:00Z") // 18:00 Shanghai, Oct 25

    private lateinit var dbFile: File

    @Before fun setup() {
        dbFile = File(context.cacheDir, "gb-test-${System.nanoTime()}.db")
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("CREATE TABLE HUAWEI_ACTIVITY_SAMPLE (TIMESTAMP INTEGER, DEVICE_ID INTEGER, USER_ID INTEGER, OTHER_TIMESTAMP INTEGER, SOURCE INTEGER, RAW_KIND INTEGER, RAW_INTENSITY INTEGER, STEPS INTEGER, CALORIES INTEGER, DISTANCE INTEGER, SPO INTEGER, HEART_RATE INTEGER, RESTING_HEART_RATE INTEGER)")
        }
    }
    @After fun cleanup() { dbFile.delete() }

    private fun insert(ts: Long, kind: Int, deviceId: Long = 1L) {
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT INTO HUAWEI_ACTIVITY_SAMPLE VALUES (?, ?, 1, ?, 0, ?, 0, 0, 0, 0, 0, -1, -1)", arrayOf(ts, deviceId, ts, kind))
        }
    }

    private fun at(hour: Int, minute: Int, day: Int = 25): Long =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().epochSecond

    private fun minute(base: Long, offset: Int) = base + offset * 60L

    @Test fun `split fragments of one night aggregate into one vendor style session`() {
        val night = 25
        // Two fragments like GB/HC real data: 01:44-04:22 and 06:04-08:25, sparse minute samples.
        listOf(
            minute(at(1, 44, night), 0), minute(at(1, 44, night), 30), minute(at(2, 20, night), 0), minute(at(4, 21, night), 0),
            minute(at(6, 4, night), 0), minute(at(7, 0, night), 0), minute(at(8, 24, night), 0),
        ).forEachIndexed { index, ts -> insert(ts, if (index == 3) 8 else 6) }
        val sessions = GadgetbridgeImporter.readSleepSessions(dbFile, zone, Instant.ofEpochSecond(at(0, 0, night)).minusSeconds(86400), now)
        assertEquals(1, sessions.size)
        val session = sessions.single()
        assertEquals(at(1, 44, night) * 1000, session.startAt)
        assertEquals((at(8, 24, night) + 60) * 1000, session.endAt)
        assertEquals("nodomain.freeyourgadget.gadgetbridge", session.originPackage)
        assertTrue(session.detail.contains("深 0 分钟"))
        assertTrue(session.uid.startsWith("gb-night:1:2026-10-"))
    }

    @Test fun `late evening and after midnight samples merge into one night`() {
        // 23:30/23:50 on Oct 24 and 01:30 on Oct 25 belong to the SAME sleep night by design.
        insert(at(23, 30, 24), 6)
        insert(at(23, 50, 24), 7)
        insert(at(1, 30, 25), 6)
        val sessions = GadgetbridgeImporter.readSleepSessions(dbFile, zone, Instant.ofEpochSecond(at(0, 0, 24)).minusSeconds(86400), now)
        assertEquals(1, sessions.size)
        assertEquals(at(23, 30, 24) * 1000, sessions.single().startAt)
        assertEquals((at(1, 30, 25) + 60) * 1000, sessions.single().endAt)
    }

    @Test fun `two distinct nights produce separate sessions`() {
        insert(at(23, 30, 23), 6)
        insert(at(1, 30, 24), 6)
        insert(at(23, 30, 24), 7)
        insert(at(1, 30, 25), 6)
        val sessions = GadgetbridgeImporter.readSleepSessions(dbFile, zone, Instant.ofEpochSecond(at(0, 0, 23)).minusSeconds(86400), now)
        assertEquals(2, sessions.size)
        assertTrue(sessions.all { it.kind == HealthSampleKind.SLEEP })
    }

    @Test fun `non sleep kinds are ignored`() {
        insert(at(12, 0, 25), 1)
        insert(at(13, 0, 25), 3)
        assertTrue(GadgetbridgeImporter.readSleepSessions(dbFile, zone, Instant.ofEpochSecond(at(0, 0, 25)).minusSeconds(86400), now).isEmpty())
    }

    @Test fun `night still in progress is skipped`() {
        insert(at(18, 30, 25), 6) // anchors Oct 25; ends after the fixed "now" (18:00 Shanghai)
        val sessions = GadgetbridgeImporter.readSleepSessions(dbFile, zone, Instant.ofEpochSecond(at(0, 0, 24)).minusSeconds(86400), now)
        assertTrue(sessions.isEmpty())
    }

    @Test fun `missing or empty file yields nothing`() {
        assertTrue(GadgetbridgeImporter.readSleepSessions(File(context.cacheDir, "nope.db"), zone, now.minusSeconds(86400), now).isEmpty())
    }
}
