package com.timeagent.reminders

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.ReminderEntity
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.data.TimeRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Phase A regression guards for the native notification surface. These describe behaviour the
 * current module already implements; they exist so the phase B wiring work cannot regress it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReminderNotificationContractTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)
    private val notificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val now = Instant.parse("2026-10-21T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val afterDue = Clock.fixed(now.plusSeconds(120), ZoneOffset.UTC)
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

    @After fun cleanup() = db.close()

    private fun reminder(id: String, title: String) =
        ReminderEntity(id, title, now.plusSeconds(60).toEpochMilli())

    private suspend fun deliver(item: ReminderEntity): Boolean =
        ReminderReceiver.deliver(context, TimeRepository(db, afterDue), ReminderIntents.alarm(context, item.id, item.remindAt), afterDue)

    @Test fun `two reminders due at the same time keep independent alarms and notifications`() = runBlocking {
        val first = reminder("r1", "交作业")
        val second = reminder("r2", "开会")
        db.dao().putReminder(first)
        db.dao().putReminder(second)

        coordinator.refresh()

        val alarms = shadowOf(alarmManager).scheduledAlarms
        assertEquals(2, alarms.size)
        assertEquals(2, alarms.map { it.operation }.toSet().size)

        assertTrue(deliver(first))
        assertTrue(deliver(second))

        assertEquals(2, shadowOf(notificationManager).allNotifications.size)
    }

    @Test fun `lockscreen public version never exposes the reminder title`() = runBlocking {
        val item = reminder("r1", "私密：复诊")
        db.dao().putReminder(item)

        assertTrue(deliver(item))

        val notification = shadowOf(notificationManager).allNotifications.single()
        val public = requireNotNull(notification.publicVersion)
        assertEquals("有一条任务提醒", public.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertFalse(public.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains(item.title))
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
    }

    @Test fun `deleting a delivered reminder dismisses its notification`() = runBlocking {
        val item = reminder("r1", "交作业")
        db.dao().putReminder(item)
        coordinator.refresh()
        assertTrue(deliver(item))
        assertEquals(1, shadowOf(notificationManager).allNotifications.size)

        db.dao().removeReminder(item.id)
        coordinator.refresh()

        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
    }

    @Test fun `refresh keeps a delivered notification until the reminder is disabled`() = runBlocking {
        val item = reminder("r1", "交作业")
        db.dao().putReminder(item)
        coordinator.refresh()
        assertTrue(deliver(item))
        assertEquals(1, shadowOf(notificationManager).allNotifications.size)

        ReminderCoordinator(context, TimeRepository(db, afterDue), afterDue).refresh()
        assertEquals("A delivered point must stay visible on refresh", 1, shadowOf(notificationManager).allNotifications.size)

        db.dao().putReminder(item.copy(enabled = false))
        ReminderCoordinator(context, TimeRepository(db, afterDue), afterDue).refresh()

        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
    }
}
