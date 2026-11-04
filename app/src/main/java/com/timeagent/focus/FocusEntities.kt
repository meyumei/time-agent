package com.timeagent.focus

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow

/** One focused stretch. Wall times are for display; elapsedMs is the authoritative duration. */
data class FocusSegment(
    val startAt: Long,
    val endAt: Long,
    val elapsedMs: Long,
    val round: Int,
)

object FocusSegments {
    private val gson = Gson()

    fun decode(raw: String): List<FocusSegment> = runCatching {
        gson.fromJson(raw, Array<FocusSegment>::class.java)?.toList().orEmpty()
    }.getOrDefault(emptyList())

    fun encode(values: List<FocusSegment>): String = gson.toJson(values)

    fun append(raw: String, segment: FocusSegment): String = encode(decode(raw) + segment)
}

/**
 * Focus sessions live beside time_records in the main database. `phaseAnchorElapsedMs` and every
 * `elapsedMs` are derived from SystemClock.elapsedRealtime; wall times are display/record anchors.
 * `focusedElapsedMs` is the total focus only (pauses and breaks are never included), while
 * `roundElapsedMs` is the current round's share used by the ring.
 */
@Entity(
    tableName = "focus_sessions",
    indices = [
        Index(value = ["requestId"], unique = true),
        Index("state"),
        Index("startedAt"),
    ],
)
data class FocusSessionEntity(
    @PrimaryKey val id: String,
    val requestId: String,
    val taskId: String?,
    val scheduleBlockId: String?,
    val label: String,
    val mode: String,
    val plannedFocusMinutes: Int,
    val plannedBreakMinutes: Int,
    val longBreakMinutes: Int,
    val roundsPlanned: Int,
    val roundIndex: Int,
    val state: String,
    val startedAt: Long,
    val pausedAccumulatedMs: Long,
    val lastResumedAt: Long?,
    val endedAt: Long?,
    val actualRecordId: String?,
    val timezone: String,
    val version: Long,
    val focusedElapsedMs: Long,
    val roundElapsedMs: Long,
    val phaseStartedAt: Long,
    val phaseAnchorElapsedMs: Long,
    val pausedAtElapsedMs: Long?,
    val pausedFromState: String?,
    val longBreakEvery: Int,
    val bootCount: Int,
    val timeChanged: Boolean,
    val needsReview: Boolean,
    val finishReason: String?,
    val segmentsJson: String,
    val lastWallAt: Long,
    val lastElapsedAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isActive: Boolean get() = state in ACTIVE_STATES

    companion object {
        val ACTIVE_STATES = setOf("FOCUSING", "PAUSED", "BREAK")
        const val STATE_FOCUSING = "FOCUSING"
        const val STATE_PAUSED = "PAUSED"
        const val STATE_BREAK = "BREAK"
        const val STATE_COMPLETED = "COMPLETED"
        const val STATE_ABANDONED = "ABANDONED"
    }
}

@Dao
interface FocusDao {
    @Query("SELECT * FROM focus_sessions WHERE state IN ('FOCUSING','PAUSED','BREAK') ORDER BY startedAt DESC LIMIT 1")
    suspend fun active(): FocusSessionEntity?

    @Query("SELECT * FROM focus_sessions WHERE state IN ('FOCUSING','PAUSED','BREAK') ORDER BY startedAt DESC LIMIT 1")
    fun observeActive(): Flow<FocusSessionEntity?>

    /** Includes the last finished row so the runtime can post one completion notification. */
    @Query("SELECT * FROM focus_sessions ORDER BY updatedAt DESC LIMIT 1")
    fun observeLatest(): Flow<FocusSessionEntity?>

    @Query("SELECT * FROM focus_sessions WHERE id=:id")
    suspend fun session(id: String): FocusSessionEntity?

    @Query("SELECT * FROM focus_sessions WHERE requestId=:requestId LIMIT 1")
    suspend fun byRequestId(requestId: String): FocusSessionEntity?

    @Query("SELECT * FROM focus_sessions ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 20): List<FocusSessionEntity>
    @Query("SELECT * FROM focus_sessions")
    suspend fun all(): List<FocusSessionEntity>
    @Query("SELECT * FROM focus_sessions WHERE id IN (:ids)")
    suspend fun sessions(ids: List<String>): List<FocusSessionEntity>
    @Query("DELETE FROM focus_sessions WHERE id=:id")
    suspend fun remove(id: String)

    @Upsert
    suspend fun put(item: FocusSessionEntity)
}
