package com.timeagent.recurring

import com.google.gson.Gson
import com.timeagent.core.*
import com.timeagent.data.*
import java.time.*

object RecurringValidation {
    private val gson=Gson()
    fun pattern(rule:RecurringRuleEntity):RecurringPattern = try {
        require(rule.id.isNotBlank() && rule.title.trim().length in 1..100 && rule.reference.length<=20000 && rule.category.length<=100)
        require(rule.domain in setOf("HABIT","DEADLINE"))
        val p=requireNotNull(gson.fromJson(rule.patternJson,RecurringPattern::class.java))
        RecurringRules.validate(p);require(p.id==rule.id)
        require(if(rule.domain=="HABIT") p.frequency in setOf("DAILY","WEEKDAYS","WEEKLY_COUNT") else p.frequency!="WEEKLY_COUNT")
        p
    } catch(e:Exception){throw IllegalArgumentException("周期规则内容无效：${e.message}",e)}

    fun instance(item:RecurringInstanceEntity) {
        try {
            require(item.status in setOf("PENDING","COMPLETED","SKIPPED","SUSPENDED","SUPERSEDED"))
            require(item.taskId=="recurring-task:${item.id}" && item.ruleVersion>=0 && item.taskVersion>=0 && (item.planVersion?:0)>=0 && (item.placementVersion?:0)>=0)
            val source=requireNotNull(gson.fromJson(item.ruleSnapshot,RecurringRuleEntity::class.java))
            val p=pattern(source);require(source.id==item.ruleId && source.version==item.ruleVersion)
            val date=LocalDate.parse(item.originalDate)
            val end=if(p.frequency=="WEEKLY_COUNT") date.plusDays(6) else date
            require(RecurringRules.expand(p.copy(paused=false),date,end).any{it.key==item.id && it.ordinal==item.ordinal})
        } catch(e:Exception){throw IllegalArgumentException("周期实例来源或状态无效：${e.message}",e)}
    }
}
