package com.timeagent.domain.command

import com.timeagent.data.ReminderEntity
import com.timeagent.data.StaleChange
import com.timeagent.data.TaskEntity
import com.timeagent.data.TimeRepository
import com.timeagent.reminders.ReminderInstanceRepository

data class TaskLinkedDetails(
    val reminderVersions: Map<String, Long>,
    val reminders: List<ReminderEntity>,
    val attachmentVersions: Map<String, Long>,
    val linkedAttachmentIds: Set<String>,
    val attachmentIds: Set<String>,
)

/** Invoked only inside WriteTaskDetails' command transaction and history group. */
internal suspend fun TimeRepository.applyTaskLinkedDetails(task: TaskEntity, edits: TaskLinkedDetails) {
    val dao = database.dao()
    val invalid = applicationContext?.getString(com.timeagent.R.string.task_links_invalid)
        ?: "Task links are invalid. Nothing was saved; reopen the editor."
    val currentReminders = dao.reminders().filter { it.taskId == task.id }.associateBy { it.id }
    if (currentReminders.mapValues { it.value.version } != edits.reminderVersions) throw StaleChange()
    require(edits.reminders.map { it.id }.distinct().size == edits.reminders.size) { invalid }
    val currentAttachments = dao.attachments().filter { it.taskId == task.id && it.deletedAt == null }.associateBy { it.id }
    if (currentAttachments.keys != edits.linkedAttachmentIds) throw StaleChange()
    val attachments = (currentAttachments.keys + edits.attachmentIds).associateWith { id ->
        val item = dao.attachment(id) ?: throw StaleChange()
        if (item.version != edits.attachmentVersions[id] || item.deletedAt != null) throw StaleChange()
        if (id !in currentAttachments && (item.taskId != null || item.projectId != null)) throw StaleChange()
        item
    }

    val reminderWriter = ReminderInstanceRepository(this)
    val selectedReminders = edits.reminders.associateBy { it.id }
    for ((id, before) in currentReminders) {
        if (id !in selectedReminders) reminderWriter.applyMutation(ReminderMutation.Delete(id, before.version))
    }
    for (candidate in edits.reminders) {
        require(candidate.taskId == task.id) { invalid }
        val before = currentReminders[candidate.id]
        if (before == null) {
            if (dao.reminder(candidate.id) != null) throw StaleChange()
            require(candidate.ownerType == "TASK" && candidate.ownerId == task.id) { invalid }
        } else {
            require(candidate.ownerType == before.ownerType && candidate.ownerId == before.ownerId) { invalid }
        }
        // Delivery metadata may change without a user edit; do not restart a repeat tail.
        val normalized = candidate.copy(
            version = before?.version ?: 0,
            createdAt = before?.createdAt ?: 0,
            updatedAt = before?.updatedAt ?: 0,
            deliveredAt = before?.deliveredAt,
            enabled = if (candidate.remindAt <= now().toEpochMilli() &&
                (before == null || candidate.remindAt != before.remindAt)) false else candidate.enabled,
        )
        if (before != normalized) reminderWriter.applyMutation(ReminderMutation.Save(normalized, before?.version))
    }
    for ((id, before) in attachments) {
        val nextTaskId = task.id.takeIf { id in edits.attachmentIds }
        if (before.taskId != nextTaskId) linkAttachment(id, null, nextTaskId, before.version)
    }
}
