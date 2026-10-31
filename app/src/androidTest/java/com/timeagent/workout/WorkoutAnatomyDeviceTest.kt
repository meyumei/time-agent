package com.timeagent.workout

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.timeagent.ui.TimeAgentTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WorkoutAnatomyDeviceTest {
    @Test fun packagedModelLoadsInPhoneWebView() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("am start -n com.timeagent/.MainActivity").use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        }
        val foregroundDeadline = System.currentTimeMillis() + 10_000
        var foreground = false
        while (!foreground && System.currentTimeMillis() < foregroundDeadline) {
            instrumentation.runOnMainSync { foreground = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).isNotEmpty() }
            if (!foreground) Thread.sleep(100)
        }
        assertTrue("MainActivity did not resume", foreground)
        instrumentation.runOnMainSync {
            instrumentation.targetContext.startActivity(WorkoutActivity.intent(instrumentation.targetContext).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        var resumed: WorkoutActivity? = null
        val launchDeadline = System.currentTimeMillis() + 15_000
        while (resumed == null && System.currentTimeMillis() < launchDeadline) {
            instrumentation.runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<WorkoutActivity>().firstOrNull()
            }
            if (resumed == null) Thread.sleep(100)
        }
        var activity = requireNotNull(resumed) { "WorkoutActivity did not resume" }
        if (InstrumentationRegistry.getArguments().getString("orientation") == "landscape") {
            instrumentation.runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            val rotationDeadline = System.currentTimeMillis() + 10_000
            var rotated: WorkoutActivity? = null
            while (rotated == null && System.currentTimeMillis() < rotationDeadline) {
                instrumentation.runOnMainSync {
                    rotated = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<WorkoutActivity>()
                        .firstOrNull { it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                }
                if (rotated == null) Thread.sleep(100)
            }
            activity = requireNotNull(rotated) { "WorkoutActivity did not rotate" }
        }
        try {
            instrumentation.runOnMainSync {
                activity.setContent { TimeAgentTheme { WorkoutAnatomyScreen("胸") {} } }
            }
            val deadline = System.currentTimeMillis() + 60_000
            var state = JsonObject()
            while (System.currentTimeMillis() < deadline) {
                val latch = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    findWebView(activity.window.decorView)?.evaluateJavascript(
                        """JSON.stringify({state:document.body.dataset.loadState||'',text:document.body.innerText,meshes:document.body.dataset.meshCount||'',width:innerWidth,height:innerHeight,visibility:document.visibilityState,scroll:[scrollX,scrollY,document.documentElement.scrollHeight],rects:Array.from(document.querySelectorAll('canvas,.tools,#label')).map(e=>({tag:e.tagName,id:e.id,rect:JSON.stringify(e.getBoundingClientRect())})),calls:typeof renderer!=='undefined'&&renderer?renderer.info.render.calls:-1,contextLost:typeof renderer!=='undefined'&&renderer?renderer.getContext().isContextLost():null,canvas:!!document.querySelector('canvas')})""",
                    ) { value ->
                        runCatching { state = Gson().fromJson(Gson().fromJson(value, String::class.java), JsonObject::class.java) }
                        latch.countDown()
                    } ?: latch.countDown()
                }
                assertTrue("WebView did not answer", latch.await(5, TimeUnit.SECONDS))
                if (state.get("state")?.asString in listOf("ready", "error")) break
                Thread.sleep(250)
            }
            val proof = File(instrumentation.targetContext.getExternalFilesDir(null), "phone-verification").apply { mkdirs() }
            File(proof, "anatomy-state.json").writeText(state.toString())
            val painted = CountDownLatch(1)
            instrumentation.runOnMainSync {
                findWebView(activity.window.decorView)?.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { painted.countDown() }
                }) ?: painted.countDown()
            }
            assertTrue("WebView did not submit a visual frame", painted.await(15, TimeUnit.SECONDS))
            Thread.sleep(500)
            var highlightedPixels = 0
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(proof, "anatomy-device.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                for (y in bitmap.height/5 until bitmap.height*9/10 step 8) for (x in bitmap.width/10 until bitmap.width*9/10 step 8) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.red(pixel) > Color.green(pixel)+20 && Color.red(pixel) > Color.blue(pixel)+15) highlightedPixels++
                }
                bitmap.recycle()
            }
            state.addProperty("highlightedPixels", highlightedPixels)
            File(proof, "anatomy-state.json").writeText(state.toString())
            println("ANATOMY_DEVICE_STATE $state")
            assertEquals(state.toString(), "ready", state.get("state")?.asString)
            assertEquals("3556", state.get("meshes")?.asString)
            assertTrue(state.get("text").asString.contains("全身"))
            assertTrue(state.get("canvas").asBoolean)
            assertTrue("Model decoded but phone display is blank (highlighted pixels=$highlightedPixels)", highlightedPixels > 100)
        } finally { instrumentation.runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; activity.finish() } }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }
}
