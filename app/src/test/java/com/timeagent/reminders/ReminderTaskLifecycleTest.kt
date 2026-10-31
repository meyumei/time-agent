package com.timeagent.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.ReminderEntity
import com.timeagent.data.TaskEntity
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.data.TimeRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Phase A contract tests for the native reminder scheduler.
 *
 * Delivery already refuses reminders whose linked task is completed, cancelled or deleted
 * (TimeRepository.markReminderDelivered). Arming a system alarm for the same records wastes a
 * wakeup and leaves deliveredAt empty forever, so reconciliation must apply the same rule.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReminderTaskLifecycleTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)
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
    }

    @After fun cleanup() = db.close()

    private fun alarms() = shadowOf(alarmManager).scheduledAlarms

    private fun idOf(operation: PendingIntent): String? =
        shadowOf(operation).savedIntent.getStringExtra(ReminderIntents.EXTRA_REMINDER_ID)

    private fun futureReminder(id: String, taskId: String) =
        ReminderEntity(id, "交报告", now.plusSeconds(600).toEpochMilli(), taskId = taskId)

    @Test fun `completing the linked task cancels its pending alarm`() = runBlocking {
        val task = TaskEntity("t1", "交报告")
        db.dao().putTask(task)
        db.dao().putReminder(futureReminder("r1", "t1"))
        coordinator.refresh()
        assertEquals(listOf("r1"), alarms().map { idOf(requireNotNull(it.operation)) })

        db.dao().putTask(task.copy(status = "COMPLETED", completedAt = now.toEpochMilli()))
        coordinator.refresh()

        assertTrue("已完成任务的提醒仍被系统闹钟唤醒", alarms().none { idOf(requireNotNull(it.operation)) == "r1" })
    }

    @Test fun `reminder of an already completed task is never armed`() = runBlocking {
        db.dao().putTask(TaskEntity("t1", "交报告", status = "COMPLETED"))
        db.dao().putReminder(futureReminder("r1", "t1"))

        coordinator.refresh()

        assertTrue("已完成任务的提醒不应进入系统闹钟", alarms().none { idOf(requireNotNull(it.operation)) == "r1" })
    }

    @Test fun `reminder of a cancelled task is never armed`() = runBlocking {
        db.dao().putTask(TaskEntity("t1", "交报告", status = "CANCELLED"))
        db.dao().putReminder(futureReminder("r1", "t1"))

        coordinator.refresh()

        assertTrue("已取消任务的提醒不应进入系统闹钟", alarms().none { idOf(requireNotNull(it.operation)) == "r1" })
    }
}
