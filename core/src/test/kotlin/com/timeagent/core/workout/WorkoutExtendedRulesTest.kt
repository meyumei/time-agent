package com.timeagent.core.workout

import org.junit.Assert.*
import org.junit.Test

class WorkoutExtendedRulesTest {
    private fun exercise(metric: WorkoutMetric) = ExerciseDefinition(metric.name, metric.name, listOf("全身"), metric = metric)
    private fun session(metric: WorkoutMetric, set: WorkoutSet, bodyWeightKg: Double? = null) = WorkoutSession(
        title = "训练", startedAt = 1000, bodyWeightKg = bodyWeightKg,
        movements = listOf(WorkoutMovement(exercise = exercise(metric), sets = listOf(set))),
    )
    private fun complete(session: WorkoutSession, now: Long = 2000): WorkoutSession =
        WorkoutRules.completeSet(session, session.movements.single().id, session.movements.single().sets.single().id, now)

    @Test fun `bodyweight added and assisted load use stable session body weight`() {
        val added = complete(session(WorkoutMetric.BODYWEIGHT_REPS, WorkoutSet(weightKg = 10.0, reps = 10), 70.0))
        val assisted = complete(session(WorkoutMetric.ASSISTED_REPS, WorkoutSet(weightKg = 20.0, reps = 8), 70.0))
        assertEquals(800.0, WorkoutRules.summary(added, 3000).volumeKg, 0.0)
        assertEquals(400.0, WorkoutRules.summary(assisted, 3000).volumeKg, 0.0)
        assertThrows(IllegalArgumentException::class.java) { complete(session(WorkoutMetric.BODYWEIGHT_REPS, WorkoutSet(weightKg = 0.0, reps = 10))) }
        assertThrows(IllegalArgumentException::class.java) { complete(session(WorkoutMetric.ASSISTED_REPS, WorkoutSet(weightKg = 80.0, reps = 10), 70.0)) }
    }

    @Test fun `separate side weights and one side repetition are preserved in capacity`() {
        val normal = session(WorkoutMetric.WEIGHT_REPS, WorkoutSet(weightKg = 20.0, leftWeightKg = 15.0, reps = 10))
        val unilateral = normal.copy(movements = normal.movements.map { it.copy(singleSide = true, oneSide = true) })
        assertEquals(700.0, WorkoutRules.summary(complete(unilateral), 3000).volumeKg, 0.0)
        val missingLeft = normal.copy(movements = normal.movements.map { it.copy(singleSide = true, sets = it.sets.map { set -> set.copy(leftWeightKg = null) }) })
        assertThrows(IllegalArgumentException::class.java) { complete(missingLeft) }
    }

    @Test fun `drop segments add to base set without duplicating group count`() {
        val workout = complete(session(WorkoutMetric.WEIGHT_REPS, WorkoutSet(
            weightKg = 40.0, reps = 10, kind = WorkoutSetKind.DROP,
            dropSegments = listOf(WorkoutDropSegment(30.0, 8), WorkoutDropSegment(20.0, 6)),
        )))
        val summary = WorkoutRules.summary(workout, 3000)
        assertEquals(760.0, summary.volumeKg, 0.0)
        assertEquals(1, summary.completedSets)
        assertEquals(1, summary.workingSets)
    }

    @Test fun `each drop segment rounds independently before capacity is summed`() {
        val workout = complete(session(WorkoutMetric.WEIGHT_REPS, WorkoutSet(
            weightKg = 1.04, reps = 1, kind = WorkoutSetKind.DROP,
            dropSegments = listOf(WorkoutDropSegment(1.04, 1), WorkoutDropSegment(1.04, 1)),
        )))
        assertEquals(3.0, WorkoutRules.summary(workout, 3000).volumeKg, 0.0)
    }

