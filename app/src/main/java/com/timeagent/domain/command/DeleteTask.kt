package com.timeagent.domain.command

import com.timeagent.data.LinkedScheduleAction

data class TaskDeletionPreview(
    val taskId: String,
    val taskVersion: Long,
    val planVersions: Map<String, Long>,
    val reminderVersions: Map<String, Long>,
)

data class DeleteTask(
    override val requestId: String,
    val preview: TaskDeletionPreview,
    val linkedSchedules: LinkedScheduleAction?,
    override val origin: Origin = Origin.UI,
) : Command
