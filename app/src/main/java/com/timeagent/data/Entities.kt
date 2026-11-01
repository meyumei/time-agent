package com.timeagent.data

import androidx.room.*

@Entity(tableName = "categories")
data class CategoryEntity(@PrimaryKey val id: String, val name: String, val color: Long = 0xFF5756B7, val sortOrder: Int = 0, val isArchived: Boolean = false)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(@PrimaryKey val id: String, val name: String, val color: Long = 0xFF5756B7)

@Entity(tableName = "activity_templates", foreignKeys = [
    ForeignKey(entity = ActivityTemplateEntity::class, parentColumns = ["id"], childColumns = ["parentId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL),
], indices = [Index("parentId"), Index("categoryId")])
data class ActivityTemplateEntity(
    @PrimaryKey val id: String, val name: String, val parentId: String? = null,
    val defaultMinutes: Int = 30, val categoryId: String? = null, val sortOrder: Int = 0,
    val archivedAt: Long? = null, val createdAt: Long = 0, val updatedAt: Long = 0, val version: Long = 0,
)

@Entity(tableName = "tasks", foreignKeys = [
    ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL),
    ForeignKey(entity = ActivityTemplateEntity::class, parentColumns = ["id"], childColumns = ["activityId"], onDelete = ForeignKey.SET_NULL),
], indices = [Index("categoryId"), Index("deletedAt"), Index("activityId")])
data class TaskEntity(
    @PrimaryKey val id: String, val title: String, val description: String = "",
    val estimatedMinutes: Int = 30, val dueAt: Long? = null, val priority: Int = 1,
    val status: String = "TODO", val categoryId: String? = null,
    val createdAt: Long = 0, val updatedAt: Long = 0, val completedAt: Long? = null,
    val source: String = "MANUAL", val deletedAt: Long? = null, val version: Long = 0,
    val activityId: String? = null,
)

@Entity(tableName = "schedule_blocks", foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("taskId"), Index("startAt")])
data class ScheduleBlockEntity(
    @PrimaryKey val id: String, val taskId: String?, val startAt: Long, val endAt: Long,
    val status: String = "SCHEDULED", val isLocked: Boolean = false,
    val createdAt: Long = 0, val updatedAt: Long = 0, val version: Long = 0,
    @ColumnInfo(defaultValue = "''") val title: String = "",
    @ColumnInfo(defaultValue = "'TIMED'") val kind: String = "TIMED",
    val allDayStartDate: String? = null, val allDayEndDate: String? = null,
    val timezoneId: String = java.time.ZoneId.systemDefault().id,
    @ColumnInfo(defaultValue = "''") val notes: String = "",
    val colorOverride: String? = null,
    @ColumnInfo(defaultValue = "'MANUAL'") val source: String = "MANUAL",
    val sourceRef: String? = null, val deletedAt: Long? = null,
) {
    fun displayTitle(task: TaskEntity?) = title.ifBlank { task?.title ?: "日程" }
}

@Entity(tableName = "command_log")
data class CommandLogEntity(
    @PrimaryKey val requestId: String, val commandType: String,
    val resultJson: String, val createdAt: Long,
)

@Entity(tableName = "time_records", foreignKeys = [
    ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.SET_NULL),
    ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL),
], indices = [Index("taskId"), Index("categoryId"), Index("startAt")])
data class TimeRecordEntity(
    @PrimaryKey val id: String, val taskId: String?, val startAt: Long, val endAt: Long?,
    val categoryId: String? = null, val notes: String = "", val source: String = "MANUAL",
    val createdAt: Long = 0, val updatedAt: Long = 0, val isManuallyEdited: Boolean = false, val version: Long = 0,
    @ColumnInfo(defaultValue = "''") val title: String = "",
)

@Entity(tableName = "academic_terms")
data class AcademicTermEntity(@PrimaryKey val id: String, val name: String, val firstWeekStart: String, val teachingWeeks: Int, val timezoneId: String)