    @Test fun `bodyweight and assisted drops preserve source raw drop load semantics`() {
        val added = complete(session(WorkoutMetric.BODYWEIGHT_REPS, WorkoutSet(
            weightKg = 10.0, reps = 10, kind = WorkoutSetKind.DROP,
            dropSegments = listOf(WorkoutDropSegment(5.0, 8)),
        ), 70.0))
        val assistedBase = session(WorkoutMetric.ASSISTED_REPS, WorkoutSet(
            weightKg = 20.0, leftWeightKg = 10.0, reps = 8, kind = WorkoutSetKind.DROP,
            dropSegments = listOf(WorkoutDropSegment(15.0, 6, 5.0)),
        ), 70.0)
        val assisted = complete(assistedBase.copy(movements = assistedBase.movements.map { it.copy(singleSide = true, oneSide = true) }))
        assertEquals(840.0, WorkoutRules.summary(added, 3000).volumeKg, 0.0)
        assertEquals(1200.0, WorkoutRules.summary(assisted, 3000).volumeKg, 0.0)
    }

    @Test fun `cardio numeric formats match original field contracts`() {
        complete(session(WorkoutMetric.CARDIO, WorkoutSet(recordValues = mapOf("bpm" to "142.5"))))
        assertThrows(IllegalArgumentException::class.java) {
            complete(session(WorkoutMetric.CARDIO, WorkoutSet(recordValues = mapOf("elevationGain" to "1.5"))))
        }
    }

    @Test fun `cardio accepts any recorded field including zero and preserves pace strings`() {
        val distanceOnly = complete(session(WorkoutMetric.CARDIO, WorkoutSet(distanceMeters = 5000.0)))
        assertEquals(1, WorkoutRules.summary(distanceOnly, 3000).completedSets)
        val pace = complete(session(WorkoutMetric.CARDIO, WorkoutSet(recordValues = mapOf("pace" to "5:30", "distance" to "0"))))
        assertEquals("5:30", pace.movements.single().sets.single().recordValues["pace"])
        assertEquals(0.0, WorkoutRules.summary(pace, 3000).volumeKg, 0.0)
        assertThrows(IllegalArgumentException::class.java) { complete(session(WorkoutMetric.CARDIO, WorkoutSet())) }
        assertThrows(IllegalArgumentException::class.java) { complete(session(WorkoutMetric.CARDIO, WorkoutSet(recordValues = mapOf("pace" to "5:90")))) }
        assertThrows(IllegalArgumentException::class.java) { complete(session(WorkoutMetric.CARDIO, WorkoutSet(distanceMeters = Double.POSITIVE_INFINITY))) }
    }

    @Test fun `stretch may complete without numbers while record still requires duration`() {
        assertEquals(1, WorkoutRules.summary(complete(session(WorkoutMetric.STRETCH, WorkoutSet())), 3000).completedSets)
        assertThrows(IllegalArgumentException::class.java) { complete(session(WorkoutMetric.DURATION, WorkoutSet())) }
        assertEquals(1, WorkoutRules.summary(complete(session(WorkoutMetric.DURATION, WorkoutSet(durationSeconds = 45))), 3000).completedSets)
    }

    @Test fun `tabata moves from prepare through rounds with no trailing rest`() {
        val original = session(WorkoutMetric.TABATA, WorkoutSet(tabata = WorkoutTabata()))
        val timer = WorkoutRules.startInterval(original, original.movements.single().id, original.movements.single().sets.single().id, 1000)
        assertNotNull(timer.intervalTimer)
        assertEquals(WorkoutIntervalPhase.PREPARE, WorkoutRules.intervalState(timer, 1000)!!.phase)
        assertEquals(5000L, WorkoutRules.intervalState(timer, 1000)!!.remainingMillis)
        assertEquals(WorkoutIntervalPhase.WORK, WorkoutRules.intervalState(timer, 6000)!!.phase)
        assertEquals(20000L, WorkoutRules.intervalState(timer, 6000)!!.remainingMillis)
        assertEquals(WorkoutIntervalPhase.REST, WorkoutRules.intervalState(timer, 26000)!!.phase)
        assertEquals(10000L, WorkoutRules.intervalState(timer, 26000)!!.remainingMillis)
        assertEquals(2, WorkoutRules.intervalState(timer, 36000)!!.round)
        assertEquals(8, WorkoutRules.intervalState(timer, 216000)!!.round)
        assertEquals(WorkoutIntervalPhase.WORK, WorkoutRules.intervalState(timer, 216000)!!.phase)
        assertEquals(WorkoutIntervalPhase.FINISHED, WorkoutRules.intervalState(timer, 236000)!!.phase)
        assertEquals(0L, WorkoutRules.intervalState(timer, 236000)!!.remainingMillis)
    }

