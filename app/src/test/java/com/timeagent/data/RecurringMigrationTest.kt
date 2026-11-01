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
@Config(sdk=[28])
class RecurringMigrationTest {
    @Test fun `v9 projects goals and original actual survive explicit recurring migration`()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();context.deleteDatabase("time-agent.db")
        val schema=JsonParser.parseString(File("schemas/com.timeagent.data.TimeAgentDatabase/9.json").readText()).asJsonObject["database"].asJsonObject
        context.openOrCreateDatabase("time-agent.db",Context.MODE_PRIVATE,null).use { old ->
            schema["entities"].asJsonArray.forEach{e->val t=e.asJsonObject;val name=t["tableName"].asString
                old.execSQL(t["createSql"].asString.replace("\${TABLE_NAME}",name));t.getAsJsonArray("indices")?.forEach{old.execSQL(it.asJsonObject["createSql"].asString.replace("\${TABLE_NAME}",name))}}
            schema["setupQueries"].asJsonArray.forEach{old.execSQL(it.asString)}
            old.execSQL("INSERT INTO projects VALUES('p','旧项目',NULL,'ACTIVE',1,2,3,NULL)")
            old.execSQL("INSERT INTO tasks(id,title,description,estimatedMinutes,priority,status,createdAt,updatedAt,source,version) VALUES('t','旧任务','保留',30,1,'TODO',1,2,'MANUAL',3)")
            old.execSQL("INSERT INTO task_placements VALUES('t','p',NULL,4)")
            old.execSQL("INSERT INTO weekly_goals VALUES('g','p',NULL,'2026-10-26',90,5)")
            old.execSQL("INSERT INTO time_records(id,taskId,startAt,endAt,notes,source,createdAt,updatedAt,isManuallyEdited,version,title) VALUES('r','t',1000,61000,'保留','MANUAL',1,2,1,6,'实际')")
            old.version=9
        }
        val db=TimeAgentDatabase.open(context)
        try {
            assertEquals(13,db.openHelper.readableDatabase.version);assertEquals("旧项目",db.dao().project("p")!!.name)
            assertEquals(4L,db.dao().placement("t")!!.version);assertEquals(90,db.dao().weeklyGoal("g")!!.targetMinutes)
            assertTrue(db.dao().record("r")!!.isManuallyEdited);assertEquals(6L,db.dao().record("r")!!.version)
            assertTrue(db.dao().attachments().isEmpty());assertTrue(db.dao().recurringRules().isEmpty());assertTrue(db.dao().recurringInstances().isEmpty())
        } finally{db.close();context.deleteDatabase("time-agent.db")}
    }
}
