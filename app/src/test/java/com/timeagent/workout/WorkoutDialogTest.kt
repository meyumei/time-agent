package com.timeagent.workout

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.timeagent.core.workout.WorkoutTabata
import com.timeagent.ui.TimeAgentTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
// shortcut: Robolectric #8460 loops with text fields in width-qualified dialogs; device tests cover physical sizes.
@Config(sdk = [28], qualifiers = "h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkoutDialogTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `Tabata stage dialog accepts work duration without other workout content`() {
        var saved: WorkoutTabata? = null
        compose.setContent { TimeAgentTheme(darkTheme = false) { TabataDialog(WorkoutTabata(), onDismiss = {}, onSave = { saved = it }) } }
        compose.onNode(hasSetTextAction() and hasText("训练(秒)")).performTextReplacement("30")
        compose.onNodeWithText("保存").performClick()
        assertEquals(30, saved!!.workSeconds)
    }
}
