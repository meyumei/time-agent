package com.timeagent.quickentry

import android.content.Context
import com.google.gson.Gson

internal data class TemplateEditorDraft(
    val template: PlanTemplateEntity,
    val originalItems: List<PlanTemplateItemEntity>,
    val name: String,
    val description: String,
    val items: List<PlanTemplateItemEntity>,
    val editingItem: PlanTemplateItemEntity? = null,
    val itemFields: Map<String, String> = emptyMap(),
)

/** Drafts are not business writes; preserve the version originally seen, not the newest template. */
internal class TemplateEditorDraftStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("plan_template_drafts", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun read(id: String): TemplateEditorDraft? = preferences.getString(id, null)?.let { raw ->
        runCatching { gson.fromJson(raw, TemplateEditorDraft::class.java) }.getOrNull()
    }

    fun all(): List<TemplateEditorDraft> = preferences.all.keys.sorted().mapNotNull(::read)

    fun save(draft: TemplateEditorDraft): Boolean = preferences.edit()
        .putString(draft.template.id, gson.toJson(draft)).commit()

    fun remove(id: String): Boolean = preferences.edit().remove(id).commit()
}