    @Test fun `tabata phase is derived from persisted timestamps and freezes during pause`() {
        val original = session(WorkoutMetric.TABATA, WorkoutSet(tabata = WorkoutTabata()))
        val started = WorkoutRules.startInterval(original, original.movements.single().id, original.movements.single().sets.single().id, 1000)
        assertNotNull(started.intervalTimer)
        val paused = WorkoutRules.pause(started, 11000)
        assertEquals(15000L, WorkoutRules.intervalState(paused, 311000)!!.remainingMillis)
        assertEquals(WorkoutIntervalPhase.WORK, WorkoutRules.intervalState(paused, 311000)!!.phase)
        val resumed = WorkoutRules.resume(paused, 311000)
        assertEquals(WorkoutIntervalPhase.REST, WorkoutRules.intervalState(resumed, 326000)!!.phase)
        assertEquals(10000L, WorkoutRules.intervalState(resumed, 326000)!!.remainingMillis)
        assertEquals(started, WorkoutRules.startInterval(started, started.movements.single().id, started.movements.single().sets.single().id, 2000))
    }

    @Test fun `tabata finish records actual configured seconds only after timer reaches end`() {
        val original = session(WorkoutMetric.TABATA, WorkoutSet(tabata = WorkoutTabata()))
        val started = WorkoutRules.startInterval(original, original.movements.single().id, original.movements.single().sets.single().id, 1000)
        assertThrows(IllegalStateException::class.java) { WorkoutRules.finishInterval(started, 2000) }
        val finished = WorkoutRules.finishInterval(started, 236000)
        assertNull(finished.intervalTimer)
        assertEquals(235, finished.movements.single().sets.single().durationSeconds)
        assertTrue(finished.movements.single().sets.single().isCompleted)
        assertEquals(finished, WorkoutRules.finishInterval(finished, 999000))
        assertEquals(0.0, WorkoutRules.summary(finished, 236000).volumeKg, 0.0)
    }

    @Test fun `superset child work counts only after entire round is completed`() {
        val a = WorkoutMovement(exercise = exercise(WorkoutMetric.WEIGHT_REPS), supersetId = "pair",
            sets = listOf(WorkoutSet(weightKg = 20.0, reps = 10)))
        val b = WorkoutMovement(exercise = exercise(WorkoutMetric.WEIGHT_REPS), supersetId = "pair",
            sets = listOf(WorkoutSet(weightKg = 15.0, reps = 10)))
        val initial = WorkoutSession(title = "超级组", startedAt = 1000, movements = listOf(a, b))
        val partial = WorkoutRules.completeSet(initial, a.id, a.sets.single().id, 2000)
        assertEquals(0, WorkoutRules.summary(partial, 3000).completedSets)
        assertEquals(0.0, WorkoutRules.summary(partial, 3000).volumeKg, 0.0)
        assertThrows(NoCompletedWorkoutSets::class.java) { WorkoutRules.finish(partial, 3000) }
        val complete = WorkoutRules.completeSet(partial, b.id, b.sets.single().id, 3000)
        val summary = WorkoutRules.summary(complete, 4000)
        assertEquals(2, summary.completedSets)
        assertEquals(1, summary.completedRounds)
        assertEquals(350.0, summary.volumeKg, 0.0)
    }

