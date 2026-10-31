package com.timeagent.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.timeagent.assistant.AndroidSpeechInput
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
class HomeVoiceDockTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `asynchronous recognizer permission error clears held microphone before finger release`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val service = ResolveInfo().apply { serviceInfo = ServiceInfo().apply { packageName = "test.voice"; name = "Recognizer" } }
        Shadows.shadowOf(app.packageManager).addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), service)
        val messages = mutableListOf<String>()
        val recognized = mutableListOf<String>()
        compose.setContent { TimeAgentTheme { HomeVoiceDock(true, recognized::add, messages::add,
            speechFactory = { context, callbacks -> AndroidSpeechInput(context, callbacks.onText, callbacks.onState, callbacks.onFailure) }) } }
        val button = compose.onNodeWithContentDescription("按住说话")
        button.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        compose.onNodeWithText("松开识别").assertExists()
        compose.runOnIdle { Shadows.shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
        compose.waitForIdle()
        compose.onNodeWithText("松开识别").assertDoesNotExist()
        button.performTouchInput { up() }
        assertTrue(recognized.isEmpty())
        assertTrue(messages.isNotEmpty())
    }
}
