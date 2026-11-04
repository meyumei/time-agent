package com.timeagent.domain.command

import com.timeagent.quickentry.PlanTemplateEntity
import com.timeagent.quickentry.PlanTemplateItemEntity
import com.timeagent.quickentry.QuickEntryEntity
import com.timeagent.data.ScheduleBlockEntity
import com.timeagent.data.TimeRecordEntity

/**
 * One visible tenant of the single main-timer slot. Quick entries never switch a running timer,
 * workout or focus silently: the UI first shows these exact rows and passes the same list back as
 * `seenSlots`. A changed id/version between reading and committing is a conflict, not a switch.
 */
data class QuickEntrySlot(
    val kind: String,
    val id: String,
    val version: Long,
    val label: String,
)

sealed interface QuickEntryMutation {
    data class Save(val item: QuickEntryEntity, val expectedVersion: Long? = null) : QuickEntryMutation

    data class Delete(val id: String, val expectedVersion: Long) : QuickEntryMutation

    data class SetPinned(val id: String, val expectedVersion: Long, val pinned: Boolean) : QuickEntryMutation

    data class Reorder(
        val orderedIds: List<String>,
        val expectedVersions: Map<String, Long> = emptyMap(),
    ) : QuickEntryMutation

    /** Usage metadata is not journaled and never creates an undo entry. */
    data class Touch(val id: String) : QuickEntryMutation

    data class SaveTemplate(
        val template: PlanTemplateEntity,
        val items: List<PlanTemplateItemEntity>,
        val expectedVersion: Long? = null,
    ) : QuickEntryMutation

    data class DeleteTemplate(val id: String, val expectedVersion: Long) : QuickEntryMutation

    data class ApplyTemplate(
        val templateId: String,
        val startDate: String,
        val itemIds: List<String>,
        /** Version the user saw in preview; a later template edit is a conflict, not a surprise. */
        val templateVersion: Long? = null,
    ) : QuickEntryMutation

    /**
     * Start a timer/focus from a quick entry. `seenSlots` is exactly what the confirmation sheet
     * displayed; an empty list means the user saw no running main timer.
     */
    data class Perform(
        val entryId: String,
        val expectedVersion: Long,
        val seenSlots: List<QuickEntrySlot> = emptyList(),
    ) : QuickEntryMutation

    data class CommitDraft(
        val entryId: String,
        val expectedVersion: Long,
        val plan: ScheduleBlockEntity? = null,
        val record: TimeRecordEntity? = null,
        val allowFuture: Boolean = false,
    ) : QuickEntryMutation
}

/**
 * The only command entry for quick entries, templates and template application. CommandExecutor
 * runs `QuickEntryRepository.applyMutation` inside its transaction + commandWrite block, so a
 * template application journals all created schedules/tasks as one undo unit and a retried
 * request id never writes twice.
 */
data class WriteQuickEntry(
    override val requestId: String,
    val mutation: QuickEntryMutation,
    override val origin: Origin = Origin.UI,
) : Command

/**
 * Serialized payload returned from [WriteQuickEntry]. Kept as one flat shape so the executor can
 * use `gson.toJson(result)` without knowing the mutation that produced it.
 */
data class QuickEntryMutationResult(
    val entry: QuickEntryEntity? = null,
    val template: PlanTemplateEntity? = null,
    val items: List<PlanTemplateItemEntity> = emptyList(),
    val recordId: String? = null,
    val focusSessionId: String? = null,
    val createdScheduleIds: List<String> = emptyList(),
    val createdTaskIds: List<String> = emptyList(),
    val deletedId: String? = null,
    val warnings: List<String> = emptyList(),
)
