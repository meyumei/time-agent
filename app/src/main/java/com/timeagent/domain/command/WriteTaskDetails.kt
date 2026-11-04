package com.timeagent.domain.command

import com.timeagent.data.TaskEntity
import com.timeagent.data.ScheduleBlockEntity

data class WriteTaskDetails(
    override val requestId: String,
    val task: TaskEntity,
    val expectedVersion: Long?,
    val updateOrganization: Boolean = false,
    val projectId: String? = null,
    val expectedPlacementVersion: Long? = null,
    val tagIds: List<String> = emptyList(),
    val expectedTagIds: List<String> = emptyList(),
    val newPlan: ScheduleBlockEntity? = null,
    val linkedDetails: TaskLinkedDetails? = null,
    val newSubtasks: List<TaskEntity> = emptyList(),
    override val origin: Origin = Origin.UI,
) : Command
