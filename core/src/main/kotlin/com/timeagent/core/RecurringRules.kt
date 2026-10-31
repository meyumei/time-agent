package com.timeagent.core

import java.time.*

data class RecurringPattern(
    val id:String, val frequency:String, val startDate:String,
    val weekdays:List<Int> = emptyList(), val weeklyCount:Int = 1,
    val minuteOfDay:Int? = null, val durationMinutes:Int = 30,
    val zoneId:String = "Asia/Shanghai", val untilDate:String? = null,
    val paused:Boolean = false, val effectiveFrom:String = startDate, val leadMinutes:Int? = null,
)
data class RecurringSeed(val key:String,val date:LocalDate?,val bucket:LocalDate,val ordinal:Int=1)
object RecurringRules {
    fun validate(rule:RecurringPattern) {
        require(rule.id.isNotBlank() && rule.id.length<=200)
        require(rule.frequency in setOf("ONCE","DAILY","WEEKDAYS","WEEKLY_COUNT","MONTHLY","YEARLY")) { "周期类型无效" }
        val start=LocalDate.parse(rule.startDate); LocalDate.parse(rule.effectiveFrom)
        rule.untilDate?.let{require(LocalDate.parse(it)>=start){"结束日期不能早于开始日期"}}
        ZoneId.of(rule.zoneId)
        require(rule.weekdays.all{it in 1..7} && rule.weekdays.distinct().size==rule.weekdays.size)
        require(rule.frequency!="WEEKDAYS" || rule.weekdays.isNotEmpty()){ "请选择星期" }
        require(rule.weeklyCount in 1..21 && rule.durationMinutes in 1..1440){ "次数须为1至21，时长须为1至1440分钟" }
        require(rule.minuteOfDay==null || rule.minuteOfDay in 0..1439)
        require(rule.frequency!="WEEKLY_COUNT" || rule.minuteOfDay==null){ "每周频次目标不预设固定时段" }
        require(rule.leadMinutes==null || rule.leadMinutes in 0..527040)
    }
    fun expand(rule:RecurringPattern,from:LocalDate,to:LocalDate):List<RecurringSeed> {
        validate(rule)
        require(to>=from && java.time.temporal.ChronoUnit.DAYS.between(from,to)<=400){ "一次最多展开400天" }
        if(rule.paused)return emptyList()
        val anchor=LocalDate.parse(rule.startDate)
        val first=maxOf(anchor,LocalDate.parse(rule.effectiveFrom),from)
        val last=minOf(to,rule.untilDate?.let(LocalDate::parse)?:to)
        if(first>last)return emptyList()
        if(rule.frequency=="WEEKLY_COUNT") {
            val seeds=mutableListOf<RecurringSeed>();var week=WeekState.startOfWeek(first)
            while(week<=last){for(n in 1..rule.weeklyCount) seeds+=RecurringSeed("${rule.id}:w:$week:$n",null,week,n);week=week.plusWeeks(1)}
            return seeds
        }
        val seeds=mutableListOf<RecurringSeed>();var date=first
        while(date<=last) {
            val matches=when(rule.frequency) {
                "ONCE"->date==anchor
                "DAILY"->true
                "WEEKDAYS"->date.dayOfWeek.value in rule.weekdays
                "MONTHLY"->date.dayOfMonth==minOf(anchor.dayOfMonth,date.lengthOfMonth())
                "YEARLY"->date.month==anchor.month && date.dayOfMonth==minOf(anchor.dayOfMonth,date.lengthOfMonth())
                else->false
            }
            if(matches)seeds+=RecurringSeed("${rule.id}:d:$date",date,date)
            date=date.plusDays(1)
        }
        return seeds
    }
}
