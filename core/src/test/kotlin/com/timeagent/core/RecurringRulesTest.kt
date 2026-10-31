package com.timeagent.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class RecurringRulesTest {
    private val monday=LocalDate.parse("2026-10-26")
    @Test fun `daily and weekdays yield stable original date keys after reopen`() {
        val r=RecurringPattern("r","DAILY",monday.toString())
        val first=RecurringRules.expand(r,monday,monday.plusDays(6))
        assertEquals(7,first.size);assertEquals(first,RecurringRules.expand(r,monday,monday.plusDays(6)))
        assertEquals("r:d:2026-10-26",first.first().key)
        val weekdays=RecurringRules.expand(r.copy(frequency="WEEKDAYS",weekdays=listOf(1,3,5)),monday,monday.plusDays(6))
        assertEquals(listOf(monday,monday.plusDays(2),monday.plusDays(4)),weekdays.map{it.date})
    }
    @Test fun `weekly frequency keeps count target distinct from fixed schedule`() {
        val r=RecurringPattern("r","WEEKLY_COUNT",monday.toString(),weeklyCount=3)
        val seeds=RecurringRules.expand(r,monday,monday.plusDays(6))
        assertEquals(3,seeds.size);assertTrue(seeds.all{it.date==null})
        assertEquals(listOf(1,2,3),seeds.map{it.ordinal});assertEquals(3,seeds.map{it.key}.toSet().size)
        assertEquals(monday,seeds.single{it.ordinal==1}.bucket)
        assertThrows(IllegalArgumentException::class.java){RecurringRules.validate(r.copy(minuteOfDay=600))}
    }
    @Test fun `pause and end boundary generate no extra instance`() {
        val r=RecurringPattern("r","DAILY",monday.toString(),untilDate=monday.plusDays(1).toString())
        assertEquals(2,RecurringRules.expand(r,monday,monday.plusDays(6)).size)
        assertTrue(RecurringRules.expand(r.copy(paused=true),monday,monday.plusDays(6)).isEmpty())
    }
    @Test fun `monthly end and leap birthday clamp but retain original anchor`() {
        val monthly=RecurringPattern("m","MONTHLY","2027-01-31")
        assertEquals(listOf("2027-01-31","2027-02-28","2027-03-31"),RecurringRules.expand(monthly,LocalDate.parse("2027-01-01"),LocalDate.parse("2027-03-31")).map{it.date.toString()})
        val birthday=RecurringPattern("b","YEARLY","2024-02-29")
        assertEquals(listOf("2027-02-28","2028-02-29"),RecurringRules.expand(birthday,LocalDate.parse("2027-02-01"),LocalDate.parse("2028-03-01")).map{it.date.toString()})
    }
    @Test fun `invalid rule is rejected before generating and once does not repeat`() {
        assertThrows(IllegalArgumentException::class.java){RecurringRules.validate(RecurringPattern("r","WEEKDAYS",monday.toString()))}
        val once=RecurringPattern("one","ONCE",monday.toString())
        assertEquals(1,RecurringRules.expand(once,monday,monday.plusDays(30)).size)
    }
}
