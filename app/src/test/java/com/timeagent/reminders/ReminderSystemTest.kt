package com.timeagent.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.ReminderEntity
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.data.TimeRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReminderSystemTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)
    private val notificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val now = Instant.parse("2026-10-21T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var db: TimeAgentDatabase
    private lateinit var repository: TimeRepository
    private lateinit var coordinator: ReminderCoordinator

    @Before fun setup() {
        context.getSharedPreferences("reminder_alarm_registry_v1", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, TimeAgentDatabase::class.java).allowMainThreadQueries().build()
        repository = TimeRepository(db, clock)
        coordinator = ReminderCoordinator(context, repository, clock)
        shadowOf(notificationManager).setNotificationsEnabled(true)
    }

    @After fun cleanup() { db.close() }

    private fun reminder(id: String = "r1", seconds: Long = 600) =
        ReminderEntity(id, "交作业", now.plusSeconds(seconds).toEpochMilli())

    private fun alarms() = shadowOf(alarmManager).scheduledAlarms

    @Test fun `changing reminder time cancels old pending intent and keeps one current alarm`() = runBlocking {
        val first = reminder()
        db.dao().putReminder(first)
        coordinator.refresh()
        val oldOperation = alarms().single().operation
        db.dao().putReminder(first.copy(remindAt = first.remindAt + 300_000))

        coordinator.refresh()

        assertEquals(1, alarms().size)
        assertEquals(first.remindAt + 300_000, alarms().single().triggerAtTime)
        assertNotEquals(oldOperation, alarms().single().operation)
        assertFalse(alarms().any { it.operation == oldOperation })
        assertTrue(shadowOf(oldOperation).isCanceled)
    }

    @Test fun `disabling reminder cancels its alarm`() = runBlocking {
        val item = reminder()
        db.dao().putReminder(item)
        coordinator.refresh()
        assertEquals(1, alarms().size)
        db.dao().putReminder(item.copy(enabled = false))

        coordinator.refresh()

        assertTrue(alarms().isEmpty())
    }

    @Test fun `deleting reminder cancels its alarm even after coordinator recreation`() = runBlocking {
        db.dao().putReminder(reminder())
        coordinator.refresh()
        assertEquals(1, alarms().size)
        db.clearAllTables()

        ReminderCoordinator(context, repository, clock).refresh()

        assertTrue(alarms().isEmpty())
    }

    @Test fun `restoring after reboot only schedules future enabled undelivered reminders`() = runBlocking {
        db.dao().putReminder(reminder("future"))
        db.dao().putReminder(reminder("past", -1))
        db.dao().putReminder(reminder("disabled").copy(enabled = false))
        db.dao().putReminder(reminder("delivered").copy(deliveredAt = now.toEpochMilli()))

        ReminderCoordinator(context, repository, clock).refresh()

        assertEquals(1, alarms().size)
        assertEquals("future", shadowOf(alarms().single().operation).savedIntent.getStringExtra(ReminderIntents.EXTRA_REMINDER_ID))
    }

    @Test fun `stale id and old scheduled time cannot post notification or mark delivery`() = runBlocking {
        val item = reminder(seconds = -1)
        db.dao().putReminder(item)

        assertFalse(ReminderReceiver.deliver(context, repository, ReminderIntents.alarm(context, "removed", item.remindAt), clock))
        assertFalse(ReminderReceiver.deliver(context, repository, ReminderIntents.alarm(context, item.id, item.remindAt - 60_000), clock))

        assertEquals(0, shadowOf(notificationManager).size())
        assertNull(db.dao().reminder(item.id)!!.deliveredAt)
    }

    @Test fun `early or disabled reminder broadcast does not post notification`() = runBlocking {
        val future = reminder()
        val disabled = reminder("disabled", -1).copy(enabled = false)
        db.dao().putReminder(future)
        db.dao().putReminder(disabled)

        assertFalse(ReminderReceiver.deliver(context, repository, ReminderIntents.alarm(context, future.id, future.remindAt), clock))
        assertFalse(ReminderReceiver.deliver(context, repository, ReminderIntents.alarm(context, disabled.id, disabled.remindAt), clock))

        assertEquals(0, shadowOf(notificationManager).size())
        assertNull(db.dao().reminder(future.id)!!.deliveredAt)
        assertNull(db.dao().reminder(disabled.id)!!.deliveredAt)
    }

    @Test fun `current reminder publishes once and notification opens its reminder id`() = runBlocking {
        val item = reminder(seconds = -1)
        db.dao().putReminder(item)
        val broadcast = ReminderIntents.alarm(context, item.id, item.remindAt)

        assertTrue(ReminderReceiver.deliver(context, repository, broadcast, clock))
        assertFalse(ReminderReceiver.deliver(context, repository, broadcast, clock))

        assertEquals(1, shadowOf(notificationManager).size())
        assertNotNull(db.dao().reminder(item.id)!!.deliveredAt)
        val notification = shadowOf(notificationManager).allNotifications.single()
        val click = shadowOf(notification.contentIntent).savedIntent
        assertEquals("com.timeagent.MainActivity", click.component!!.className)
        assertEquals(item.id, click.getStringExtra(ReminderIntents.EXTRA_REMINDER_ID))
        assertNotNull(notificationManager.getNotificationChannel(ReminderNotifications.CHANNEL_ID))
    }

    @Test fun `unrecognized broadcast action cannot post notification`() = runBlocking {
        val item = reminder(seconds = -1)
        db.dao().putReminder(item)
        val unexpected = ReminderIntents.alarm(context, item.id, item.remindAt).setAction(Intent.ACTION_VIEW)

        assertFalse(ReminderReceiver.deliver(context, repository, unexpected, clock))

        assertEquals(0, shadowOf(notificationManager).size())
        assertNull(db.dao().reminder(item.id)!!.deliveredAt)
    }

    @Test @Config(sdk = [33])
    fun `Android 13 denied notification permission never marks reminder as delivered`() = runBlocking {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val item = reminder(seconds = -1)
        db.dao().putReminder(item)

        assertFalse(ReminderReceiver.deliver(context, repository, ReminderIntents.alarm(context, item.id, item.remindAt), clock))

        assertEquals(0, shadowOf(notificationManager).size())
        assertNull(db.dao().reminder(item.id)!!.deliveredAt)
        assertFalse(reminderAccessState(context).notificationsAllowed)
    }

    @Test @Config(sdk = [31])
    fun `denied exact alarm permission schedules a system inexact fallback`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val item = reminder()

        val precision = AndroidReminderScheduler(context).schedule(item.id, item.remindAt)

        assertEquals(AlarmPrecision.INEXACT, precision)
        assertEquals(1, alarms().size)
        assertEquals(item.remindAt, alarms().single().triggerAtTime)
        assertFalse(reminderAccessState(context).exactAlarmsAllowed)
    }

    @Test fun `blocked notification channel never marks reminder as delivered`() = runBlocking {
        notificationManager.createNotificationChannel(NotificationChannel(
            ReminderNotifications.CHANNEL_ID, "任务点提醒", NotificationManager.IMPORTANCE_NONE,
        ))
        val item = reminder(seconds = -1)
        db.dao().putReminder(item)

        assertFalse(ReminderReceiver.deliver(context, repository, ReminderIntents.alarm(context, item.id, item.remindAt), clock))

        assertEquals(0, shadowOf(notificationManager).size())
        assertNull(db.dao().reminder(item.id)!!.deliveredAt)
    }
}
