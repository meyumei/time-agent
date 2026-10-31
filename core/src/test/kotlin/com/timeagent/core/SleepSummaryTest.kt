package com.timeagent.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepSummaryTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = Instant.parse("2026-10-20T16:00:00Z")
    private fun record(id: String, source: String, start: String, end: String, kind: SleepKind? = null) =
        SleepRecord(id, source, Instant.parse(start), Instant.parse(end), kind)

    @Test fun `fragments and same day nap are grouped by wake date`() {
        val day = LocalDate.of(2026, 10, 20)
        val result = SleepSummary.aggregate(listOf(
            record("a", "watch", "2026-10-19T15:00:00Z", "2026-10-19T18:00:00Z"),
            record("b", "watch", "2026-10-19T19:00:00Z", "2026-10-19T23:00:00Z"),
            record("nap", "watch", "2026-10-20T05:00:00Z", "2026-10-20T06:00:00Z")
        ), today, zone)
        val summary = result.days.getValue(day)
        assertEquals(7 * 60L, summary.mainMinutes)
        assertEquals(listOf("a", "b"), summary.mainRecordIds)
        assertEquals(60L, summary.napMinutes)
        assertEquals(listOf("nap"), summary.napRecordIds)
    }

    @Test fun `overlaps union within source and competing sources are alternatives`() {
        val date = LocalDate.of(2026, 10, 20)
        val result = SleepSummary.aggregate(listOf(
            record("w1", "watch", "2026-10-19T15:00:00Z", "2026-10-19T20:00:00Z"),
            record("w2", "watch", "2026-10-19T19:00:00Z", "2026-10-19T23:00:00Z"),
            record("p1", "phone", "2026-10-19T15:00:00Z", "2026-10-19T23:00:00Z")
        ), today, zone)
        val summary = result.days.getValue(date)
        assertEquals(8 * 60L, summary.mainMinutes)
        assertEquals("phone", summary.selectedSource)
        assertEquals(listOf("watch"), summary.alternatives.map { it.source })
        assertEquals(8 * 60L, summary.alternatives.single().mainMinutes)
    }

    @Test fun `empty input produces no dates and future or invalid intervals are ignored`() {
        assertTrue(SleepSummary.aggregate(emptyList(), today, zone).days.isEmpty())
        val result = SleepSummary.aggregate(listOf(
            record("future", "watch", "2026-10-20T17:00:00Z", "2026-10-20T18:00:00Z"),
            record("invalid", "watch", "2026-10-20T08:00:00Z", "2026-10-20T07:00:00Z")
        ), today, zone)
        assertTrue(result.days.isEmpty())
    }

    @Test fun `duration uses elapsed instants across daylight saving changes`() {
        val date = LocalDate.of(2026, 3, 8)
        val result = SleepSummary.aggregate(listOf(
            record("dst", "watch", "2026-03-08T04:00:00Z", "2026-03-08T11:00:00Z", SleepKind.MAIN)
        ), today, ZoneId.of("America/New_York"))
        assertEquals(7 * 60L, result.days.getValue(date).mainMinutes)
    }
    @Test fun `evening fragment ending before midnight belongs to the following wake day`() {
        val result = SleepSummary.aggregate(listOf(
            record("first", "watch", "2026-10-19T14:00:00Z", "2026-10-19T15:30:00Z"),
            record("last", "watch", "2026-10-19T16:30:00Z", "2026-10-19T23:30:00Z")
        ), today, zone)
        assertEquals(510L, result.days.getValue(LocalDate.of(2026, 10, 20)).mainMinutes)
        assertEquals(listOf("first", "last"), result.days.getValue(LocalDate.of(2026, 10, 20)).mainRecordIds)
    }
    @Test fun `partially future sleep is not reported as a finished night`() {
        val result = SleepSummary.aggregate(listOf(SleepRecord("future-end", "watch", today.minusSeconds(3600), today.plusSeconds(3600))), today, zone)
        assertTrue(result.days.isEmpty())
    }
}
