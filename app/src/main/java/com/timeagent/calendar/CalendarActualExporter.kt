package com.timeagent.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.timeagent.data.TimeRepository
import com.timeagent.focus.FocusSegments
import java.security.MessageDigest

/** One-way export into a separate calendar. Paused focus gaps never become calendar events. */
internal class CalendarActualExporter(private val repository: TimeRepository, private val context: Context) {
    private val resolver = context.contentResolver
    private val links = repository.database.calendarSyncDao()
    private data class Remote(val id: Long, val marker: String, val title: String, val notes: String,
        val start: Long, val end: Long, val zone: String)
    private data class Actual(val id: String, val title: String, val notes: String, val start: Long,
        val end: Long, val version: Long)

    suspend fun sync(calendarId: Long, from: Long, through: Long): Int {
        if (calendarId == 0L) return 0
        require(context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            "日历写权限已关闭，实际记录未导出"
        }
        val remote = linkedMapOf<String, Remote>()
        requireNotNull(resolver.query(CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID, CalendarContract.Events.CUSTOM_APP_URI, CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_TIMEZONE),
            "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.DELETED}=0", arrayOf(calendarId.toString()), null)) {
            "实际记录日历暂时不可读，未删除导出映射"
        }.use { cursor ->
            while (cursor.moveToNext()) {
                val marker = cursor.getString(1).orEmpty()
                if (marker.startsWith("timeagent://actual/")) remote[marker] = Remote(cursor.getLong(0), marker,
                    cursor.getString(2).orEmpty(), cursor.getString(3).orEmpty(), cursor.getLong(4),
                    cursor.getLong(5), cursor.getString(6).orEmpty())
            }
        }
        val dao = repository.database.dao()
        val records = dao.records().associateBy { it.id }
        val tasks = dao.tasks().associateBy { it.id }
        val sessions = repository.database.focusDao().all().associateBy { "focus:${it.id}" }
        val samples = repository.database.sampleDataDao().all().map { it.entityId }.toSet()
        val actuals = records.values.filter { it.endAt != null && it.id !in samples }.flatMap { record ->
            val title = record.title.ifBlank { tasks[record.taskId]?.title ?: "实际记录" }
            val focus = sessions[record.id]
            if (focus == null) listOf(Actual(record.id, title, record.notes, record.startAt,
                requireNotNull(record.endAt), record.version))
            else FocusSegments.decode(focus.segmentsJson).mapIndexed { index, segment ->
                Actual("${record.id}#segment:$index", title, record.notes, segment.startAt,
                    segment.startAt + segment.elapsedMs, record.version)
            }
        }.associateBy { it.id }
        val mapped = links.linksForCalendar(calendarId).filter { it.localEntityType == "ACTUAL_RECORD" }.associateBy { it.localEntityId }
        var changed = 0
        mapped.values.filter { it.localEntityId !in actuals }.forEach { link ->
            remote[marker(link.localEntityId)]?.let { event ->
                resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id), null, null)
            }
            links.removeLink(link.id)
        }
        actuals.values.filter { it.start < through && it.end > from && it.end > it.start }.forEach { actual ->
            val tag = marker(actual.id)
            val before = mapped[actual.id]
            val event = remote[tag]
            val desiredHash = hash(actual.title, actual.notes, actual.start, actual.end, repository.zone.id)
            val currentHash = event?.let { hash(it.title, it.notes, it.start, it.end, it.zone) }
            var eventId = event?.id
            if (currentHash != desiredHash) {
                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(CalendarContract.Events.TITLE, actual.title)
                    put(CalendarContract.Events.DESCRIPTION, actual.notes)
                    put(CalendarContract.Events.DTSTART, actual.start)
                    put(CalendarContract.Events.DTEND, actual.end)
                    put(CalendarContract.Events.EVENT_TIMEZONE, repository.zone.id)
                    put(CalendarContract.Events.CUSTOM_APP_URI, tag)
                    put(CalendarContract.Events.CUSTOM_APP_PACKAGE, context.packageName)
                    put(CalendarContract.Events.ALL_DAY, 0)
                }
                if (eventId == null) {
                    eventId = ContentUris.parseId(requireNotNull(resolver.insert(CalendarContract.Events.CONTENT_URI, values)) {
                        "系统日历未接收实际记录，未标记为已导出"
                    })
                } else check(resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                    values, null, null) == 1) { "系统日历事件已变化，请重新同步" }
                changed++
            }
            links.putLink(CalendarLinkEntity(id = before?.id ?: "actual:${actual.id}",
                localEntityType = "ACTUAL_RECORD", localEntityId = actual.id, calendarId = calendarId,
                eventId = requireNotNull(eventId), origin = CalendarLinkOrigin.LOCAL,
                lastLocalVersion = actual.version, lastRemoteHash = desiredHash,
                lastSyncedAt = repository.now().toEpochMilli(), version = (before?.version ?: -1L) + 1))
        }
        return changed
    }

    private fun marker(id: String) = "timeagent://actual/${android.net.Uri.encode(id)}"
    private fun hash(title: String, notes: String, start: Long, end: Long, zone: String): String =
        MessageDigest.getInstance("SHA-256").digest(org.json.JSONArray(listOf(title, notes, start, end, zone))
            .toString().toByteArray()).joinToString("") { "%02x".format(it) }
}
