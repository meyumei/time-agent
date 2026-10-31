package com.timeagent.data

/** Only the bundled example import is eligible; a title alone never grants this scope. */
object SampleCourseScope {
    fun courses(courses: List<CourseEntity>) = courses.filter {
        it.sourceId == "example-v1" && it.termId == "example-2026-fall" && it.name.startsWith("示例")
    }.sortedBy { it.id }

    fun occurrences(courses: List<CourseEntity>, occurrences: List<CourseOccurrenceEntity>): List<CourseOccurrenceEntity> {
        val ids = courses(courses).map { it.id }.toSet()
        return occurrences.filter { it.courseId in ids && it.status != "CANCELLED" }.sortedBy { it.id }
    }
}

data class CourseBatchSnapshot(val occurrences: List<CourseOccurrenceEntity>)
