package com.timeagent.onboarding

import com.timeagent.data.LocalSnapshot

/** Example rows stay visible in the planner but never create personal targets or suggestions. */
fun LocalSnapshot.withoutSampleStatistics(): LocalSnapshot {
    val excluded = sampleRecords.groupBy { it.entityType }.mapValues { (_, rows) -> rows.map { it.entityId }.toSet() }
    val tasks = excluded[SampleEntityTypes.TASK].orEmpty()
    val plans = excluded[SampleEntityTypes.PLAN].orEmpty()
    val records = excluded[SampleEntityTypes.RECORD].orEmpty()
    val projects = excluded[SampleEntityTypes.PROJECT].orEmpty()
    return copy(
        tasks = this.tasks.filterNot { it.id in tasks },
        plans = this.plans.filterNot { it.id in plans },
        records = this.records.filterNot { it.id in records },
        projects = this.projects.filterNot { it.id in projects },
        placements = placements.filterNot { it.taskId in tasks },
        weeklyGoals = weeklyGoals.filterNot { it.projectId in projects },
        suggestions = suggestions.filterNot { it.taskId in tasks },
        entryContexts = entryContexts.filterNot { if (it.entityType == "PLAN") it.entityId in plans else it.entityId in records },
        sampleRecords = emptyList(),
    )
}
