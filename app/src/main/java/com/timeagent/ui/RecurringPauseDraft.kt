package com.timeagent.ui

import android.content.Context
import com.google.gson.Gson
import com.timeagent.core.RecurringPattern
import com.timeagent.data.RecurringRuleEntity
import java.time.LocalDate
import java.util.UUID

internal data class RecurringPauseDraft(
    val id: String,
    val original: RecurringRuleEntity,
    val pattern: RecurringPattern,
    val from: String,
    val requestId: String = UUID.randomUUID().toString(),
    val preview: RecurringSubmission? = null,
    val selectedScope: String? = null,
    val saved: Boolean = false,
) {
    fun entity() = original.copy(patternJson = Gson().toJson(pattern))

    companion object {
        fun create(rule: RecurringRuleEntity, pattern: RecurringPattern, today: LocalDate) =
            RecurringPauseDraft(rule.id, rule, pattern.copy(paused = !pattern.paused, effectiveFrom = today.toString()),
                today.toString())
    }
}

internal class RecurringPauseDraftStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("recurring_pause_drafts", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun read(id: String): RecurringPauseDraft? = preferences.getString(id, null)?.let { raw ->
        runCatching { gson.fromJson(raw, RecurringPauseDraft::class.java) }.getOrNull()
    }

    fun all(): List<RecurringPauseDraft> = preferences.all.keys.sorted().mapNotNull(::read)

    fun save(draft: RecurringPauseDraft): Boolean = runCatching {
        preferences.edit().putString(draft.id, gson.toJson(draft)).commit()
    }.getOrDefault(false)

    fun remove(id: String): Boolean = runCatching { preferences.edit().remove(id).commit() }.getOrDefault(false)
}
