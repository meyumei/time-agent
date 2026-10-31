package com.timeagent.imports

import android.content.Context
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.AcademicTermEntity
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.data.TimeRepository
import com.timeagent.ui.TimeAgentTheme
import com.timeagent.ui.TimeViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
class CourseImportScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: TimeAgentDatabase
    private lateinit var model: TimeViewModel
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TimeAgentDatabase::class.java).allowMainThreadQueries().build()
        model = TimeViewModel(TimeRepository(db, Clock.fixed(Instant.parse("2026-10-19T08:00:00Z"), ZoneId.of("Asia/Shanghai"))))
        compose.setContent { TimeAgentTheme { CourseImportScreen(model, {}) } }
        compose.waitForIdle()
    }
    @After fun cleanup() { model.viewModelScope.cancel(); db.close() }

    private fun set(label: String, text: String) {
        compose.onNodeWithTag("course-import-list").performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performScrollTo().performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(text)) }
    }
    private fun waitForPreview() = compose.waitUntil(10000) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        compose.onAllNodesWithText("课表导入预览").fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun manualDraftCanBeEditedAndPreviewCancelDoesNotWriteWhileConfirmationDoes() {
        compose.onNodeWithText("手工补录").performClick()
        set("学期标识（同一学期保持一致）", "synthetic-ui-term")
        set("第一教学周周一（YYYY-MM-DD）", "2026-08-31")
        set("学期教学周数", "2")
        compose.onNodeWithText("使用五山作息").performScrollTo().performClick()
        set("课程名称", "合成课程")
        set("星期 1–7", "1")
        set("节次，如 1-2", "1-2")
        set("周次，如 1-16周(单),18周", "1-2周")
        set("教师", "合成教师")
        set("地点", "合成教室")
        compose.onNodeWithTag("course-import-list").performScrollToNode(isToggleable())
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("生成导入预览").performScrollTo().performClick()
        waitForPreview()
        assertTrue(runBlocking { db.dao().occurrences().isEmpty() })
        compose.onNodeWithText("返回修改").performClick()
        assertTrue(runBlocking { db.dao().occurrences().isEmpty() })
        compose.onNodeWithText("生成导入预览").performScrollTo().performClick()
        waitForPreview()
        compose.onNodeWithText("确认导入").performClick()
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); runBlocking { db.dao().occurrences().size == 2 } }
        val occurrences = runBlocking { db.dao().occurrences() }
        assertEquals(2, occurrences.size)
        assertEquals("08:00", Instant.ofEpochMilli(occurrences.first().startAt).atZone(ZoneId.of("Asia/Shanghai")).toLocalTime().toString())
        assertEquals("09:40", Instant.ofEpochMilli(occurrences.first().endAt).atZone(ZoneId.of("Asia/Shanghai")).toLocalTime().toString())
        assertEquals(1, runBlocking { db.dao().importBatches().size })
    }

    @Test fun failedConfirmationShowsErrorInsidePreviewAndKeepsTheDraftForCorrection() {
        runBlocking { db.dao().putTerm(AcademicTermEntity("synthetic-ui-term", "导入学期", "2026-08-24", 2, "Asia/Shanghai")) }
        compose.onNodeWithText("手工补录").performClick()
        set("学期标识（同一学期保持一致）", "synthetic-ui-term")
        set("第一教学周周一（YYYY-MM-DD）", "2026-08-31")
        set("学期教学周数", "2")
        set("课程名称", "合成课程")
        set("星期 1–7", "1")
        set("节次，如 1-2", "1-2")
        set("周次，如 1-16周(单),18周", "1-2周")
        compose.onNodeWithTag("course-import-list").performScrollToNode(isToggleable())
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("生成导入预览").performScrollTo().performClick()
        waitForPreview()
        compose.onNodeWithText("确认导入").performClick()
        val errorInDialog = hasText("同一学期的起始日或时区已改变，请使用新的学期 ID") and hasAnyAncestor(isDialog())
        compose.waitUntil(10000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodes(errorInDialog).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(errorInDialog).assertIsDisplayed()
        assertTrue(runBlocking { db.dao().occurrences().isEmpty() })
        assertTrue(runBlocking { db.dao().importBatches().isEmpty() })
        compose.onNodeWithText("返回修改").performClick()
        set("第一教学周周一（YYYY-MM-DD）", "2026-08-24")
        compose.onNodeWithTag("course-import-list").performScrollToNode(isToggleable())
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("生成导入预览").performScrollTo().performClick()
        waitForPreview()
        compose.onNode(errorInDialog).assertDoesNotExist()
        compose.onNodeWithText("确认导入").performClick()
        compose.waitUntil(10000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runBlocking { db.dao().occurrences().size == 2 }
        }
        assertEquals("2026-08-24", runBlocking { db.dao().occurrences().first().date })
    }
}
