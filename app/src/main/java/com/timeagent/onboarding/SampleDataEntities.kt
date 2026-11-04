package com.timeagent.onboarding

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.Upsert

/**
 * Marks rows that belong to a sample batch. The association table is deliberately separate from
 * tasks/schedules/records/projects so no production table needs a nullable sample column.
 *
 * `initialVersion` is the revision written when the sample was created. Any later revision means
 * the user edited the row; cleanup must ask before deleting it.
 */
@Entity(
    tableName = "sample_records",
    primaryKeys = ["entityType", "entityId"],
    indices = [Index("batchId")],
)
data class SampleRecordEntity(
    val entityType: String,
    val entityId: String,
    val batchId: String,
    val initialVersion: Long,
)

object SampleEntityTypes {
    const val TASK = "TASK"
    const val PLAN = "PLAN"
    const val RECORD = "RECORD"
    const val PROJECT = "PROJECT"

    val all: Set<String> = setOf(TASK, PLAN, RECORD, PROJECT)
}

@Dao
interface SampleDataDao {
    @Query("SELECT * FROM sample_records ORDER BY entityType, entityId")
    suspend fun all(): List<SampleRecordEntity>

    @Query("SELECT * FROM sample_records WHERE batchId=:batchId ORDER BY entityType, entityId")
    suspend fun batch(batchId: String): List<SampleRecordEntity>

    @Query("SELECT * FROM sample_records WHERE entityType=:entityType AND entityId=:entityId LIMIT 1")
    suspend fun find(entityType: String, entityId: String): SampleRecordEntity?

    @Query("SELECT DISTINCT batchId FROM sample_records ORDER BY batchId")
    suspend fun batches(): List<String>

    @Query("SELECT entityId FROM sample_records WHERE entityType=:entityType")
    suspend fun ids(entityType: String): List<String>

    @Query("SELECT COUNT(*) FROM sample_records")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM sample_records WHERE batchId=:batchId")
    suspend fun countBatch(batchId: String): Int

    @Upsert
    suspend fun put(item: SampleRecordEntity)

    @Query("DELETE FROM sample_records WHERE entityType=:entityType AND entityId=:entityId")
    suspend fun remove(entityType: String, entityId: String)

    @Query("DELETE FROM sample_records WHERE batchId=:batchId")
    suspend fun removeBatch(batchId: String)
}

/** Immutable sample id sets used by calendar sync, export, backup and statistics filters. */
data class SampleDataIds(
    val tasks: Set<String> = emptySet(),
    val plans: Set<String> = emptySet(),
    val records: Set<String> = emptySet(),
    val projects: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = tasks.isEmpty() && plans.isEmpty() && records.isEmpty() && projects.isEmpty()

    fun contains(entityType: String, entityId: String): Boolean = when (entityType) {
        SampleEntityTypes.TASK -> entityId in tasks
        SampleEntityTypes.PLAN -> entityId in plans
        SampleEntityTypes.RECORD -> entityId in records
        SampleEntityTypes.PROJECT -> entityId in projects
        else -> false
    }
}

/**
 * Row-level backup/export exclusions. The marker table itself is bookkeeping and is never exported.
 * `rowIds` maps a main-database table name to primary keys that must be dropped from a capture.
 */
data class SampleExportExclusions(
    val rowIds: Map<String, Set<String>> = emptyMap(),
) {
    fun excludesTable(table: String): Boolean = table == "sample_records"

    fun excludes(table: String, primaryKey: String): Boolean =
        excludesTable(table) || primaryKey in rowIds[table].orEmpty()
}
