package com.timeagent.ui

import com.timeagent.data.*
import java.time.Instant

fun deadlineReminderText(instance:RecurringInstanceEntity,task:TaskEntity,reminder:ReminderEntity?,leadMinutes:Int?,now:Instant,notificationsAllowed:Boolean,exactAllowed:Boolean):String {
    if(task.deletedAt!=null)return "原事项已删除，未来提醒停止"
    if(task.status=="COMPLETED")return "事项已处理，未来提醒停止；未自动补写实际时长"
    if(task.status=="CANCELLED")return if(instance.status=="SUSPENDED")"规则已暂停，提醒停止" else "事项已取消或跳过，提醒停止"
    if(reminder!=null && !reminder.enabled)return "提醒已停用；不会自动恢复"+if(reminder.deliveredAt!=null)"；历史触发显示未核实" else ""
    if(reminder?.deliveredAt!=null)return "已有触发处理记录；系统是否显示未核实，不代表已读"
    if(leadMinutes==null && reminder==null)return "未启用提前提醒"
    val expected=reminder?.remindAt?:task.dueAt?.let{it-(leadMinutes?:0)*60000L}
    if(expected!=null && expected<=now.toEpochMilli())return "提醒时间已过，未查到触发处理记录；不补发，可延期"
    if(reminder==null)return "尚未登记提醒，请核对规则或手动提醒设置"
    if(!notificationsAllowed)return "通知未开启，已保存的未来提醒不能保证显示"
    if(!exactAllowed)return "等待未来触发；精确闹钟未授权，可能延后，实际显示未核实"
    return "等待未来触发；实际显示未核实"
}
