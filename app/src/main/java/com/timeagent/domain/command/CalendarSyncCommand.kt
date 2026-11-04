package com.timeagent.domain.command

/**
 * N04 calendar sync writes. The executor applies this command through
 * [com.timeagent.calendar.CalendarSyncRepository.applyMutation] inside the normal transaction +
 * `commandWrite` scope, so request ids are idempotent, local schedule edits keep their plan
 * history, and undo covers a remote deletion that was mirrored as a soft delete.
 *
 * Remote provider writes are not part of the Room transaction. A failed write must surface as a
 * failed command; the next sync re-reads the provider and reconciles the mapping from
 * `CUSTOM_APP_URI` + `lastRemoteHash` instead of pretending the write succeeded.
 */
sealed interface CalendarSyncMutation {
    /** Remember the selected calendar and its direction. */
    data class Configure(
        val calendarId: Long,
        val calendarName: String,
        val accountName: String,
        val direction: String,
        val expectedVersion: Long? = null,
    ) : CalendarSyncMutation

    /** Export target and configurable past/future scan windows. */
    data class SaveSettings(
        val exportCalendarId: Long,
        val rangeDays: Int,
        val actualCalendarId: Long = 0,
        val expectedVersion: Long = 0,
        val pastDays: Int = 30,
    ) : CalendarSyncMutation

    /** Create a local calendar owned by the app ("时间 Agent") and make it the export target. */
    data class CreateLocalCalendar(val displayName: String) : CalendarSyncMutation
    data object CreateActualCalendar : CalendarSyncMutation

    /** Run one full pass now (also used by the debounce and periodic workers). */
    data class SyncNow(val fullScan: Boolean = true) : CalendarSyncMutation
    data class ReportFailure(val message: String) : CalendarSyncMutation

    /** Apply the stored snapshot of the other side for one conflict. */
    data class ResolveConflict(
        val linkId: String,
        val keep: CalendarConflictChoice,
        val expectedVersion: Long,
    ) : CalendarSyncMutation

    /**
     * A local delete of a remote-origin event was held for confirmation. Either remove the remote
     * event too, or keep it and drop the mapping.
     */
    data class ResolveLocalDeletion(
        val linkId: String,
        val deleteRemote: Boolean,
        val expectedVersion: Long,
    ) : CalendarSyncMutation

    /** Stop syncing a calendar. Imported blocks can stay local; exported events can stay remote. */
    data class Disconnect(
        val calendarId: Long,
        val keepImported: Boolean,
        val deleteRemoteExports: Boolean,
        val expectedVersion: Long?,
    ) : CalendarSyncMutation
}

enum class CalendarConflictChoice { LOCAL, REMOTE }

data class WriteCalendarSync(
    override val requestId: String,
    val mutation: CalendarSyncMutation,
    override val origin: Origin = Origin.CALENDAR_SYNC,
) : Command