    @Test fun `history clone clears measured results and retains prescriptions`() {
        val metrics = listOf(WorkoutMetric.DURATION, WorkoutMetric.CARDIO, WorkoutMetric.STRETCH, WorkoutMetric.TABATA)
        val original = WorkoutSession(title = "综合训练", startedAt = 1000, movements = metrics.map { metric ->
            WorkoutMovement(exercise = exercise(metric), sets = listOf(WorkoutSet(
                weightKg = 10.0, reps = 8, durationSeconds = 235, distanceMeters = 1500.0, caloriesKcal = 80.0,
                recordValues = mapOf("pace" to "5:30"), tabata = if (metric == WorkoutMetric.TABATA) WorkoutTabata() else null,
                completedAt = 2000, rpe = 8.5, comment = "实测结果",
            )))
        })
        val template = WorkoutRules.templateFrom(original, "复训", 3000)
        val cloned = WorkoutRules.startFromTemplate(template, 4000)
        cloned.movements.forEach { movement ->
            val set = movement.sets.single()
            assertNull(set.durationSeconds)
            assertNull(set.distanceMeters)
            assertNull(set.caloriesKcal)
            assertTrue(set.recordValues.isEmpty())
            assertNull(set.rpe)
            assertEquals("", set.comment)
            assertEquals(10.0, set.weightKg!!, 0.0)
            assertEquals(8, set.reps)
        }
        val tabata = cloned.movements.last()
        assertEquals(WorkoutTabata(), tabata.sets.single().tabata)
        assertThrows(IllegalArgumentException::class.java) { WorkoutRules.completeSet(cloned, tabata.id, tabata.sets.single().id, 5000) }
    }

    @Test fun `explicit template duration remains a prescription when starting`() {
        val movement = WorkoutMovement(exercise = exercise(WorkoutMetric.DURATION), sets = listOf(WorkoutSet(durationSeconds = 45)))
        val started = WorkoutRules.startFromTemplate(WorkoutTemplate(title = "平板处方", movements = listOf(movement)), 1000)
        assertEquals(45, started.movements.single().sets.single().durationSeconds)
    }

