package com.timeagent.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.*
import com.timeagent.workout.data.WorkoutDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScheduleBackupTest {
    @Test fun `v13 backup round trips standalone points metadata and command results`() = runBlocking(Dispatchers.IO) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mainName = "schedule-backup-main.db"
        val workoutName = "schedule-backup-workout.db"
        context.deleteDatabase(mainName)
        context.deleteDatabase(workoutName)
        val main = Room.databaseBuilder(context, TimeAgentDatabase::class.java, mainName).build()
        val workout = Room.databaseBuilder(context, WorkoutDatabase::class.java, workoutName).build()
        try {
            val repo = TimeRepository(main)
            repo.savePlan(ScheduleBlockEntity("point", null, 1000, 1000, title = "Point", kind = "POINT", notes = "Notes", source = "FISH", sourceRef = "message-1"))
            val plan = main.dao().plan("point")
            val command = CommandLogEntity("request-1", "CreatePlan", """{"status":"APPLIED"}""", 1000)
            main.dao().putCommandResult(command)
            val service = PersonalBackup(context, main, workout)
            val bytes = service.create()
            assertEquals(13, service.preview(bytes).mainVersion)
            repo.deletePlan("point")
            main.openHelper.writableDatabase.execSQL("DELETE FROM command_log")
            service.restore(bytes)
            assertEquals(plan, main.dao().plan("point"))
            assertEquals(command, main.dao().commandResult("request-1"))
            assertTrue(main.dao().tasks().isEmpty())
        } finally {
            workout.close()
            main.close()
            context.deleteDatabase(mainName)
            context.deleteDatabase(workoutName)
        }
    }
}
