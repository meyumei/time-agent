package com.timeagent.organization

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.timeagent.data.CategoryEntity
import com.timeagent.data.HealthSyncItemEntity
import com.timeagent.data.RecordTagEntity
import com.timeagent.data.TagEntity
import com.timeagent.data.TaskTagEntity

/**
 * SQL for the classification tables. Categories and tags have no revision column of their own;
 * monotonic revisions live in entity_revisions through TimeRepository.nextVersion, exactly like
 * the entities TimeRepository already manages.
 */
@Dao
interface OrganizationDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder, name")
    suspend fun categories(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id=:id")
    suspend fun category(id: String): CategoryEntity?

    @Upsert
    suspend fun putCategory(item: CategoryEntity)

    @Query("DELETE FROM categories WHERE id=:id")
    suspend fun removeCategory(id: String)

    @Query("SELECT * FROM tags ORDER BY name")
    suspend fun tags(): List<TagEntity>

    @Query("SELECT * FROM tags WHERE id=:id")
    suspend fun tag(id: String): TagEntity?

    @Upsert
    suspend fun putTag(item: TagEntity)

    @Query("DELETE FROM tags WHERE id=:id")
    suspend fun removeTag(id: String)

    @Query("SELECT * FROM task_tags")
    suspend fun taskTags(): List<TaskTagEntity>

    @Query("SELECT * FROM record_tags")
    suspend fun recordTags(): List<RecordTagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTaskTag(item: TaskTagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addRecordTag(item: RecordTagEntity)

    @Query("DELETE FROM task_tags WHERE taskId=:taskId")
    suspend fun clearTaskTags(taskId: String)

    @Query("DELETE FROM record_tags WHERE recordId=:recordId")
    suspend fun clearRecordTags(recordId: String)

    /** Direct lookup; the shared DAO limits its list query to the latest 200 sync items. */
    @Query("SELECT * FROM health_sync_items WHERE recordId=:recordId")
    suspend fun healthItemsForRecord(recordId: String): List<HealthSyncItemEntity>
}
