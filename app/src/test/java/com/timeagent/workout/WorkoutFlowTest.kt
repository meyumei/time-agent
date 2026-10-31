package com.timeagent.workout

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.timeagent.core.workout.*
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.data.TimeRepository
import com.timeagent.ui.TimeAgentTheme
import com.timeagent.workout.data.WorkoutDatabase
import com.timeagent.workout.data.WorkoutRepository
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkoutFlowTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var mainDb: TimeAgentDatabase
    private lateinit var db: WorkoutDatabase
    private lateinit var time: TimeRepository
    private lateinit var model: WorkoutViewModel
    private lateinit var renderView: View
    private lateinit var clock: MutableClock
    private var closed = false
    private class MutableClock(var value: Long) : Clock() {
        override fun instant() = Instant.ofEpochMilli(value)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("workout", 0).edit().clear().commit()
        mainDb = TimeAgentDatabase.openInMemory(context)
        db = Room.inMemoryDatabaseBuilder(context, WorkoutDatabase::class.java).build()
        clock = MutableClock(System.currentTimeMillis() - 60_000)
        time = TimeRepository(mainDb, clock)
        time.initialize()
        model = WorkoutViewModel(WorkoutRepository(db, time, clock), WorkoutCatalog(context))
        closed = false
        compose.setContent { renderView = LocalView.current; TimeAgentTheme(darkTheme = false) { WorkoutApp(model, onClose = { closed = true }) } }
        await { !model.loading.value && model.catalog.exercises.value.isNotEmpty() }
    }
    @After fun cleanup() { model.viewModelScope.cancel(); db.close(); mainDb.close() }
    private fun await(predicate: () -> Boolean) = compose.waitUntil(20_000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); predicate() }
    private fun screenshot(name: String) = compose.runOnIdle {
        val image = Bitmap.createBitmap(renderView.width, renderView.height, Bitmap.Config.ARGB_8888)
        renderView.draw(Canvas(image))
        val target = File("build/outputs/previews/$name.png").apply { parentFile!!.mkdirs() }
        target.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `local library selection records a set pauses and finishes into timeline exactly once`() {
        compose.onNodeWithTag("workout-start").performClick()
        await { model.sessions.value.size == 1 }
        compose.onNodeWithTag("workout-add-exercise").performClick()
        compose.onNodeWithTag("workout-exercise-search").performTextInput("杠铃卧推")
        compose.onNodeWithTag("workout-exercise-benchpress").performClick()
        compose.onNodeWithTag("workout-picker-done").performClick()
        await { model.sessions.value.single().movements.isNotEmpty() }
        val session = model.sessions.value.single()
        val movement = session.movements.single()
        val set = movement.sets.single()
        compose.runOnIdle {
            model.updateSet(session.id, movement.id, set.id) { it.copy(weightKg = 20.0) }
            model.updateSet(session.id, movement.id, set.id) { it.copy(reps = 10) }
        }
        await { model.sessions.value.single().movements.single().sets.single().let { it.weightKg == 20.0 && it.reps == 10 } }
        clock.value += 10_000
        compose.onNodeWithTag("workout-complete-set").performClick()
        await { model.sessions.value.single().movements.single().sets.single().isCompleted }
        compose.onNodeWithTag("workout-volume").assertTextContains("200 kg", substring = true)
        compose.onNodeWithTag("workout-rest").assertIsDisplayed()
        screenshot("workout-recording")
        compose.onNodeWithTag("workout-pause").performClick()
        await { model.sessions.value.single().status == WorkoutSessionStatus.PAUSED }
        compose.onNodeWithContentDescription("恢复训练").performClick()
        await { model.sessions.value.single().status == WorkoutSessionStatus.ACTIVE }
        clock.value += 50_000
        compose.onNodeWithTag("workout-finish").performClick()
        compose.onNodeWithText("确认", useUnmergedTree = true).performClick()
        await { model.sessions.value.single().timelineRecordId != null }
        assertEquals(1, runBlocking { time.snapshot() }.records.count { it.source == "WORKOUT" })
        screenshot("workout-summary")
        compose.onNodeWithText("再练一次").performClick()
        await { model.sessions.value.size == 2 }
        val repeated = model.sessions.value.first { it.status == WorkoutSessionStatus.ACTIVE }
        assertNotEquals(session.id, repeated.id)
        assertFalse(repeated.movements.single().sets.single().isCompleted)
        assertEquals(20.0, repeated.movements.single().sets.single().weightKg!!, 0.0)
    }

    @Test fun `pace stays a local draft until done and complete button commits focused fields`() {
        val cardio = ExerciseDefinition("cardio-test", "跑步", listOf("有氧"), metric = WorkoutMetric.CARDIO, recordFieldKeys = listOf("pace"))
        compose.runOnIdle { model.perform { model.repository.start(exercises = listOf(cardio)) } }
        await { model.sessions.value.size == 1 }
        compose.onNodeWithTag("workout-start").performClick()
        compose.onNodeWithTag("workout-cardio-pace").performTextInput("5:3")
        compose.runOnIdle { assertNull(model.error.value); assertTrue(model.sessions.value.single().movements.single().sets.single().recordValues.isEmpty()) }
        compose.onNodeWithTag("workout-cardio-pace").performTextInput("0")
        compose.onNodeWithTag("workout-complete-set").performClick()
        await { model.sessions.value.single().movements.single().sets.single().isCompleted }
        assertEquals("5:30", model.sessions.value.single().movements.single().sets.single().recordValues["pace"])
        assertNull(model.error.value)
    }

    // Robolectric #8460: text-field dialogs loop with explicit width qualifiers.
    @Config(qualifiers = "h891dp")
    @Test fun `new template permits configuring Tabata before starting a workout`() {
        compose.onNodeWithTag("workout-new-template").performClick()
        compose.onNodeWithText("添加动作").performClick()
        val action = model.catalog.exercises.value.first { it.metric == WorkoutMetric.TABATA }
        compose.onNodeWithTag("workout-exercise-search").performTextInput(action.name)
        compose.onNodeWithTag("workout-exercise-${action.id}").performClick()
        compose.onNodeWithTag("workout-picker-done").performClick()
        compose.onNodeWithTag("workout-template-tabata").performClick()
        compose.onNode(hasSetTextAction() and hasText("训练(秒)")).performTextReplacement("30")
        compose.onNode(hasText("保存") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
        compose.onNodeWithTag("workout-save-template").performClick()
        await { model.templates.value.size == 1 }
        assertEquals(30, model.templates.value.single().movements.single().sets.single().tabata!!.workSeconds)
        assertTrue(model.sessions.value.isEmpty())
        compose.onNodeWithText("开练").performClick()
        await { model.sessions.value.size == 1 }
        assertEquals(30, model.sessions.value.single().movements.single().sets.single().tabata!!.workSeconds)
    }
    @Test fun `minimize flushes the currently focused number before leaving activity`() {
        val press = ExerciseDefinition("press", "卧推", listOf("胸"))
        compose.runOnIdle { model.perform { model.repository.start(exercises = listOf(press)) } }
        await { model.sessions.value.size == 1 }
        compose.onNodeWithTag("workout-start").performClick()
        compose.onNodeWithTag("workout-weight").performTextInput("20")
        compose.onNodeWithTag("workout-reps").performTextInput("10")
        compose.onNodeWithText("最小化").performClick()
        await { closed }
        val saved = runBlocking { model.repository.activeSession() }!!.movements.single().sets.single()
        assertEquals(20.0, saved.weightKg!!, 0.0); assertEquals(10, saved.reps)
    }
    @Test fun `selected actions and query survive a visit to anatomy`() {
        compose.onNodeWithTag("workout-start").performClick()
        await { model.sessions.value.size == 1 }
        compose.onNodeWithTag("workout-add-exercise").performClick()
        compose.onNodeWithTag("workout-exercise-search").performTextInput("杠铃卧推")
        compose.onNodeWithTag("workout-exercise-benchpress").performClick()
        compose.onNodeWithText("3D 肌群").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("workout-exercise-search").assertTextContains("杠铃卧推", substring = true)
        compose.onNodeWithTag("workout-picker-done").assertTextContains("完成（1）", substring = true)
    }
}
