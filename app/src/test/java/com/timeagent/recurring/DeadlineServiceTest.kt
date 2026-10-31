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
class DeadlineServiceTest {
    private lateinit var db:TimeAgentDatabase
    private lateinit var repo:TimeRepository
    private lateinit var service:RecurringService
    private val zone=ZoneId.of("Asia/Shanghai")
    private val now=Instant.parse("2026-10-27T02:00:00Z")
    private val gson=Gson()
    @Before fun setup(){db=TimeAgentDatabase.openInMemory(ApplicationProvider.getApplicationContext<Context>());repo=TimeRepository(db,Clock.fixed(now,zone));service=RecurringService(repo)}
    @After fun cleanup()=db.close()
    private fun deadline(date:LocalDate,frequency:String="ONCE",lead:Int?=0)=RecurringRuleEntity("life","归还演示",gson.toJson(RecurringPattern("life",frequency,date.toString(),minuteOfDay=540,leadMinutes=lead)),domain="DEADLINE",category="归还",reference="用户主动填的资料")
    @Test fun `explicit old overdue and far future once remain real pending points without fake records`()=runBlocking<Unit>{
        val today=now.atZone(zone).toLocalDate();val old=today.minusDays(30)
        service.saveRule(deadline(old),null,old,"old-once")
        assertEquals(1,repo.snapshot().recurringInstances.size);assertEquals("TODO",repo.snapshot().tasks.single().status)
        assertTrue(repo.snapshot().reminders.isEmpty());assertTrue(repo.snapshot().records.isEmpty());assertTrue(repo.snapshot().plans.isEmpty())
        val future=today.plusDays(500);val next=deadline(future).copy(id="far",patternJson=gson.toJson(RecurringPattern("far","ONCE",future.toString(),minuteOfDay=540,leadMinutes=0)))
        service.saveRule(next,null,future,"far-once")
        assertEquals(2,repo.snapshot().recurringInstances.size);assertEquals(1,repo.snapshot().reminders.size)
    }
    @Test fun `monthly deadline keeps month end anchor and overdue advance alert is not caught up`()=runBlocking<Unit>{
        val fixed=Instant.parse("2027-01-30T02:00:00Z");repo=TimeRepository(db,Clock.fixed(fixed,zone));service=RecurringService(repo)
        val p=RecurringPattern("life","MONTHLY","2027-01-31",minuteOfDay=540,untilDate="2027-03-31",leadMinutes=1440)
        service.saveRule(RecurringRuleEntity("life","续费",gson.toJson(p),domain="DEADLINE"),null,LocalDate.parse("2027-01-30"),"month")
        assertEquals(listOf("2027-01-31","2027-02-28","2027-03-31"),repo.snapshot().recurringInstances.map{it.originalDate})
        assertNull(db.dao().reminder("recurring-reminder:life:d:2027-01-31"));assertEquals(2,repo.snapshot().reminders.size)
        assertTrue(repo.snapshot().plans.isEmpty());assertTrue(repo.snapshot().records.isEmpty())
    }
    @Test fun `yearly leap birthday clamps only the instance while next leap returns to february 29`()=runBlocking<Unit>{
        repo=TimeRepository(db,Clock.fixed(Instant.parse("2027-02-27T02:00:00Z"),zone));service=RecurringService(repo)
        val p=RecurringPattern("life","YEARLY","2024-02-29",minuteOfDay=540,untilDate="2028-03-01",leadMinutes=0)
        service.saveRule(RecurringRuleEntity("life","生日",gson.toJson(p),domain="DEADLINE"),null,LocalDate.parse("2027-02-27"),"birthday")
        assertEquals(listOf("2027-02-28","2028-02-29"),repo.snapshot().recurringInstances.map{it.originalDate})
        assertEquals("2024-02-29",gson.fromJson(repo.snapshot().recurringRules.single().patternJson,RecurringPattern::class.java).startDate)
    }
    @Test fun `deleted owned reminder is not resurrected by edited rule or explicit delay`()=runBlocking<Unit>{
        val day=now.atZone(zone).toLocalDate().plusDays(1);service.saveRule(deadline(day),null,day,"create")
        val item=repo.snapshot().recurringInstances.single();val reminder=repo.snapshot().reminders.single();repo.deleteReminder(reminder.id,reminder.version)
        var rule=repo.snapshot().recurringRules.single();service.saveRule(rule.copy(title="归还改名"),rule.version,day,"edit")
        assertNull(db.dao().reminder(reminder.id))
        val current=db.dao().recurringInstance(item.id)!!;val target=now.plusSeconds(3*86400).toEpochMilli();val checks=service.previewMove(current.id,current.version,target,false)
        service.move(current.id,current.version,target,false,checks,"delay")
        assertNull(db.dao().reminder(reminder.id));assertEquals(item.id,repo.snapshot().recurringInstances.single().id)
    }
    @Test fun `manual reminder off survives delay and handling stops future reminder without fake actual`()=runBlocking<Unit>{
        val day=now.atZone(zone).toLocalDate().plusDays(1);service.saveRule(deadline(day),null,day,"create")
        val reminder=repo.snapshot().reminders.single();repo.saveReminder(reminder.copy(enabled=false),reminder.version)
        var item=repo.snapshot().recurringInstances.single();val target=now.plusSeconds(3*86400).toEpochMilli();val checks=service.previewMove(item.id,item.version,target,false)
        service.move(item.id,item.version,target,false,checks,"delay");assertFalse(db.dao().reminder(reminder.id)!!.enabled)
        item=repo.snapshot().recurringInstances.single();service.act(item.id,item.version,"COMPLETE","handle")
        assertEquals("COMPLETED",repo.snapshot().tasks.single().status);assertTrue(repo.snapshot().records.isEmpty());assertTrue(repo.snapshot().plans.isEmpty())
    }
    @Test fun `generation window follows rule timezone across device midnight`()=runBlocking<Unit>{
        repo=TimeRepository(db,Clock.fixed(Instant.parse("2026-10-26T16:30:00Z"),ZoneOffset.UTC));service=RecurringService(repo)
        service.saveRule(RecurringRuleEntity("r","阅读",gson.toJson(RecurringPattern("r","DAILY","2026-10-20",zoneId="Asia/Shanghai"))),null,LocalDate.parse("2026-10-20"),"timezone")
        assertTrue(repo.snapshot().recurringInstances.any{it.originalDate=="2026-11-24"})
    }
    @Test fun `manual stop after elapsed trigger survives delay and undo restores user preference`()=runBlocking<Unit>{
        val day=now.atZone(zone).toLocalDate();service.saveRule(deadline(day),null,day,"create")
        var item=repo.snapshot().recurringInstances.single();val at=now.minusSeconds(3600).toEpochMilli();val id="recurring-reminder:${item.id}"
        val prior=ReminderEntity(id,"归还演示",at,item.taskId,deliveredAt=at+1000)
        db.dao().putReminder(prior);db.dao().putRevision(EntityRevision("REMINDER_DELIVERY:$id:$at",at+1000))
        repo.saveReminder(prior.copy(enabled=false),prior.version)
        val target=now.plusSeconds(86400).toEpochMilli();var checked=service.previewMove(item.id,item.version,target,false)
        service.move(item.id,item.version,target,false,checked,"delay")
        assertFalse(db.dao().reminder(id)!!.enabled);assertEquals(at,db.dao().reminder(id)!!.remindAt)
        repo.undoLastAction("delay");repo.undoLastAction()
        assertTrue(db.dao().reminder(id)!!.enabled)
        item=repo.snapshot().recurringInstances.single();checked=service.previewMove(item.id,item.version,target,false)
        service.move(item.id,item.version,target,false,checked,"delay-after-undo")
        assertTrue(db.dao().reminder(id)!!.enabled);assertEquals(target,db.dao().reminder(id)!!.remindAt);assertNull(db.dao().reminder(id)!!.deliveredAt)
        assertEquals(at+1000,db.dao().revision("REMINDER_DELIVERY:$id:$at")!!.revision)
    }
    @Test fun `automatic stop from no advance alert can be explicitly enabled again`()=runBlocking<Unit>{
        val day=now.atZone(zone).toLocalDate().plusDays(1);service.saveRule(deadline(day),null,day,"create")
        var r=repo.snapshot().recurringRules.single();var p=gson.fromJson(r.patternJson,RecurringPattern::class.java)
        service.saveRule(r.copy(patternJson=gson.toJson(p.copy(leadMinutes=null))),r.version,day,"no-alert")
        assertFalse(repo.snapshot().reminders.single().enabled)
        r=repo.snapshot().recurringRules.single();p=gson.fromJson(r.patternJson,RecurringPattern::class.java)
        service.saveRule(r.copy(patternJson=gson.toJson(p.copy(leadMinutes=0))),r.version,day,"alert-again")
        assertTrue(repo.snapshot().reminders.single().enabled)
    }
    @Test fun `clearing project uses nullable placement and remains stable after reopen`()=runBlocking<Unit>{
        val day=now.atZone(zone).toLocalDate().plusDays(1);repo.saveProject(ProjectEntity("p","项目"))
        service.saveRule(deadline(day).copy(projectId="p"),null,day,"create")
        val r=repo.snapshot().recurringRules.single();service.saveRule(r.copy(projectId=null),r.version,day,"clear-project")
        assertNull(repo.snapshot().placements.single().projectId);service.refresh();assertEquals(1,repo.snapshot().tasks.size)
    }
    @Test fun `manual cancelled task is preserved while rule edit succeeds without reminder rewrite`()=runBlocking<Unit>{
        val day=now.atZone(zone).toLocalDate().plusDays(1);service.saveRule(deadline(day),null,day,"create")
        val task=repo.snapshot().tasks.single();repo.saveTask(task.copy(status="CANCELLED"),task.version)
        val r=repo.snapshot().recurringRules.single();service.saveRule(r.copy(title="新规则标题"),r.version,day,"rename")
        assertEquals("新规则标题",repo.snapshot().recurringRules.single().title);assertEquals("CANCELLED",repo.snapshot().tasks.single().status)
    }
}
