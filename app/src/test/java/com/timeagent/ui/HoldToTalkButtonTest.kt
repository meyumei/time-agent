package com.timeagent.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
class HoldToTalkButtonTest {
    @get:Rule val compose = createComposeRule()
    private var starts = 0; private var finishes = 0; private var cancels = 0; private var taps = 0
    private fun button() = compose.onNodeWithContentDescription("按住说话")
    private fun content(allowed: Boolean = true) { compose.setContent { TimeAgentTheme { HoldToTalkButton(onStart = { starts++; allowed }, onFinish = { finishes++ }, onCancel = { cancels++ }, onTap = { taps++ }) } } }
    private fun hold() {
        button().performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
    }
    @Test fun `short physical tap does not start recording`() {
        content(); button().performTouchInput { click() }
        assertEquals(0, starts); assertEquals(0, finishes); assertEquals(1, taps)
    }
    @Test fun `long hold starts once and release finishes once`() {
        content(); hold()
        assertEquals(1, starts); assertEquals(0, finishes)
        button().performTouchInput { up() }
        assertEquals(1, finishes); assertEquals(0, cancels)
    }
    @Test fun `upward release cancels rather than submitting`() {
        content(); hold()
        button().performTouchInput { moveTo(center - Offset(0f, 100f)); up() }
        assertEquals(1, starts); assertEquals(0, finishes); assertEquals(1, cancels)
    }
    @Test fun `permission or unavailable service refusal does not finish a recording`() {
        content(allowed = false); hold(); button().performTouchInput { up() }
        assertEquals(1, starts); assertEquals(0, finishes); assertEquals(0, cancels)
    }
    @Test fun `leaving while pressed cancels the recording`() {
        var visible by mutableStateOf(true)
        compose.setContent { TimeAgentTheme { if (visible) HoldToTalkButton(onStart = { starts++; true }, onFinish = { finishes++ }, onCancel = { cancels++ }) } }
        hold(); compose.runOnIdle { visible = false }
        compose.waitForIdle()
        assertEquals(1, starts); assertEquals(1, cancels); assertEquals(0, finishes)
    }
    @Test fun `screen reader can start and explicitly finish speech without physical pointer events`() {
        content()
        button().performSemanticsAction(SemanticsActions.OnLongClick) { assertTrue(it()) }
        assertEquals(1, starts); assertEquals(0, finishes)
        button().performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        assertEquals(1, finishes); assertEquals(0, taps)
    }
    @Test fun `screen reader cancellation does not submit speech`() {
        content()
        button().performSemanticsAction(SemanticsActions.OnLongClick) { assertTrue(it()) }
        val action = button().fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "取消语音输入" }
        compose.runOnIdle { assertTrue(action.action()) }
        assertEquals(1, starts); assertEquals(1, cancels); assertEquals(0, finishes)
    }
    @Test fun `external error resets accessible capture so next click cannot submit failed speech`() {
        var reset by mutableIntStateOf(0)
        compose.setContent { TimeAgentTheme { HoldToTalkButton(resetSignal = reset, onStart = { starts++; true }, onFinish = { finishes++ }, onCancel = { cancels++ }, onTextEntry = { taps++ }) } }
        button().performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.runOnIdle { reset++ }
        button().performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(0, finishes); assertEquals(1, cancels); assertEquals(1, taps)
    }
    @Test fun `processing while finger remains held does not claim microphone is listening`() {
        var processing by mutableStateOf(false)
        compose.setContent { TimeAgentTheme { HoldToTalkButton(processing = processing, onStart = { true }, onFinish = {}, onCancel = {}) } }
        hold(); compose.runOnIdle { processing = true }
        button().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "录音已结束，正在识别；松开后查看"))
        button().performTouchInput { up() }
    }
    @Test fun `early completed recognition stops claiming microphone is still listening`() {
        var resultReady by mutableStateOf(false)
        compose.setContent { TimeAgentTheme { HoldToTalkButton(resultReady = resultReady, onStart = { true }, onFinish = { finishes++ }, onCancel = {}) } }
        hold(); compose.runOnIdle { resultReady = true }
        compose.onNodeWithText("松开查看").assertIsDisplayed()
        button().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "识别已完成，松开查看，上滑取消"))
        button().performTouchInput { up() }
        assertEquals(1, finishes)
    }
    @Test fun `sideways finger drift never means upward cancellation`() {
        content(); hold()
        button().performTouchInput { moveTo(center + Offset(120f, 0f)); up() }
        assertEquals(1, finishes)
        assertEquals(0, cancels)
    }
    @Test fun `layout movement under a stationary finger does not cancel recorded speech`() {
        var shift by mutableStateOf(0.dp)
        compose.setContent {
            TimeAgentTheme {
                Box(Modifier.padding(top = shift)) {
                    HoldToTalkButton(onStart = { starts++; true }, onFinish = { finishes++ }, onCancel = { cancels++ })
                }
            }
        }
        hold()
        compose.runOnIdle { shift = 96.dp }
        compose.waitForIdle()
        button().performTouchInput { up() }
        assertEquals(1, finishes)
        assertEquals(0, cancels)
    }
}
