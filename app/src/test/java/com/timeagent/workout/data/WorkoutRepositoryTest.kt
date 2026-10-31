package com.timeagent.workout.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.timeagent.core.workout.*
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.data.TimeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkoutRepositoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var mainDb: TimeAgentDatabase
    private lateinit var workoutDb: WorkoutDatabase
    private lateinit var time: TimeRepository
    private lateinit var repository: WorkoutRepository
    private lateinit var clock: MutableClock
    private val press = ExerciseDefinition("press", "卧推", listOf("胸"), listOf("三头肌"), "杠铃")

    private class MutableClock(var value: Long) : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(value)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    @Before fun setup() = runBlocking {
        context.deleteDatabase("workouts.db")
        context.deleteDatabase("time-agent.db")
        clock = MutableClock(Instant.parse("2026-10-25T08:00:00Z").toEpochMilli())
        mainDb = TimeAgentDatabase.open(context)
        workoutDb = WorkoutDatabase.open(context)
        time = TimeRepository(mainDb, clock)
        time.initialize()
        repository = WorkoutRepository(workoutDb, time, clock)
    }

    @After fun cleanup() {
        workoutDb.close()
        mainDb.close()
        context.deleteDatabase("workouts.db")
        context.deleteDatabase("time-agent.db")
    }

    private suspend inline fun <reified T : Throwable> failure(noinline action: suspend () -> Unit): T {
        try { action() } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    private suspend fun completedWorkout(): WorkoutSession {
        var session = repository.start("胸背训练", listOf(press))
        session = repository.mutate(session.id) { current -> current.copy(movements = current.movements.map { movement ->
            movement.copy(warmupRestSeconds = 90, sets = listOf(
                movement.sets.single().copy(weightKg = 40.0, reps = 10),
                WorkoutSet(weightKg = 20.0, reps = 10, kind = WorkoutSetKind.WARMUP),
            ))
        }) }
        clock.value += 10_000
        val movement = session.movements.single()
        repository.completeSet(session.id, movement.id, movement.sets.first().id)
        clock.value += 10_000
        return repository.completeSet(session.id, movement.id, movement.sets.last().id)
    }

    @Test fun `database is shared by receiver and activity and can reopen after close`() {
        assertSame(workoutDb, WorkoutDatabase.open(context))
        val closed = workoutDb
        workoutDb.close()
        workoutDb = WorkoutDatabase.open(context)
        assertNotSame(closed, workoutDb)
    }

    @Test fun `session progress and paused rest survive real database close and reopen`() = runBlocking {
        val session = completedWorkout()
        clock.value += 30_000
        val paused = repository.pause(session.id)
        workoutDb.close()
        clock.value += 300_000
        workoutDb = WorkoutDatabase.open(context)
        repository = WorkoutRepository(workoutDb, time, clock)
        val restored = repository.activeSession()!!
        assertEquals(paused, restored)
        assertEquals(paused, repository.observeSessions().first().single())
        assertEquals(50_000L, WorkoutRules.elapsedMillis(restored, clock.millis()))
        assertEquals(60_000L, WorkoutRules.restRemainingMillis(restored, clock.millis()))
        val resumed = repository.resume(restored.id)
        assertEquals(clock.millis() + 60_000L, resumed.restEndsAt)
        assertEquals(50_000L, WorkoutRules.elapsedMillis(resumed, clock.millis()))
    }

    @Test fun `concurrent repositories admit exactly one active training`() = runBlocking {
        val anotherDb = Room.databaseBuilder(context, WorkoutDatabase::class.java, "workouts.db").build()
        try {
            val another = WorkoutRepository(anotherDb, time, clock)
            val outcomes = coroutineScope {
                listOf(repository, another).map { repo -> async(Dispatchers.Default) {
                    runCatching { repo.start("自由训练", listOf(press, press)) }
                } }.awaitAll()
            }
            assertEquals(1, outcomes.count { it.isSuccess })
            assertTrue(outcomes.single { it.isFailure }.exceptionOrNull() is WorkoutAlreadyActive)
            val saved = repository.observeSessions().first().single()
            assertEquals(2, saved.movements.map { it.id }.toSet().size)
            assertEquals(2, saved.movements.flatMap { it.sets }.map { it.id }.toSet().size)
        } finally { anotherDb.close() }
    }

    @Test fun `invalid set edits fail atomically and preserve last saved values`() = runBlocking {
        val original = repository.start(exercises = listOf(press))
        failure<IllegalArgumentException> {
            repository.mutate(original.id) { it.copy(movements = it.movements.map { movement ->
                movement.copy(sets = movement.sets.map { set -> set.copy(weightKg = Double.NaN) })
            }) }
        }
        assertEquals(original, repository.getSession(original.id))
        failure<IllegalArgumentException> { repository.mutate(original.id) { it.copy(status = WorkoutSessionStatus.COMPLETED) } }
        assertEquals(original, repository.activeSession())
    }

    @Test fun `finish writes actual timeline once with title summary notes and fitness category`() = runBlocking {
        val session = completedWorkout()
        clock.value += 40_000
        val done = repository.finish(session.id, "今天状态良好，保持肩胛稳定")
        val record = time.snapshot().records.single()
        assertEquals("workout:${session.id}", record.id)
        assertEquals(record.id, done.timelineRecordId)
        assertEquals("WORKOUT", record.source)
        assertEquals("胸背训练", record.title)
        assertEquals(session.startedAt, record.startAt)
        assertEquals(clock.millis(), record.endAt)
        assertEquals(time.snapshot().activities.single { it.id == "fitness" }.categoryId, record.categoryId)
        assertTrue(record.notes.contains("今天状态良好"))
        assertTrue(record.notes.contains("卧推"))
        assertTrue(record.notes.contains("正式组 1"))
        assertTrue(record.notes.contains("热身组 1"))
        assertTrue(record.notes.contains("400"))
        assertFalse(record.isManuallyEdited)
        assertNull(repository.activeSession())
        clock.value += 60_000
        assertEquals(done, repository.finish(session.id, "重复点击不能更改心得"))
        assertEquals(1, time.snapshot().records.size)
        assertEquals(record, time.snapshot().records.single())
        assertEquals(1, time.snapshot().history.count { it.entityId == "workout-timeline:${session.id}" })
    }

    @Test fun `no completed sets finish returns actionable error and keeps session active`() = runBlocking {
        val session = repository.start(exercises = listOf(press))
        clock.value += 10_000
        val error = failure<NoCompletedWorkoutSets> { repository.finish(session.id) }
        assertTrue(error.message!!.contains("至少一组"))
        assertEquals(session, repository.activeSession())
        assertTrue(time.snapshot().records.isEmpty())
    }

    @Test fun `timeline failure preserves completed workout and allows retry after reopen`() = runBlocking {
        val session = completedWorkout()
        clock.value += 10_000
        mainDb.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_workout BEFORE INSERT ON time_records WHEN NEW.source='WORKOUT' BEGIN SELECT RAISE(ABORT, 'write unavailable'); END")
        failure<android.database.sqlite.SQLiteException> { repository.finish(session.id, "等待回记") }
        assertTrue(time.snapshot().records.isEmpty())
        mainDb.close()
        val waiting = repository.getSession(session.id)!!
        assertEquals(WorkoutSessionStatus.COMPLETED, waiting.status)
        assertEquals("等待回记", waiting.notes)
        assertNull(waiting.timelineRecordId)
        val endedAt = waiting.endedAt
        mainDb = TimeAgentDatabase.open(context)
        mainDb.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_workout")
        time = TimeRepository(mainDb, clock)
        repository = WorkoutRepository(workoutDb, time, clock)
        clock.value += 30_000
        val retried = repository.finish(session.id)
        assertEquals(endedAt, retried.endedAt)
        assertEquals("workout:${session.id}", retried.timelineRecordId)
        assertEquals(1, time.snapshot().records.size)
    }

    @Test fun `crash after timeline write before link commit retries without overwriting manual edit`() = runBlocking {
        val done = repository.finish(completedWorkout().id)
        val record = time.snapshot().records.single()
        time.saveRecord(record.copy(title = "手工修改标题", notes = "手工内容"), expectedVersion = record.version)
        // Simulate the durable session state just before the cross-database link commit.
        val unlinked = done.copy(timelineRecordId = null)
        workoutDb.dao().putSession(WorkoutSessionRow.from(unlinked, Gson()))
        val retried = repository.finish(done.id)
        assertEquals(record.id, retried.timelineRecordId)
        assertEquals("手工修改标题", time.snapshot().records.single().title)
        assertEquals("手工内容", time.snapshot().records.single().notes)
        assertEquals(1, time.snapshot().records.size)
    }

    @Test fun `crash retry never resurrects a manually deleted timeline record`() = runBlocking {
        val done = repository.finish(completedWorkout().id)
        time.deleteRecord(done.timelineRecordId!!)
        workoutDb.dao().putSession(WorkoutSessionRow.from(done.copy(timelineRecordId = null), Gson()))
        repository.finish(done.id)
        assertTrue(time.snapshot().records.isEmpty())
        assertEquals(done.timelineRecordId, repository.getSession(done.id)!!.timelineRecordId)
    }

    @Test fun `parallel duplicate finish produces one record and one history action`() = runBlocking {
        val session = completedWorkout()
        val results = coroutineScope { (1..8).map { async(Dispatchers.Default) { repository.finish(session.id) } }.awaitAll() }
        assertEquals(1, results.toSet().size)
        assertEquals(1, time.snapshot().records.size)
        assertEquals(1, time.snapshot().history.count { it.entityId == "workout-timeline:${session.id}" })
    }

    @Test fun `saved template survives reopen and starts with fresh incomplete sets`() = runBlocking {
        val original = completedWorkout()
        val template = repository.saveTemplate(original.id, "胸日模板")
        repository.discard(original.id)
        workoutDb.close()
        workoutDb = WorkoutDatabase.open(context)
        repository = WorkoutRepository(workoutDb, time, clock)
        assertEquals(template, repository.observeTemplates().first().single())
        val started = repository.startFromTemplate(template.id)
        assertEquals(template.id, started.templateId)
        assertEquals("胸日模板", started.title)
        assertEquals(40.0, started.movements.single().sets.first().weightKg!!, 0.0)
        assertTrue(started.movements.flatMap { it.sets }.none { it.isCompleted })
        assertNotEquals(original.movements.single().id, started.movements.single().id)
        assertNotEquals(original.movements.single().sets.first().id, started.movements.single().sets.first().id)
        assertTrue(time.snapshot().records.isEmpty())
    }

    @Test fun `template edits and delete do not affect existing sessions`() = runBlocking<Unit> {
        val first = repository.start(exercises = listOf(press))
        val template = repository.saveTemplate(first.id, "原模板")
        repository.discard(first.id)
        clock.value += 1000
        val edited = repository.saveTemplateDefinition(template.copy(title = "修改模板", notes = "动作处方"))
        assertEquals(template.createdAt, edited.createdAt)
        assertEquals(clock.millis(), edited.updatedAt)
        val started = repository.startFromTemplate(edited.id)
        repository.deleteTemplate(edited.id)
        assertTrue(repository.observeTemplates().first().isEmpty())
        assertEquals("修改模板", repository.getSession(started.id)!!.title)
        failure<IllegalArgumentException> { repository.startFromTemplate(edited.id) }
    }

    @Test fun `last data comes from completed sessions and repeat keeps prescription only`() = runBlocking {
        val finished = repository.finish(completedWorkout().id, "上次的训练心得")
        clock.value += 10_000
        val repeated = repository.repeatSession(finished.id)
        assertEquals("", repeated.notes)
        assertEquals(finished.title, repeated.title)
        assertTrue(repeated.movements.flatMap { it.sets }.none { it.isCompleted })
        assertEquals(finished.movements.single(), repository.lastMovement(press.id, repeated.id))
        assertEquals(40.0, repeated.movements.single().sets.first().weightKg!!, 0.0)
        assertNull(repeated.timelineRecordId)
    }

    @Test fun `discarded workout frees active slot and cannot write actual timeline`() = runBlocking {
        val session = completedWorkout()
        clock.value += 1000
        val discarded = repository.discard(session.id)
        assertEquals(WorkoutSessionStatus.DISCARDED, discarded.status)
        assertNull(repository.activeSession())
        failure<IllegalArgumentException> { repository.finish(discarded.id) }
        assertEquals(discarded, repository.discard(discarded.id))
        assertTrue(time.snapshot().records.isEmpty())
        assertNotEquals(session.id, repository.start().id)
    }

    @Test fun `Tabata timer persists through database restart pause finish and discard`() = runBlocking {
        val tabata = press.copy(id = "tabata", name = "间歇训练", metric = WorkoutMetric.TABATA)
        val session = repository.start(exercises = listOf(tabata))
        val movement = session.movements.single()
        repository.startInterval(session.id, movement.id, movement.sets.single().id)
        clock.value += 11_000
        val paused = repository.pause(session.id)
        workoutDb.close()
        clock.value += 300_000
        workoutDb = WorkoutDatabase.open(context)
        repository = WorkoutRepository(workoutDb, time, clock)
        assertEquals(paused, repository.activeSession())
        assertEquals(14_000L, WorkoutRules.intervalState(repository.activeSession()!!, clock.millis())!!.remainingMillis)
        repository.resume(session.id)
        clock.value += 224_000
        val completed = repository.finishInterval(session.id)
        assertNull(completed.intervalTimer)
        assertEquals(235, completed.movements.single().sets.single().durationSeconds)
        assertTrue(completed.movements.single().sets.single().isCompleted)
        repository.discard(session.id)
        val second = repository.start(exercises = listOf(tabata))
        repository.startInterval(second.id, second.movements.single().id, second.movements.single().sets.single().id)
        val discarded = repository.discard(second.id)
        assertNull(discarded.intervalTimer)
        assertTrue(time.snapshot().records.isEmpty())
    }

    @Test fun `timeline preserves all eight metric records sides drops and cardio units`() = runBlocking {
        val session = repository.start("综合训练")
        val entries = listOf(
            WorkoutMetric.WEIGHT_REPS to WorkoutSet(kind = WorkoutSetKind.DROP, weightKg = 20.0, leftWeightKg = 15.0, reps = 10,
                dropSegments = listOf(WorkoutDropSegment(10.0, 8, 5.0))),
            WorkoutMetric.BODYWEIGHT_REPS to WorkoutSet(weightKg = 5.0, reps = 8),
            WorkoutMetric.ASSISTED_REPS to WorkoutSet(weightKg = 20.0, reps = 6),
            WorkoutMetric.REPS to WorkoutSet(reps = 12),
            WorkoutMetric.DURATION to WorkoutSet(durationSeconds = 45),
            WorkoutMetric.CARDIO to WorkoutSet(distanceMeters = 1500.0, caloriesKcal = 80.0, recordValues = mapOf("pace" to "5:30", "bpm" to "142.5")),
            WorkoutMetric.STRETCH to WorkoutSet(),
            WorkoutMetric.TABATA to WorkoutSet(durationSeconds = 235, tabata = WorkoutTabata()),
        )
        val prepared = repository.mutate(session.id) { it.copy(bodyWeightKg = 70.0,
            movements = entries.map { (metric, set) -> WorkoutMovement(
                exercise = press.copy(id = metric.name, name = metric.name, metric = metric), sets = listOf(set),
                singleSide = metric == WorkoutMetric.WEIGHT_REPS, oneSide = metric == WorkoutMetric.WEIGHT_REPS,
            ) }) }
        clock.value += 1_000
        prepared.movements.forEach { repository.completeSet(session.id, it.id, it.sets.single().id) }
        clock.value += 1_000
        repository.finish(session.id)
        val notes = time.snapshot().records.single().notes
        entries.forEach { assertTrue(notes.contains(it.first.name)) }
        listOf("左", "15", "10", "8", "1500", "80", "5:30", "142.5", "235", "附加", "辅助", "拉伸", "8轮").forEach {
            assertTrue("Missing $it in $notes", notes.contains(it))
        }
    }

    @Test fun `RPE and set comment survive reopen and are included in timeline`() = runBlocking {
        val started = repository.start(exercises = listOf(press))
        val session = repository.mutate(started.id) { it.copy(movements = it.movements.map { movement ->
            movement.copy(warmupRestSeconds = 35, sets = movement.sets.map { set ->
                set.copy(weightKg = 30.0, reps = 8, durationSeconds = 12, rpe = 8.5, comment = "末次略慢，保持肩胛稳定")
            })
        }) }
        workoutDb.close()
        workoutDb = WorkoutDatabase.open(context)
        repository = WorkoutRepository(workoutDb, time, clock)
        assertEquals(session, repository.activeSession())
        val movement = session.movements.single()
        clock.value += 1000
        repository.completeSet(session.id, movement.id, movement.sets.single().id)
        clock.value += 1000
        repository.finish(session.id)
        val notes = time.snapshot().records.single().notes
        assertTrue(notes.contains("RPE 8.5"))
        assertTrue(notes.contains("计时 12 秒"))
        assertTrue(notes.contains("末次略慢，保持肩胛稳定"))
    }

    @Test fun `partial superset cannot finish and is excluded from completed timeline detail`() = runBlocking {
        val started = repository.start()
        val session = repository.mutate(started.id) { it.copy(movements = listOf(
            WorkoutMovement(exercise = press.copy(id = "partial-a", name = "未完成超级组甲"), supersetId = "pair", sets = listOf(WorkoutSet(weightKg = 30.0, reps = 10))),
            WorkoutMovement(exercise = press.copy(id = "partial-b", name = "未完成超级组乙"), supersetId = "pair", sets = listOf(WorkoutSet(weightKg = 20.0, reps = 10))),
            WorkoutMovement(exercise = press.copy(id = "solo", name = "单独完成动作"), sets = listOf(WorkoutSet(weightKg = 10.0, reps = 10))),
        )) }
        clock.value += 1000
        val partial = session.movements.first()
        repository.completeSet(session.id, partial.id, partial.sets.single().id)
        failure<NoCompletedWorkoutSets> { repository.finish(session.id) }
        assertTrue(time.snapshot().records.isEmpty())
        val solo = session.movements.last()
        repository.completeSet(session.id, solo.id, solo.sets.single().id)
        clock.value += 1000
        repository.finish(session.id)
        val notes = time.snapshot().records.single().notes
        assertTrue(notes.contains("单独完成动作"))
        assertFalse(notes.contains("未完成超级组"))
        assertNull(repository.lastMovement("partial-a"))
        assertEquals(solo.id, repository.lastMovement("solo")?.id)
        assertTrue(repository.lastMovement("solo")!!.sets.single().isCompleted)
    }

    @Test fun `single set stopwatch survives repository reopen pause and measured duration completion`() = runBlocking {
        val timed = press.copy(id = "timed", name = "平板支撑", metric = WorkoutMetric.DURATION)
        val session = repository.start(exercises = listOf(timed))
        val movement = session.movements.single()
        repository.startSetTimer(session.id, movement.id, movement.sets.single().id)
        clock.value += 5000
        repository.pause(session.id)
        workoutDb.close()
        clock.value += 300000
        workoutDb = WorkoutDatabase.open(context)
        repository = WorkoutRepository(workoutDb, time, clock)
        assertEquals(5000L, WorkoutRules.sessionSetTimerElapsedMillis(repository.activeSession()!!, clock.millis()))
        repository.resume(session.id)
        clock.value += 3000
        val stopped = repository.stopSetTimer(session.id)
        assertEquals(8, stopped.movements.single().sets.single().durationSeconds)
        assertNull(stopped.setTimer)
        assertFalse(stopped.movements.single().sets.single().isCompleted)
        repository.startSetTimer(session.id, movement.id, movement.sets.single().id)
        clock.value += 2000
        val completed = repository.completeSet(session.id, movement.id, movement.sets.single().id)
        assertNull(completed.setTimer)
        assertEquals(2, completed.movements.single().sets.single().durationSeconds)
        val other = repository.mutate(session.id) { it.copy(movements = it.movements + WorkoutMovement(exercise = timed)) }
        repository.startSetTimer(session.id, other.movements.last().id, other.movements.last().sets.single().id)
        assertNull(repository.discard(session.id).setTimer)
    }

    @Test fun `removing or replacing stopwatch target clears persisted timer`() = runBlocking {
        val session = repository.start(exercises = listOf(press))
        val movement = session.movements.single()
        repository.startSetTimer(session.id, movement.id, movement.sets.single().id)
        val replaced = repository.mutate(session.id) { it.copy(movements = it.movements.map { item ->
            item.copy(exercise = item.exercise.copy(id = "replacement", name = "替换动作"))
        }) }
        assertNull(replaced.setTimer)
        repository.startSetTimer(session.id, movement.id, movement.sets.single().id)
        assertNull(repository.mutate(session.id) { it.copy(movements = emptyList()) }.setTimer)
    }

    @Test fun `last movement includes only complete superset rounds from finished session`() = runBlocking {
        val started = repository.start()
        val prepared = repository.mutate(started.id) { it.copy(movements = listOf(
            WorkoutMovement(exercise = press.copy(id = "a"), supersetId = "pair", sets = List(2) { WorkoutSet(weightKg = 20.0, reps = 10) }),
            WorkoutMovement(exercise = press.copy(id = "b"), supersetId = "pair", sets = List(2) { WorkoutSet(weightKg = 15.0, reps = 10) }),
        )) }
        clock.value += 1000
        prepared.movements.forEach { repository.completeSet(prepared.id, it.id, it.sets.first().id) }
        val first = prepared.movements.first()
        repository.completeSet(prepared.id, first.id, first.sets.last().id)
        clock.value += 1000
        repository.finish(prepared.id)
        assertEquals(listOf(first.sets.first().id), repository.lastMovement("a")!!.sets.map { it.id })
    }
}
