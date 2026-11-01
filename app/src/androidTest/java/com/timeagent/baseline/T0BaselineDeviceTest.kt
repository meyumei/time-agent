package com.timeagent.baseline

import android.app.AlarmManager
import android.content.Context
import android.os.Bundle
import androidx.room.Room
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.timeagent.core.RecurringPattern
import com.timeagent.data.*
import com.timeagent.fish.FishTimeTools
import com.timeagent.recurring.RecurringService
import com.timeagent.reminders.ReminderCoordinator
import com.timeagent.reminders.reminderAccessState
import com.timeagent.workout.data.WorkoutDatabase
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Uses in-memory business data and separately named fixture copies; never opens the user's main DB. */
class T0BaselineDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private fun evidence(name: String, value: JSONObject) {
        instrumentation.sendStatus(0, Bundle().apply {
            putString("t0_evidence", JSONObject().put("check", name).put("result", value).toString())
        })
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }

    @Test
    fun tenNativeTimeToolsReturnRealResultsWithoutRuntimeOrKey() = runBlocking {
        val db = TimeAgentDatabase.openInMemory(context)
        val clock = Clock.fixed(Instant.parse("2026-11-01T02:00:00Z"), ZoneId.of("Asia/Shanghai"))
        val repository = TimeRepository(db, clock)
        val results = JSONArray()
        try {
            repository.saveProject(ProjectEntity("t0-project", "T0_DEVICE_PROJECT"), null)
            val tools = FishTimeTools(repository)
            suspend fun call(name: String, args: JSONObject = JSONObject()): JSONObject {
                val result = tools.execute(name, args)
                results.put(JSONObject().put("tool", name).put("args", args).put("return", result))
                return result
            }
            val task = call("time_create_task", JSONObject()
                .put("title", "T0_DEVICE_TASK").put("projectId", "t0-project")).getString("id")
            val plan = call("time_create_plan", JSONObject().put("title", "T0_DEVICE_PLAN")
                .put("taskId", task).put("start", "2026-11-01T11:00:00+08:00")
                .put("end", "2026-11-01T11:30:00+08:00")).getString("id")
            assertEquals(task, repository.snapshot().plans.single { it.id == plan }.taskId)
            call("time_record", JSONObject().put("title", "T0_DEVICE_RECORD")
                .put("taskId", task).put("start", "2026-11-01T08:00:00+08:00")
                .put("end", "2026-11-01T08:30:00+08:00"))
            assertEquals(1, call("time_tasks").getJSONArray("tasks").length())
            assertEquals(1, call("time_projects").getJSONArray("projects").length())
            assertEquals(1, call("time_today").getJSONArray("actual").length())
            assertEquals(1, call("time_schedule", JSONObject().put("date", "2026-11-01"))
                .getJSONArray("plans").length())
            call("time_complete_task", JSONObject().put("taskId", task))
            assertEquals("COMPLETED", repository.snapshot().tasks.single().status)
            assertEquals(1, repository.snapshot().records.size)
            call("time_undo")
            assertEquals("TODO", repository.snapshot().tasks.single().status)
            assertTrue(call("time_quick_action", JSONObject().put("text", "\u67e5\u770b\u4efb\u52a1"))
                .has("needsConfirmation"))
            assertEquals(10, results.length())
            try {
                tools.execute("time_schedule", JSONObject().put("date", "invalid"))
                fail("Invalid date accepted")
            } catch (_: java.time.format.DateTimeParseException) {
                assertEquals(1, repository.snapshot().records.size)
            }
            evidence("ten-native-time-tools", JSONObject().put("calls", results)
                .put("invalidDateRejected", true).put("runtimeRpcCovered", false))
        } finally {
            db.close()
        }
    }

    @Test
    fun anonymizedExportsOpenOnDeviceWithoutTouchingUserDatabase() = runBlocking(Dispatchers.IO) {
        val mainName = "t0-v12-export-device.db"
        val workoutName = "t0-workout-export-device.db"
        for ((asset, name) in listOf("v12-sample.db" to mainName, "workouts-sample.db" to workoutName)) {
            context.deleteDatabase(name)
            context.getDatabasePath(name).apply {
                parentFile!!.mkdirs()
                instrumentation.context.assets.open("db/$asset").use { input ->
                    outputStream().use { input.copyTo(it) }
                }
            }
        }
        val main = Room.databaseBuilder(context, TimeAgentDatabase::class.java, mainName).addMigrations(com.timeagent.data.ScheduleMigration).build()
        val workouts = Room.databaseBuilder(context, WorkoutDatabase::class.java, workoutName).build()
        try {
            assertEquals(13, main.openHelper.readableDatabase.version)
            assertEquals(1, workouts.openHelper.readableDatabase.version)
            val counts = JSONObject()
            for ((table, expected) in listOf("tasks" to 30, "time_records" to 48, "schedule_blocks" to 13)) {
                main.openHelper.readableDatabase.query("SELECT count(*) FROM $table").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(expected, it.getInt(0))
                    counts.put(table, it.getInt(0))
                }
            }
            workouts.openHelper.readableDatabase.query("SELECT count(*) FROM workout_sessions").use {
                assertTrue(it.moveToFirst())
                assertEquals(3, it.getInt(0))
                counts.put("workout_sessions", it.getInt(0))
            }
            for (sql in listOf(main.openHelper.readableDatabase, workouts.openHelper.readableDatabase)) {
                sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                sql.query("PRAGMA integrity_check").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("ok", it.getString(0))
                }
            }
            evidence("anonymized-device-export-open", counts)
        } finally {
            main.close()
            workouts.close()
            context.deleteDatabase(mainName)
            context.deleteDatabase(workoutName)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 34)
    fun actualTimezoneBroadcastRestoresExistingAndRecurringReminders() = runBlocking {
        val originalZone = shell("getprop persist.sys.timezone").trim()
        val originalAutomatic = shell("settings get global auto_time_zone").trim()
        assertTrue(originalZone.matches(Regex("[A-Za-z_]+/[A-Za-z_]+")))
        assertTrue(originalAutomatic in setOf("0", "1"))
        val db = TimeAgentDatabase.openInMemory(context)
        val repository = TimeRepository(db, Clock.system(ZoneId.of(originalZone)))
        val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val coordinator = ReminderCoordinator(context, repository)
        val alarms = context.getSystemService(AlarmManager::class.java)
        // Removal history repeats the same tag; count only standalone pending-alarm tag lines.
        fun pendingCount(): Int = shell("dumpsys alarm").lineSequence()
            .count { it.trim() == "tag=*walarm*:com.timeagent.reminders.FIRE" }
        suspend fun awaitCount(expected: Int) {
            withTimeout(8_000) {
                while (pendingCount() != expected) delay(150)
            }
        }
        try {
            val tomorrow = LocalDate.now(ZoneId.of(originalZone)).plusDays(1)
            val at = tomorrow.atTime(10, 0).atZone(ZoneId.of(originalZone)).toInstant().toEpochMilli()
            repository.saveTask(TaskEntity("t0-plain-deadline", "T0_PLAIN_DEADLINE", dueAt = at))
            repository.savePlan(ScheduleBlockEntity("t0-plain-plan", "t0-plain-deadline", at, at + 1_800_000))
            assertTrue(repository.snapshot().reminders.isEmpty())
            repository.saveReminder(ReminderEntity("t0-point", "T0_POINT", at))
            for ((id, domain, minute) in listOf(
                Triple("t0-habit", "HABIT", 660), Triple("t0-deadline", "DEADLINE", 720),
            )) {
                val pattern = RecurringPattern(id, "DAILY", tomorrow.toString(), minuteOfDay = minute,
                    untilDate = tomorrow.toString(), leadMinutes = 10, zoneId = originalZone)
                RecurringService(repository).saveRule(
                    RecurringRuleEntity(id, "T0_$domain", Gson().toJson(pattern), domain = domain),
                    null, tomorrow, "t0-rule-$id",
                )
            }
            val expected = repository.snapshot().reminders.size
            assertEquals(3, expected)
            coordinator.start(observerScope)
            coordinator.refresh()
            awaitCount(expected)
            // Remove pending alarms first, so their later presence proves restoration.
            alarms.cancelAll()
            awaitCount(0)
            shell("settings put global auto_time_zone 0")
            val changedZone = if (originalZone == "Asia/Tokyo") "Asia/Shanghai" else "Asia/Tokyo"
            shell("cmd alarm set-timezone $changedZone")
            assertEquals(changedZone, shell("getprop persist.sys.timezone").trim())
            awaitCount(expected)
            val access = reminderAccessState(context)
            evidence("actual-timezone-restore", JSONObject()
                .put("event", "system timezone changed").put("before", expected)
                .put("afterCancellation", 0).put("afterTimezoneBroadcast", pendingCount())
                .put("manualPoint", 1).put("habit", 1).put("recurringDeadline", 1)
                .put("ordinaryPlanOrDueDateCreatedReminder", false)
                .put("notificationsAllowed", access.notificationsAllowed)
                .put("exactAlarmsAllowed", access.exactAlarmsAllowed)
                .put("bootCovered", false).put("workoutCovered", false))
        } finally {
            shell("cmd alarm set-timezone $originalZone")
            shell("settings put global auto_time_zone $originalAutomatic")
            observerScope.cancel()
            alarms.cancelAll()
            db.close()
        }
    }
}
