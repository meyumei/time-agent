package com.timeagent.data

import androidx.room.*

@Entity(tableName="recurring_rules",foreignKeys=[ForeignKey(entity=ProjectEntity::class,parentColumns=["id"],childColumns=["projectId"],onDelete=ForeignKey.RESTRICT)],indices=[Index("projectId")])
data class RecurringRuleEntity(
    @PrimaryKey val id:String,val title:String,val patternJson:String,val domain:String="HABIT",
    val category:String="",val reference:String="",val projectId:String?=null,
    val createdAt:Long=0,val updatedAt:Long=0,val version:Long=0,val deletedAt:Long?=null,
)

@Entity(tableName="recurring_instances",foreignKeys=[
    ForeignKey(entity=RecurringRuleEntity::class,parentColumns=["id"],childColumns=["ruleId"],onDelete=ForeignKey.RESTRICT),
    ForeignKey(entity=TaskEntity::class,parentColumns=["id"],childColumns=["taskId"],onDelete=ForeignKey.RESTRICT),
],indices=[Index("ruleId"),Index(value=["taskId"],unique=true)])
data class RecurringInstanceEntity(
    @PrimaryKey val id:String,val ruleId:String,val taskId:String,val originalDate:String,val ordinal:Int=1,
    val scheduledAt:Long?=null,val status:String="PENDING",val manualOverride:Boolean=false,
    val ruleSnapshot:String,val ruleVersion:Long,val taskVersion:Long,
    val planVersion:Long?=null,val placementVersion:Long?=null,val scheduleNote:String="",val version:Long=0,
)
