package com.timeagent.recurring

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.timeagent.core.*
import com.timeagent.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class RecurringServiceTest {
    private lateinit var db:TimeAgentDatabase
    private lateinit var repo:TimeRepository
    private lateinit var service:RecurringService
    private val zone=ZoneId.of("Asia/Shanghai")
    private val now=Instant.parse("2026-10-27T02:00:00Z")
    private val today=now.atZone(zone).toLocalDate()
    private val gson=Gson()
    @Before fun setup(){db=TimeAgentDatabase.openInMemory(ApplicationProvider.getApplicationContext<Context>());repo=TimeRepository(db,Clock.fixed(now,zone));service=RecurringService(repo)}
    @After fun cleanup()=db.close()
    private fun rule(frequency:String="DAILY",count:Int=1,minute:Int?=600)=RecurringRuleEntity("r","读书",gson.toJson(RecurringPattern("r",frequency,"2026-10-26",weeklyCount=count,minuteOfDay=minute,untilDate="2026-10-29")))
    @Test fun `reopen and refresh preserve instance identities with plans separate from actual`()=runBlocking<Unit>{
        service.saveRule(rule(),null,LocalDate.parse("2026-10-26"),"create")
        val before=repo.snapshot();assertEquals(4,before.recurringInstances.size);assertEquals(4,before.plans.size);assertTrue(before.records.isEmpty())
        service.refresh();RecurringService(repo).refresh()
        val after=repo.snapshot();assertEquals(before.recurringInstances.map{it.id}.toSet(),after.recurringInstances.map{it.id}.toSet());assertEquals(4,after.tasks.size);assertTrue(after.records.isEmpty())
        assertTrue(after.tasks.all{it.status=="TODO"});assertTrue(after.recurringInstances.all{it.ruleSnapshot.contains("读书")})
    }
    @Test fun `weekly frequency has no fabricated slots and completion no fabricated actual`()=runBlocking<Unit>{
        service.saveRule(rule("WEEKLY_COUNT",3,null),null,LocalDate.parse("2026-10-26"),"create")
        val before=repo.snapshot();assertEquals(3,before.recurringInstances.size);assertTrue(before.plans.isEmpty())
        val item=before.recurringInstances.first();service.act(item.id,item.version,"COMPLETE","complete")
        val completed=repo.snapshot();assertEquals(1,completed.tasks.count{it.status=="COMPLETED"});assertTrue(completed.records.isEmpty())
        service.refresh();assertEquals(3,repo.snapshot().tasks.size)
    }
    @Test fun `skip and manual edit survive future rule changes without duplication`()=runBlocking<Unit>{
        service.saveRule(rule(),null,LocalDate.parse("2026-10-26"),"create")
        val skip=repo.snapshot().recurringInstances.single{it.originalDate==today.toString()}
        service.act(skip.id,skip.version,"SKIP","skip")
        val manual=repo.snapshot().tasks.single{it.id=="recurring-task:r:d:2026-10-28"}
        repo.saveTask(manual.copy(title="手改保留"),manual.version)
        val current=repo.snapshot().recurringRules.single()
        service.saveRule(current.copy(title="新标题"),current.version,today,"edit")
        service.refresh();val after=repo.snapshot()
        assertEquals(4,after.tasks.size);assertEquals("SKIPPED",after.recurringInstances.single{it.id==skip.id}.status)
        assertEquals("手改保留",after.tasks.single{it.id==manual.id}.title)
        assertEquals("新标题",after.tasks.single{it.id=="recurring-task:r:d:2026-10-29"}.title)
        assertTrue(after.records.isEmpty())
    }
    @Test fun `pause resume reuses original instances and undo rule creation is atomic`()=runBlocking<Unit>{
        service.saveRule(rule(),null,LocalDate.parse("2026-10-26"),"create")
        var r=repo.snapshot().recurringRules.single();var pattern=gson.fromJson(r.patternJson,RecurringPattern::class.java)
        service.saveRule(r.copy(patternJson=gson.toJson(pattern.copy(paused=true))),r.version,today,"pause")
        assertTrue(repo.snapshot().recurringInstances.filter{it.originalDate>=today.toString()}.all{it.status=="SUSPENDED"})
        r=repo.snapshot().recurringRules.single();pattern=gson.fromJson(r.patternJson,RecurringPattern::class.java)
        service.saveRule(r.copy(patternJson=gson.toJson(pattern.copy(paused=false))),r.version,today,"resume")
        assertEquals(4,repo.snapshot().tasks.size);assertTrue(repo.snapshot().records.isEmpty())
        repo.undoLastAction("resume");repo.undoLastAction("pause");repo.undoLastAction("create")
        assertTrue(repo.snapshot().tasks.isEmpty());assertTrue(repo.snapshot().recurringRules.isEmpty());assertTrue(repo.snapshot().recurringInstances.isEmpty())
        service.refresh();assertTrue(repo.snapshot().tasks.isEmpty());repo.redoLastAction();assertEquals(4,repo.snapshot().tasks.size)
        val restored=repo.snapshot().recurringRules.single()
        service.saveRule(restored.copy(title="重做后可继续更新"),restored.version,today,"after-redo")
        assertEquals("重做后可继续更新",repo.snapshot().tasks.single{it.id=="recurring-task:r:d:2026-10-28"}.title)
    }
    @Test fun `single reschedule checks actual conflict and retains original key`()=runBlocking<Unit>{
        service.saveRule(rule(),null,LocalDate.parse("2026-10-26"),"create")
        val instance=repo.snapshot().recurringInstances.single{it.originalDate==today.toString()}
        val target=now.plusSeconds(3600).toEpochMilli()
        val blocker=repo.saveTask(TaskEntity("busy","已安排"))
        repo.savePlan(ScheduleBlockEntity("busy-plan",blocker.id,target,target+1800000))
        val warnings=service.previewMove(instance.id,instance.version,target,true);assertEquals(1,warnings.size)
        assertThrows(StaleChange::class.java){runBlocking{service.move(instance.id,instance.version,target,true,emptyList(),"move-rejected")}}
        assertFalse(repo.snapshot().recurringInstances.single{it.id==instance.id}.manualOverride)
        service.move(instance.id,instance.version,target,true,warnings,"move")
        val moved=repo.snapshot().recurringInstances.single{it.id==instance.id};assertTrue(moved.manualOverride);assertEquals(today.toString(),moved.originalDate)
        service.refresh();assertEquals(4,repo.snapshot().recurringInstances.size);assertTrue(repo.snapshot().records.isEmpty())
    }
    @Test fun `manual deletion is not recreated by refresh or edited rule`()=runBlocking<Unit>{
        service.saveRule(rule(),null,LocalDate.parse("2026-10-26"),"create")
        val task=repo.snapshot().tasks.single{it.id=="recurring-task:r:d:2026-10-28"};repo.deleteTask(task.id)
        val current=repo.snapshot().recurringRules.single();service.saveRule(current.copy(title="更新"),current.version,today,"edit")
        service.refresh();assertEquals(4,repo.snapshot().tasks.size);assertNotNull(repo.snapshot().tasks.single{it.id==task.id}.deletedAt)
    }
    @Test fun `frequency edit retires unmatched future keys with original provenance intact`()=runBlocking<Unit>{
        service.saveRule(rule(),null,LocalDate.parse("2026-10-26"),"create")
        val current=repo.snapshot().recurringRules.single();val p=gson.fromJson(current.patternJson,RecurringPattern::class.java)
        service.saveRule(current.copy(patternJson=gson.toJson(p.copy(frequency="WEEKDAYS",weekdays=listOf(1)))),current.version,today,"edit-days")
        val after=repo.snapshot();assertEquals(4,after.recurringInstances.size)
        assertTrue(after.recurringInstances.filter{it.originalDate>=today.toString()}.all{it.status=="SUPERSEDED"})
        assertTrue(after.recurringInstances.all{it.ruleSnapshot.contains("DAILY")});assertTrue(after.records.isEmpty())
    }
    @Test fun `weekly pause includes remaining instances in current week and resume is stable`()=runBlocking<Unit>{
        service.saveRule(rule("WEEKLY_COUNT",3,null),null,LocalDate.parse("2026-10-26"),"create")
        var r=repo.snapshot().recurringRules.single();var p=gson.fromJson(r.patternJson,RecurringPattern::class.java)
        service.saveRule(r.copy(patternJson=gson.toJson(p.copy(paused=true))),r.version,today,"pause")
        assertTrue(repo.snapshot().recurringInstances.all{it.status=="SUSPENDED"})
        r=repo.snapshot().recurringRules.single();p=gson.fromJson(r.patternJson,RecurringPattern::class.java)
        service.saveRule(r.copy(patternJson=gson.toJson(p.copy(paused=false))),r.version,today,"resume")
        assertEquals(3,repo.snapshot().tasks.size);assertTrue(repo.snapshot().tasks.all{it.status=="TODO"});assertTrue(repo.snapshot().plans.isEmpty())
    }
    @Test fun `explicit project assignment and manual plan protection remain independent`()=runBlocking<Unit>{
        repo.saveProject(ProjectEntity("p","项目"));service.saveRule(rule().copy(projectId="p"),null,LocalDate.parse("2026-10-26"),"create")
        assertEquals(4,repo.snapshot().placements.count{it.projectId=="p"})
        val id="r:d:2026-10-28";val plan=db.dao().plan("recurring-plan:$id")!!
        repo.savePlan(plan.copy(startAt=plan.startAt+3600000,endAt=plan.endAt+3600000),plan.version)
        val changed=db.dao().plan(plan.id)!!
        val r=repo.snapshot().recurringRules.single();val p=gson.fromJson(r.patternJson,RecurringPattern::class.java)
        service.saveRule(r.copy(patternJson=gson.toJson(p.copy(paused=true))),r.version,today,"pause")
        assertEquals(changed,db.dao().plan(plan.id));assertEquals("TODO",db.dao().task("recurring-task:$id")!!.status)
    }
    @Test fun `stale checked move never overwrites intervening manual task edit`()=runBlocking<Unit>{
        service.saveRule(rule(),null,LocalDate.parse("2026-10-26"),"create")
        val i=repo.snapshot().recurringInstances.single{it.originalDate==today.toString()};val task=db.dao().task(i.taskId)!!;val target=now.plusSeconds(7200).toEpochMilli()
        val warnings=service.previewMove(i.id,i.version,target,true)
        repo.saveTask(task.copy(description="人工说明"),task.version)
        assertThrows(StaleChange::class.java){runBlocking{service.move(i.id,i.version,target,true,warnings,"stale-move")}}
        assertEquals("人工说明",db.dao().task(task.id)!!.description);assertEquals(task.dueAt,db.dao().task(task.id)!!.dueAt)
    }
    private fun remindedRule():RecurringRuleEntity {
        val r=rule(minute=660);val p=gson.fromJson(r.patternJson,RecurringPattern::class.java)
        return r.copy(patternJson=gson.toJson(p.copy(leadMinutes=60)))
    }
    @Test fun `future owned reminder title follows untouched rule edit`()=runBlocking<Unit>{
        service.saveRule(remindedRule(),null,LocalDate.parse("2026-10-26"),"create")
        val r=repo.snapshot().recurringRules.single();service.saveRule(r.copy(title="新提醒标题"),r.version,today,"rename")
        assertEquals("新提醒标题",db.dao().reminder("recurring-reminder:r:d:2026-10-28")!!.title)
    }
    @Test fun `manual reminder protects its instance from automatic pause`()=runBlocking<Unit>{
        service.saveRule(remindedRule(),null,LocalDate.parse("2026-10-26"),"create")
        val taskId="recurring-task:r:d:2026-10-28"
        repo.saveReminder(ReminderEntity("user-reminder","我自己的提醒",now.plusSeconds(3600).toEpochMilli(),taskId))
        val r=repo.snapshot().recurringRules.single();val p=gson.fromJson(r.patternJson,RecurringPattern::class.java)
        service.saveRule(r.copy(patternJson=gson.toJson(p.copy(paused=true))),r.version,today,"pause")
        assertEquals("TODO",db.dao().task(taskId)!!.status);assertTrue(db.dao().reminder("user-reminder")!!.enabled)
    }
    @Test fun `added child tasks protect existing family when recurring project changes`()=runBlocking<Unit>{
        repo.saveProject(ProjectEntity("p","原项目"));repo.saveProject(ProjectEntity("q","新项目"))
        service.saveRule(rule().copy(projectId="p"),null,LocalDate.parse("2026-10-26"),"create")
        val parent="recurring-task:r:d:2026-10-28";repo.saveTask(TaskEntity("child","手动子任务"));repo.savePlacement(TaskPlacementEntity("child","p",parent))
        val r=repo.snapshot().recurringRules.single();service.saveRule(r.copy(projectId="q"),r.version,today,"change-project")
        assertEquals("p",db.dao().placement(parent)!!.projectId);assertEquals("p",db.dao().placement("child")!!.projectId)
        assertEquals("q",db.dao().placement("recurring-task:r:d:2026-10-29")!!.projectId)
    }
    @Test fun `ongoing actual timer protects instance from automatic pause`()=runBlocking<Unit>{
        service.saveRule(rule(),null,LocalDate.parse("2026-10-26"),"create")
        val taskId="recurring-task:r:d:2026-10-27";val actual=repo.startTimer(taskId,"timer")
        val r=repo.snapshot().recurringRules.single();val p=gson.fromJson(r.patternJson,RecurringPattern::class.java)
        service.saveRule(r.copy(patternJson=gson.toJson(p.copy(paused=true))),r.version,today,"pause")
        assertEquals("TODO",db.dao().task(taskId)!!.status);assertEquals(actual,db.dao().record(actual.id));assertNull(db.dao().record(actual.id)!!.endAt)
    }
}
