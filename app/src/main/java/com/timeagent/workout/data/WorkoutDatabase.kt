package com.timeagent.workout.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.google.gson.Gson
import com.timeagent.core.workout.WorkoutSession
import com.timeagent.core.workout.WorkoutSessionStatus
import com.timeagent.core.workout.WorkoutTemplate
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "workout_sessions", indices = [Index("startedAt"), Index(value = ["activeSlot"], unique = true)])
data class WorkoutSessionRow(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val activeSlot: Int?,
    val payload: String,
) {
    companion object {
        fun from(session: WorkoutSession, gson: Gson) = WorkoutSessionRow(session.id, session.startedAt,
            if (session.status == WorkoutSessionStatus.ACTIVE || session.status == WorkoutSessionStatus.PAUSED) 1 else null,
            gson.toJson(session))
    }
}

@Entity(tableName = "workout_templates")
data class WorkoutTemplateRow(@PrimaryKey val id: String, val updatedAt: Long, val payload: String)

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM workout_sessions ORDER BY startedAt DESC, id") fun observeSessions(): Flow<List<WorkoutSessionRow>>
    @Query("SELECT * FROM workout_sessions ORDER BY startedAt DESC, id") suspend fun sessions(): List<WorkoutSessionRow>
    @Query("SELECT * FROM workout_sessions WHERE id=:id") suspend fun session(id: String): WorkoutSessionRow?
    @Query("SELECT * FROM workout_sessions WHERE activeSlot=1 LIMIT 1") suspend fun activeSession(): WorkoutSessionRow?
    @Upsert suspend fun putSession(row: WorkoutSessionRow)
    @Query("SELECT * FROM workout_templates ORDER BY updatedAt DESC, id") fun observeTemplates(): Flow<List<WorkoutTemplateRow>>
    @Query("SELECT * FROM workout_templates WHERE id=:id") suspend fun template(id: String): WorkoutTemplateRow?
    @Upsert suspend fun putTemplate(row: WorkoutTemplateRow)
    @Query("DELETE FROM workout_templates WHERE id=:id") suspend fun deleteTemplate(id: String)
}

@Database(entities = [WorkoutSessionRow::class, WorkoutTemplateRow::class], version = 1, exportSchema = false)
abstract class WorkoutDatabase : RoomDatabase() {
    abstract fun dao(): WorkoutDao

    override fun close() = synchronized(Companion) {
        super.close()
        if (instance === this) instance = null
    }

    companion object {
        @Volatile private var instance: WorkoutDatabase? = null

        fun open(context: Context): WorkoutDatabase {
            com.timeagent.backup.PersonalBackup.recoverIfNeeded(context)
            return instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, WorkoutDatabase::class.java, "workouts.db")
                .build().also { instance = it }
            }
        }
    }
}
