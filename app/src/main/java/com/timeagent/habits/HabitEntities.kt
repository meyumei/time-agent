package com.timeagent.habits

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.timeagent.data.ActivityTemplateEntity
import com.timeagent.data.CategoryEntity
import com.timeagent.data.HealthSyncItemEntity
import com.timeagent.data.ProjectEntity
import com.timeagent.data.RecordTagEntity
import com.timeagent.data.TaskEntity
import com.timeagent.data.TaskPlacementEntity
import com.timeagent.data.TimeRecordEntity
import com.timeagent.data.TagEntity
import com.timeagent.focus.FocusSessionEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * Habits are goals over data that already exists. Only manual check-ins add rows; every other
 * source is derived on read from actual records, training sessions, sleep records and focus
 * segments, so editing history immediately changes the numbers and nothing is stored twice.
 */

enum class HabitMetric {
    /** Minutes per evaluated day/week. `target` is minutes. */
    DURATION,

    /** Finished occurrences (records, workouts, focus rounds). `target` is a count. */
    COUNT,

    /** Minute of day (0..1439) that must be reached at or before `target`, e.g. sleep before 23:30. */
    BEFORE_TIME,

    /** Minute of day (0..1439) that must be reached at or after `target`, e.g. wake after 07:00. */
    AFTER_TIME,
    ;

    companion object {
        fun parse(raw: String?): HabitMetric =
            HabitMetric.values().firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: DURATION
    }
}

enum class HabitSource {
    ACTUAL_BY_PROJECT,
    ACTUAL_BY_TAG,
    ACTUAL_BY_ACTIVITY,
    ACTUAL_BY_CATEGORY,
    WORKOUT,
    SLEEP,
    FOCUS,
    MANUAL_CHECKIN,
    ;

    val needsSourceRef: Boolean
        get() = this == ACTUAL_BY_PROJECT || this == ACTUAL_BY_TAG ||
            this == ACTUAL_BY_ACTIVITY || this == ACTUAL_BY_CATEGORY

    companion object {
        fun parse(raw: String?): HabitSource =
            HabitSource.values().firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: MANUAL_CHECKIN
    }
}

enum class HabitPeriod {
    DAILY,
    WEEKLY,
    ;

    companion object {
        fun parse(raw: String?): HabitPeriod =
            HabitPeriod.values().firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: DAILY
    }
}

/**
 * Day outcome. `NO_DATA` is never treated as failure: a missing sleep sync is not a missed night.
 * `UPCOMING` is a day later today/this week that cannot be judged yet.
 */
enum class HabitDayState { ACHIEVED, MISSED, NO_DATA, INACTIVE, BEFORE_START, UPCOMING }

@Entity(tableName = "habits", indices = [Index("archivedAt"), Index("sortOrder")])
data class HabitEntity(
    @PrimaryKey val id: String,
    val name: String,
    val icon: String = "check",
    val color: Long = 0xFF425F76,
    val metric: String = "DURATION",
    val source: String = "MANUAL_CHECKIN",
    /** Project / tag / activity / category id; null for derived whole-dataset sources. */
    val sourceRef: String? = null,
    /** Minutes for DURATION, count for COUNT, minute-of-day for BEFORE_TIME / AFTER_TIME. */
    val target: Int = 1,
    val period: String = "DAILY",
    /** ISO day numbers (1 = Monday .. 7 = Sunday), comma separated. Blank means every day. */
    val activeWeekdays: String = ALL_WEEKDAYS,
    /** ISO local date; days before it are never evaluated or shown as failures. */
    val startDate: String,
    val archivedAt: Long? = null,
    /** Optional 21:00 reminder for a day that is still unmet. */
    val reminderEnabled: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Long = 0,
) {
    val archived: Boolean get() = archivedAt != null
    val metricKind: HabitMetric get() = HabitMetric.parse(metric)
    val sourceKind: HabitSource get() = HabitSource.parse(source)
    val periodKind: HabitPeriod get() = HabitPeriod.parse(period)
    val start: LocalDate get() = runCatching { LocalDate.parse(startDate) }.getOrDefault(LocalDate.MIN)
    val weekdays: Set<DayOfWeek>
        get() = activeWeekdays.split(',', ' ', '，')
            .mapNotNull { it.trim().toIntOrNull()?.takeIf { day -> day in 1..7 } }
            .map { DayOfWeek.of(it) }
            .toSet()
            .ifEmpty { DayOfWeek.values().toSet() }

    fun isActive(date: LocalDate): Boolean = date.dayOfWeek in weekdays

    companion object {
        const val ALL_WEEKDAYS = "1,2,3,4,5,6,7"

        fun weekdaysOf(days: Collection<DayOfWeek>): String =
            days.map { it.value }.distinct().sorted().joinToString(",").ifBlank { ALL_WEEKDAYS }
    }
}

