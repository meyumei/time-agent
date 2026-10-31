package com.timeagent.recurring

import com.timeagent.data.*

fun recurringState(instance:RecurringInstanceEntity,task:TaskEntity?):String {
    if(task==null || task.deletedAt!=null)return "DELETED"
    if(task.status=="COMPLETED")return "COMPLETED"
    if(task.status!="CANCELLED")return "PENDING"
    return instance.status.takeIf{it in setOf("SKIPPED","SUSPENDED","SUPERSEDED")}?:"CANCELLED"
}
