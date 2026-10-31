package com.timeagent.core

import java.security.MessageDigest
import java.time.*

data class ImportTerm(val id: String, val firstWeekStart: LocalDate, val teachingWeeks: Int, val zone: ZoneId, val name: String = "学期")
data class PeriodTime(val start: LocalTime, val end: LocalTime)
data class ImportedCourse(
    val name: String, val teacher: String, val location: String,
    val dayOfWeek: Int, val startPeriod: Int, val endPeriod: Int,
    val weeks: Set<Int>, val externalKey: String? = null,
)
data class NormalizedCourse(val key: String, val name: String, val teacher: String, val location: String)
data class NormalizedOccurrence(val key: String, val courseKey: String, val date: LocalDate, val span: TimeSpan, val locationOverride: String? = null, val teacherOverride: String? = null)
data class ImportBundle(val sourceId: String, val term: ImportTerm, val courses: List<NormalizedCourse>, val occurrences: List<NormalizedOccurrence>)

interface CourseSourceAdapter {
    val sourceType: String
    fun parse(raw: String): ImportBundle
}

object CourseNormalizer {
    private fun stableKey(parts: List<String>): String = MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun normalize(sourceId: String, term: ImportTerm, periods: Map<Int, PeriodTime>, courses: List<ImportedCourse>): ImportBundle {
        require(sourceId.isNotBlank() && term.id.isNotBlank()) { "缺少来源或学期标识" }
        require(term.firstWeekStart.dayOfWeek == DayOfWeek.MONDAY) { "第一教学周须从周一开始" }
        require(term.teachingWeeks in 1..60) { "学期周数须在 1–60 之间" }
        require(courses.isNotEmpty() && courses.size <= 1000) { "课表为空或课程行过多" }
        val normalized = linkedMapOf<String, NormalizedCourse>()
        val occurrences = linkedMapOf<String, NormalizedOccurrence>()
        for (course in courses) {
            require(course.name.isNotBlank()) { "课程名称不能为空" }
            require(course.dayOfWeek in 1..7) { "星期须在 1–7 之间" }
            require(course.startPeriod > 0 && course.endPeriod >= course.startPeriod && course.endPeriod <= 50) { "节次范围无效" }
            require(course.weeks.isNotEmpty() && course.weeks.all { it in 1..term.teachingWeeks }) { "教学周超出学期范围" }
            for (period in course.startPeriod..course.endPeriod) {
                val timing = requireNotNull(periods[period]) { "缺少第 $period 节的学校时间配置" }
                require(timing.end > timing.start) { "节次结束时间必须晚于开始时间" }
                if (period > course.startPeriod) require(periods.getValue(period - 1).end <= timing.start) { "节次时间顺序无效" }
            }
            val key = stableKey(listOf(sourceId, term.id, course.externalKey?.takeIf { it.isNotBlank() } ?: listOf(course.name.trim(), course.teacher.trim(), course.location.trim()).joinToString("\u001f")))
            val definition = normalized.getOrPut(key) { NormalizedCourse(key, course.name.trim(), course.teacher.trim(), course.location.trim()) }
            for (week in course.weeks.sorted()) {
                val date = term.firstWeekStart.plusWeeks(week - 1L).plusDays(course.dayOfWeek - 1L)
                val span = TimeSpan(date.atTime(periods.getValue(course.startPeriod).start).atZone(term.zone).toInstant(), date.atTime(periods.getValue(course.endPeriod).end).atZone(term.zone).toInstant())
                val occurrenceKey = stableKey(listOf(key, date.toString(), span.start.toString(), span.end.toString()))
                occurrences[occurrenceKey] = NormalizedOccurrence(occurrenceKey, key, date, span, course.location.trim().takeIf { it != definition.location }, course.teacher.trim().takeIf { it != definition.teacher })
            }
        }
        return ImportBundle(sourceId, term, normalized.values.toList(), occurrences.values.sortedWith(compareBy<NormalizedOccurrence> { it.span.start }.thenBy { it.key }))
    }
}
