package com.timeagent.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.LocalTime

enum class SleepKind { MAIN, NAP }

data class SleepRecord(
    val id: String,
    val source: String,
    val start: Instant,
    val end: Instant,
    val kind: SleepKind? = null
)

data class SleepSourceSummary(
    val source: String,
    val mainMinutes: Long,
    val mainRecordIds: List<String>,
    val napMinutes: Long,
    val napRecordIds: List<String>
)

data class SleepDaySummary(
    val wakeDate: LocalDate,
    val selectedSource: String,
    val mainMinutes: Long,
    val mainRecordIds: List<String>,
    val napMinutes: Long,
    val napRecordIds: List<String>,
    val alternatives: List<SleepSourceSummary>
)

data class SleepSummaryResult(val days: Map<LocalDate, SleepDaySummary>)

object SleepSummary {
    /** Heuristic: morning ends and evening fragments form main sleep; evening fragments use the next wake day. */
    fun aggregate(records: List<SleepRecord>, now: Instant, zone: ZoneId): SleepSummaryResult {
        val byDayAndSource = records.asSequence()
            .filter { it.start < now && it.end > it.start && it.end <= now }
            .map { record ->
                val end = record.end
                val endDate = end.atZone(zone).toLocalDate()
                val startDate = record.start.atZone(zone).toLocalDate()
                val endTime = end.atZone(zone).toLocalTime()
                val eveningFragment = record.kind == null && startDate == endDate && record.start.atZone(zone).hour >= 18
                val date = if (eveningFragment) endDate.plusDays(1) else endDate
                val kind = record.kind ?: if (startDate != endDate || endTime <= LocalTime.NOON || eveningFragment) SleepKind.MAIN else SleepKind.NAP
                Triple(record.copy(end = end), date, kind)
            }
            .groupBy { (record, date, kind) -> Triple(date, record.source, kind) }

        val sourcesByDate = byDayAndSource.entries.groupBy { it.key.first }
        val days = sourcesByDate.mapValues { (date, entries) ->
            val sources = entries.groupBy { it.key.second }.map { (source, sourceEntries) ->
                val main = sourceEntries.filter { it.key.third == SleepKind.MAIN }.flatMap { it.value }
                val naps = sourceEntries.filter { it.key.third == SleepKind.NAP }.flatMap { it.value }
                source to SleepSourceSummary(
                    source = source,
                    mainMinutes = unionMinutes(main),
                    mainRecordIds = main.map { it.first.id }.distinct().sorted(),
                    napMinutes = unionMinutes(naps),
                    napRecordIds = naps.map { it.first.id }.distinct().sorted()
                )
            }.sortedWith(compareByDescending<Pair<String, SleepSourceSummary>> { it.second.mainMinutes }
                .thenByDescending { it.second.napMinutes }
                .thenBy { it.first })
            val (selectedSource, selected) = sources.first()
            SleepDaySummary(
                wakeDate = date,
                selectedSource = selectedSource,
                mainMinutes = selected.mainMinutes,
                mainRecordIds = selected.mainRecordIds,
                napMinutes = selected.napMinutes,
                napRecordIds = selected.napRecordIds,
                alternatives = sources.drop(1).map { it.second }
            )
        }.toSortedMap()
        return SleepSummaryResult(days)
    }

    private fun unionMinutes(records: List<Triple<SleepRecord, LocalDate, SleepKind>>): Long {
        val spans = records.map { it.first }.sortedBy { it.start }
        var total = Duration.ZERO
        var start: Instant? = null
        var end: Instant? = null
        for (record in spans) {
            val currentStart = start
            val currentEnd = end
            if (currentStart == null || currentEnd == null) {
                start = record.start
                end = record.end
            } else if (record.start <= currentEnd) {
                end = maxOf(currentEnd, record.end)
            } else {
                total = total.plus(Duration.between(currentStart, currentEnd))
                start = record.start
                end = record.end
            }
        }
        if (start != null && end != null) total = total.plus(Duration.between(start, end))
        return total.toMinutes()
    }
}
