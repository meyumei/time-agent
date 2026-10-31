package com.timeagent.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.timeagent.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FishNativePreviewTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var view:View
    @Test fun `plan exposes project task and complete task list with central input retained`() {
        var destination=""
        var selected=""
        val tasks=(1..8).map { TaskEntity("t$it","合成任务 $it") }
        val data=LocalSnapshot(tasks=tasks,projects=listOf(ProjectEntity("p","新版本验收")),placements=listOf(TaskPlacementEntity("t1","p")),loading=false)
        compose.setContent {
            view=LocalView.current
            TimeAgentTheme { Surface(Modifier.fillMaxSize()) { Column {
                Box(Modifier.weight(1f)) { PlanHubScreen(data,ZoneId.of("Asia/Shanghai"),{selected=it.id},{},{},{destination=it}) }
                TimeAgentNavigation(1,{},voice={Text("按住说话")})
            } } }
        }
        compose.onNodeWithContentDescription("今天页面").assertExists()
        compose.onNodeWithContentDescription("计划页面").assertExists()
        compose.onNodeWithContentDescription("管家页面").assertExists()
        compose.onNodeWithText("按住说话").assertExists()
        capture("plan-native")
        compose.onNodeWithText("新版本验收").performClick()
        compose.runOnIdle { assertEquals("project:p",destination) }
        compose.onNodeWithText("合成任务 1").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("t1",selected) }
        compose.onNodeWithContentDescription("打开全部任务").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("tasks",destination) }
    }
    private fun capture(name:String)=compose.runOnIdle {
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file=File("build/outputs/previews/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
}
