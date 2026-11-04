package com.timeagent.quickentry

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.timeagent.backup.PersonalBackup

/** The preset used for a specific entry, independent of later preset or task edits. */
@Entity(tableName = "entry_contexts", primaryKeys = ["entityType", "entityId"])
data class EntryContextEntity(
    val entityType: String,
    val entityId: String,
    val activityId: String?,
    val projectId: String?,
    val tagIds: String = "",
    val quickEntryId: String? = null,
)

@Dao
interface EntryContextDao {
    @Query("SELECT * FROM entry_contexts")
    suspend fun all(): List<EntryContextEntity>
    @Query("SELECT * FROM entry_contexts WHERE entityType=:type AND entityId=:id")
    suspend fun find(type: String, id: String): EntryContextEntity?
    @Upsert suspend fun put(item: EntryContextEntity)
    @Query("DELETE FROM entry_contexts WHERE entityType=:type AND entityId=:id")
    suspend fun remove(type: String, id: String)
}

object EntryContextMigration : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS entry_contexts (entityType TEXT NOT NULL, entityId TEXT NOT NULL, " +
            "activityId TEXT, projectId TEXT, tagIds TEXT NOT NULL, quickEntryId TEXT, PRIMARY KEY(entityType, entityId))")
    }

    fun withBackup(context: Context): Migration = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            PersonalBackup.createBeforeMigration(context, db, 21, 22)
            EntryContextMigration.migrate(db)
        }
    }
}
