package com.timeagent.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import com.timeagent.assistant.*
import com.timeagent.data.*
import com.timeagent.ui.design.PresentationMode
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Exercises the real shell with synthetic business data and isolated preferences. */
class NavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var database: TimeAgentDatabase
    private lateinit var model: TimeViewModel
    private lateinit var preferences: ThemePreference
    private val now = Instant.parse("2026-11-02T04:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    private var speechCreated = 0
    private var incomingDestination by mutableStateOf<String?>(null)
    private val speechFactory: SpeechInputFactory = { _, callbacks ->
        speechCreated++
        object : SpeechInput {
            override val available = true
            override fun start() { callbacks.onState(SpeechInputStatus.LISTENING, null) }
            override fun stop() { callbacks.onState(SpeechInputStatus.IDLE, null) }
            override fun cancel() { callbacks.onState(SpeechInputStatus.IDLE, null) }
            override fun close() {}
        }
    }

    private fun show(destination: String? = null, timer: Boolean = false) {
        incomingDestination = destination
        val context = compose.activity
        preferences = ThemePreference(context)
        database = TimeAgentDatabase.openInMemory(context)
        val repository = TimeRepository(database, Clock.fixed(now, zone))
        runBlocking {
            repository.initialize()
            repository.saveTask(TaskEntity("navigation-task", "DEVICE_TASK"))
            if (timer) repository.startTimer(null, "navigation-timer")
        }
        model = TimeViewModel(repository)
        compose.setContent {
            DeviceTestFont { CompositionLocalProvider(LocalContext provides context) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides compose.activity) {
                    TimeAgentTheme {
                        TimelineColorEnvironment {
                            TimeAgentContent(model, LocalAppearance.current.theme, speechFactory,
                                null, {}, null, {}, null, {}, incomingDestination, { incomingDestination = null }, preferences::save)
                        }
                    }
                }
            } }
        }
        compose.waitUntil(15_000) { !model.data.value.loading }
        compose.waitForIdle()
    }

    @After fun close() {
        if (::model.isInitialized) model.viewModelScope.cancel()
        if (::database.isInitialized) database.close()
    }

    private fun tab(name: String) { compose.onNodeWithContentDescription("${name}页面").performClick() }
    private fun back() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input keyevent 4").use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
            }
        compose.waitForIdle()
    }

    @Test fun eachTabRestoresItsChildAndRootBackReturnsToToday() {
        show()
        tab("计划")
        compose.onNodeWithTag("plan-tab-3").performClick()
        compose.onNodeWithText("返回").assertExists()
        tab("管家")
        compose.onNodeWithTag("butler-settings").performClick()
        compose.onNodeWithTag("settings-title").assertExists()
        tab("计划")
        compose.onNodeWithText("返回").assertExists()
        back()
        compose.onNodeWithTag("task-list").assertExists()
        back()
        compose.onNodeWithTag("timeline-viewport").assertExists()
        tab("管家")
        compose.onNodeWithTag("settings-title").assertExists()
        tab("管家")
        compose.onNodeWithTag("butler-hub").assertExists()
    }

    @Test fun voicePanelClosesToSamePageAndUsesOneSpeechEngine() {
        show()
        tab("计划")
        val engines = speechCreated
        compose.onNodeWithContentDescription("按住说话").performClick()
        waitUntilDisplayed("voice-sheet")
        compose.onNodeWithTag("voice-sheet").assertIsDisplayed()
        compose.onNodeWithText("关闭面板").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("voice-sheet").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("task-list").assertIsDisplayed()
        assertEquals(engines, speechCreated)
        compose.onNodeWithContentDescription("按住说话").performClick()
        waitUntilDisplayed("voice-sheet")
        compose.onNodeWithTag("voice-sheet").assertIsDisplayed()
        back()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("voice-sheet").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("voice-sheet").assertDoesNotExist()
        compose.onNodeWithTag("task-list").assertExists()
    }

    @Test fun editorDraftSurvivesTabAndAppearanceChangesWhileRealTimerKeepsItsIdentity() {
        show(timer = true)
        val activity = compose.activity
        val record = runBlocking { model.repository.snapshot().records.single { it.endAt == null } }
        tab("计划")
        compose.onNodeWithContentDescription("新建任务").performClick()
        compose.onNodeWithText("任务名称").performTextInput("DEVICE_DRAFT_ONLY")
        back() // keyboard, if it is visible
        if (compose.onAllNodesWithText("保留草稿").fetchSemanticsNodes().isEmpty()) back()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithText("任务名称").assertTextContains("DEVICE_DRAFT_ONLY")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("保留草稿").performClick()
        // A pristine second editor must not erase the retained first editor.
        compose.onNodeWithContentDescription("新建任务").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("继续编辑草稿").assertExists()
        tab("管家")
        compose.runOnIdle {
            preferences.savePresentation(PresentationMode.TOOL)
            preferences.save(ThemeMode.DARK)
            preferences.saveDynamicColor(true)
        }
        tab("计划")
        compose.onNodeWithText("继续编辑草稿").performClick()
        compose.onNodeWithText("任务名称").assertTextContains("DEVICE_DRAFT_ONLY")
        assertSame(activity, compose.activity)
        val after = runBlocking { model.repository.snapshot() }
        assertEquals(record, after.records.single { it.endAt == null })
        assertFalse(after.tasks.any { it.title == "DEVICE_DRAFT_ONLY" })
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("丢弃修改").performClick()
        compose.onNodeWithTag("task-list").assertExists()
        compose.onNodeWithText("继续编辑草稿").assertDoesNotExist()
    }

    @Test fun deepLinkBackReturnsToOwningRootAndSettingsLeavesAreReachable() {
        show(destination = "appearance")
        compose.onNodeWithText("呈现模式").assertExists()
        back()
        compose.onNodeWithTag("butler-hub").assertExists()
        compose.onNodeWithTag("butler-settings").performClick()
        compose.onNodeWithTag("settings-link-notification-settings").performScrollTo().performClick()
        compose.onNodeWithText("打开系统通知设置").assertExists()
        back()
        compose.onNodeWithTag("settings-title").assertExists()
    }

    @Test fun notificationReturnResetsTheSavedButlerChildToItsRoot() {
        show(destination = "notification-settings")
        compose.onNodeWithText("打开系统通知设置").assertExists()
        tab("计划")
        compose.runOnIdle { incomingDestination = "butler" }
        compose.onNodeWithTag("butler-hub").assertExists()
        compose.onNodeWithText("打开系统通知设置").assertDoesNotExist()
    }

    @Test fun tabReselectScrollsRootToTopWithoutDroppingIt() {
        show()
        tab("管家")
        compose.onNodeWithTag("butler-link-organization").performScrollTo()
        tab("管家")
        compose.onNodeWithTag("butler-mode-tool").assertIsDisplayed()
        compose.onNodeWithTag("butler-link-organization").performScrollTo()
        val before = compose.onNodeWithTag("butler-link-organization").fetchSemanticsNode().boundsInRoot.top
        tab("计划")
        tab("管家")
        compose.onNodeWithTag("butler-link-organization").assertIsDisplayed()
        val after = compose.onNodeWithTag("butler-link-organization").fetchSemanticsNode().boundsInRoot.top
        assertEquals(before, after, 2f)
    }

    @Test fun extractionAndReplyKeepUnsubmittedTextAcrossTabsAndChildPages() {
        show(destination = "extract")
        compose.onNodeWithText("聊天文字（最多20000字）").performTextInput("DEVICE_EXTRACTION_DRAFT")
        hideKeyboard()
        tab("计划")
        tab("管家")
        compose.onNodeWithText("聊天文字（最多20000字）").assertTextContains("DEVICE_EXTRACTION_DRAFT")
        compose.onNodeWithText("结合空档起草回复").performScrollTo().performClick()
        compose.onNodeWithTag("reply-source").performTextReplacement("DEVICE_REPLY_DRAFT")
        hideKeyboard()
        tab("复盘")
        tab("管家")
        compose.onNodeWithTag("reply-source").assertTextContains("DEVICE_REPLY_DRAFT")
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("聊天文字（最多20000字）").assertTextContains("DEVICE_EXTRACTION_DRAFT")
    }

    @Test fun noteEditorHasAllThreeExitChoicesAndKeepsItsDraft() {
        show(destination = "tasks")
        compose.onNodeWithText("DEVICE_TASK").performScrollTo().performClick()
        compose.onNodeWithText("记笔记").performClick()
        compose.onNodeWithTag("note-body").performTextInput("DEVICE_NOTE_DRAFT")
        compose.onNodeWithTag("note-back").performClick()
        compose.onNodeWithText("继续编辑").assertExists()
        compose.onNodeWithText("丢弃修改").assertExists()
        compose.onNodeWithText("保留草稿").performClick()
        tab("管家")
        tab("计划")
        compose.onNodeWithText("继续编辑笔记草稿 · DEVICE_TASK").performClick()
        compose.onNodeWithTag("note-body").assertTextContains("DEVICE_NOTE_DRAFT")
        assertTrue(runBlocking { model.repository.snapshot().notes.isEmpty() })
        compose.onNodeWithTag("note-back").performClick()
        compose.onNodeWithText("丢弃修改").performClick()
    }

    private fun waitUntilDisplayed(tag: String) {
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess
        }
    }

    private fun hideKeyboard() {
        compose.runOnUiThread {
            compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
    }
}
