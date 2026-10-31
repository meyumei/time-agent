package com.timeagent.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.timeagent.core.workout.WorkoutMetric
import com.timeagent.core.workout.WorkoutRules
import com.timeagent.ledger.LedgerAccountType
import com.timeagent.ledger.LedgerDirection
import com.timeagent.ledger.LedgerRepository
import com.timeagent.recurring.RecurringService
import com.timeagent.recurring.RecurringValidation
import com.timeagent.workout.data.WorkoutDatabase
import com.timeagent.workout.data.WorkoutRepository
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class V12SampleDatabaseTest {
    @Test
    fun `generated v12 and workout samples open with current Room and preserve domain data`() =
        runBlocking(Dispatchers.IO) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val assets = File("src/androidTest/assets/db")
            for ((asset, name) in listOf(
                "v12-sample.db" to "time-agent.db",
                "workouts-sample.db" to "workouts.db",
            )) {
                assertTrue("Missing $asset", File(assets, asset).isFile)
                context.deleteDatabase(name)
                context.getDatabasePath(name).apply {
                    parentFile!!.mkdirs()
                    File(assets, asset).copyTo(this, overwrite = true)
                }
            }
            val db = TimeAgentDatabase.open(context)
            val workoutDb = WorkoutDatabase.open(context)
            val clock = Clock.fixed(Instant.parse("2026-10-31T04:00:00Z"), ZoneId.of("Asia/Shanghai"))
            try {
                assertEquals(12, db.openHelper.readableDatabase.version)
                assertEquals(1, workoutDb.openHelper.readableDatabase.version)
                db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use {
                    assertFalse(it.moveToFirst())
                }
                val repository = TimeRepository(db, clock)
                repository.initialize()
                val before = repository.snapshot()
                assertEquals(500, before.tasks.size)
                assertEquals(2000, db.dao().plans().size)
                assertTrue(before.plans.isNotEmpty())
                assertEquals(10000, before.records.size)
                assertTrue(before.recurringInstances.any { it.manualOverride })
                before.recurringRules.forEach { RecurringValidation.pattern(it) }
                before.recurringInstances.forEach { RecurringValidation.instance(it) }
                assertTrue(before.tasks.all { it.priority in 0..2 })
                val ledger = LedgerRepository(db, clock).snapshot()
                assertEquals(2000, ledger.transactions.size)
                assertTrue(ledger.accounts.all { it.type in LedgerAccountType.ALL })
                assertTrue(ledger.transactions.all { it.amountMinor > 0 && it.direction in LedgerDirection.ALL })
                val sessions = WorkoutRepository(workoutDb, repository, clock).observeSessions().first()
                assertEquals(200, sessions.size)
                sessions.forEach { WorkoutRules.validateSession(it) }
                assertEquals(WorkoutMetric.entries.toSet(), sessions.flatMap { it.movements }.map { it.exercise.metric }.toSet())
                val recordIds = before.records.map { it.id }.toSet()
                assertTrue(sessions.all { it.timelineRecordId in recordIds })
                val exceptions = before.recurringInstances.filter { it.manualOverride }
                RecurringService(repository).refresh()
                val after = repository.snapshot()
                assertEquals(before.tasks.size, after.tasks.size)
                assertEquals(before.records.size, after.records.size)
                assertEquals(exceptions, after.recurringInstances.filter { it.manualOverride })
            } finally {
                workoutDb.close()
                db.close()
                context.deleteDatabase("workouts.db")
                context.deleteDatabase("time-agent.db")
            }
        }
}
