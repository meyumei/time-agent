package com.timeagent.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
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
class ProjectsBackupTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var main: TimeAgentDatabase
    private lateinit var workout: WorkoutDatabase
    private lateinit var backup: PersonalBackup
    private val gson=Gson()
    private val added=setOf("projects","task_placements","weekly_goals")
    @Before fun setup() {
        context.deleteDatabase("projects-test.db"); context.deleteDatabase("projects-workout-test.db")
        main=Room.databaseBuilder(context,TimeAgentDatabase::class.java,"projects-test.db").allowMainThreadQueries().build()
        workout=Room.databaseBuilder(context,WorkoutDatabase::class.java,"projects-workout-test.db").allowMainThreadQueries().build()
        backup=PersonalBackup(context,main,workout,File(context.noBackupFilesDir,"projects-rollback.json"))
        File(context.filesDir,"workout-custom-exercises.json").writeText("[]")
    }
    @After fun cleanup(){main.close();workout.close();context.deleteDatabase("projects-test.db");context.deleteDatabase("projects-workout-test.db");File(context.noBackupFilesDir,"projects-rollback.json").delete()}
    private fun encode(p:BackupPayload):ByteArray {
        val raw=gson.toJson(p); val hash=MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString(""){"%02x".format(it)}
        return gson.toJson(mapOf("format" to 1,"sha256" to hash,"payload" to raw)).toByteArray()
    }
    private fun payload(bytes:ByteArray):BackupPayload = gson.fromJson(com.google.gson.JsonParser.parseString(String(bytes)).asJsonObject["payload"].asString,BackupPayload::class.java)
    @Test fun `project relations and goals roundtrip and legacy v7 v8 stay explicitly unassigned`() = runBlocking<Unit> {
        main.dao().putTask(TaskEntity("old","旧任务")); main.dao().putProject(ProjectEntity("p","项目"))
        main.dao().putPlacement(TaskPlacementEntity("old","p")); main.dao().putWeeklyGoal(WeeklyGoalEntity("g","p",weekStart="2026-10-26",targetMinutes=30))
        val bytes=backup.create(); assertEquals(1,backup.preview(bytes).mainCounts["task_placements"])
        backup.restore(bytes); assertEquals("p",main.dao().placement("old")!!.projectId)
        for(version in listOf(7,8)) {
            val current=payload(bytes)
            val legacy=current.copy(main=BackupDatabase(version,current.main.tables.filterKeys{it in BackupTables.mainForVersion(version)}))
            backup.restore(encode(legacy)); assertEquals("旧任务",main.dao().task("old")!!.title)
            assertTrue(main.dao().projects().isEmpty());assertTrue(main.dao().placements().isEmpty())
        }
    }
    @Test fun `restore preflight rejects deeper or cross project family without touching live tables`() = runBlocking<Unit> {
        val dao=main.dao();dao.putProject(ProjectEntity("p","项目"))
        listOf("a","b","c").forEach{dao.putTask(TaskEntity(it,it))}
        dao.putPlacement(TaskPlacementEntity("a","p"));dao.putPlacement(TaskPlacementEntity("b","p","a"));dao.putPlacement(TaskPlacementEntity("c","p"))
        val valid=payload(backup.create())
        val table=valid.main.tables.getValue("task_placements")
        val key=table.columns.indexOf("taskId"); val parent=table.columns.indexOf("parentTaskId")
        val invalid=table.copy(rows=table.rows.map { row -> if(row[key].value=="c") row.toMutableList().also{it[parent]=BackupCell(3,"b")} else row })
        val bytes=encode(valid.copy(main=valid.main.copy(tables=valid.main.tables+("task_placements" to invalid))))
        assertThrows(IllegalArgumentException::class.java){backup.preview(bytes)}
        assertEquals(3,dao.tasks().size)
    }
    @Test fun `restore rejects child whose parent has no explicit placement`() = runBlocking<Unit> {
        main.dao().putTask(TaskEntity("a","父"));main.dao().putTask(TaskEntity("b","子"))
        main.dao().putPlacement(TaskPlacementEntity("b",parentTaskId="a"))
        val data=BackupTables.capture(main.openHelper.readableDatabase.backupSql(),BackupTables.main)
        assertThrows(IllegalArgumentException::class.java){BackupTables.preflight(data,main.openHelper.readableDatabase.backupSql(),BackupTables.main,false)}
    }
    @Test fun `failure after replacing main rolls back new project relations and goals`() = runBlocking<Unit> {
        val dao=main.dao();dao.putProject(ProjectEntity("p","目标备份"));dao.putTask(TaskEntity("t","任务"));dao.putPlacement(TaskPlacementEntity("t","p"))
        dao.putWeeklyGoal(WeeklyGoalEntity("g","p",weekStart="2026-10-26",targetMinutes=90))
        val bytes=backup.create()
        dao.putProject(ProjectEntity("p","恢复前现场"));dao.putWeeklyGoal(WeeklyGoalEntity("g","p",weekStart="2026-10-26",targetMinutes=120))
        backup.afterFirstDatabaseRestored={error("synthetic failure")}
        assertThrows(IllegalStateException::class.java){runBlocking{backup.restore(bytes)}}
        assertEquals("恢复前现场",dao.project("p")!!.name);assertEquals(120,dao.weeklyGoal("g")!!.targetMinutes);assertEquals("p",dao.placement("t")!!.projectId)
    }
}
