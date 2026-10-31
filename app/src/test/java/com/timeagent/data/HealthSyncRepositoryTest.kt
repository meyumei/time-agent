package com.timeagent.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HealthSyncRepositoryTest {
    private lateinit var db: TimeAgentDatabase
    private lateinit var repo: TimeRepository
    private val now = Instant.parse("2026-10-19T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("Asia/Shanghai"))
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setup() {
        context.deleteDatabase("health-sync-test.db")
        db = Room.databaseBuilder(context, TimeAgentDatabase::class.java, "health-sync-test.db").allowMainThreadQueries().build()
        repo = TimeRepository(db, clock)
    }
    @After fun teardown() { db.close(); context.deleteDatabase("health-sync-test.db") }

    private fun sleep(uid: String = "s1", startMinutesAgo: Long = 480, endMinutesAgo: Long = 30, title: String = "睡眠") =
        HealthSample(uid, HealthSampleKind.SLEEP, title, now.minusSeconds(startMinutesAgo * 60).toEpochMilli(), now.minusSeconds(endMinutesAgo * 60).toEpochMilli(), "com.mi.health")

    @Test fun `first sync creates records with source category and link`() = runBlocking {
        repo.initialize()
        val outcome = repo.syncHealthSamples(listOf(sleep(), HealthSample("e1", HealthSampleKind.EXERCISE, "练胸", now.minusSeconds(7200).toEpochMilli(), now.minusSeconds(3600).toEpochMilli(), "com.trainnote.rn")))
        assertEquals(HealthSyncOutcome(2, 0, 0, 0), outcome)
        val sleepRecord = db.dao().record("health:sleep:s1")!!
        assertEquals("睡眠", sleepRecord.title)
        assertEquals("HEALTH_SYNC", sleepRecord.source)
        assertEquals("life", sleepRecord.categoryId)
        assertNull(sleepRecord.taskId)
        val exerciseRecord = db.dao().record("health:exercise:e1")!!
        assertEquals("练胸", exerciseRecord.title)
        assertEquals(2, db.dao().healthItems().size)
        assertEquals("health:sleep:s1", db.dao().healthItem("s1")!!.recordId)
    }

    @Test fun `repeated sync is idempotent`() = runBlocking {
        repo.syncHealthSamples(listOf(sleep()))
        val second = repo.syncHealthSamples(listOf(sleep()))
        assertEquals(HealthSyncOutcome(0, 0, 0, 1), second)
        assertEquals(1, db.dao().records().size)
        assertEquals(1, db.dao().healthItems().size)
    }

    @Test fun `overlapping sleep from another source in one batch is deduplicated`() = runBlocking {
        val gadgetbridge = sleep("gb-1", startMinutesAgo = 480, endMinutesAgo = 30)
        val xiaomi = sleep("mi-1", startMinutesAgo = 470, endMinutesAgo = 20) // 50 min later, still overlapping
        val outcome = repo.syncHealthSamples(listOf(xiaomi, gadgetbridge))
        assertEquals(HealthSyncOutcome(1, 0, 0, 0, 1), outcome)
        assertEquals(1, db.dao().records().size)
        assertEquals("health:sleep:gb-1", db.dao().records().single().id)
    }

    @Test fun `new sample overlapping stored sleep is deduplicated and never displaces it`() = runBlocking {
        repo.syncHealthSamples(listOf(sleep("gb-1", startMinutesAgo = 480, endMinutesAgo = 30)))
        val xiaomi = sleep("mi-1", startMinutesAgo = 470, endMinutesAgo = 20)
        val outcome = repo.syncHealthSamples(listOf(xiaomi))
        assertEquals(HealthSyncOutcome(0, 0, 0, 0, 1), outcome)
        assertEquals(1, db.dao().records().size)
        assertEquals("health:sleep:gb-1", db.dao().records().single().id)
        assertNull(db.dao().healthItem("mi-1"))
    }

    @Test fun `non overlapping sleeps from different sources are both kept`() = runBlocking {
        val night = sleep("gb-1", startMinutesAgo = 60 * 20, endMinutesAgo = 60 * 12)
        val nap = sleep("mi-1", startMinutesAgo = 100, endMinutesAgo = 40) // > 30 min gap after night ends
        val outcome = repo.syncHealthSamples(listOf(night, nap))
        assertEquals(HealthSyncOutcome(2, 0, 0, 0, 0), outcome)
        assertEquals(2, db.dao().records().size)
    }

    private fun huawei(uid: String, startMinutesAgo: Long, endMinutesAgo: Long) =
        HealthSample(uid, HealthSampleKind.SLEEP, "睡眠", now.minusSeconds(startMinutesAgo * 60).toEpochMilli(), now.minusSeconds(endMinutesAgo * 60).toEpochMilli(), "com.huawei.health")

    private fun gadgetbridge(uid: String, startMinutesAgo: Long, endMinutesAgo: Long) =
        HealthSample(uid, HealthSampleKind.SLEEP, "睡眠", now.minusSeconds(startMinutesAgo * 60).toEpochMilli(), now.minusSeconds(endMinutesAgo * 60).toEpochMilli(), "nodomain.freeyourgadget.gadgetbridge")

    @Test fun `preferred origin displaces stored sleep from another origin`() = runBlocking {
        repo.syncHealthSamples(listOf(gadgetbridge("gb-1", 480, 30)))
        val outcome = repo.syncHealthSamples(listOf(huawei("hw-1", 470, 20)), preferredSleepOrigin = "华为运动健康")
        assertEquals(HealthSyncOutcome(1, 0, 0, 0, 0), outcome)
        assertNull(db.dao().record("health:sleep:gb-1"))
        assertNull(db.dao().healthItem("gb-1"))
        val record = db.dao().record("health:sleep:hw-1")!!
        assertEquals(470 * 60_000L, now.toEpochMilli() - record.startAt)
        assertEquals(1, db.dao().records().size)
    }

    @Test fun `preferred origin never displaces manually edited sleep`() = runBlocking {
        repo.syncHealthSamples(listOf(gadgetbridge("gb-1", 480, 30)))
        val record = db.dao().record("health:sleep:gb-1")!!
        repo.saveRecord(record.copy(title = "补觉", startAt = record.startAt + 600000), expectedVersion = record.version)
        val outcome = repo.syncHealthSamples(listOf(huawei("hw-1", 470, 20)), preferredSleepOrigin = "华为运动健康")
        assertEquals(HealthSyncOutcome(0, 0, 0, 0, 1), outcome)
        assertEquals("补觉", db.dao().record("health:sleep:gb-1")!!.title)
        assertEquals(1, db.dao().records().size)
    }

    @Test fun `preferred origin sample wins within one mixed batch`() = runBlocking {
        val gadgetbridgeSample = gadgetbridge("gb-1", 480, 30) // earlier start, but non-preferred
        val huaweiSample = huawei("hw-1", 470, 20)
        val outcome = repo.syncHealthSamples(listOf(gadgetbridgeSample, huaweiSample), preferredSleepOrigin = "华为运动健康")
        assertEquals(HealthSyncOutcome(1, 0, 0, 0, 1), outcome)
        assertNotNull(db.dao().record("health:sleep:hw-1"))
        assertNull(db.dao().record("health:sleep:gb-1"))
    }

    @Test fun `two preferred fragments replace two non preferred segments`() = runBlocking {
        repo.syncHealthSamples(listOf(
            gadgetbridge("gb-a", 480, 360), // 01:xx style split segments like GB produces
            gadgetbridge("gb-b", 300, 30),
        ))
        val outcome = repo.syncHealthSamples(listOf(huawei("hw-1", 470, 20)), preferredSleepOrigin = "华为运动健康")
        assertEquals(HealthSyncOutcome(1, 0, 0, 0, 0), outcome)
        assertEquals(1, db.dao().records().size)
        assertNull(db.dao().healthItem("gb-a"))
        assertNull(db.dao().healthItem("gb-b"))
        assertEquals("health:sleep:hw-1", db.dao().records().single().id)
    }

    @Test fun `source changes follow through when record not manually edited`() = runBlocking {
        repo.syncHealthSamples(listOf(sleep()))
        val changed = sleep(startMinutesAgo = 510, endMinutesAgo = 60)
        val outcome = repo.syncHealthSamples(listOf(changed))
        assertEquals(HealthSyncOutcome(0, 1, 0, 0), outcome)
        val record = db.dao().record("health:sleep:s1")!!
        assertEquals(changed.startAt, record.startAt)
        assertEquals(changed.endAt, record.endAt)
        assertEquals(1, db.dao().records().size)
    }

    @Test fun `manual edits win over later source changes`() = runBlocking {
        repo.syncHealthSamples(listOf(sleep()))
        val record = db.dao().record("health:sleep:s1")!!
        repo.saveRecord(record.copy(title = "午睡", startAt = record.startAt + 600000), expectedVersion = record.version)
        val outcome = repo.syncHealthSamples(listOf(sleep(startMinutesAgo = 510)))
        assertEquals(HealthSyncOutcome(0, 0, 1, 0), outcome)
        val after = db.dao().record("health:sleep:s1")!!
        assertEquals("午睡", after.title)
        assertTrue(after.isManuallyEdited)
    }

    @Test fun `deleted synced record is tombstoned and not resurrected`() = runBlocking {
        repo.syncHealthSamples(listOf(sleep()))
        repo.deleteRecord("health:sleep:s1")
        val outcome = repo.syncHealthSamples(listOf(sleep()))
        assertEquals(HealthSyncOutcome(0, 0, 0, 1), outcome)
        assertTrue(db.dao().records().isEmpty())
        val item = db.dao().healthItem("s1")!!
        assertTrue(item.userDeleted)
        assertNull(item.recordId)
    }

    @Test fun `undo of a delete relinks the restored record instead of duplicating`() = runBlocking {
        repo.syncHealthSamples(listOf(sleep()))
        repo.deleteRecord("health:sleep:s1")
        repo.undoLastAction()
        assertNotNull(db.dao().record("health:sleep:s1"))
        val outcome = repo.syncHealthSamples(listOf(sleep()))
        assertEquals(HealthSyncOutcome(0, 0, 0, 1), outcome)
        assertEquals(1, db.dao().records().size)
        val item = db.dao().healthItem("s1")!!
        assertEquals("health:sleep:s1", item.recordId)
        assertFalse(item.userDeleted)
    }

    @Test fun `in-progress sessions are skipped until they close`() = runBlocking {
        val inProgress = HealthSample("s2", HealthSampleKind.SLEEP, "睡眠", now.minusSeconds(3600).toEpochMilli(), now.plusSeconds(3600).toEpochMilli(), "com.mi.health")
        assertEquals(HealthSyncOutcome(0, 0, 0, 0), repo.syncHealthSamples(listOf(inProgress)))
        assertTrue(db.dao().records().isEmpty())
        assertTrue(db.dao().healthItems().isEmpty())
    }

    @Test fun `sync writes do not journal or retire redo`() = runBlocking {
        repo.initialize()
        repo.saveTask(TaskEntity("t1", "任务"))
        repo.deleteTask("t1")
        repo.undoLastAction()
        assertTrue(db.dao().history().any { it.undone && it.redoable })
        repo.syncHealthSamples(listOf(sleep()))
        assertTrue(db.dao().history().none { it.entityType == "RECORD" })
        repo.redoLastAction()
        assertNotNull(db.dao().task("t1")!!.deletedAt)
        assertEquals(1, db.dao().records().size)
    }

    @Test fun `snapshot exposes synced items for the settings screen`() = runBlocking {
        repo.syncHealthSamples(listOf(sleep()))
        val items = repo.snapshot().healthItems
        assertEquals(1, items.size)
        assertEquals("SLEEP", items.single().kind)
        assertEquals("com.mi.health", items.single().originPackage)
    }
}
