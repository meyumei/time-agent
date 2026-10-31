package com.timeagent.workout

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.timeagent.core.workout.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkoutRuntimeTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun `anatomy serves packaged wasm and blocks other origins and path traversal`() {
        val client = WorkoutAssetClient(context)
        client.response(Uri.parse("https://appassets.androidplatform.net/workout/anatomy/gltf/draco_decoder.wasm")).let {
            assertEquals(200, it.statusCode); assertEquals("application/wasm", it.mimeType); it.data.use { input -> assertTrue(input.available() > 1000) }
        }
        listOf("https://example.com/a.js", "file:///workout/anatomy/index.html", "https://appassets.androidplatform.net/workout/anatomy/%2e%2e/catalog.json", "https://appassets.androidplatform.net/course-import-example.json").forEach {
            assertEquals(it, 403, client.response(Uri.parse(it)).statusCode)
        }
    }
    @Test fun `rest and interval alarms are derived from persisted clock and paused timer is silent`() {
        val move = WorkoutMovement(exercise = ExerciseDefinition("tabata", "间歇", listOf("全身"), metric = WorkoutMetric.TABATA), sets = listOf(WorkoutSet(tabata = WorkoutTabata(5, 20, 10, 2))))
        val session = WorkoutSession(title = "训练", startedAt = 1000, movements = listOf(move), restEndsAt = 21_000)
        assertEquals(21_000L, WorkoutNotifications.nextDeadline(session, 2000))
        assertNull(WorkoutNotifications.nextDeadline(WorkoutRules.pause(session, 2000), 5000))
        val interval = WorkoutRules.startInterval(session, move.id, move.sets.single().id, 2000)
        assertEquals(7000L, WorkoutNotifications.nextDeadline(interval, 3000))
        assertEquals(27_000L, WorkoutNotifications.nextDeadline(interval, 8000))
        assertNull(WorkoutNotifications.nextDeadline(interval, 60_000))
    }
    @Test fun `rest cue is claimed once and old alarms cannot claim rescheduled or paused timer`() {
        val active = WorkoutSession(title = "训练", startedAt = 1000, restEndsAt = 5000)
        WorkoutNotifications.sync(context, active, 2000)
        assertFalse(WorkoutNotifications.claimDue(context, active, 4000))
        assertTrue(WorkoutNotifications.claimDue(context, active, 5000))
        assertFalse(WorkoutNotifications.claimDue(context, active, 5000))
        val extended = active.copy(restEndsAt = 20_000)
        WorkoutNotifications.sync(context, extended, 6000)
        assertFalse(WorkoutNotifications.claimDue(context, active, 30_000))
        assertFalse(WorkoutNotifications.claimDue(context, WorkoutRules.pause(extended, 7000), 30_000))
        assertTrue(WorkoutNotifications.claimDue(context, extended, 30_000))
    }
}
