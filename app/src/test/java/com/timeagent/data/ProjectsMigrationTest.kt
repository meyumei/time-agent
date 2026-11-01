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
class ProjectsMigrationTest {
    @Test fun `actual exported v8 migrates preserving running actual reminders history and no guessed membership`() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("time-agent.db")
        val schema=JsonParser.parseString(File("schemas/com.timeagent.data.TimeAgentDatabase/8.json").readText()).asJsonObject["database"].asJsonObject
        context.openOrCreateDatabase("time-agent.db",Context.MODE_PRIVATE,null).use { old ->
            schema["entities"].asJsonArray.forEach { entry -> val table=entry.asJsonObject;val name=table["tableName"].asString
                old.execSQL(table["createSql"].asString.replace("\${TABLE_NAME}",name))
                table.getAsJsonArray("indices")?.forEach{old.execSQL(it.asJsonObject["createSql"].asString.replace("\${TABLE_NAME}",name))}
            }
            schema["setupQueries"].asJsonArray.forEach{old.execSQL(it.asString)}
            old.execSQL("INSERT INTO tasks(id,title,description,estimatedMinutes,priority,status,createdAt,updatedAt,source,version) VALUES('old','保留','备注',30,1,'TODO',10,10,'MANUAL',7)")
            old.execSQL("INSERT INTO time_records(id,taskId,startAt,notes,source,createdAt,updatedAt,isManuallyEdited,version,title) VALUES('running','old',10,'手工','TIMER',10,10,1,8,'计时')")
            old.execSQL("INSERT INTO reminders(id,title,remindAt,taskId,enabled,createdAt,updatedAt,version) VALUES('rem','提醒',9999999999999,'old',1,10,10,3)")
            old.version=8
        }
        val db=TimeAgentDatabase.open(context)
        try {
            assertEquals(13,db.openHelper.readableDatabase.version)
            assertEquals(7L,db.dao().task("old")!!.version);assertEquals("备注",db.dao().task("old")!!.description)
            assertEquals("running",db.dao().runningTimer()!!.id);assertTrue(db.dao().runningTimer()!!.isManuallyEdited)
            assertEquals("old",db.dao().reminder("rem")!!.taskId)
            assertTrue(db.dao().projects().isEmpty());assertTrue(db.dao().placements().isEmpty());assertTrue(db.dao().weeklyGoals().isEmpty())
        } finally {db.close();context.deleteDatabase("time-agent.db")}
    }
}
