package com.timeagent.fish

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.timeagent.assistant.*
import com.timeagent.core.*
import com.timeagent.ui.TimeAgentTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FishCompanionNativeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var view: View

    @Test fun `changing expression keeps overlapping character detail opaque through the transition`() {
        var frame by mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            view = LocalView.current
            Surface(color = androidx.compose.ui.graphics.Color.White) {
                FishSprite(FishAppearance.CHIBI, FishPresence(), Modifier.size(200.dp), animate = false, fixedFrame = frame)
            }
        }
        fun pixels(): IntArray {
            lateinit var result: IntArray
            compose.runOnIdle {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                result = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(result, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                bitmap.recycle()
            }
            return result
        }
        compose.waitForIdle()
        val before = pixels()
        compose.runOnIdle { frame = 2 }
        val samples = (1..12).map {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            pixels()
        }
        val after = samples.last()
        assertFalse(before.contentEquals(after))
        val intermediate = samples.filter { !it.contentEquals(before) && !it.contentEquals(after) }
        println("Transition sample hashes: ${samples.map { it.contentHashCode() }}")
        assertTrue("A real intermediate frame must be sampled", intermediate.isNotEmpty())
        val overlap = before.indices.filter {
            android.graphics.Color.red(before[it]) < 180 && android.graphics.Color.red(after[it]) < 180 &&
                android.graphics.Color.blue(before[it]) < 180 && android.graphics.Color.blue(after[it]) < 180
        }
        assertTrue("Both poses must contain overlapping dark character detail", overlap.size > 50)
        intermediate.forEach { middle ->
            val faded = overlap.count { index ->
                android.graphics.Color.red(middle[index]) > maxOf(android.graphics.Color.red(before[index]), android.graphics.Color.red(after[index])) + 5 ||
                    android.graphics.Color.blue(middle[index]) > maxOf(android.graphics.Color.blue(before[index]), android.graphics.Color.blue(after[index])) + 5
            }
            assertTrue("Overlapping character detail must not flash lighter during blending ($faded/${overlap.size})", faded <= overlap.size / 100)
        }
    }

    @Test fun `both real atlases render distinct facial and work poses in native compose`() {
        var frame by mutableIntStateOf(0)
        compose.setContent {
            view = LocalView.current
            TimeAgentTheme {
                Surface {
                    Column {
                        Text("大肥鱼 · 两种外观，同一个角色")
                        Row {
                            FishSprite(FishAppearance.CHIBI, FishPresence(), Modifier.size(180.dp), animate = false, fixedFrame = frame)
                            FishSprite(FishAppearance.ELEGANT, FishPresence(), Modifier.size(180.dp), animate = false, fixedFrame = frame)
                        }
                    }
                }
            }
        }
        val captures = mutableListOf<Int>()
        listOf(0, 2, 5, 8, 11, 13, 14).forEach { index ->
            compose.runOnIdle { frame = index }
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            compose.runOnIdle {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                captures += pixels.contentHashCode()
                val file = File("build/outputs/previews/fish-companion-$index.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        assertEquals(7, captures.distinct().size)
    }

    @Test fun `appearance position routine and mood survive rebuilding presentation state`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = FishCompanionState(context)
        first.save(FishCompanionSettings(appearance = FishAppearance.ELEGANT, positionX = .25f, positionY = .4f, wakeHour = 7, sleepHour = 22))
        first.event("reply", "太好了，恭喜完成！")
        val restored = FishCompanionState(context)
        assertEquals(FishAppearance.ELEGANT, restored.settings.value.appearance)
        assertEquals(.25f, restored.settings.value.positionX, .001f)
        assertEquals(7, restored.settings.value.wakeHour)
        assertEquals(FishMood.HAPPY, restored.presence.value.mood)
        assertEquals(FishActivity.IDLE, restored.presence.value.activity)
    }

    @Test fun `a fish sticker displays inside the original conversation panel`() {
        val turn = ConversationTurn("assistant", "太好了 [[fish:happy]]")
        compose.setContent {
            TimeAgentTheme {
                AssistantConversationPanel(AssistantChatState(turns = listOf(turn)), "", {}, true, {}, {}, {},
                    turnDecoration = { FishMessageSticker(it.text, FishAppearance.CHIBI) })
            }
        }
        compose.onNodeWithTag("assistant-conversation").assertExists()
        compose.onNodeWithTag("assistant-input").assertExists()
        compose.onNodeWithTag("fish-sticker-happy").assertExists()
        compose.onNodeWithText("太好了").assertExists()
        assertEquals("太好了", fishPlainText(turn.text))
    }
}
