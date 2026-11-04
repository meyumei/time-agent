package com.timeagent.data

import com.timeagent.core.TimeSpan
import com.timeagent.focus.FocusSegments
import java.time.Instant

/** A focus has one stored actual; its paused/break gaps are excluded from display and statistics. */
fun LocalSnapshot.actualSpans(record: TimeRecordEntity, now: Instant): List<TimeSpan> {
    val session = if (record.source == "FOCUS") focusSessions.firstOrNull { "focus:${it.id}" == record.id } else null
    if (session != null) return FocusSegments.decode(session.segmentsJson).mapNotNull { segment ->
        val start = Instant.ofEpochMilli(segment.startAt)
        val end = minOf(start.plusMillis(segment.elapsedMs), now)
        if (end > start) TimeSpan(start, end) else null
    }
    val start = Instant.ofEpochMilli(record.startAt)
    val end = minOf(record.endAt?.let(Instant::ofEpochMilli) ?: now, now)
    return if (end > start) listOf(TimeSpan(start, end)) else emptyList()
}

fun LocalSnapshot.recordsForStatistics(now: Instant): List<TimeRecordEntity> = records.flatMap { record ->
    actualSpans(record, now).map { span ->
        record.copy(startAt = span.start.toEpochMilli(), endAt = span.end.toEpochMilli())
    }
}
