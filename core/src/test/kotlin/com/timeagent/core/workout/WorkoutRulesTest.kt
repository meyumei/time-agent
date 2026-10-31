package com.timeagent.core.workout

import org.junit.Assert.*
import org.junit.Test

class WorkoutRulesTest {
    private val press = ExerciseDefinition("press", "卧推", listOf("胸"))
    private fun session(vararg sets: WorkoutSet) = WorkoutSession(
        title = "练胸", startedAt = 1_000,
        movements = listOf(WorkoutMovement(exercise = press, sets = sets.toList())),
    )

    @Test fun `summary counts only completed sets and weight reps volume`() {
        val actual = session(
            WorkoutSet(weightKg = 40.0, reps = 10, completedAt = 2_000),
            WorkoutSet(weightKg = 50.0, reps = 8, kind = WorkoutSetKind.DROP, completedAt = 3_000),
            WorkoutSet(weightKg = 100.0, reps = 20),
        ).let { it.copy(movements = it.movements + WorkoutMovement(
            exercise = press.copy(id = "plank", name = "平板支撑", metric = WorkoutMetric.DURATION),
            sets = listOf(WorkoutSet(durationSeconds = 45, completedAt = 4_000)),
        )) }
        val summary = WorkoutRules.summary(actual, 61_000)
        assertEquals(800.0, summary.volumeKg, 0.0)
        assertEquals(3, summary.completedSets)
        assertEquals(2, summary.completedMovements)
        assertEquals(60_000L, summary.elapsedMillis)
    }

    @Test fun `pause freezes training and rest and resume reconstructs deadline`() {
        val workout = session(WorkoutSet(weightKg = 0.0, reps = 10)).copy(restEndsAt = 91_000)
        val paused = WorkoutRules.pause(workout, 31_000)
        assertEquals(30_000L, WorkoutRules.elapsedMillis(paused, 331_000))
        assertEquals(60_000L, WorkoutRules.restRemainingMillis(paused, 331_000))
        assertNull(paused.restEndsAt)
        val resumed = WorkoutRules.resume(paused, 331_000)
        assertEquals(391_000L, resumed.restEndsAt)
        assertEquals(40_000L, WorkoutRules.elapsedMillis(resumed, 341_000))
        assertEquals(50_000L, WorkoutRules.restRemainingMillis(resumed, 341_000))
        assertEquals(resumed, WorkoutRules.resume(resumed, 350_000))
    }

    @Test fun `rest expires from persisted deadline without timer callbacks`() {
        val workout = session().copy(restEndsAt = 10_000)
        assertEquals(3_000L, WorkoutRules.restRemainingMillis(workout, 7_000))
        assertEquals(0L, WorkoutRules.restRemainingMillis(workout, 50_000))
    }

    @Test fun `complete rejects missing metric fields and accepts zero kg bodyweight`() {
        val invalid = session(WorkoutSet(weightKg = 20.0))
        assertThrows(IllegalArgumentException::class.java) {
            WorkoutRules.completeSet(invalid, invalid.movements.single().id, invalid.movements.single().sets.single().id, 2_000)
        }
        val valid = session(WorkoutSet(weightKg = 0.0, reps = 8))
        val saved = WorkoutRules.completeSet(valid, valid.movements.single().id, valid.movements.single().sets.single().id, 2_000)
        assertTrue(saved.movements.single().sets.single().isCompleted)
        assertEquals(92_000L, saved.restEndsAt)
        assertEquals(saved, WorkoutRules.completeSet(saved, saved.movements.single().id, saved.movements.single().sets.single().id, 8_000))
    }