@Entity(tableName = "courses", foreignKeys = [ForeignKey(entity = AcademicTermEntity::class, parentColumns = ["id"], childColumns = ["termId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("termId"), Index(value = ["sourceId", "externalKey"], unique = true)])
data class CourseEntity(@PrimaryKey val id: String, val name: String, val teacher: String, val location: String, val termId: String, val sourceId: String, val externalKey: String, val createdAt: Long, val updatedAt: Long)

@Entity(tableName = "course_occurrences", foreignKeys = [ForeignKey(entity = CourseEntity::class, parentColumns = ["id"], childColumns = ["courseId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("courseId"), Index(value = ["sourceOccurrenceKey"], unique = true), Index("startAt")])
data class CourseOccurrenceEntity(
    @PrimaryKey val id: String, val courseId: String, val date: String, val startAt: Long, val endAt: Long,
    val sourceOccurrenceKey: String, val locationOverride: String? = null, val teacherOverride: String? = null,
    val status: String = "SCHEDULED", val isManuallyModified: Boolean = false,
    val createdAt: Long = 0, val updatedAt: Long = 0, val version: Long = 0,
)

@Entity(tableName = "task_tags", primaryKeys = ["taskId", "tagId"], foreignKeys = [
    ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
], indices = [Index("tagId")])
data class TaskTagEntity(val taskId: String, val tagId: String)

@Entity(tableName = "record_tags", primaryKeys = ["recordId", "tagId"], foreignKeys = [
    ForeignKey(entity = TimeRecordEntity::class, parentColumns = ["id"], childColumns = ["recordId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
], indices = [Index("tagId")])
data class RecordTagEntity(val recordId: String, val tagId: String)

@Entity(tableName = "operation_history")
data class OperationEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val actionType: String, val entityType: String, val entityId: String, val beforeSnapshot: String?, val afterSnapshot: String?, val createdAt: Long, val undone: Boolean = false, @ColumnInfo(defaultValue = "0") val redoable: Boolean = false)

@Entity(tableName = "reminders", foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("taskId"), Index("remindAt")])
data class ReminderEntity(
    @PrimaryKey val id: String, val title: String, val remindAt: Long, val taskId: String? = null,
    val enabled: Boolean = true, val deliveredAt: Long? = null,
    val createdAt: Long = 0, val updatedAt: Long = 0, val version: Long = 0,
)

@Entity(tableName = "ideas")
data class IdeaEntity(
    @PrimaryKey val id: String, val title: String, val content: String = "", val status: String = "UNSCHEDULED",
    val createdAt: Long = 0, val updatedAt: Long = 0, val version: Long = 0, val deletedAt: Long? = null,
)

@Entity(tableName = "notes", indices = [Index(value = ["targetType", "targetId"], unique = true)])
data class NoteEntity(
    @PrimaryKey val id: String, val targetType: String, val targetId: String, val title: String = "", val body: String = "",
    val createdAt: Long = 0, val updatedAt: Long = 0, val version: Long = 0,
)

@Entity(tableName = "import_batches")
data class ImportBatchEntity(@PrimaryKey val id: String, val sourceId: String, val sourceType: String = "JSON", val startedAt: Long, val completedAt: Long, val importedCount: Int, val skippedCount: Int, val status: String = "COMPLETED")

/** One row per synced Health Connect record; uid is the stable source key (import externalKey pattern). */
@Entity(tableName = "health_sync_items", foreignKeys = [
    ForeignKey(entity = TimeRecordEntity::class, parentColumns = ["id"], childColumns = ["recordId"], onDelete = ForeignKey.SET_NULL),
], indices = [Index("recordId"), Index("startAt")])
data class HealthSyncItemEntity(
    @PrimaryKey val id: String, val kind: String, val title: String,
    val startAt: Long, val endAt: Long, val originPackage: String,
    val recordId: String? = null, val userDeleted: Boolean = false, val lastSyncedAt: Long = 0,
)

/** Proactive assistant suggestion; dedupKey is unique so a dismissed kind never returns. */
@Entity(tableName = "agent_suggestions", indices = [Index(value = ["dedupKey"], unique = true)])
data class AgentSuggestionEntity(
    @PrimaryKey val id: String, val type: String, val title: String, val detail: String,
    val dedupKey: String, val status: String = "PENDING",
    val createdAt: Long = 0, val decidedAt: Long? = null,
    val taskId: String? = null, val taskVersion: Long? = null,
    val proposedStartAt: Long? = null, val proposedEndAt: Long? = null,
    val forDate: String? = null, val expiresAt: Long? = null, val snoozedUntil: Long? = null,
    @ColumnInfo(defaultValue = "0") val version: Long = 0,
)

/** Survives undo/deletion so old editors can never reuse an issued revision. */
@Entity(tableName = "entity_revisions")
data class EntityRevision(@PrimaryKey val key: String, val revision: Long)
