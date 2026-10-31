package com.timeagent.projects

import com.timeagent.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ProjectProgressTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-27T02:00:00Z")
    private val start = now.minusSeconds(7200).toEpochMilli()
    @Test fun `overlap is separately accumulated and deduplicated with exact sources`() {
        val data = LocalSnapshot(tasks=listOf(TaskEntity("a","父"),TaskEntity("b","子",status="COMPLETED",completedAt=now.toEpochMilli())),
            placements=listOf(TaskPlacementEntity("a","p"), TaskPlacementEntity("b","p","a")),
            plans=listOf(ScheduleBlockEntity("plan","b",start,start+3600000)),
            records=listOf(TimeRecordEntity("r1","a",start,start+3600000),TimeRecordEntity("r2","b",start+1800000,start+5400000)))
        val p=projectProgress(data,"p",LocalDate.parse("2026-10-26"),now,zone)
        assertEquals(60L,p.plannedMinutes); assertEquals(120L,p.recordedMinutes); assertEquals(90L,p.elapsedMinutes)
        assertEquals(listOf("b"),p.completedTasks.map{it.id}); assertEquals(listOf("a"),p.pendingTasks.map{it.id})
        assertEquals(setOf("r1","r2"),p.records.map{it.id}.toSet()); assertEquals("plan",p.plans.single().id)
    }
    @Test fun `ongoing actual clips to now and week while deleted task history stays traceable`() {
        val week=LocalDate.parse("2026-10-26"); val ms=week.atStartOfDay(zone).toInstant().toEpochMilli()
        val data=LocalSnapshot(tasks=listOf(TaskEntity("a","删除",deletedAt=1)),placements=listOf(TaskPlacementEntity("a","p")),records=listOf(TimeRecordEntity("r","a",ms-60000,null)))
        val p=projectProgress(data,"p",week,Instant.ofEpochMilli(ms+120000),zone)
        assertEquals(2L,p.recordedMinutes); assertTrue(p.pendingTasks.isEmpty()); assertEquals("r",p.records.single().id)
    }
}
