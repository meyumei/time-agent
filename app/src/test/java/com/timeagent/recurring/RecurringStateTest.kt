package com.timeagent.recurring

import com.timeagent.data.*
import org.junit.Assert.*
import org.junit.Test

class RecurringStateTest {
    private val i=RecurringInstanceEntity("r:d:2026-10-28","r","t","2026-10-28",ruleSnapshot="{}",ruleVersion=0,taskVersion=0)
    @Test fun `manual task reopening wins over old skipped or suspended instance marker`() {
        val reopened=TaskEntity("t","事项",status="TODO",version=2)
        assertEquals("PENDING",recurringState(i.copy(status="SKIPPED"),reopened))
        assertEquals("PENDING",recurringState(i.copy(status="SUSPENDED"),reopened))
        assertEquals("COMPLETED",recurringState(i,reopened.copy(status="COMPLETED")))
    }
    @Test fun `cancelled deleted and paused states never appear as pending`() {
        val cancelled=TaskEntity("t","事项",status="CANCELLED")
        assertEquals("CANCELLED",recurringState(i,cancelled));assertEquals("SUSPENDED",recurringState(i.copy(status="SUSPENDED"),cancelled))
        assertEquals("DELETED",recurringState(i,cancelled.copy(deletedAt=1)))
    }
}
