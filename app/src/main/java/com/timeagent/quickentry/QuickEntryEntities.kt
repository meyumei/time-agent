package com.timeagent.quickentry

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.timeagent.data.ActivityTemplateEntity
import kotlinx.coroutines.flow.Flow

/** The four actions a quick entry can perform. The database stores the enum name. */
enum class QuickEntryAction {
    START_TIMER,
    START_FOCUS,
    CREATE_SCHEDULE,
    CREATE_ACTUAL,
}

const val TEMPLATE_KIND_SCHEDULE = "SCHEDULE"
const val TEMPLATE_KIND_TASK = "TASK"

const val TEMPLATE_SCHEDULE_TIMED = "TIMED"
const val TEMPLATE_SCHEDULE_POINT = "POINT"
const val TEMPLATE_SCHEDULE_ALL_DAY = "ALL_DAY"

val QUICK_ENTRY_ACTIONS: Set<String> = QuickEntryAction.entries.map { it.name }.toSet()
val TEMPLATE_ITEM_KINDS: Set<String> = setOf(TEMPLATE_KIND_SCHEDULE, TEMPLATE_KIND_TASK)
val TEMPLATE_SCHEDULE_KINDS: Set<String> = setOf(TEMPLATE_SCHEDULE_TIMED, TEMPLATE_SCHEDULE_POINT, TEMPLATE_SCHEDULE_ALL_DAY)

/**
 * A quick entry always points at an existing [ActivityTemplateEntity]; it never creates a second
 * classification system. `projectId`/`taskId` are optional context only: projects and tasks are
 * soft references whose deletion must never break the chip.
 */
@Entity(
    tableName = "quick_entries",
    foreignKeys = [
        ForeignKey(
            entity = ActivityTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("activityId"),
        Index("projectId"),
        Index("taskId"),
        Index(value = ["pinned", "sortOrder"]),
    ],
)
data class QuickEntryEntity(
    @PrimaryKey val id: String,
    val activityId: String,
    val label: String? = null,
    val icon: String = "",
    val color: Long = 0,
    val projectId: String? = null,
    val taskId: String? = null,
    /** Comma separated tag ids. Unknown ids are ignored when applying; stored text is untouched. */
    val tagIds: String = "",
    val action: String = QuickEntryAction.START_TIMER.name,
    val defaultDurationMinutes: Int? = null,
    val sortOrder: Int = 0,
    val pinned: Boolean = false,
    val usageCount: Int = 0,
    val lastUsedAt: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Long = 0,
) {
    val actionType: QuickEntryAction?
        get() = QuickEntryAction.entries.firstOrNull { it.name == action }

    val tagIdList: List<String>
        get() = tagIds.split(',').map(String::trim).filter(String::isNotEmpty).distinct()
}

/**
 * A named group of schedules/tasks. Items are always read through the template so applying is one
 * previewable operation; `version` guards edits made from two screens.
 */
@Entity(tableName = "plan_templates")
data class PlanTemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val version: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Entity(
    tableName = "plan_template_items",
    foreignKeys = [
        ForeignKey(
            entity = PlanTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("templateId")],
)
data class PlanTemplateItemEntity(
    @PrimaryKey val id: String,
    val templateId: String,
    val kind: String = TEMPLATE_KIND_SCHEDULE,
    val title: String,
    val dayOffset: Int = 0,
    /** Local HH:mm in the applying device's zone. Null for all-day entries. */
    val startTime: String? = null,
    val durationMinutes: Int? = null,
    val scheduleKind: String = TEMPLATE_SCHEDULE_TIMED,
    val projectId: String? = null,
    val tagIds: String = "",
    /** Comma separated minutes-before-start offsets; kept with the item for future reminder apply. */
    val reminderOffsets: String = "",
    val notes: String = "",
) {
    val tagIdList: List<String>
        get() = tagIds.split(',').map(String::trim).filter(String::isNotEmpty).distinct()

    val reminderOffsetList: List<Int>
        get() = reminderOffsets.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it >= 0 }.distinct()
}

@Dao
interface QuickEntryDao {
    @Query("SELECT * FROM quick_entries ORDER BY pinned DESC, sortOrder ASC, usageCount DESC, lastUsedAt DESC, id ASC")
    suspend fun entries(): List<QuickEntryEntity>

    @Query("SELECT * FROM quick_entries ORDER BY pinned DESC, sortOrder ASC, usageCount DESC, lastUsedAt DESC, id ASC")
    fun observeEntries(): Flow<List<QuickEntryEntity>>

    @Query("SELECT * FROM quick_entries WHERE id=:id")
    suspend fun entry(id: String): QuickEntryEntity?

    @Upsert
    suspend fun putEntry(item: QuickEntryEntity)

    @Query("DELETE FROM quick_entries WHERE id=:id")
    suspend fun removeEntry(id: String)

    @Query("SELECT * FROM plan_templates ORDER BY updatedAt DESC, name ASC")
    suspend fun templates(): List<PlanTemplateEntity>

    @Query("SELECT * FROM plan_templates ORDER BY updatedAt DESC, name ASC")
    fun observeTemplates(): Flow<List<PlanTemplateEntity>>

    @Query("SELECT * FROM plan_templates WHERE id=:id")
    suspend fun template(id: String): PlanTemplateEntity?

    @Upsert
    suspend fun putTemplate(item: PlanTemplateEntity)

    @Query("DELETE FROM plan_templates WHERE id=:id")
    suspend fun removeTemplate(id: String)

    @Query("SELECT * FROM plan_template_items WHERE templateId=:templateId ORDER BY dayOffset ASC, startTime ASC, id ASC")
    suspend fun items(templateId: String): List<PlanTemplateItemEntity>

    @Query("SELECT * FROM plan_template_items WHERE id=:id")
    suspend fun item(id: String): PlanTemplateItemEntity?

    @Upsert
    suspend fun putItem(item: PlanTemplateItemEntity)

    @Query("DELETE FROM plan_template_items WHERE id=:id")
    suspend fun removeItem(id: String)

    @Query("DELETE FROM plan_template_items WHERE templateId=:templateId")
    suspend fun removeItems(templateId: String)
}
