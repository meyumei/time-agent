package com.timeagent.ui.timeline

import java.util.EnumSet

enum class TimelineSourceFilter {
    PLAN,
    RECORD,
    COURSE,
    HEALTH,
}

data class TimelineFilterState(
    val plan: Boolean = true,
    val record: Boolean = true,
    val course: Boolean = true,
    val health: Boolean = true,
) {
    fun allows(kind: String): Boolean {
        val normalized = kind.trim()
        return when {
            normalized.equals("PLAN", ignoreCase = true) -> plan
            normalized.equals("RECORD", ignoreCase = true) -> record
            normalized.equals("COURSE", ignoreCase = true) -> course
            normalized.equals("HEALTH", ignoreCase = true) -> health
            else -> true
        }
    }

    fun isEnabled(filter: TimelineSourceFilter): Boolean = when (filter) {
        TimelineSourceFilter.PLAN -> plan
        TimelineSourceFilter.RECORD -> record
        TimelineSourceFilter.COURSE -> course
        TimelineSourceFilter.HEALTH -> health
    }

    fun withFilter(filter: TimelineSourceFilter, enabled: Boolean): TimelineFilterState = when (filter) {
        TimelineSourceFilter.PLAN -> copy(plan = enabled)
        TimelineSourceFilter.RECORD -> copy(record = enabled)
        TimelineSourceFilter.COURSE -> copy(course = enabled)
        TimelineSourceFilter.HEALTH -> copy(health = enabled)
    }

    fun toggle(filter: TimelineSourceFilter): TimelineFilterState =
        withFilter(filter, !isEnabled(filter))

    fun activeFilters(): EnumSet<TimelineSourceFilter> =
        EnumSet.noneOf(TimelineSourceFilter::class.java).apply {
            if (plan) add(TimelineSourceFilter.PLAN)
            if (record) add(TimelineSourceFilter.RECORD)
            if (course) add(TimelineSourceFilter.COURSE)
            if (health) add(TimelineSourceFilter.HEALTH)
        }

    fun allSelected(): Boolean = plan && record && course && health
}
