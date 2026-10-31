package com.timeagent.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.timeagent.core.RecurringPattern
import com.timeagent.data.*
import com.timeagent.recurring.RecurringService
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeadlineEntryTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var db:TimeAgentDatabase
    private lateinit var model:TimeViewModel
    private lateinit var view:View
    @Before fun setup(){db=TimeAgentDatabase.openInMemory(ApplicationProvider.getApplicationContext<Context>());model=TimeViewModel(TimeRepository(db,Clock.fixed(Instant.parse("2026-10-27T02:00:00Z"),ZoneId.of("Asia/Shanghai"))))}
    @After fun cleanup(){
        if(this::view.isInitialized) compose.runOnIdle {
            var host=view.context
            while(host is ContextWrapper && host !is ComponentActivity) host=host.baseContext
            (host as ComponentActivity).setContentView(android.widget.FrameLayout(host))
        }
        model.viewModelScope.cancel()
        idle()
        runBlocking { PersonalDataGate.access { db.close() } }
    }
    private fun idle()=Shadows.shadowOf(Looper.getMainLooper()).idle()
    private fun open(){compose.setContent{view=LocalView.current;TimeAgentApp(model,speechFactory={_,callbacks->TestSpeechInput(callbacks)})};compose.onNodeWithContentDescription("计划页面").performClick();compose.onNodeWithTag("plan-deadlines-action").performScrollTo().performClick()}
    private fun shot(name:String)=compose.runOnIdle{val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap));val out=File("build/outputs/butler-next-previews/$name.png");out.parentFile!!.mkdirs();out.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    @Test fun `user creates an explicit point and handles it without fabricating duration`() {
        open();compose.onNodeWithTag("deadline-create").performScrollTo().performClick()
        compose.onNodeWithTag("deadline-title").performTextInput("归还资料")
        compose.onNodeWithTag("deadline-date").performScrollTo().performTextReplacement("2026-10-28")
        compose.onNodeWithTag("deadline-time").performScrollTo().performTextReplacement("09:00")
        compose.onNodeWithTag("deadline-lead").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("deadline-save").performClick()
        compose.waitUntil(15000){idle();(model.data.value.recurringInstances.size==1 || model.error.value!=null)&&!model.busy.value}
        assertNull(model.error.value);val item=model.data.value.recurringInstances.single()
        assertEquals("DEADLINE",model.data.value.recurringRules.single().domain);assertTrue(runBlocking{db.dao().plans().isEmpty()});assertEquals(1,runBlocking{db.dao().reminders().size})
        compose.onNodeWithTag("deadline-handle-${item.id}").performScrollTo().performClick()
        compose.waitUntil(10000){idle();model.data.value.tasks.single().status=="COMPLETED"}
        assertTrue(runBlocking{db.dao().records().isEmpty()});assertFalse(runBlocking{db.dao().reminders().single().enabled})
    }
    @Config(qualifiers="w411dp-h891dp")
    @Test fun `deadline preview shows original date and true pending reminder state`() {
        val p=RecurringPattern("life","MONTHLY","2026-10-31",minuteOfDay=540,leadMinutes=1440,untilDate="2026-12-31")
        runBlocking{RecurringService(model.repository).saveRule(RecurringRuleEntity("life","归还资料演示",Gson().toJson(p),domain="DEADLINE",category="归还",reference="借用记录 DEMO-01"),null,LocalDate.parse("2026-10-27"),"seed")}
        open();compose.waitUntil(10000){idle();model.data.value.recurringInstances.size==3}
        compose.waitUntil(10000){idle();compose.onAllNodesWithText("归还资料演示",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodesWithText("归还资料演示",useUnmergedTree=true).onFirst().assertExists()
        shot("deadlines")
    }
}

