package com.timeagent.ui

import com.timeagent.core.TimeSpan
import org.junit.Test
import org.junit.Assert.*
import java.time.Instant

class TimelineConflictsTest {
    private val start = Instant.parse("2026-10-19T00:00:00Z")
    private fun item(id: String, kind: ItemKind, first: Long, last: Long) = TimelineItem(id, kind, id, TimeSpan(start.plusSeconds(first), start.plusSeconds(last)), entity = Unit)
    @Test fun `conflicts expose the exact overlap once and ignore actual records`() {
        val items = listOf(item("course", ItemKind.COURSE, 0, 7200), item("plan", ItemKind.PLAN, 3600, 10800), item("record", ItemKind.RECORD, 0, 86400))
        val conflict = timelineConflicts(items).single()
        assertEquals(start.plusSeconds(3600), conflict.overlap.start)
        assertEquals(start.plusSeconds(7200), conflict.overlap.end)
        assertEquals(setOf("course", "plan"), setOf(conflict.first.id, conflict.second.id))
    }
    @Test fun `adjacent events and duplicate entries do not generate false warnings`() {
        val first = item("course", ItemKind.COURSE, 0, 3600)
        assertTrue(timelineConflicts(listOf(first, first, item("plan", ItemKind.PLAN, 3600, 7200))).isEmpty())
    }

    @Test fun `conflict groups contain connected scheduled chains in start order`() {
        val first = item("a", ItemKind.COURSE, 0, 600)
        val middle = item("b", ItemKind.PLAN, 300, 900)
        val last = item("c", ItemKind.COURSE, 840, 1200)
        val secondGroupFirst = item("d", ItemKind.COURSE, 1800, 2400)
        val secondGroupLast = item("e", ItemKind.PLAN, 2100, 2700)
        val groups = timelineConflictGroups(listOf(last, secondGroupLast, middle, secondGroupFirst, first))

        assertEquals(listOf(listOf("a", "b", "c"), listOf("d", "e")), groups.map { group -> group.map { it.id } })
    }

    @Test fun `actual records never bridge groups and touching or duplicate events are not a group`() {
        val first = item("a", ItemKind.COURSE, 0, 600)
        val adjacent = item("adjacent", ItemKind.PLAN, 600, 1200)
        val recordBridge = item("actual", ItemKind.RECORD, 300, 2100)
        val distant = item("distant", ItemKind.COURSE, 1800, 2400)

        assertTrue(timelineConflictGroups(listOf(first, first, adjacent, recordBridge, distant)).isEmpty())
        val overlap = item("overlap", ItemKind.PLAN, 300, 900)
        assertEquals(listOf(listOf("a", "overlap", "adjacent")), timelineConflictGroups(listOf(first, overlap, adjacent, recordBridge, distant)).map { group -> group.map { it.id } })
    }

    @Test fun `sparse schedules preserve exact conflict ordering while excluding duplicate and actual items`() {
        val sparse = (0L until 2000L).map { index -> item("sparse-$index", ItemKind.COURSE, index * 120, index * 120 + 60) }
        val firstOverlap = item("overlap-first", ItemKind.PLAN, 30, 90)
        val lastOverlap = item("overlap-last", ItemKind.PLAN, 1999 * 120L + 30, 1999 * 120L + 90)
        val conflicts = timelineConflicts(sparse + firstOverlap + lastOverlap + firstOverlap + item("actual", ItemKind.RECORD, 0, 999999))

        assertEquals(listOf("sparse-0" to "overlap-first", "sparse-1999" to "overlap-last"), conflicts.map { it.first.id to it.second.id })
        assertTrue(conflicts.all { it.overlap.seconds == 30L })
    }
}
