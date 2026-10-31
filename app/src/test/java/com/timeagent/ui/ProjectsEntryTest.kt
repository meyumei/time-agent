package com.timeagent.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.*
import org.junit.Assert.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectsEntryTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: TimeAgentDatabase
    private lateinit var model: TimeViewModel
    @Before fun setup() { db = TimeAgentDatabase.openInMemory(ApplicationProvider.getApplicationContext<Context>()); model = TimeViewModel(TimeRepository(db)) }
    @After fun cleanup() { model.viewModelScope.cancel(); db.close() }
    @Test fun `task page opens projects without creating assignments automatically`() {
        compose.setContent { TimeAgentApp(model, speechFactory = { _, callbacks -> TestSpeechInput(callbacks) }) }
        compose.onNodeWithContentDescription("计划页面").performClick()
        compose.onNodeWithTag("plan-projects-action").performScrollTo().performClick()
        compose.onNodeWithText("新建项目").assertExists()
    }
    private fun shot(view:View,name:String) = compose.runOnIdle {
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        val out=File("build/outputs/butler-next-previews/$name.png");out.parentFile!!.mkdirs()
        out.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun `project preview opens exact child task and review stats open original actual`() {
        val now=Instant.parse("2026-10-27T02:00:00Z"); val zone=ZoneId.of("Asia/Shanghai")
        val data=LocalSnapshot(loading=false,projects=listOf(ProjectEntity("p","课设演示",now.plusSeconds(86400).toEpochMilli())),
            tasks=listOf(TaskEntity("a","完成课设"),TaskEntity("b","子报告")),placements=listOf(TaskPlacementEntity("a","p"),TaskPlacementEntity("b","p","a")),
            plans=listOf(ScheduleBlockEntity("plan","b",now.toEpochMilli(),now.plusSeconds(3600).toEpochMilli())),
            records=listOf(TimeRecordEntity("r","b",now.minusSeconds(1800).toEpochMilli(),now.toEpochMilli(),title="报告初稿")))
        var opened:String?=null;lateinit var view:View
        compose.setContent {view=LocalView.current;TimeAgentTheme {ProjectScreen(data,model,now,zone,{opened=it.id},{})}}
        compose.onNodeWithTag("project-p").performClick()
        compose.onNodeWithText("└ 子报告").performScrollTo().assertExists()
        shot(view,"projects")
        compose.onNodeWithText("└ 子报告").performClick();assertEquals("b",opened)
    }
    @Test fun `each review number has original record navigation`() {
        val now=Instant.parse("2026-10-27T02:00:00Z");val zone=ZoneId.of("Asia/Shanghai")
        val data=LocalSnapshot(loading=false,projects=listOf(ProjectEntity("p","课设演示")),tasks=listOf(TaskEntity("a","报告")),placements=listOf(TaskPlacementEntity("a","p")),records=listOf(TimeRecordEntity("r","a",now.minusSeconds(1800).toEpochMilli(),now.toEpochMilli())))
        var opened:Pair<String,String>?=null;lateinit var view:View
        compose.setContent {view=LocalView.current;TimeAgentTheme {Surface(Modifier.fillMaxSize()) {Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {ProjectReviewSection(data,LocalDate.parse("2026-10-26"),now,zone,{type,id->opened=type to id},{})}}}}
        shot(view,"project-review")
        compose.onNodeWithText("累计实际投入 30 分钟").performScrollTo().performClick()
        compose.onNodeWithTag("project-source-r").performClick();assertEquals("RECORD" to "r",opened)
    }
}

