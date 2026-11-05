package com.timeagent.quickentry

import android.content.Context
import com.google.gson.Gson
import java.util.UUID

internal data class QuickEntryEditorDraft(
    val original: QuickEntryEntity?,
    val entry: QuickEntryEntity,
    val durationText: String,
    val baselineEntry: QuickEntryEntity = entry,
    val requestId: String = UUID.randomUUID().toString(),
    val saved: Boolean = false,
) {
    companion object {
        fun create(original: QuickEntryEntity?, activityId: String): QuickEntryEditorDraft {
            val entry = original ?: QuickEntryEntity(UUID.randomUUID().toString(), activityId)
            return QuickEntryEditorDraft(
                original, entry.copy(color = entry.color.takeIf { it != 0L } ?: 0xFF5756B7L),
                entry.defaultDurationMinutes?.toString().orEmpty(),
            )
        }
    }
}

/** The original entity and request id survive process death without refreshing the seen version. */
internal class QuickEntryEditorDraftStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("quick_entry_drafts", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun read(id: String): QuickEntryEditorDraft? = preferences.getString(id, null)?.let { raw ->
        runCatching { gson.fromJson(raw, QuickEntryEditorDraft::class.java) }.getOrNull()
    }

    fun all(): List<QuickEntryEditorDraft> = preferences.all.keys.sorted().mapNotNull(::read)

    fun save(draft: QuickEntryEditorDraft): Boolean = runCatching {
        preferences.edit().putString(draft.entry.id, gson.toJson(draft)).commit()
    }.getOrDefault(false)

    fun remove(id: String): Boolean = runCatching { preferences.edit().remove(id).commit() }.getOrDefault(false)
}
