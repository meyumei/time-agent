package com.timeagent.ui

import android.content.Context
import com.google.gson.Gson
import com.timeagent.core.RecurringPattern
import com.timeagent.data.RecurringRuleEntity
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

internal data class DeadlineFields(
    val title: String,
    val category: String,
    val dateText: String,
    val timeText: String,
    val frequency: String,
    val leadText: String,
    val projectId: String?,
    val reference: String,
    val fromText: String,
) {
    fun values(): List<Any?> = listOf(title, category, dateText, timeText, frequency, leadText, projectId, reference, fromText)
}

internal data class DeadlineEditorDraft(
    val id: String,
    val original: RecurringRuleEntity?,
    val fields: DeadlineFields,
    val baseline: DeadlineFields,
    val baseDate: String,
    val zoneId: String,
    val requestId: String = UUID.randomUUID().toString(),
    val saved: Boolean = false,
) {
    companion object {
        fun create(original: RecurringRuleEntity?, from: LocalDate?, today: LocalDate, zone: ZoneId, defaultCategory: String): DeadlineEditorDraft {
            val pattern = original?.let { runCatching { Gson().fromJson(it.patternJson, RecurringPattern::class.java) }.getOrNull() }
            val fields = DeadlineFields(
                original?.title.orEmpty(), original?.category?.ifBlank { defaultCategory } ?: defaultCategory,
                pattern?.startDate ?: today.plusDays(1).toString(),
                pattern?.minuteOfDay?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "09:00",
                pattern?.frequency ?: "ONCE", pattern?.leadMinutes?.let { (it / 1440).toString() }.orEmpty(),
                original?.projectId, original?.reference.orEmpty(), (from ?: today).toString(),
            )
            return DeadlineEditorDraft(original?.id ?: UUID.randomUUID().toString(), original, fields, fields,
                today.toString(), zone.id)
        }
    }
}

internal class DeadlineEditorDraftStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("deadline_editor_drafts", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun read(id: String): DeadlineEditorDraft? = preferences.getString(id, null)?.let { raw ->
        runCatching { gson.fromJson(raw, DeadlineEditorDraft::class.java) }.getOrNull()
    }

    fun all(): List<DeadlineEditorDraft> = preferences.all.keys.sorted().mapNotNull(::read)

    fun save(draft: DeadlineEditorDraft): Boolean = runCatching {
        preferences.edit().putString(draft.id, gson.toJson(draft)).commit()
    }.getOrDefault(false)

    fun remove(id: String): Boolean = runCatching { preferences.edit().remove(id).commit() }.getOrDefault(false)
}
