package com.timeagent.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScheduleHistoryTest {
    @Test fun `pre v13 plan history still supports undo and redo`() = runBlocking {
        val db = TimeAgentDatabase.openInMemory(ApplicationProvider.getApplicationContext<Context>())
        try {
            val repo = TimeRepository(db, Clock.fixed(Instant.ofEpochMilli(10000), ZoneId.systemDefault()))
            db.dao().putTask(TaskEntity("t", "Old task"))
            val before = ScheduleBlockEntity("p", "t", 1000, 2000)
            val after = before.copy(startAt = 3000, endAt = 4000, version = 1)
            db.dao().putPlan(after)
            fun oldJson(plan: ScheduleBlockEntity) = Gson().toJsonTree(plan).asJsonObject.apply {
                listOf("title", "kind", "allDayStartDate", "allDayEndDate", "timezoneId", "notes",
                    "colorOverride", "source", "sourceRef", "deletedAt").forEach(::remove)
            }.toString()
            db.dao().putAction(OperationEntity(actionType = "Old move", entityType = "PLAN", entityId = "p",
                beforeSnapshot = oldJson(before), afterSnapshot = oldJson(after), createdAt = 5000))
            repo.undoLastAction()
            val restored = db.dao().plan("p")!!
            assertEquals(1000L, restored.startAt)
            assertEquals("", restored.title)
            assertEquals("TIMED", restored.kind)
            assertEquals("MANUAL", restored.source)
            assertEquals(ZoneId.systemDefault().id, restored.timezoneId)
            repo.redoLastAction()
            assertEquals(3000L, db.dao().plan("p")!!.startAt)
        } finally { db.close() }
    }
}
