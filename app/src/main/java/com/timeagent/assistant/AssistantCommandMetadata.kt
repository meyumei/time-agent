package com.timeagent.assistant

import com.timeagent.domain.command.Origin

internal fun AssistantMutation.createdId(): String = when (this) {
    is AssistantMutation.SaveTask -> task.id
    is AssistantMutation.SavePlan -> plan.id
    is AssistantMutation.SaveRecord -> record.id
    is AssistantMutation.SaveEvent -> plan.id
    is AssistantMutation.SaveIdea -> idea.id
    is AssistantMutation.SaveReminder -> reminder.id
    is AssistantMutation.SaveCourse -> occurrence.id
    is AssistantMutation.StartTimer -> requestId
    is AssistantMutation.StopTimer -> recordId
    is AssistantMutation.DeleteRecord -> id
    is AssistantMutation.CancelSampleCourses -> requestId
    is AssistantMutation.Batch -> mutations.first().createdId()
}

internal fun AssistantMutation.fromOrigin(origin: Origin): AssistantMutation {
    if (origin !is Origin.FISH) return this
    return when (this) {
        is AssistantMutation.Batch -> copy(mutations = mutations.map { it.fromOrigin(origin) })
        is AssistantMutation.SaveTask -> if (expectedVersion == null) copy(task = task.copy(source = "FISH")) else this
        is AssistantMutation.SaveRecord -> if (expectedVersion == null) copy(record = record.copy(source = "FISH")) else this
        is AssistantMutation.SavePlan -> if (expectedVersion == null) copy(plan = plan.copy(source = "FISH", sourceRef = "${origin.sessionId}/${origin.messageId}")) else this
        is AssistantMutation.SaveEvent -> copy(task = task.copy(source = "FISH"),
            plan = plan.copy(source = "FISH", sourceRef = "${origin.sessionId}/${origin.messageId}"))
        else -> this
    }
}

internal fun AssistantMutation.deletedCount(): Int = when (this) {
    is AssistantMutation.Batch -> mutations.sumOf { it.deletedCount() }
    is AssistantMutation.CancelSampleCourses -> occurrences.size
    is AssistantMutation.DeleteRecord -> 1
    is AssistantMutation.SaveTask -> if (task.deletedAt != null || task.status == "CANCELLED") 1 else 0
    is AssistantMutation.SavePlan -> if (plan.deletedAt != null || plan.status == "CANCELLED") 1 else 0
    is AssistantMutation.SaveCourse -> if (occurrence.status == "CANCELLED") 1 else 0
    is AssistantMutation.SaveIdea -> if (idea.deletedAt != null) 1 else 0
    is AssistantMutation.SaveReminder -> if (!reminder.enabled) 1 else 0
    else -> 0
}
