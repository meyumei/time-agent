package com.timeagent.reminders

import android.app.AlarmManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.room.MultiInstanceInvalidationService
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.timeagent.MainActivity
import com.timeagent.data.ReminderEntity
import com.timeagent.data.TimeAgentDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Phase A contract test: a reminder stored in Room must be armed with AlarmManager when the app
 * starts. ReminderCoordinator exists and is unit-tested, but nothing in the app calls start() or
 * refresh(), so today the production APK never schedules a reminder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(androidx.room.ExperimentalRoomApi::class)
class ReminderAppLaunchTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val alarmManager get() = app.getSystemService(AlarmManager::class.java)
    private val remindAt = System.currentTimeMillis() + 3_600_000L

    @Before fun seed() {
        bindRoomMultiInstanceService()
        app.deleteDatabase("time-agent.db")
        app.getSharedPreferences("reminder_alarm_registry_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val db = TimeAgentDatabase.open(app)
        try {
            runBlocking { db.dao().putReminder(ReminderEntity("launch-r1", "交作业", remindAt)) }
        } finally {
            db.close()
        }
    }

    @After fun cleanup() {
        app.deleteDatabase("time-agent.db")
    }

    @Test fun `launching the app arms the system alarm of an existing future reminder`() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val armed = awaitAlarm("launch-r1")
            assertNotNull("应用启动后没有为未来提醒设置任何系统闹钟", armed)
            assertEquals(remindAt, armed!!.triggerAtTime)
            assertEquals(
                "launch-r1",
                shadowOf(armed.operation).savedIntent.getStringExtra(ReminderIntents.EXTRA_REMINDER_ID),
            )
        }
    }

    /**
     * Room's multi-instance invalidation binds a service. Robolectric's default bind shadow passes a
     * null connection name, which crashes the real ServiceConnection. Bind the real service binder so
     * production keeps enableMultiInstanceInvalidation().
     */
    private fun bindRoomMultiInstanceService() {
        val service = Robolectric.buildService(MultiInstanceInvalidationService::class.java).create().get()
        val binder = requireNotNull(service.onBind(Intent(app, MultiInstanceInvalidationService::class.java)))
        shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName(app, MultiInstanceInvalidationService::class.java), binder,
        )
    }

    private fun awaitAlarm(id: String): ShadowAlarmManager.ScheduledAlarm? {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(alarmManager).scheduledAlarms.firstOrNull {
                shadowOf(it.operation).savedIntent.getStringExtra(ReminderIntents.EXTRA_REMINDER_ID) == id
            }?.let { return it }
            Thread.sleep(50)
        }
        return null
    }
}
