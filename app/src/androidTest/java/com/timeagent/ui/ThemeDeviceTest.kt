package com.timeagent.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.timeagent.ui.design.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ThemeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun isolatedContext(): Context = object : ContextWrapper(compose.activity) {
        val id = "theme-device-${UUID.randomUUID()}"
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$id-$name", mode)
        override fun getDatabasePath(name: String) = File(cacheDir, "$id-$name")
    }

    @Test fun allFourAppearancesAndSemanticPairsHaveReadableContrast() {
        PresentationMode.entries.forEach { mode ->
            listOf(false, true).forEach { dark ->
                val scheme = themeColors(mode, dark)
                val pairs = listOf(
                    scheme.primary to scheme.onPrimary,
                    scheme.primaryContainer to scheme.onPrimaryContainer,
                    scheme.secondary to scheme.onSecondary,
                    scheme.secondaryContainer to scheme.onSecondaryContainer,
                    scheme.tertiary to scheme.onTertiary,
                    scheme.tertiaryContainer to scheme.onTertiaryContainer,
                    scheme.surface to scheme.onSurface,
                    scheme.surfaceVariant to scheme.onSurfaceVariant,
                    scheme.error to scheme.onError,
                    scheme.errorContainer to scheme.onErrorContainer,
                )
                val semantic = semanticColors(dark)
                val semanticPairs = listOf(semantic.plan to semantic.onPlan, semantic.actual to semantic.onActual,
                    semantic.conflict to semantic.onConflict, semantic.running to semantic.onRunning,
                    semantic.completed to semantic.onCompleted)
                (pairs + semanticPairs).forEach { (background, text) ->
                    assertTrue("$mode dark=$dark contrast=${contrastRatio(text, background)}",
                        contrastRatio(text, background) >= 4.5f)
                }
                TimelinePalette.presets.forEach {
                    val paint = it.paint(dark)
                    assertTrue(contrastRatio(paint.content, paint.container) >= 4.5f)
                }
            }
        }
    }

    @Test fun oldThemeMigratesWithoutChangingShadeAndHintIsOnlyOnce() {
        val context = isolatedContext()
        context.getSharedPreferences("appearance", 0).edit().putString("theme", "DARK").commit()
        val preference = ThemePreference(context)
        assertEquals(ThemeMode.DARK, preference.read())
        assertEquals(PresentationMode.COMPANION, preference.readAll().presentation)
        assertFalse(preference.readAll().dynamicColor)
        assertTrue(preference.readAll().modeHintPending)
        preference.acknowledgeModeHint()
        assertFalse(ThemePreference(context).readAll().modeHintPending)
    }

    @Test fun modeShadeAndDynamicColorKeepActivityDraftAndRunningState() {
        val context = isolatedContext()
        val preference = ThemePreference(context)
        val activity = compose.activity
        var identity: Any? = null
        compose.setContent {
            DeviceTestFont { CompositionLocalProvider(LocalContext provides context) {
                TimeAgentTheme {
                    val runningState = remember { Any() }
                    SideEffect { identity = runningState }
                    var draft by rememberSaveable { mutableStateOf("") }
                    Column {
                        BasicTextField(draft, { draft = it }, Modifier.testTag("theme-draft"))
                        Text(LocalAppearance.current.presentation.name, Modifier.testTag("theme-mode"))
                    }
                }
            } }
        }
        compose.onNodeWithTag("theme-draft").performTextInput("未提交的任务草稿")
        compose.waitForIdle()
        val running = identity
        PresentationMode.entries.forEach { mode ->
            compose.runOnIdle {
                preference.savePresentation(mode)
                preference.save(ThemeMode.DARK)
                preference.saveDynamicColor(true)
            }
            compose.onNodeWithTag("theme-mode").assertTextEquals(mode.name)
            compose.onNodeWithTag("theme-draft").assertTextEquals("未提交的任务草稿")
            compose.runOnIdle {
                assertSame(activity, compose.activity)
                assertSame(running, identity)
            }
        }
    }
}
