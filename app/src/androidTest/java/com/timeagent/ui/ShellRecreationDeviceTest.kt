package com.timeagent.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.timeagent.MainActivity
import com.timeagent.fish.FishSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

/** Runs the real Activity in the disposable acceptance application. */
class ShellRecreationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun ready() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "com.timeagent.acceptance")
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("timeline-viewport").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun tab(name: String) = compose.onNodeWithContentDescription("${name}页面").performClick()

    @Test fun keptEditorSurvivesActivityRecreationAndKeepsTheSameFishSession() {
        ready()
        val fish = FishSession.current()
        tab("计划")
        compose.onNodeWithContentDescription("新建任务").performClick()
        compose.onNodeWithText("任务名称").performTextInput("DEVICE_ROTATION_DRAFT")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("保留草稿").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("继续编辑草稿").performClick()
        compose.onNodeWithText("任务名称").assertTextContains("DEVICE_ROTATION_DRAFT")
        assertSame(fish, FishSession.current())
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("丢弃修改").performClick()
    }

    @Test fun restoredReselectCounterDoesNotResetTheSavedScroll() {
        ready()
        tab("管家")
        tab("管家")
        compose.onNodeWithTag("butler-link-organization").performScrollTo()
        val before = compose.onNodeWithTag("butler-link-organization").fetchSemanticsNode().boundsInRoot.top
        val scrollBefore = compose.onNodeWithTag("butler-content").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("butler-link-organization").assertIsDisplayed()
        val after = compose.onNodeWithTag("butler-link-organization").fetchSemanticsNode().boundsInRoot.top
        val scrollAfter = compose.onNodeWithTag("butler-content").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        val maximumAfter = compose.onNodeWithTag("butler-content").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].maxValue()
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("scrollRestoration", "scroll=$scrollBefore/$scrollAfter maximum=$maximumAfter screenY=$before/$after")
        })
        org.junit.Assert.assertTrue("Restored content must not jump back to the top", scrollAfter > 0)
        compose.onNodeWithTag("butler-mode-tool").assertIsNotDisplayed()
    }
}
