package com.timeagent.calendar

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * N04 system-calendar sync (v20). Only two tables are introduced:
 *
 * - [CalendarLinkEntity] maps one local schedule block to one system-calendar event occurrence.
 *   A recurring remote event has one link per occurrence (`instanceOriginalStart`); a single
 *   event stores `null` here. The row is never deleted just because a permission was revoked.
 * - [CalendarSyncStateEntity] stores the user's per-calendar direction and the last scan result.
 *
 * Conflict snapshots live on the link row: the spec asks for "双方快照 + 一键改用另一版本", and a
 * third table would only duplicate JSON we never query by field.
 */
const val CALENDAR_LOCAL_ENTITY_TYPE = "SCHEDULE_BLOCK"

object CalendarLinkOrigin {
    const val LOCAL = "LOCAL"
    const val REMOTE = "REMOTE"
    val ALL = setOf(LOCAL, REMOTE)
}

object CalendarLinkState {
    const val ACTIVE = "ACTIVE"
    const val DELETED_LOCAL = "DELETED_LOCAL"
    const val DELETED_REMOTE = "DELETED_REMOTE"
    const val CONFLICT = "CONFLICT"
}

object CalendarConflictKind {
    /** Both sides changed since the last successful sync. */
    const val BOTH_CHANGED = "BOTH_CHANGED"

    /** The local block was deleted, but the remote event was created by the system calendar. */
    const val LOCAL_DELETED_REMOTE_ORIGIN = "LOCAL_DELETED_REMOTE_ORIGIN"
}

object CalendarSyncDirection {
    const val OFF = "OFF"
    const val IMPORT = "IMPORT"
    const val EXPORT = "EXPORT"
    const val BIDIRECTIONAL = "BIDIRECTIONAL"
    val ALL = setOf(OFF, IMPORT, EXPORT, BIDIRECTIONAL)
    fun imports(direction: String) = direction == IMPORT || direction == BIDIRECTIONAL
    fun exports(direction: String) = direction == EXPORT || direction == BIDIRECTIONAL
}

@Entity(
    tableName = "calendar_links",
    indices = [
        Index(value = ["localEntityId"], unique = true),
        Index("calendarId"),
        Index(value = ["calendarId", "eventId"]),
        Index("state"),
        Index("origin"),
    ],
)
data class CalendarLinkEntity(
    @PrimaryKey val id: String,
    val localEntityType: String = CALENDAR_LOCAL_ENTITY_TYPE,
    val localEntityId: String,
    val calendarId: Long,
    /** 0 means "not written to the provider yet"; the next sync inserts and fills this in. */
    val eventId: Long = 0,
    /** Original start of the occurrence (recurring events only); null for a single event. */
    val instanceOriginalStart: Long? = null,
    val syncAccount: String = "",
    /** Version of the local schedule block seen at the last successful sync. */
    val lastLocalVersion: Long = 0,
    /** Hash of the nine remote fields at the last successful sync (echo suppression). */
    val lastRemoteHash: String = "",
    val lastSyncedAt: Long = 0,
    val origin: String = CalendarLinkOrigin.LOCAL,
    val state: String = CalendarLinkState.ACTIVE,
    val remoteDeleted: Boolean = false,
    val conflictKind: String? = null,
    /** Which side the automatic "keep the most recent" rule kept while a conflict is recorded. */
    val conflictKept: String? = null,
    val conflictLocalSnapshot: String? = null,
    val conflictRemoteSnapshot: String? = null,
    val conflictAt: Long? = null,
    /** Non-null when the remote RRULE cannot be expressed by the local recurring model. */
    val readOnlyReason: String? = null,
    val version: Long = 0,
)

@Entity(tableName = "calendar_sync_state")
data class CalendarSyncStateEntity(
    @PrimaryKey val calendarId: Long,
    val calendarName: String,
    val accountName: String = "",
    val direction: String = CalendarSyncDirection.OFF,
    val lastFullScanAt: Long? = null,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    val version: Long = 0,
)

@Dao
interface CalendarSyncDao {
    @Query("SELECT * FROM calendar_links ORDER BY calendarId, eventId, instanceOriginalStart")
    suspend fun links(): List<CalendarLinkEntity>

    @Query("SELECT * FROM calendar_links WHERE id=:id")
    suspend fun link(id: String): CalendarLinkEntity?

    @Query("SELECT * FROM calendar_links WHERE localEntityId=:localEntityId LIMIT 1")
    suspend fun linkForLocal(localEntityId: String): CalendarLinkEntity?

    @Query("SELECT * FROM calendar_links WHERE localEntityId IN (:ids) AND readOnlyReason IS NOT NULL")
    suspend fun readOnlyLinks(ids: List<String>): List<CalendarLinkEntity>

    @Query("SELECT * FROM calendar_links WHERE calendarId=:calendarId ORDER BY eventId, instanceOriginalStart")
    suspend fun linksForCalendar(calendarId: Long): List<CalendarLinkEntity>

    @Query("SELECT * FROM calendar_links WHERE calendarId=:calendarId AND eventId=:eventId ORDER BY instanceOriginalStart")
    suspend fun linksForEvent(calendarId: Long, eventId: Long): List<CalendarLinkEntity>

    @Query("SELECT * FROM calendar_links WHERE state=:state ORDER BY conflictAt DESC")
    suspend fun linksInState(state: String): List<CalendarLinkEntity>

    @Query("SELECT * FROM calendar_sync_state ORDER BY calendarName, calendarId")
    suspend fun states(): List<CalendarSyncStateEntity>

    @Query("SELECT * FROM calendar_sync_state WHERE calendarId=:calendarId")
    suspend fun state(calendarId: Long): CalendarSyncStateEntity?

    @Upsert
    suspend fun putLink(item: CalendarLinkEntity)

    @Upsert
    suspend fun putState(item: CalendarSyncStateEntity)

    @Query("DELETE FROM calendar_links WHERE id=:id")
    suspend fun removeLink(id: String)

    @Query("DELETE FROM calendar_sync_state WHERE calendarId=:calendarId")
    suspend fun removeState(calendarId: Long)
}
