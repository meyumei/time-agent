package com.timeagent.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConflictLayoutSampleTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var renderView: View

    @Test fun `native conflict sample offers both layouts and opens the complete course group`() {
        compose.setContent {
            renderView = LocalView.current
            TimeAgentTheme(darkTheme = false) { ConflictLayoutSample() }
        }
        compose.onNodeWithText("冲突课程排版样例").assertIsDisplayed()
        compose.onNodeWithText("A 叠放摘要").assertIsDisplayed()
        compose.onNodeWithText("B 错层课程卡").assertIsDisplayed()
        compose.onNodeWithTag("conflict-summary").assertIsDisplayed()
        screenshot("conflict-layout-sample")

        compose.onNodeWithTag("conflict-summary").performClick()
        compose.onNodeWithTag("conflict-course-sheet").assertIsDisplayed()
        listOf("高等数学", "大学物理", "程序设计基础", "A楼 302 · 王老师", "理科楼 205 · 李老师", "实验楼 401 · 陈老师")
            .forEach { text -> compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("conflict-course-sheet"))).assertIsDisplayed() }
        screenshot("conflict-layout-sample-expanded")
        compose.onNodeWithContentDescription("关闭课程详情").performClick()

        compose.onNodeWithText("B 错层课程卡").performClick()
        compose.onNodeWithTag("conflict-staggered").assertIsDisplayed()
        listOf("高等数学", "大学物理", "程序设计基础")
            .forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        screenshot("conflict-layout-sample-staggered")
        compose.onNodeWithTag("conflict-staggered-course-coding").performClick()
        compose.onNodeWithTag("conflict-course-sheet").assertIsDisplayed()
        compose.onNode(hasText("实验楼 401 · 陈老师") and hasAnyAncestor(hasTestTag("conflict-course-sheet"))).assertIsDisplayed()
    }

    private fun screenshot(name: String) = compose.runOnIdle {
        assertTrue("The native Android view must have a measured viewport", renderView.width > 0 && renderView.height > 0)
        val bitmap = Bitmap.createBitmap(renderView.width, renderView.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        renderView.draw(canvas)
        org.robolectric.shadows.ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let { it.draw(canvas) }
        val file = File("build/outputs/previews/$name.png")
        requireNotNull(file.parentFile).mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
