package com.timeagent.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import com.timeagent.suggestions.SuggestionEngine
import com.timeagent.suggestions.toEntity
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DatabaseMigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun clean() { context.deleteDatabase("time-agent.db") }
    @After fun cleanup() { context.deleteDatabase("time-agent.db") }
    @Test fun `v6 upgrade retains old suggestions and generates revision bound actionable cards`() = runBlocking {
        val schema = JsonParser.parseString(File("schemas/com.timeagent.data.TimeAgentDatabase/6.json").readText()).asJsonObject["database"].asJsonObject
        context.openOrCreateDatabase("time-agent.db", Context.MODE_PRIVATE, null).use { old ->
            schema["entities"].asJsonArray.forEach { entry ->
                val table = entry.asJsonObject; val name = table["tableName"].asString
                old.execSQL(table["createSql"].asString.replace("\${TABLE_NAME}", name))
                table.getAsJsonArray("indices")?.forEach { old.execSQL(it.asJsonObject["createSql"].asString.replace("\${TABLE_NAME}", name)) }
            }
            schema["setupQueries"].asJsonArray.forEach { old.execSQL(it.asString) }
            old.execSQL("INSERT INTO tasks VALUES ('v6-task','已有任务','保留描述',30,1000,1,'TODO',NULL,1000,1000,NULL,'MANUAL',NULL,0,NULL)")
            old.execSQL("INSERT INTO agent_suggestions VALUES ('legacy','OVERDUE_TASK','旧卡片','只有文字','overdue:v6-task:1000','PENDING',1000,NULL)")
            old.version = 6
        }
        val upgraded = TimeAgentDatabase.open(context)
        try {
            val legacy = upgraded.dao().suggestion("legacy")!!
            assertEquals("EXPIRED", legacy.status)
            assertNull(legacy.taskId)
            assertEquals("已有任务", upgraded.dao().task("v6-task")!!.title)
            val repo = TimeRepository(upgraded, Clock.fixed(Instant.parse("2026-10-26T02:00:00Z"), ZoneId.of("Asia/Shanghai")))
            val next = SuggestionEngine.generate(repo.snapshot(), repo.zone, repo.now())
            repo.recordSuggestions(next.mapIndexed { i, candidate -> candidate.toEntity("typed-$i", repo.now().toEpochMilli()) })
            assertTrue(upgraded.dao().suggestions().all { it.taskId == "v6-task" && it.taskVersion == 0L })
            assertEquals(3, upgraded.dao().allSuggestions().size)
        } finally { upgraded.close() }
    }
    @Test fun `upgrade actual exported v1 schema preserves task plan running timer and operation history`() = runBlocking {
        val schema = JsonParser.parseString(File("schemas/com.timeagent.data.TimeAgentDatabase/1.json").readText()).asJsonObject["database"].asJsonObject
        context.openOrCreateDatabase("time-agent.db", Context.MODE_PRIVATE, null).use { old ->
            schema["entities"].asJsonArray.forEach { entry ->
                val table = entry.asJsonObject
                val name = table["tableName"].asString
                old.execSQL(table["createSql"].asString.replace("\${TABLE_NAME}", name))
                table.getAsJsonArray("indices")?.forEach { index -> old.execSQL(index.asJsonObject["createSql"].asString.replace("\${TABLE_NAME}", name)) }
            }
            schema["setupQueries"].asJsonArray.forEach { old.execSQL(it.asString) }
            old.execSQL("INSERT INTO tasks VALUES ('old-task','旧任务','备注',30,NULL,1,'TODO',NULL,1000,1000,NULL,'MANUAL',NULL,0)")
            old.execSQL("INSERT INTO schedule_blocks VALUES ('old-plan','old-task',1000,2000,'SCHEDULED',0,1000,1000,0)")
            old.execSQL("INSERT INTO time_records VALUES ('running','old-task',1000,NULL,NULL,'计时备注','TIMER',1000,1000,0,0)")
            old.execSQL("INSERT INTO operation_history VALUES (1,'保存任务','TASK','old-task',NULL,NULL,1000,0)")
            old.version = 1
        }
        val upgraded = TimeAgentDatabase.open(context)
        try {
            assertEquals("旧任务", upgraded.dao().task("old-task")!!.title)
            assertNull(upgraded.dao().task("old-task")!!.activityId)
            assertEquals("old-plan", upgraded.dao().plans().single().id)
            assertEquals(1000L, upgraded.dao().runningTimer()!!.startAt)
            assertEquals("计时备注", upgraded.dao().runningTimer()!!.notes)
            assertEquals(1, upgraded.dao().history().size)
            assertTrue(upgraded.dao().activities().isEmpty())
            assertEquals(13, upgraded.openHelper.readableDatabase.version)
            assertEquals("", upgraded.dao().runningTimer()!!.title)
            assertFalse(upgraded.dao().history().single().redoable)
            assertTrue(upgraded.dao().reminders().isEmpty())
            assertTrue(upgraded.dao().ideas().isEmpty())
            assertTrue(upgraded.dao().notes().isEmpty())
            assertTrue(upgraded.dao().healthItems().isEmpty())
        } finally { upgraded.close() }
    }

    @Test fun `upgrade actual exported v4 schema preserves records and starts empty health sync`() = runBlocking {
        val schema = JsonParser.parseString(File("schemas/com.timeagent.data.TimeAgentDatabase/4.json").readText()).asJsonObject["database"].asJsonObject
        context.openOrCreateDatabase("time-agent.db", Context.MODE_PRIVATE, null).use { old ->
            schema["entities"].asJsonArray.forEach { entry ->
                val table = entry.asJsonObject
                val name = table["tableName"].asString
                old.execSQL(table["createSql"].asString.replace("\${TABLE_NAME}", name))
                table.getAsJsonArray("indices")?.forEach { index -> old.execSQL(index.asJsonObject["createSql"].asString.replace("\${TABLE_NAME}", name)) }
            }
            schema["setupQueries"].asJsonArray.forEach { old.execSQL(it.asString) }
            old.execSQL("INSERT INTO tasks VALUES ('v4-task','旧任务','备注',30,NULL,1,'TODO',NULL,1000,1000,NULL,'MANUAL',NULL,0,NULL)")
            old.execSQL("INSERT INTO time_records VALUES ('v4-record','v4-task',1000,2000,NULL,'备注','MANUAL',1000,1000,0,0,'睡眠')")
            old.version = 4
        }
        val upgraded = TimeAgentDatabase.open(context)
        try {
            assertEquals(13, upgraded.openHelper.readableDatabase.version)
            assertEquals("旧任务", upgraded.dao().task("v4-task")!!.title)
            val record = upgraded.dao().record("v4-record")!!
            assertEquals("睡眠", record.title)
            assertEquals(2000L - 1000L, record.endAt!! - record.startAt)
            assertTrue(upgraded.dao().healthItems().isEmpty())
        } finally { upgraded.close() }
    }
}