    @Test fun `invalid numeric input is rejected even on incomplete sets`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0).forEach { weight ->
            assertThrows(IllegalArgumentException::class.java) { WorkoutRules.validateSession(session(WorkoutSet(weightKg = weight))) }
        }
        assertThrows(IllegalArgumentException::class.java) { WorkoutRules.validateSession(session(WorkoutSet(reps = 0))) }
        assertThrows(IllegalArgumentException::class.java) { WorkoutRules.validateSession(session(WorkoutSet(durationSeconds = -1))) }
    }

    @Test fun `reps and duration require their own positive input`() {
        fun complete(metric: WorkoutMetric, set: WorkoutSet): WorkoutSession {
            val movement = WorkoutMovement(exercise = press.copy(metric = metric), sets = listOf(set))
            val workout = session().copy(movements = listOf(movement))
            return WorkoutRules.completeSet(workout, movement.id, set.id, 3_000)
        }
        assertEquals(1, WorkoutRules.summary(complete(WorkoutMetric.REPS, WorkoutSet(reps = 10)), 4_000).completedSets)
        assertEquals(1, WorkoutRules.summary(complete(WorkoutMetric.DURATION, WorkoutSet(durationSeconds = 30)), 4_000).completedSets)
        assertThrows(IllegalArgumentException::class.java) { complete(WorkoutMetric.DURATION, WorkoutSet(reps = 10)) }
        assertThrows(IllegalArgumentException::class.java) { complete(WorkoutMetric.WEIGHT_REPS, WorkoutSet(reps = 10)) }
    }

    @Test fun `superset waits for paired round before resting`() {
        val a = WorkoutMovement(exercise = press, supersetId = "superset", sets = listOf(WorkoutSet(weightKg = 30.0, reps = 10)))
        val b = WorkoutMovement(exercise = press.copy(id = "row", name = "划船"), supersetId = "superset", restSeconds = 60, sets = listOf(WorkoutSet(weightKg = 25.0, reps = 12)))
        val initial = session().copy(movements = listOf(a, b))
        val first = WorkoutRules.completeSet(initial, a.id, a.sets.single().id, 2_000)
        assertEquals(0L, WorkoutRules.restRemainingMillis(first, 2_000))
        val second = WorkoutRules.completeSet(first, b.id, b.sets.single().id, 3_000)
        assertEquals(63_000L, second.restEndsAt)
    }

    @Test fun `finish needs completed work and seals paused duration idempotently`() {
        assertThrows(NoCompletedWorkoutSets::class.java) { WorkoutRules.finish(session(WorkoutSet()), 5_000) }
        val started = session(WorkoutSet(weightKg = 10.0, reps = 10, completedAt = 2_000))
        val paused = WorkoutRules.pause(started, 11_000)
        val finished = WorkoutRules.finish(paused, 101_000)
        assertEquals(WorkoutSessionStatus.COMPLETED, finished.status)
        assertEquals(10_000L, WorkoutRules.elapsedMillis(finished, 201_000))
        assertNull(finished.restEndsAt)
        assertNull(finished.pausedAt)
        assertEquals(finished, WorkoutRules.finish(finished, 301_000))
    }

    @Test fun `template restart preserves prescription and superset but renews identities`() {
        val group = "pair"
        val original = session(WorkoutSet(weightKg = 42.5, reps = 8, kind = WorkoutSetKind.WARMUP, completedAt = 2_000))
            .let { it.copy(movements = it.movements + it.movements.single().copy(id = "second", supersetId = group), notes = "训练心得") }
        val template = WorkoutRules.templateFrom(original, "胸日", 5_000)
        val restarted = WorkoutRules.startFromTemplate(template, 8_000)
        assertEquals("胸日", restarted.title)
        assertEquals(template.id, restarted.templateId)
        assertEquals(WorkoutSessionStatus.ACTIVE, restarted.status)
        assertEquals("", restarted.notes)
        assertNotEquals(original.id, restarted.id)
        assertNotEquals(original.movements.first().id, restarted.movements.first().id)
        assertNotEquals(original.movements.first().sets.first().id, restarted.movements.first().sets.first().id)
        assertEquals(42.5, restarted.movements.first().sets.first().weightKg!!, 0.0)
        assertEquals(WorkoutSetKind.WARMUP, restarted.movements.first().sets.first().kind)
        assertFalse(restarted.movements.first().sets.first().isCompleted)
        assertEquals(2, restarted.movements.map { it.id }.toSet().size)
    }

    @Test fun `repeated exercises are separate movement and set identities`() {
        val first = WorkoutMovement(exercise = press)
        val second = WorkoutMovement(exercise = press)
        assertNotEquals(first.id, second.id)
        assertNotEquals(first.sets.single().id, second.sets.single().id)
        WorkoutRules.validateSession(session().copy(movements = listOf(first, second)))
        assertThrows(IllegalArgumentException::class.java) { WorkoutRules.validateSession(session().copy(movements = listOf(first, first))) }
    }

    @Test fun `warmup is recorded separately and excluded from working volume`() {
        val workout = session(
            WorkoutSet(kind = WorkoutSetKind.WARMUP, weightKg = 20.0, reps = 10, completedAt = 2_000),
            WorkoutSet(weightKg = 40.0, reps = 10, completedAt = 3_000),
            WorkoutSet(kind = WorkoutSetKind.DROP, weightKg = 30.0, reps = 10, completedAt = 4_000),
            WorkoutSet(kind = WorkoutSetKind.FAILURE, weightKg = 0.0, reps = 5, completedAt = 5_000),
        )
        val summary = WorkoutRules.summary(workout, 6_000)
        assertEquals(700.0, summary.volumeKg, 0.0)
        assertEquals(4, summary.completedSets)
        assertEquals(3, summary.workingSets)
        assertEquals(1, summary.warmupSets)
    }
}
