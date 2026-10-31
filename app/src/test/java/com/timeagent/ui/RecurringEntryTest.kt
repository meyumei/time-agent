package com.timeagent.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.*
import com.timeagent.core.RecurringPattern
import com.timeagent.recurring.RecurringService
import com.google.gson.Gson
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.junit.Assert.*
import java.time.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecurringEntryTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var db:TimeAgentDatabase
    private lateinit var model:TimeViewModel
    @Before fun setup(){db=TimeAgentDatabase.openInMemory(ApplicationProvider.getApplicationContext<Context>());model=TimeViewModel(TimeRepository(db,Clock.fixed(Instant.parse("2026-10-27T02:00:00Z"),ZoneId.of("Asia/Shanghai"))))}
    @After fun cleanup(){model.viewModelScope.cancel();db.close()}
    @Test fun `recurring task entry is available from tasks`() {
        compose.setContent{TimeAgentApp(model,speechFactory={_,callbacks->TestSpeechInput(callbacks)})}
        compose.onNodeWithContentDescription("计划页面").performClick()
        compose.onNodeWithTag("plan-habits-action").performScrollTo().performClick()
        compose.onNodeWithTag("recurring-create").assertExists()
    }
    private fun idle()=Shadows.shadowOf(Looper.getMainLooper()).idle()
    private fun shot(view:View,name:String)=compose.runOnIdle {
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        val out=File("build/outputs/butler-next-previews/$name.png");out.parentFile!!.mkdirs();out.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun `weekly goal preview completes once with no fixed slots or fabricated actual`() {
        runBlocking{RecurringService(model.repository).saveRule(RecurringRuleEntity("r","每周阅读",Gson().toJson(RecurringPattern("r","WEEKLY_COUNT","2026-10-26",weeklyCount=3,untilDate="2026-11-01"))),null,LocalDate.parse("2026-10-26"),"seed")}
        lateinit var view:View
        compose.setContent {view=LocalView.current;val data by model.data.collectAsState();TimeAgentTheme{RecurringScreen(data,model,{},{})}}
        compose.waitUntil(10000){idle();model.data.value.recurringInstances.size==3}
        compose.onNodeWithTag("recurring-toggle-r").performClick()
        compose.onNodeWithText("固定时段：无固定时刻").assertExists()
        val item=runBlocking{model.repository.snapshot().recurringInstances.first()}
        compose.onNodeWithTag("recurring-complete-${item.id}").performScrollTo().performClick()
        compose.waitUntil(10000){idle();model.data.value.tasks.count{it.status=="COMPLETED"}==1}
        assertTrue(runBlocking{db.dao().records().isEmpty()});assertTrue(runBlocking{db.dao().plans().isEmpty()})
        shot(view,"recurring-weekly")
    }
    // Same dialog fixture as WorkoutDialogTest: width-qualified Robolectric text fields can loop.
    @Config(qualifiers="h891dp")
    @Test fun `new weekly frequency form saves count while hiding fixed time`() {
        compose.setContent{val data by model.data.collectAsState();TimeAgentTheme{RecurringScreen(data,model,{},{})}}
        compose.onNodeWithTag("recurring-create").performClick()
        compose.onNodeWithTag("recurring-title").performTextInput("每周两次")
        compose.onNodeWithText("每周频次").performScrollTo().performClick()
        compose.onNodeWithTag("recurring-count").performScrollTo().performTextReplacement("2")
        compose.onNodeWithTag("recurring-save").performScrollTo().performClick()
        compose.waitUntil(10000){idle();(model.data.value.recurringRules.size==1 || model.error.value!=null) && !model.busy.value}
        assertNull(model.error.value)
        val saved=Gson().fromJson(model.data.value.recurringRules.single().patternJson,RecurringPattern::class.java)
        assertEquals("WEEKLY_COUNT",saved.frequency);assertEquals(2,saved.weeklyCount);assertNull(saved.minuteOfDay)
        assertTrue(runBlocking{db.dao().records().isEmpty()});assertTrue(runBlocking{db.dao().plans().isEmpty()})
    }
}
