package com.timeagent.ui

import android.content.Context
import com.google.gson.Gson
import com.timeagent.data.ProjectEntity
import java.util.UUID

internal data class ProjectEditorDraft(
    val id: String,
    val original: ProjectEntity?,
    val name: String,
    val dueText: String,
    val status: String,
    val initialDueText: String,
    val requestId: String = UUID.randomUUID().toString(),
    val saved: Boolean = false,
) {
    companion object {
        fun create(original: ProjectEntity?, dueText: String): ProjectEditorDraft = ProjectEditorDraft(
            original?.id ?: UUID.randomUUID().toString(), original,
            original?.name.orEmpty(), dueText, original?.status ?: "ACTIVE", dueText,
        )
    }
}

internal class ProjectEditorDraftStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("project_editor_drafts", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun read(id: String): ProjectEditorDraft? = preferences.getString(id, null)?.let { raw ->
        runCatching { gson.fromJson(raw, ProjectEditorDraft::class.java) }.getOrNull()
    }

    fun all(): List<ProjectEditorDraft> = preferences.all.keys.sorted().mapNotNull(::read)

    fun save(draft: ProjectEditorDraft): Boolean = runCatching {
        preferences.edit().putString(draft.id, gson.toJson(draft)).commit()
    }.getOrDefault(false)

    fun remove(id: String): Boolean = runCatching { preferences.edit().remove(id).commit() }.getOrDefault(false)
}
