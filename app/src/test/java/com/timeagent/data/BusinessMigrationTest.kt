package com.timeagent.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BusinessMigrationTest {
    @Test fun `v11 upgrade keeps records classifications and adds empty ledger tables`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("time-agent.db")
        val schema = JsonParser.parseString(File("schemas/com.timeagent.data.TimeAgentDatabase/11.json").readText())
            .asJsonObject["database"].asJsonObject
        context.openOrCreateDatabase("time-agent.db", Context.MODE_PRIVATE, null).use { old ->
            schema["entities"].asJsonArray.forEach { entry ->
                val table = entry.asJsonObject
                val name = table["tableName"].asString
                old.execSQL(table["createSql"].asString.replace("\${TABLE_NAME}", name))
                table.getAsJsonArray("indices")?.forEach { old.execSQL(it.asJsonObject["createSql"].asString.replace("\${TABLE_NAME}", name)) }
            }
            schema["setupQueries"].asJsonArray.forEach { old.execSQL(it.asString) }
            old.execSQL("INSERT INTO categories VALUES('synthetic-category','Synthetic',1,0,0)")
            old.execSQL("INSERT INTO tags VALUES('synthetic-tag','Synthetic tag',1)")
            old.execSQL("INSERT INTO time_records(id,taskId,startAt,endAt,categoryId,notes,source,createdAt,updatedAt,isManuallyEdited,version,title) VALUES('synthetic-record',NULL,1000,2000,'synthetic-category','Keep notes','MANUAL',10,10,1,7,'Synthetic record')")
            old.execSQL("INSERT INTO record_tags VALUES('synthetic-record','synthetic-tag')")
            old.version = 11
        }
        val db = TimeAgentDatabase.open(context)
        try {
            val sql = db.openHelper.readableDatabase
            assertEquals(13, sql.version)
            val record = db.dao().record("synthetic-record")!!
            assertEquals("Keep notes", record.notes)
            assertEquals(7L, record.version)
            assertEquals("synthetic-category", record.categoryId)
            sql.query("SELECT count(*) FROM record_tags").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            for (table in listOf("ledger_accounts", "ledger_categories", "payment_captures", "ledger_transactions")) {
                sql.query("SELECT count(*) FROM $table").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            }
        } finally {
            db.close()
            context.deleteDatabase("time-agent.db")
        }
    }
}