/**
 * Manual check-in only. Automatic sources never write here, so an auto habit can never drift from
 * its records. `value` follows the habit metric: minutes, count, or minute-of-day.
 */
@Entity(
    tableName = "habit_checkins",
    primaryKeys = ["habitId", "date"],
    foreignKeys = [ForeignKey(
        entity = HabitEntity::class,
        parentColumns = ["id"],
        childColumns = ["habitId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("habitId"), Index("date")],
)
data class HabitCheckinEntity(
    val habitId: String,
    /** ISO local date of the habit day (not the device instant). */
    val date: String,
    val value: Int = 1,
    val note: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Long = 0,
) {
    val day: LocalDate get() = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.MIN)
}

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits ORDER BY archivedAt IS NOT NULL, sortOrder, createdAt")
    suspend fun habits(): List<HabitEntity>

    @Query("SELECT * FROM habits ORDER BY archivedAt IS NOT NULL, sortOrder, createdAt")
    fun observeHabits(): kotlinx.coroutines.flow.Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits WHERE id=:id")
    suspend fun habit(id: String): HabitEntity?

    @Upsert
    suspend fun putHabit(item: HabitEntity)

    @Query("DELETE FROM habits WHERE id=:id")
    suspend fun removeHabit(id: String)

    @Query("SELECT * FROM habit_checkins")
    suspend fun checkins(): List<HabitCheckinEntity>

    @Query("SELECT * FROM habit_checkins")
    fun observeCheckins(): kotlinx.coroutines.flow.Flow<List<HabitCheckinEntity>>

    @Query("SELECT * FROM habit_checkins WHERE habitId=:habitId ORDER BY date DESC")
    suspend fun checkinsFor(habitId: String): List<HabitCheckinEntity>

    @Query("SELECT * FROM habit_checkins WHERE habitId=:habitId AND date=:date")
    suspend fun checkin(habitId: String, date: String): HabitCheckinEntity?

    @Upsert
    suspend fun putCheckin(item: HabitCheckinEntity)

    @Query("DELETE FROM habit_checkins WHERE habitId=:habitId AND date=:date")
    suspend fun removeCheckin(habitId: String, date: String)

    // ---- Read-only inputs for derived statistics -------------------------------------------------

    @Query("SELECT * FROM time_records WHERE endAt IS NOT NULL")
    suspend fun finishedRecords(): List<TimeRecordEntity>

    @Query("SELECT * FROM tasks")
    suspend fun tasks(): List<TaskEntity>

    @Query("SELECT * FROM task_placements")
    suspend fun placements(): List<TaskPlacementEntity>

    @Query("SELECT * FROM record_tags")
    suspend fun recordTags(): List<RecordTagEntity>

    @Query("SELECT * FROM health_sync_items WHERE kind='SLEEP'")
    suspend fun sleepLinks(): List<HealthSyncItemEntity>

    @Query("SELECT * FROM focus_sessions WHERE focusedElapsedMs > 0")
    suspend fun focusSessions(): List<FocusSessionEntity>

    @Query("SELECT * FROM projects WHERE deletedAt IS NULL ORDER BY name")
    suspend fun projects(): List<ProjectEntity>

    @Query("SELECT * FROM tags ORDER BY name")
    suspend fun tags(): List<TagEntity>

    @Query("SELECT * FROM activity_templates WHERE archivedAt IS NULL ORDER BY sortOrder, name")
    suspend fun activities(): List<ActivityTemplateEntity>

    @Query("SELECT * FROM categories ORDER BY sortOrder")
    suspend fun categories(): List<CategoryEntity>

    @Query("SELECT * FROM activity_templates WHERE id='sleep' LIMIT 1")
    suspend fun sleepActivity(): ActivityTemplateEntity?
}

internal fun habitToday(zone: java.time.ZoneId, nowMillis: Long): LocalDate =
    java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()

/** "23:30" -> 1410 minutes. Used by the editor and by tests. */
fun parseMinuteOfDay(text: String): Int? {
    val parts = text.trim().split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

fun formatMinuteOfDay(value: Int?): String {
    if (value == null || value < 0) return "—"
    val normalized = value % (24 * 60)
    return String.format(Locale.ROOT, "%02d:%02d", normalized / 60, normalized % 60)
}
