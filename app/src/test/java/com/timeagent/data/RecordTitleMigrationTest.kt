package com.timeagent.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.timeagent.ui.timeline
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecordTitleMigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun clean() { context.deleteDatabase("time-agent.db") }
    @After fun cleanup() { context.deleteDatabase("time-agent.db") }

    @Test fun `real v3 record and old history survive upgrade undo and redo without inventing a title`() = runBlocking {
        val schema = JsonParser.parseString(File("schemas/com.timeagent.data.TimeAgentDatabase/3.json").readText()).asJsonObject["database"].asJsonObject
        val oldSnapshot = Gson().toJsonTree(TimeRecordEntity("old-record", null, 1000, 2000, notes = "旧记录", createdAt = 1000, updatedAt = 1000)).asJsonObject
            .apply { remove("title") }.toString()
        context.openOrCreateDatabase("time-agent.db", Context.MODE_PRIVATE, null).use { old ->
            schema["entities"].asJsonArray.forEach { entry ->
                val table = entry.asJsonObject
                val name = table["tableName"].asString
                old.execSQL(table["createSql"].asString.replace("\${TABLE_NAME}", name))
                table.getAsJsonArray("indices")?.forEach { old.execSQL(it.asJsonObject["createSql"].asString.replace("\${TABLE_NAME}", name)) }
            }
            schema["setupQueries"].asJsonArray.forEach { old.execSQL(it.asString) }
            old.execSQL("INSERT INTO time_records VALUES ('old-record',NULL,1000,2000,NULL,'旧记录','MANUAL',1000,1000,0,0)")
            old.execSQL("INSERT INTO operation_history VALUES (1,'保存实际记录','RECORD','old-record',NULL,?,1000,0,0)", arrayOf(oldSnapshot))
            old.version = 3
        }
        val upgraded = TimeAgentDatabase.open(context)
        try {
            val repository = TimeRepository(upgraded, Clock.fixed(Instant.parse("2026-10-23T02:00:00Z"), ZoneId.of("Asia/Shanghai")))
            assertEquals("", upgraded.dao().record("old-record")!!.title)
            assertEquals("旧记录", repository.snapshot().timeline(repository.now()).single().title)
            repository.undoLastAction()
            assertNull(upgraded.dao().record("old-record"))
            repository.redoLastAction()
            assertEquals("", upgraded.dao().record("old-record")!!.title)
            assertEquals("旧记录", upgraded.dao().record("old-record")!!.notes)
        } finally { upgraded.close() }
    }
}
