package com.timeagent.ui

import android.content.Context
import com.google.gson.Gson
import com.timeagent.data.ProjectEntity
import com.timeagent.data.TaskPlacementEntity
import java.util.UUID

internal data class ProjectTaskFields(
    val title: String = "",
    val minutesText: String = "30",
    val parentId: String? = null,
) {
    fun values(): List<Any?> = listOf(title, minutesText, parentId)
}

internal data class ProjectTaskEditorDraft(
    val id: String,
    val project: ProjectEntity,
    val seenParents: List<TaskPlacementEntity>,
    val fields: ProjectTaskFields,
    val baseline: ProjectTaskFields,
    val requestId: String = UUID.randomUUID().toString(),
    val saved: Boolean = false,
) {
    companion object {
        fun create(project: ProjectEntity, parents: List<TaskPlacementEntity>, parentId: String?): ProjectTaskEditorDraft {
            val fields = ProjectTaskFields(parentId = parentId)
            return ProjectTaskEditorDraft(UUID.randomUUID().toString(), project, parents.toList(), fields, fields)
        }
    }
}

internal class ProjectTaskEditorDraftStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("project_task_editor_drafts", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun read(id: String): ProjectTaskEditorDraft? = preferences.getString(id, null)?.let { raw ->
        runCatching { gson.fromJson(raw, ProjectTaskEditorDraft::class.java) }.getOrNull()
    }

    fun all(): List<ProjectTaskEditorDraft> = preferences.all.keys.sorted().mapNotNull(::read)

    fun save(draft: ProjectTaskEditorDraft): Boolean = runCatching {
        preferences.edit().putString(draft.id, gson.toJson(draft)).commit()
    }.getOrDefault(false)

    fun remove(id: String): Boolean = runCatching { preferences.edit().remove(id).commit() }.getOrDefault(false)
}
