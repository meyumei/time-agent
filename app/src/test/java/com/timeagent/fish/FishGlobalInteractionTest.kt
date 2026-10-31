package com.timeagent.fish

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/**
 * Robolectric (sdk 28) evidence for the global companion touch surface and the opt-in default.
 *
 * These tests exercise the view and the settings store directly. They are not device acceptance:
 * no phone was attached, and the author did not run a build.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class FishGlobalInteractionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var downTime = 1_000L
    private var eventTime = 1_000L

    private fun motion(action: Int, x: Float, y: Float): MotionEvent {
        eventTime += 16
        if (action == MotionEvent.ACTION_DOWN) downTime = eventTime
        return MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
    }

    @Test
    fun `tap opens the chat once while a drag only moves and drops`() {
        val recorder = TouchRecorder()
        val view = recorder.view(context)
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val startX = 40f
        val startY = 90f

        assertTrue("the touch surface must claim the gesture", view.onTouchEvent(motion(MotionEvent.ACTION_DOWN, startX, startY)))
        view.onTouchEvent(motion(MotionEvent.ACTION_MOVE, startX + slop / 2f, startY))
        view.onTouchEvent(motion(MotionEvent.ACTION_UP, startX + slop / 2f, startY))

        assertEquals("a press inside the touch slop is a click", 1, recorder.taps)
        assertTrue("a click must not move the window", recorder.moves.isEmpty())
        assertEquals("a click must not save a position", 0, recorder.drops)
        assertEquals("a click must not open the settings", 0, recorder.longPresses)

        val firstX = startX + slop + 30f
        view.onTouchEvent(motion(MotionEvent.ACTION_DOWN, startX, startY))
        view.onTouchEvent(motion(MotionEvent.ACTION_MOVE, firstX, startY))
        val secondX = firstX + 24f
        val secondY = startY + 8f
        view.onTouchEvent(motion(MotionEvent.ACTION_MOVE, secondX, secondY))
        view.onTouchEvent(motion(MotionEvent.ACTION_UP, secondX, secondY))

        assertEquals("a drag must not open the chat", 1, recorder.taps)
        assertEquals("the drag is delivered in two moves", 2, recorder.moves.size)
        assertEquals("the first move covers the travel from the down point", slop + 30f, recorder.moves[0].dx, 0.01f)
        assertEquals(0f, recorder.moves[0].dy, 0.01f)
        assertEquals(24f, recorder.moves[1].dx, 0.01f)
        assertEquals(8f, recorder.moves[1].dy, 0.01f)
        assertEquals("window travel equals finger travel in raw screen pixels",
            (secondX - startX).toDouble(), recorder.moves.sumOf { it.dx.toDouble() }, 0.01)
        assertEquals("release after a drag persists the position", 1, recorder.drops)
        assertEquals("a drag must not open the settings", 0, recorder.longPresses)
    }

    @Test
    fun `a cancelled gesture never opens the chat`() {
        val recorder = TouchRecorder()
        val view = recorder.view(context)
        val slop = ViewConfiguration.get(context).scaledTouchSlop

        view.onTouchEvent(motion(MotionEvent.ACTION_DOWN, 30f, 30f))
        view.onTouchEvent(motion(MotionEvent.ACTION_CANCEL, 30f, 30f))
        assertEquals("a cancelled press is not a click", 0, recorder.taps)
        assertEquals(0, recorder.drops)
        assertEquals(0, recorder.longPresses)

        view.onTouchEvent(motion(MotionEvent.ACTION_DOWN, 30f, 30f))
        view.onTouchEvent(motion(MotionEvent.ACTION_MOVE, 30f + slop + 20f, 40f))
        view.onTouchEvent(motion(MotionEvent.ACTION_CANCEL, 30f + slop + 20f, 40f))
        assertEquals("cancel after a drag is still not a click", 0, recorder.taps)
        assertEquals("cancel after a drag saves where the fish was left", 1, recorder.drops)
        assertEquals("cancel after a drag must not open the settings", 0, recorder.longPresses)
    }

    @Test
    fun `long press opens the companionship settings and suppresses the chat`() {
        val recorder = TouchRecorder()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = recorder.view(activity)
        activity.setContentView(view)

        view.onTouchEvent(motion(MotionEvent.ACTION_DOWN, 60f, 80f))
        view.onTouchEvent(motion(MotionEvent.ACTION_UP, 60f, 80f))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        assertEquals("a short tap must not become a long press", 0, recorder.longPresses)
        assertEquals("a short tap opens the chat", 1, recorder.taps)

        view.onTouchEvent(motion(MotionEvent.ACTION_DOWN, 60f, 80f))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        assertEquals("holding past the timeout opens the settings", 1, recorder.longPresses)
        view.onTouchEvent(motion(MotionEvent.ACTION_UP, 60f, 80f))
        assertEquals("the long press must not also open the chat", 1, recorder.taps)
        assertEquals("the long press must not save a position", 0, recorder.drops)
    }

    @Test
    fun `global companion stays off by default and roundtrips without moving the in-app fish`() {
        context.getSharedPreferences("fish_companion", Context.MODE_PRIVATE).edit().clear().commit()

        val original = FishCompanionState(context).settings.value
        assertFalse("the global window is opt-in", original.globalVisible)

        FishCompanionState(context).save(original.copy(globalVisible = true, globalX = 0.2f, globalY = 0.35f))
        val reopened = FishCompanionState(context).settings.value
        assertTrue("enabling the global window survives a cold read", reopened.globalVisible)
        assertEquals(0.2f, reopened.globalX, 0f)
        assertEquals(0.35f, reopened.globalY, 0f)
        assertEquals("the in-app fish keeps its horizontal position", original.positionX, reopened.positionX, 0f)
        assertEquals("the in-app fish keeps its vertical position", original.positionY, reopened.positionY, 0f)
        assertEquals(original.wakeHour, reopened.wakeHour)
        assertEquals(original.sleepHour, reopened.sleepHour)
        assertEquals(original.visible, reopened.visible)

        FishCompanionState(context).save(reopened.copy(globalVisible = false))
        val closed = FishCompanionState(context).settings.value
        assertFalse("turning the window off is persisted", closed.globalVisible)
        assertEquals("turning the window off keeps the last global position", 0.2f, closed.globalX, 0f)
        assertEquals(0.35f, closed.globalY, 0f)
        assertEquals(original.positionX, closed.positionX, 0f)
        assertEquals(original.positionY, closed.positionY, 0f)
    }
}

private data class Delta(val dx: Float, val dy: Float)

private class TouchRecorder {
    var taps = 0
    var longPresses = 0
    var drops = 0
    val moves = mutableListOf<Delta>()

    fun view(host: Context) = FishOverlayTouchView(
        host,
        onTap = { taps += 1 },
        onLongPress = { longPresses += 1 },
        onMove = { dx, dy -> moves += Delta(dx, dy) },
        onDrop = { drops += 1 },
    )
}
