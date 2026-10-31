package com.timeagent.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class CourseImportTest {
    private val term = ImportTerm("term-2026", LocalDate.of(2026, 8, 31), 20, ZoneId.of("Asia/Shanghai"))
    private val periods = mapOf(1 to PeriodTime(LocalTime.of(8, 0), LocalTime.of(8, 45)), 2 to PeriodTime(LocalTime.of(8, 55), LocalTime.of(9, 40)))
    private val lesson = ImportedCourse("高等数学", "王老师", "A101", 1, 1, 2, setOf(1, 3, 5), "math-01")

    @Test fun `normalizer expands explicit weeks using school period times and timezone`() {
        val result = CourseNormalizer.normalize("manual-json", term, periods, listOf(lesson))
        assertEquals(3, result.occurrences.size)
        assertEquals(Instant.parse("2026-08-31T00:00:00Z"), result.occurrences.first().span.start)
        assertEquals(Instant.parse("2026-08-31T01:40:00Z"), result.occurrences.first().span.end)
        assertEquals(LocalDate.of(2026, 9, 14), result.occurrences[1].date)
    }
    @Test fun `normalization is deterministic and duplicate source rows collapse`() {
        val first = CourseNormalizer.normalize("manual-json", term, periods, listOf(lesson, lesson))
        val second = CourseNormalizer.normalize("manual-json", term, periods, listOf(lesson))
        assertEquals(second, first)
        assertEquals(1, first.courses.size)
        assertEquals(3, first.occurrences.map { it.key }.distinct().size)
    }
    @Test fun `missing school periods and out of term weeks reject whole preview`() {
        assertThrows(IllegalArgumentException::class.java) { CourseNormalizer.normalize("s", term, emptyMap(), listOf(lesson)) }
        assertThrows(IllegalArgumentException::class.java) { CourseNormalizer.normalize("s", term, periods, listOf(lesson.copy(weeks = setOf(21)))) }
        assertThrows(IllegalArgumentException::class.java) { CourseNormalizer.normalize("s", term, periods, listOf(lesson.copy(dayOfWeek = 0))) }
    }
    @Test fun `same source course id can have multiple weekly meeting patterns`() {
        val result = CourseNormalizer.normalize("manual-json", term, periods, listOf(lesson, lesson.copy(dayOfWeek = 3)))
        assertEquals(1, result.courses.size)
        assertEquals(6, result.occurrences.size)
        assertEquals(6, result.occurrences.map { it.key }.distinct().size)
    }
    @Test fun `one course can meet in different rooms without losing source detail`() {
        val result = CourseNormalizer.normalize("manual-json", term, periods, listOf(lesson, lesson.copy(dayOfWeek = 3, location = "B202")))
        assertEquals("A101", result.courses.single().location)
        assertEquals("B202", result.occurrences.first { it.date.dayOfWeek == DayOfWeek.WEDNESDAY }.locationOverride)
    }
}
