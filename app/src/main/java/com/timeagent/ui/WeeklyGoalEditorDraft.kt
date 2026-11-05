package com.timeagent.ui

import android.content.Context
import com.google.gson.Gson
import com.timeagent.data.WeeklyGoalEntity
import java.util.UUID

internal data class WeeklyGoalFields(
    val useProject: Boolean,
    val projectId: String?,
    val activityId: String? = null,
    val weekText: String,
    val minutesText: String = "",
) {
    fun values(): List<Any?> = listOf(useProject, projectId, activityId, weekText, minutesText)
}

internal data class WeeklyGoalEditorDraft(
    val id: String,
    val scopeProjectId: String?,
    val seenGoals: List<WeeklyGoalEntity>,
    val fields: WeeklyGoalFields,
    val baseline: WeeklyGoalFields,
    val requestId: String = UUID.randomUUID().toString(),
    val saved: Boolean = false,
) {
    companion object {
        fun create(projectId: String?, week: String, goals: List<WeeklyGoalEntity>): WeeklyGoalEditorDraft {
            val fields = WeeklyGoalFields(projectId != null, projectId, weekText = week)
            return WeeklyGoalEditorDraft(UUID.randomUUID().toString(), projectId, goals.toList(), fields, fields)
        }
    }
}

internal class WeeklyGoalEditorDraftStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("weekly_goal_editor_drafts", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun read(id: String): WeeklyGoalEditorDraft? = preferences.getString(id, null)?.let { raw ->
        runCatching { gson.fromJson(raw, WeeklyGoalEditorDraft::class.java) }.getOrNull()
    }

    fun all(): List<WeeklyGoalEditorDraft> = preferences.all.keys.sorted().mapNotNull(::read)

    fun save(draft: WeeklyGoalEditorDraft): Boolean = runCatching {
        preferences.edit().putString(draft.id, gson.toJson(draft)).commit()
    }.getOrDefault(false)

    fun remove(id: String): Boolean = runCatching { preferences.edit().remove(id).commit() }.getOrDefault(false)
}
