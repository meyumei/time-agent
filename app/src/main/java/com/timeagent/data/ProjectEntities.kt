package com.timeagent.data

import androidx.room.*

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String, val name: String, val dueAt: Long? = null, val status: String = "ACTIVE",
    val createdAt: Long = 0, val updatedAt: Long = 0, val version: Long = 0, val deletedAt: Long? = null,
)

@Entity(tableName = "task_placements", foreignKeys = [
    ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["parentTaskId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.RESTRICT),
], indices = [Index("projectId"), Index("parentTaskId")])
data class TaskPlacementEntity(@PrimaryKey val taskId: String, val projectId: String? = null, val parentTaskId: String? = null, val version: Long = 0)

@Entity(tableName = "weekly_goals", foreignKeys = [
    ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = ActivityTemplateEntity::class, parentColumns = ["id"], childColumns = ["activityId"], onDelete = ForeignKey.RESTRICT),
], indices = [Index("projectId"), Index("activityId"), Index("weekStart")])
data class WeeklyGoalEntity(@PrimaryKey val id: String, val projectId: String? = null, val activityId: String? = null, val weekStart: String, val targetMinutes: Int, val version: Long = 0)