    @Test fun `RPE and per set comment reject invalid values and preserve valid values`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.5, 10.5).forEach { rpe ->
            assertThrows(IllegalArgumentException::class.java) {
                WorkoutRules.validateSession(session(WorkoutMetric.WEIGHT_REPS, WorkoutSet(rpe = rpe)))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkoutRules.validateSession(session(WorkoutMetric.WEIGHT_REPS, WorkoutSet(comment = "x".repeat(20_001))))
        }
        val completed = complete(session(WorkoutMetric.WEIGHT_REPS, WorkoutSet(weightKg = 20.0, reps = 10, rpe = 8.5, comment = "保持肩胛稳定")))
        assertEquals(8.5, completed.movements.single().sets.single().rpe!!, 0.0)
        assertEquals("保持肩胛稳定", completed.movements.single().sets.single().comment)
    }

    @Test fun `warmup rest differs from working rest and all warmup superset waits for round`() {
        val warmup = session(WorkoutMetric.WEIGHT_REPS, WorkoutSet(kind = WorkoutSetKind.WARMUP, weightKg = 20.0, reps = 10))
        val configured = warmup.copy(movements = warmup.movements.map { it.copy(restSeconds = 90, warmupRestSeconds = 30) })
        assertEquals(32_000L, complete(configured).restEndsAt)
        val a = configured.movements.single().copy(supersetId = "warmup")
        val b = a.copy(id = "other", sets = listOf(a.sets.single().copy(id = "other-set")))
        val paired = configured.copy(movements = listOf(a, b))
        val partial = WorkoutRules.completeSet(paired, a.id, a.sets.single().id, 2000)
        assertNull(partial.restEndsAt)
        val done = WorkoutRules.completeSet(partial, b.id, b.sets.single().id, 3000)
        assertEquals(33_000L, done.restEndsAt)
        assertEquals(0.0, WorkoutRules.summary(done, 4000).volumeKg, 0.0)
        assertEquals(1, WorkoutRules.summary(done, 4000).completedRounds)
        assertEquals(2, WorkoutRules.summary(done, 4000).warmupSets)
        assertThrows(IllegalArgumentException::class.java) {
            WorkoutRules.validateSession(configured.copy(movements = listOf(a.copy(warmupRestSeconds = -1))))
        }
    }

    @Test fun `single set stopwatch persists timestamp and freezes across global pause`() {
        val original = session(WorkoutMetric.DURATION, WorkoutSet())
        val movement = original.movements.single()
        val started = WorkoutRules.startSetTimer(original, movement.id, movement.sets.single().id, 2000)
        assertEquals(5000L, WorkoutRules.sessionSetTimerElapsedMillis(started.copy(), 7000))
        val paused = WorkoutRules.pause(started, 7000)
        assertEquals(5000L, WorkoutRules.sessionSetTimerElapsedMillis(paused.copy(), 307000))
        val resumed = WorkoutRules.resume(paused, 307000)
        assertEquals(8000L, WorkoutRules.sessionSetTimerElapsedMillis(resumed.copy(), 310000))
        val stopped = WorkoutRules.stopSetTimer(resumed, 310000)
        assertNull(stopped.setTimer)
        assertEquals(8, stopped.movements.single().sets.single().durationSeconds)
        assertFalse(stopped.movements.single().sets.single().isCompleted)
        assertEquals(stopped, WorkoutRules.stopSetTimer(stopped, 320000))
    }

    @Test fun `stopwatch starts idempotently saves at least one second and can stop while paused`() {
        val original = session(WorkoutMetric.WEIGHT_REPS, WorkoutSet(weightKg = 20.0, reps = 10))
        val movement = original.movements.single()
        val started = WorkoutRules.startSetTimer(original, movement.id, movement.sets.single().id, 2000)
        assertEquals(started, WorkoutRules.startSetTimer(started, movement.id, movement.sets.single().id, 2500))
        val paused = WorkoutRules.pause(started, 2500)
        val stopped = WorkoutRules.stopSetTimer(paused, 999000)
        assertEquals(1, stopped.movements.single().sets.single().durationSeconds)
        assertFalse(stopped.movements.single().sets.single().isCompleted)
        assertNull(stopped.setTimer)
    }

    @Test fun `stopwatch conflicts with Tabata and clears when its set completes or workout ends`() {
        val original = session(WorkoutMetric.WEIGHT_REPS, WorkoutSet(weightKg = 20.0, reps = 10))
        val tabata = WorkoutMovement(exercise = exercise(WorkoutMetric.TABATA))
        val workout = original.copy(movements = original.movements + tabata)
        val movement = original.movements.single()
        val started = WorkoutRules.startSetTimer(workout, movement.id, movement.sets.single().id, 2000)
        assertThrows(IllegalArgumentException::class.java) { WorkoutRules.startInterval(started, tabata.id, tabata.sets.single().id, 3000) }
        assertThrows(IllegalArgumentException::class.java) { WorkoutRules.startSetTimer(started, tabata.id, tabata.sets.single().id, 3000) }
        val interval = WorkoutRules.startInterval(workout, tabata.id, tabata.sets.single().id, 2000)
        assertThrows(IllegalArgumentException::class.java) { WorkoutRules.startSetTimer(interval, movement.id, movement.sets.single().id, 3000) }
        val completed = WorkoutRules.completeSet(started, movement.id, movement.sets.single().id, 5000)
        assertNull(completed.setTimer)
        assertEquals(3, completed.movements.first().sets.single().durationSeconds)
        val restarted = WorkoutRules.startSetTimer(completed, tabata.id, tabata.sets.single().id, 6000)
        assertNull(WorkoutRules.finish(restarted, 7000).setTimer)
        assertThrows(IllegalArgumentException::class.java) { WorkoutRules.validateSession(started.copy(movements = listOf(tabata))) }
    }
}
