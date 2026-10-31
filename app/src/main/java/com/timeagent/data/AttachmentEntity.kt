package com.timeagent.data
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
@Entity(tableName="attachments",indices=[Index("taskId"),Index("projectId")])
data class AttachmentEntity(@PrimaryKey val id:String,val storageKey:String,val originalName:String,val mimeType:String,val sizeBytes:Long,val sha256:String,val ocrText:String="",val taskId:String?=null,val projectId:String?=null,val createdAt:Long=0,val updatedAt:Long=0,val version:Long=0,val deletedAt:Long?=null,val purgedAt:Long?=null)
