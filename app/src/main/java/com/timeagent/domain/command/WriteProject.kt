package com.timeagent.domain.command

import com.timeagent.data.ProjectEntity
import com.timeagent.data.TaskPlacementEntity
import com.timeagent.data.WeeklyGoalEntity

data class WriteProject(
    override val requestId: String,
    val mutation: ProjectMutation,
    override val origin: Origin = Origin.UI,
) : Command

sealed interface ProjectMutation {
    data class Save(val project: ProjectEntity, val expectedVersion: Long?) : ProjectMutation
    data class Delete(val id: String, val expectedVersion: Long) : ProjectMutation
    data class PlaceTask(val placement: TaskPlacementEntity, val expectedVersion: Long?) : ProjectMutation
    data class SaveGoal(val goal: WeeklyGoalEntity, val expectedVersion: Long?) : ProjectMutation
}
