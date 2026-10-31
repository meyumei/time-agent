package com.timeagent.ui

import com.timeagent.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DeadlineStatusTest {
    private val now=Instant.parse("2026-10-27T02:00:00Z")
    private val task=TaskEntity("recurring-task:r:d:2026-10-28","归还",dueAt=now.plusSeconds(86400).toEpochMilli())
    private val instance=RecurringInstanceEntity("r:d:2026-10-28","r",task.id,"2026-10-28",ruleSnapshot="{}",ruleVersion=0,taskVersion=0)
    @Test fun `future missing registration and denied notification are separate true states`() {
        assertTrue(deadlineReminderText(instance,task,null,0,now,true,true).contains("未登记"))
        val reminder=ReminderEntity("rem","归还",task.dueAt!!,task.id)
        assertTrue(deadlineReminderText(instance,task,reminder,0,now,false,true).contains("通知未开启"))
        assertTrue(deadlineReminderText(instance,task,reminder,0,now,true,false).contains("延后"))
    }
    @Test fun `past time without trigger marker never says delivered and submitted marker never claims visible`() {
        val past=task.copy(dueAt=now.minusSeconds(60).toEpochMilli())
        val text=deadlineReminderText(instance,past,null,0,now,true,true)
        assertTrue(text.contains("已过"));assertTrue(text.contains("不补发"))
        val marked=ReminderEntity("rem","归还",past.dueAt!!,task.id,deliveredAt=now.toEpochMilli())
        assertTrue(deadlineReminderText(instance,past,marked,0,now,true,true).contains("未核实"))
    }
}
