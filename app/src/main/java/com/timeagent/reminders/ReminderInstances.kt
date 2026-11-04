package com.timeagent.reminders

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

const val REMINDER_INSTANCE_PRIMARY = "PRIMARY"
const val REMINDER_INSTANCE_REPEAT = "REPEAT"
const val REMINDER_INSTANCE_SNOOZE = "SNOOZE"

const val REMINDER_STATE_SCHEDULED = "SCHEDULED"
const val REMINDER_STATE_FIRED = "FIRED"
const val REMINDER_STATE_DISMISSED = "DISMISSED"
const val REMINDER_STATE_ACTED = "ACTED"
const val REMINDER_STATE_CANCELLED = "CANCELLED"

/**
 * Every alarm that can fire is a persisted instance. A primary instance is created for each
 * reminder revision; repeats and snoozes hang off the same reminder and keep the owner snapshot
 * that was visible when they were planned. Rows are never deleted with their parent, so a deleted
 * task/schedule/reminder still has an auditable fire history.
 */
@Entity(
    tableName = "reminder_instances",
    indices = [
        Index("reminderId"),
        Index("fireAt"),
        Index(value = ["reminderId", "state"]),
        Index(value = ["ownerType", "ownerId"]),
    ],
)
data class ReminderInstanceEntity(
    @PrimaryKey val id: String,
    val reminderId: String,
    val ownerType: String = "REMINDER",
    val ownerId: String? = null,
    val ownerVersion: Long = 0,
    val reminderVersion: Long = 0,
    val fireAt: Long = 0,
    val kind: String = REMINDER_INSTANCE_PRIMARY,
    val repeatIndex: Int = 0,
    val state: String = REMINDER_STATE_SCHEDULED,
    val requestCode: Int = 0,
    val version: Long = 0,
)

/** Payload produced by [ReminderInstanceRepository.fire]; the receiver turns it into a notification. */
data class ReminderFireEvent(
    val instanceId: String,
    val reminderId: String,
    val title: String,
    val fireAt: Long,
    val kind: String,
    val repeatIndex: Int,
)

@Dao
interface ReminderInstanceDao {
    @Query("SELECT * FROM reminder_instances ORDER BY fireAt, reminderId, kind, repeatIndex")
    suspend fun all(): List<ReminderInstanceEntity>

    @Query("SELECT * FROM reminder_instances WHERE state='SCHEDULED' ORDER BY fireAt")
    suspend fun scheduled(): List<ReminderInstanceEntity>

    @Query("SELECT * FROM reminder_instances WHERE reminderId=:reminderId ORDER BY fireAt, kind, repeatIndex")
    suspend fun forReminder(reminderId: String): List<ReminderInstanceEntity>

    @Query("SELECT * FROM reminder_instances WHERE id=:id")
    suspend fun instance(id: String): ReminderInstanceEntity?

    @Upsert
    suspend fun put(item: ReminderInstanceEntity)

    @Query("DELETE FROM reminder_instances WHERE id=:id")
    suspend fun remove(id: String)
}

/**
 * PendingIntent identity is the instance hash plus the instance URI. The request code is stable for
 * an instance id even after a process restart; the URI keeps hash collisions from merging alarms.
 */
internal fun reminderInstanceRequestCode(id: String): Int {
    val hash = id.hashCode()
    return (hash xor (hash ushr 16)) and 0x7fffffff
}
