package com.timeagent.ui

import android.content.Context
import android.Manifest
import android.app.Application
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.content.ContextWrapper
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.geometry.Offset
import androidx.room.Room
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.*
import com.timeagent.assistant.*
import com.timeagent.core.ImportTerm
import com.timeagent.imports.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.Clock
import java.time.ZoneId
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppFlowTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: TimeAgentDatabase
    private lateinit var viewModel: TimeViewModel
    private lateinit var renderView: View
    private lateinit var restoration: StateRestorationTester
    private var voiceCallbacks: SpeechCallbacks? = null
    private val speechFactory: SpeechInputFactory = { _, callbacks -> voiceCallbacks = callbacks; TestSpeechInput(callbacks) }
    @Before fun setup() {
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit()
        db = TimeAgentDatabase.openInMemory(ApplicationProvider.getApplicationContext<Context>())
        viewModel = TimeViewModel(TimeRepository(db, Clock.fixed(Instant.parse("2026-10-19T08:00:00Z"), ZoneId.of("Asia/Shanghai"))))
        restoration = StateRestorationTester(compose)
        restoration.setContent { renderView = LocalView.current; TimeAgentTheme { TimeAgentApp(viewModel, speechFactory) } }
        compose.waitForIdle()
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); !viewModel.data.value.loading }
    }
    @After fun cleanup() { viewModel.viewModelScope.cancel(); db.close() }
    private fun screenshot(name: String) = compose.runOnIdle {
        val bitmap = Bitmap.createBitmap(renderView.width, renderView.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        renderView.draw(canvas)
        org.robolectric.shadows.ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let { dialogView -> dialogView.draw(canvas) }
        val file = File("build/outputs/previews/$name.png")
        requireNotNull(file.parentFile).mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun startVoice() {
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        compose.onNodeWithContentDescription("按住说话").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
    }
    @Test fun `speech appears at top then executes on the same timeline with undo`() {
        startVoice()
        compose.runOnIdle { voiceCallbacks!!.onPartial("新建任务阅读三十分钟") }
        compose.onNodeWithTag("voice-live-input").assertIsDisplayed()
        compose.onNodeWithText("新建任务阅读三十分钟").assertIsDisplayed()
        val banner = compose.onNodeWithTag("voice-live-input").fetchSemanticsNode().boundsInRoot
        assertTrue(banner.top < renderView.height * .2f)
        assertTrue(runBlocking { db.dao().tasks().isEmpty() })
        screenshot("voice-live-input")
        compose.onNodeWithContentDescription("按住说话").performTouchInput { up() }
        compose.runOnIdle { voiceCallbacks!!.onText("新建任务阅读三十分钟"); voiceCallbacks!!.onState(SpeechInputStatus.IDLE, null) }
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); viewModel.data.value.tasks.isNotEmpty() }
        compose.onNodeWithContentDescription("按住说话").assertIsDisplayed()
        compose.onNodeWithText("24:00").assertIsDisplayed()
        compose.onNodeWithTag("assistant-input").assertDoesNotExist()
        compose.onNodeWithContentDescription("撤销这次").assertIsDisplayed()
        assertEquals("阅读", runBlocking { db.dao().tasks().single().title })
        compose.onNodeWithText("确认保存").assertDoesNotExist()
        screenshot("voice-same-page-executed")
    }
    @Test fun `upward cancellation removes live words without opening assistant or saving`() {
        startVoice()
        compose.runOnIdle { voiceCallbacks!!.onPartial("新建任务阅读三十分钟") }
        compose.onNodeWithText("新建任务阅读三十分钟").assertIsDisplayed()
        compose.onNodeWithContentDescription("按住说话").performTouchInput { moveTo(center - Offset(0f, 100f)); up() }
        compose.onNodeWithTag("voice-live-input").assertDoesNotExist()
        compose.onNodeWithTag("assistant-input").assertDoesNotExist()
        assertTrue(runBlocking { db.dao().tasks().isEmpty() })
    }
    @Test fun `completed speech removes the idle transcript banner`() {
        startVoice()
        compose.runOnIdle { voiceCallbacks!!.onPartial("新建任务阅读三十分钟") }
        compose.onNodeWithContentDescription("按住说话").performTouchInput { up() }
        compose.runOnIdle { voiceCallbacks!!.onText("新建任务阅读三十分钟"); voiceCallbacks!!.onState(SpeechInputStatus.IDLE, null) }
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); viewModel.data.value.tasks.isNotEmpty() }
        compose.onNodeWithTag("voice-live-input").assertDoesNotExist()
        compose.onNodeWithText("24:00").assertIsDisplayed()
    }
    @Test fun `completed action returns to a single compact row and history can be reopened`() {
        startVoice()
        compose.onNodeWithContentDescription("按住说话").performTouchInput { up() }
        compose.runOnIdle { voiceCallbacks!!.onText("新建任务阅读三十分钟"); voiceCallbacks!!.onState(SpeechInputStatus.IDLE, null) }
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); viewModel.data.value.tasks.isNotEmpty() && !viewModel.assistant.state.value.busy }
        compose.waitForIdle()
        val density = renderView.resources.displayMetrics.density
        assertTrue("A completed operation must leave the calendar most of the screen", compose.onNodeWithTag("assistant-conversation").fetchSemanticsNode().boundsInRoot.height <= 52f * density)
        compose.onNodeWithTag("assistant-input").assertDoesNotExist()
        compose.onNodeWithContentDescription("展开助手").performClick()
        compose.onNodeWithTag("assistant-input").assertTextContains("新建任务阅读三十分钟")
        compose.onNodeWithText("已执行 · 新建任务", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("收起助手").performClick()
        assertTrue(compose.onNodeWithTag("assistant-conversation").fetchSemanticsNode().boundsInRoot.height <= 52f * density)
        screenshot("agent-compact-completed")
    }
    @Test fun `sleep without overlap uses the day column despite a later scheduled activity`() {
        val start = Instant.parse("2026-10-19T02:00:00Z").toEpochMilli()
        runBlocking { viewModel.repository.saveTask(TaskEntity("later-task", "稍后阅读")); viewModel.repository.savePlan(ScheduleBlockEntity("later-plan", "later-task", start, start + 3600000)) }
        startVoice()
        compose.onNodeWithContentDescription("按住说话").performTouchInput { up() }
        compose.runOnIdle { voiceCallbacks!!.onText("记录睡眠一点半到八点半"); voiceCallbacks!!.onState(SpeechInputStatus.IDLE, null) }
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); viewModel.data.value.records.size == 1 }
        val dayWidth = compose.onNodeWithText("20", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x - compose.onNodeWithText("19", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        val card = compose.onNodeWithContentDescription("实际 睡眠", substring = true)
        assertTrue("A nonoverlapping sleep is a readable event, not a narrow strip", card.fetchSemanticsNode().boundsInRoot.width > dayWidth * .9f)
        card.assertIsDisplayed()
        screenshot("sleep-normal-width")
    }
    @Test fun `clarification remains open until supplied time completes the action`() {
        compose.onNodeWithContentDescription("语音助手").performClick()
        compose.onNodeWithTag("assistant-input").performTextReplacement("记录睡眠")
        compose.onNodeWithContentDescription("发送给助手").performClick()
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); viewModel.assistant.state.value.turns.size >= 2 && !viewModel.assistant.state.value.busy }
        compose.onNodeWithTag("assistant-input").assertIsDisplayed()
        assertTrue(runBlocking { db.dao().records().isEmpty() })
        compose.onNodeWithTag("assistant-input").performTextReplacement("一点半到八点半")
        compose.onNodeWithContentDescription("发送给助手").performClick()
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); viewModel.data.value.records.size == 1 && !viewModel.assistant.state.value.busy }
        compose.onNodeWithTag("assistant-input").assertDoesNotExist()
        compose.onNodeWithText("24:00").assertIsDisplayed()
    }
    @Test fun `pending confirmation stays expanded after state restoration following a completed action`() {
        compose.onNodeWithContentDescription("语音助手").performClick()
        compose.onNodeWithTag("assistant-input").performTextReplacement("新建任务阅读三十分钟")
        compose.onNodeWithContentDescription("发送给助手").performClick()
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); viewModel.data.value.tasks.isNotEmpty() && !viewModel.assistant.state.value.busy }
        compose.onNodeWithTag("assistant-input").assertDoesNotExist()
        compose.onNodeWithContentDescription("展开助手").performClick()
        compose.onNodeWithTag("assistant-input").performTextReplacement("记录今天19:00到20:00阅读")
        compose.onNodeWithContentDescription("发送给助手").performClick()
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); viewModel.assistant.state.value.pending != null && !viewModel.assistant.state.value.busy }
        compose.onNodeWithText("确认记录未来时间").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("assistant-input").assertIsDisplayed()
        compose.onNodeWithText("确认记录未来时间").assertIsDisplayed()
        assertTrue(runBlocking { db.dao().records().isEmpty() })
        compose.onNodeWithText("取消", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); viewModel.assistant.state.value.pending == null && !viewModel.assistant.state.value.busy }
        compose.onNodeWithTag("assistant-input").assertDoesNotExist()
    }
    @Test fun `reopened completed conversation stays expanded after state restoration`() {
        compose.onNodeWithContentDescription("语音助手").performClick()
        compose.onNodeWithTag("assistant-input").performTextReplacement("新建任务阅读三十分钟")
        compose.onNodeWithContentDescription("发送给助手").performClick()
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); viewModel.data.value.tasks.isNotEmpty() && !viewModel.assistant.state.value.busy }
        compose.onNodeWithContentDescription("展开助手").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("assistant-input").assertIsDisplayed()
        compose.onNodeWithText("已执行 · 新建任务", substring = true).assertIsDisplayed()
    }
    @Test fun `homophone suggestion retains original input and changes only after user selection`() {
        startVoice()
        compose.onNodeWithContentDescription("按住说话").performTouchInput { up() }
        compose.runOnIdle { voiceCallbacks!!.onSuggestion("停止计时"); voiceCallbacks!!.onText("停止进食") }
        compose.onNodeWithTag("assistant-input").assertTextContains("停止进食")
        compose.onNodeWithTag("voice-command-suggestion").assertIsDisplayed().performClick()
        compose.onNodeWithTag("assistant-input").assertTextContains("停止计时")
        assertTrue(runBlocking { db.dao().records().isEmpty() })
        compose.waitUntil(10000) { viewModel.assistant.state.value.turns.last().text.contains("当前没有正在进行的计时") }
        compose.onNodeWithText("当前没有正在进行的计时。").performScrollTo().assertIsDisplayed()
    }

    @Test fun `user can create task from empty app and see it in tasks`() {
        compose.onNodeWithContentDescription("计划页面").performClick()
        compose.onNodeWithContentDescription("新建").performClick()
        compose.onNodeWithText("任务名称").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("复习线性代数")) }
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); viewModel.data.value.tasks.any { it.title == "复习线性代数" } }
        compose.onNodeWithText("复习线性代数").assertIsDisplayed()
        assertEquals("复习线性代数", runBlocking { db.dao().tasks().single().title })
        screenshot("tasks")
    }
    @Test fun `week browsing and return to current week remain available`() {
        compose.onNodeWithContentDescription("下一周").performClick()
        compose.onNodeWithText("本周", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("上一周").assertExists()
        compose.onNodeWithText("周一").assertExists()
        screenshot("timeline")
    }
    @Test fun `review clearly distinguishes cumulative effort and union elapsed time`() {
        compose.onNodeWithContentDescription("复盘页面").performClick()
        compose.onNodeWithText("累计记录投入").assertIsDisplayed()
        compose.onNodeWithText("去重时间跨度").assertIsDisplayed()
    }
    @Test fun `editing notes on a short timer record preserves second precision`() {
        val end = viewModel.repository.now().minusSeconds(3600).toEpochMilli() / 60000 * 60000 + 45000
        val start = end - 20000
        runBlocking { viewModel.repository.saveRecord(TimeRecordEntity("short-record", null, start, end, notes = "短计时记录")) }
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); viewModel.data.value.records.any { it.id == "short-record" } }
        compose.onNodeWithText("列表").performClick()
        compose.onNodeWithContentDescription("实际 短计时记录", substring = true).performClick()
        compose.onNodeWithText("修改").performClick()
        compose.onNodeWithText("备注").performScrollTo().performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("新的备注")) }
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); viewModel.data.value.records.any { it.id == "short-record" && it.notes == "新的备注" } }
        val saved = runBlocking { db.dao().record("short-record")!! }
        assertEquals(start, saved.startAt)
        assertEquals(end, saved.endAt)
    }
    @Test fun `import preview cancel changes nothing and confirmation saves once`() {
        fun selectJsonFile() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val uri = Uri.parse("content://test.import/timetable.json")
            Shadows.shadowOf(context.contentResolver).registerInputStream(uri, context.assets.open("course-import-example.json"))
            compose.onNodeWithText("导入 JSON 课表").performScrollTo().performClick()
            compose.runOnIdle {
                var host = renderView.context
                while (host is ContextWrapper && host !is ComponentActivity) host = host.baseContext
                val activity = host as ComponentActivity
                val request = Shadows.shadowOf(activity).nextStartedActivityForResult
                activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent().setData(uri))
            }
        }
        compose.onNodeWithContentDescription("管家页面").performClick()
        compose.onNodeWithText("打开设置").performClick()
        selectJsonFile()
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); compose.onAllNodesWithText("课表导入预览").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("取消").performClick()
        assertTrue(runBlocking { db.dao().occurrences().isEmpty() })
        selectJsonFile()
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); compose.onAllNodesWithText("课表导入预览").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("确认导入").performClick()
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); viewModel.data.value.occurrences.size == 32 }
        assertEquals(1, runBlocking { db.dao().importBatches().size })
        screenshot("timeline-with-sample-courses")
    }
    @Test fun `failed save retains task draft and does not write partial data`() {
        compose.onNodeWithContentDescription("计划页面").performClick()
        compose.onNodeWithContentDescription("新建").performClick()
        compose.onNodeWithText("任务名称").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("保留我的草稿")) }
        compose.onNodeWithText("预计耗时（分钟）").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("0")) }
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); viewModel.error.value != null }
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithText("保留我的草稿").assertExists()
        assertTrue(runBlocking { db.dao().tasks().isEmpty() })
    }
    @Test fun `back during a pending save keeps activity and draft open`() {
        compose.onNodeWithContentDescription("计划页面").performClick()
        compose.onNodeWithContentDescription("新建").performClick()
        compose.onNodeWithText("任务名称").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("保存中的草稿")) }
        val pending = CompletableDeferred<Unit>()
        compose.runOnIdle { viewModel.perform { pending.await() } }
        compose.onNodeWithText("保存中…").assertExists()
        try {
            compose.runOnIdle {
                var context = renderView.context
                while (context is ContextWrapper && context !is ComponentActivity) context = context.baseContext
                val activity = context as ComponentActivity
                activity.onBackPressedDispatcher.onBackPressed()
                assertFalse("保存期间不应退出 Activity", activity.isFinishing)
            }
            compose.onNodeWithText("保存中的草稿").assertExists()
        } finally { pending.complete(Unit) }
    }
    @Test fun `fitness child template pre-fills a task and persists its module association`() {
        compose.onNodeWithContentDescription("新建").performClick()
        compose.onNodeWithText("练胸").performClick()
        compose.onNodeWithText("健身 / 练胸").assertExists()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); viewModel.data.value.tasks.any { it.title == "健身 / 练胸" } }
        val saved = runBlocking { db.dao().tasks().single() }
        val activity = runBlocking { db.dao().activities().single { it.id == saved.activityId } }
        assertEquals("练胸", activity.name)
        assertEquals("健身", runBlocking { db.dao().activities().single { it.id == activity.parentId }.name })
    }
    @Test fun `home no longer reserves a row for a future assistant placeholder`() {
        compose.onNodeWithText("语音助手 · 即将开放").assertDoesNotExist()
        compose.onNodeWithContentDescription("语音助手").assertIsDisplayed()
        compose.onNodeWithText("00:00").assertIsDisplayed()
        compose.onNodeWithText("24:00").assertIsDisplayed()
    }
    @Test fun `continuous date position survives switching app tabs`() {
        compose.onRoot().performTouchInput {
            swipe(Offset(width * .75f, height * .5f), Offset(width * .75f - 30f, height * .5f), 400)
        }
        val before = compose.onNodeWithText("20", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        compose.onNodeWithContentDescription("计划页面").performClick()
        compose.onNodeWithContentDescription("今天页面").performClick()
        val after = compose.onNodeWithText("20", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        assertEquals(before, after, 1f)
    }
    @Test fun `private PDF imports into Room and renders the full day timeline`() {
        val pdfPath = System.getenv("TIME_AGENT_TEST_PDF")
        Assume.assumeTrue("Set TIME_AGENT_TEST_PDF for local private-PDF UI acceptance", !pdfPath.isNullOrBlank())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val draft = File(requireNotNull(pdfPath)).inputStream().use { ScutPdfTimetableParser.parse(PdfTimetableExtractor.extract(context, it)) }
        val term = ImportTerm(draft.termIdHint, requireNotNull(ScutImportPresets.firstMonday(draft.termIdHint)), maxOf(20, draft.suggestedTeachingWeeks), viewModel.zone, "2026–2027 秋")
        val bundle = draft.normalize(term, TimetableFields.periodTimes(ScutImportPresets.WUSHAN_PERIODS))
        runBlocking {
            val preview = viewModel.repository.previewImport(bundle)
            assertEquals(145, preview.newCount)
            viewModel.repository.commitImport(preview, "private-pdf-ui")
        }
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); viewModel.data.value.occurrences.size == 145 }
        compose.onNodeWithText("00:00").assertIsDisplayed()
        compose.onNodeWithText("24:00").assertIsDisplayed()
        screenshot("timeline-personal-pdf")
        compose.onRoot().performTouchInput {
            val focus = Offset(width * .36f, height * .46f)
            down(0, focus - Offset(18f, 0f)); down(1, focus + Offset(18f, 0f))
            repeat(12) { step ->
                val radius = 18f + (step + 1) * 1.7f
                moveTo(0, focus - Offset(radius, 0f)); moveTo(1, focus + Offset(radius, 0f))
            }
            up(0); up(1)
        }
        screenshot("timeline-v03-zoomed-pdf")
        compose.onNodeWithText("本周").performClick()
        val first = viewModel.data.value.timeline(viewModel.now.value).first { it.span.overlaps(viewModel.week.value.window(viewModel.zone)) }
        compose.onNodeWithText("列表").performClick()
        compose.onAllNodesWithContentDescription("课程 ${first.title}", substring = true).onFirst().assertExists()
    }
    @Test fun `assistant selected week follows continuous timeline browsing`() {
        repeat(2) {
            compose.onRoot().performTouchInput { swipe(Offset(width * .9f, height * .5f), Offset(width * .1f, height * .5f), 500) }
        }
        compose.onNodeWithContentDescription("语音助手").performClick()
        compose.onNodeWithTag("assistant-input").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("本周有什么安排")) }
        compose.onNodeWithContentDescription("发送给助手").performClick()
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); viewModel.assistant.state.value.turns.any { it.text.contains("所选周") } }
        compose.onNodeWithContentDescription("展开助手").performClick()
        compose.onNodeWithText("所选周 2026-10-26 至 2026-11-01", substring = true).performScrollTo().assertIsDisplayed()
        assertTrue(runBlocking { db.dao().plans().isEmpty() })
    }
    @Test fun `returning to current week from review resets a continuously browsed timeline`() {
        repeat(2) {
            compose.onRoot().performTouchInput { swipe(Offset(width * .9f, height * .5f), Offset(width * .1f, height * .5f), 500) }
        }
        compose.onNodeWithContentDescription("复盘页面").performClick()
        compose.onNodeWithText("10月26日 — 11月1日").assertIsDisplayed()
        compose.onNodeWithText("本周").performClick()
        compose.onNodeWithText("10月19日 — 10月25日").assertIsDisplayed()
        compose.onNodeWithContentDescription("今天页面").performClick()
        compose.onNodeWithText("19", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("26", useUnmergedTree = true).assertDoesNotExist()
    }
    @Test fun `home places a hold to talk circle at bottom center`() {
        val voice = compose.onNodeWithContentDescription("按住说话").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(root.center.x, voice.center.x, 8f)
        assertTrue(voice.center.y > root.height * .8f)
        assertTrue(voice.width >= 48f && voice.height >= 48f)
    }
    @Test fun `empty timeline tap creates a task and its selected plan atomically`() {
        val x = compose.onNodeWithText("19", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        val y = compose.onNodeWithText("13:00").fetchSemanticsNode().boundsInRoot.center.y + 2f
        compose.onRoot().performTouchInput { click(Offset(x, y)) }
        compose.onNodeWithTag("timeline-pending-add").performClick()
        compose.onNodeWithTag("grid-block-editor").assertExists()
        compose.onNodeWithTag("grid-kind-plan").assertIsSelected()
        compose.onNodeWithText("开始日期 YYYY-MM-DD").assertExists()
        compose.onNodeWithText("13:00").assertExists()
        compose.onNodeWithText("14:00").assertExists()
        compose.onNodeWithText("任务名称").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("时间轴新任务")) }
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); runBlocking { db.dao().plans().size == 1 } }
        val task = runBlocking { db.dao().tasks().single() }
        val plan = runBlocking { db.dao().plans().single() }
        assertEquals("时间轴新任务", task.title)
        assertEquals(task.id, plan.taskId)
        assertEquals("2026-10-19T13:00", Instant.ofEpochMilli(plan.startAt).atZone(viewModel.zone).toLocalDateTime().toString())
        assertEquals("2026-10-19T14:00", Instant.ofEpochMilli(plan.endAt).atZone(viewModel.zone).toLocalDateTime().toString())
    }
    @Test fun `theme switch offers explicit light and dark modes`() {
        compose.onNodeWithContentDescription("切换深浅色").assertIsDisplayed().performClick()
        compose.onNodeWithText("浅色").performClick()
        compose.onNodeWithContentDescription("切换深浅色").assertExists().performClick()
        compose.onNodeWithText("深色").performClick()
        compose.onNodeWithText("00:00").assertIsDisplayed()
        compose.onNodeWithText("24:00").assertIsDisplayed()
    }
    @Test fun `long actual records keep course width and conflicts open a clear pair list`() {
        val monday = Instant.parse("2026-10-18T16:00:00Z").toEpochMilli()
        val hour = 3_600_000L
        runBlocking {
            db.dao().putTerm(AcademicTermEntity("visual", "本周课表", "2026-10-19", 1, "Asia/Shanghai"))
            db.dao().putCourse(CourseEntity("visual-a", "数字信号处理", "合成教师", "五山 · 博学201", "visual", "synthetic-visual", "a", monday, monday))
            db.dao().putCourse(CourseEntity("visual-b", "信息论与编码", "合成教师", "五山 · 博学304", "visual", "synthetic-visual", "b", monday, monday))
            db.dao().putOccurrence(CourseOccurrenceEntity("visual-occ-a", "visual-a", "2026-10-19", monday + 8 * hour, monday + 10 * hour, "visual-occ-a"))
            db.dao().putOccurrence(CourseOccurrenceEntity("visual-occ-b", "visual-b", "2026-10-20", monday + 33 * hour, monday + 35 * hour, "visual-occ-b"))
            viewModel.repository.saveTask(TaskEntity("visual-task", "论文阅读"))
            viewModel.repository.savePlan(ScheduleBlockEntity("visual-plan", "visual-task", monday + 34 * hour, monday + 36 * hour))
            viewModel.repository.saveRecord(TimeRecordEntity("visual-actual", null, monday, monday + 24 * hour, notes = "全天实际记录"), allowFuture = true)
        }
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); viewModel.data.value.occurrences.size == 2 && viewModel.data.value.plans.size == 1 && viewModel.data.value.records.size == 1 }
        val dayWidth = compose.onNodeWithText("20", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x - compose.onNodeWithText("19", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        val courseWidth = compose.onNodeWithContentDescription("课程 数字信号处理", substring = true).fetchSemanticsNode().boundsInRoot.width
        assertTrue("an actual record must not halve the course column", courseWidth > dayWidth * .72f)
        compose.onNodeWithContentDescription("查看时间冲突").assertIsDisplayed()
        screenshot("timeline-v03-light")
        compose.onNodeWithContentDescription("切换深浅色").performClick()
        compose.onNodeWithText("深色").performClick()
        screenshot("timeline-v03-dark")
        compose.onNodeWithContentDescription("查看时间冲突").performClick()
        compose.onNodeWithText("时间冲突 · 1 组").assertIsDisplayed()
        compose.onNodeWithText("重叠 10-20 10:00 — 10-20 11:00").assertIsDisplayed()
        screenshot("timeline-v03-conflicts")
    }
}
