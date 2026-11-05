package com.timeagent.ui

import android.content.Context
import com.google.gson.Gson
import com.timeagent.core.RecurringPattern
import com.timeagent.data.RecurringRuleEntity
import com.timeagent.recurring.RuleChangePreview
import com.timeagent.recurring.RuleChangeScope
import com.timeagent.ui.components.RecurrenceInputDraft
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

internal data class RecurringEditorFields(
    val title: String,
    val pattern: RecurringPattern,
    val projectId: String?,
    val input: RecurrenceInputDraft,
) {
    fun values(): List<Any?> = listOf(title, pattern, projectId, input.durationText, input.leadText)
}

internal data class RecurringSubmission(
    val scope: String,
    val editable: Int? = null,
    val protectedCount: Int? = null,
    val firstUnmaterializedDate: String? = null,
    val versions: String? = null,
) {
    fun preview(): RuleChangePreview? = firstUnmaterializedDate?.let {
        RuleChangePreview(requireNotNull(editable), requireNotNull(protectedCount),
            LocalDate.parse(it), requireNotNull(versions))
    }

    companion object {
        fun create(scope: RuleChangeScope, preview: RuleChangePreview?) = RecurringSubmission(
            scope.name, preview?.editable, preview?.protectedCount, preview?.firstUnmaterializedDate?.toString(), preview?.versions)
    }
}

internal data class RecurringEditorDraft(
    val id: String,
    val original: RecurringRuleEntity?,
    val fields: RecurringEditorFields,
    val baseline: RecurringEditorFields,
    val createdAt: Long,
    val requestId: String = UUID.randomUUID().toString(),
    val submission: RecurringSubmission? = null,
    val saved: Boolean = false,
) {
    fun entity(): RecurringRuleEntity = (original ?: RecurringRuleEntity(
        id = id, title = "", patternJson = "", domain = "HABIT", createdAt = createdAt,
    )).copy(title = fields.title.trim(), patternJson = Gson().toJson(fields.pattern), projectId = fields.projectId)

    companion object {
        fun create(original: RecurringRuleEntity?, today: LocalDate, zone: ZoneId, nowMillis: Long,
            from: String = today.toString()): RecurringEditorDraft {
            val id = original?.id ?: UUID.randomUUID().toString()
            val old = original?.let { runCatching {
                Gson().fromJson(it.patternJson, RecurringPattern::class.java)
                    .takeIf { value -> value.frequency.isNotBlank() && value.startDate.isNotBlank() }
            }.getOrNull() }
            val pattern = old?.copy(effectiveFrom = from) ?: RecurringPattern(
                id = id, frequency = "DAILY", startDate = today.toString(), effectiveFrom = from,
                durationMinutes = 30, zoneId = zone.id)
            val fields = RecurringEditorFields(original?.title.orEmpty(), pattern, original?.projectId, RecurrenceInputDraft.from(pattern))
            return RecurringEditorDraft(id, original, fields, fields, original?.createdAt ?: nowMillis)
        }
    }
}

internal class RecurringEditorDraftStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("recurring_editor_drafts", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun read(id: String): RecurringEditorDraft? = preferences.getString(id, null)?.let { raw ->
        runCatching { gson.fromJson(raw, RecurringEditorDraft::class.java) }.getOrNull()
    }

    fun all(): List<RecurringEditorDraft> = preferences.all.keys.sorted().mapNotNull(::read)

    fun save(draft: RecurringEditorDraft): Boolean = runCatching {
        preferences.edit().putString(draft.id, gson.toJson(draft)).commit()
    }.getOrDefault(false)

    fun remove(id: String): Boolean = runCatching { preferences.edit().remove(id).commit() }.getOrDefault(false)
}
