package com.timeagent.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.timeagent.core.RecurringPattern
import com.timeagent.data.*
import com.timeagent.workout.data.WorkoutDatabase
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class RecurringBackupTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private lateinit var main:TimeAgentDatabase
    private lateinit var workout:WorkoutDatabase
    private lateinit var backup:PersonalBackup
    private val gson=Gson()
    @Before fun setup(){
        context.deleteDatabase("recurrence-test.db");context.deleteDatabase("recurrence-workout.db")
        main=Room.databaseBuilder(context,TimeAgentDatabase::class.java,"recurrence-test.db").allowMainThreadQueries().build()
        workout=Room.databaseBuilder(context,WorkoutDatabase::class.java,"recurrence-workout.db").allowMainThreadQueries().build()
        backup=PersonalBackup(context,main,workout,File(context.noBackupFilesDir,"recurrence-rollback.json"));File(context.filesDir,"workout-custom-exercises.json").writeText("[]")
    }
    @After fun cleanup(){main.close();workout.close();context.deleteDatabase("recurrence-test.db");context.deleteDatabase("recurrence-workout.db");File(context.noBackupFilesDir,"recurrence-rollback.json").delete()}
    private suspend fun seed(){
        val rule=RecurringRuleEntity("r","读书",gson.toJson(RecurringPattern("r","DAILY","2026-10-26")))
        main.dao().putRecurringRule(rule);main.dao().putTask(TaskEntity("recurring-task:r:d:2026-10-27","读书"))
        main.dao().putRecurringInstance(RecurringInstanceEntity("r:d:2026-10-27","r","recurring-task:r:d:2026-10-27","2026-10-27",ruleSnapshot=gson.toJson(rule),ruleVersion=0,taskVersion=0))
    }
    private fun payload(bytes:ByteArray):BackupPayload=gson.fromJson(com.google.gson.JsonParser.parseString(String(bytes)).asJsonObject["payload"].asString,BackupPayload::class.java)
    private fun encode(p:BackupPayload):ByteArray{val raw=gson.toJson(p);val hash=MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString(""){"%02x".format(it)};return gson.toJson(mapOf("format" to 1,"sha256" to hash,"payload" to raw)).toByteArray()}
    @Test fun `rules instance snapshots and suppression ledger roundtrip while v9 stays empty`()=runBlocking<Unit>{
        seed();main.dao().putRevision(EntityRevision("RECURRING_INSTANCE:undone",4))
        val bytes=backup.create();assertEquals(1,backup.preview(bytes).mainCounts["recurring_instances"])
        backup.restore(bytes);assertEquals("r:d:2026-10-27",main.dao().recurringInstances().single().id);assertEquals(4L,main.dao().revision("RECURRING_INSTANCE:undone")!!.revision);assertTrue(main.dao().records().isEmpty())
        val p=payload(bytes);val legacy=p.copy(main=BackupDatabase(9,p.main.tables.filterKeys{it in BackupTables.mainForVersion(9)}))
        backup.restore(encode(legacy));assertTrue(main.dao().recurringInstances().isEmpty());assertTrue(main.dao().recurringRules().isEmpty());assertEquals(1,main.dao().tasks().size)
    }
    @Test fun `malformed recurring pattern is rejected by export and preflight without modifying tasks`()=runBlocking<Unit>{
        seed();val rule=main.dao().recurringRules().single();main.dao().putRecurringRule(rule.copy(patternJson="{\"id\":\"r\"}"))
        val failure=assertThrows(IllegalArgumentException::class.java){runBlocking{backup.create()}}
        assertTrue(failure.message.orEmpty().contains("周期规则"))
        assertEquals(1,main.dao().tasks().size)
    }
}
