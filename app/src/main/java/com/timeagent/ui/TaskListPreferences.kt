package com.timeagent.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * Visual-only task list preferences. No task, project, tag, or tag link is ever stored here.
 *
 * Manual order is stored per project/tag scope. [loadOrder] filters out ids that no longer exist
 * and appends new ids in their current list order, so a new task never disappears and no limit is
 * applied.
 */
class TaskListPreferences(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun readProject(): String? =
        preferences.getString(KEY_PROJECT, null)?.takeIf { it.isNotBlank() }

    fun saveProject(projectId: String?) {
        val editor = preferences.edit()
        if (projectId == null || projectId.isBlank()) {
            editor.remove(KEY_PROJECT)
        } else {
            editor.putString(KEY_PROJECT, projectId)
        }
        editor.apply()
    }

    fun readTag(): String? =
        preferences.getString(KEY_TAG, null)?.takeIf { it.isNotBlank() }

    fun saveTag(tagId: String?) {
        val editor = preferences.edit()
        if (tagId == null || tagId.isBlank()) {
            editor.remove(KEY_TAG)
        } else {
            editor.putString(KEY_TAG, tagId)
        }
        editor.apply()
    }

    fun readSort(): String {
        val stored = preferences.getString(KEY_SORT, SORT_DUE)
        return stored?.takeIf { it in SORTS } ?: SORT_DUE
    }

    fun saveSort(sort: String) {
        if (sort !in SORTS) return
        preferences.edit().putString(KEY_SORT, sort).apply()
    }

    fun readShowDone(): Boolean = preferences.getBoolean(KEY_SHOW_DONE, false)

    fun saveShowDone(show: Boolean) {
        preferences.edit().putBoolean(KEY_SHOW_DONE, show).apply()
    }

    /** Returns stored ids for this scope, then appends any new current ids without a cap. */
    fun loadOrder(projectId: String?, tagId: String?, currentIds: List<String>): List<String> {
        val stored = preferences.getString(orderKey(projectId, tagId), null)
            ?.split(ORDER_SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()
        val current = currentIds.toSet()
        val kept = stored.filter { it in current }
        return kept + currentIds.filterNot { it in kept }
    }

    fun saveOrder(projectId: String?, tagId: String?, order: List<String>) {
        val value = order.filter { it.isNotBlank() }.distinct().joinToString(ORDER_SEPARATOR)
        preferences.edit().putString(orderKey(projectId, tagId), value).apply()
    }

    fun loadSubtaskOrder(taskId: String, currentIds: List<String>): List<String> {
        val current = currentIds.toSet()
        val stored = preferences.getString(SUBTASK_ORDER_PREFIX + taskId, null)
            ?.split(ORDER_SEPARATOR)?.filter { it in current }?.distinct().orEmpty()
        return stored + currentIds.filterNot { it in stored }
    }

    fun saveSubtaskOrder(taskId: String, order: List<String>) {
        preferences.edit().putString(SUBTASK_ORDER_PREFIX + taskId,
            order.distinct().joinToString(ORDER_SEPARATOR)).apply()
    }

    private fun orderKey(projectId: String?, tagId: String?): String =
        "$ORDER_KEY_PREFIX${projectId ?: "*"}\u0001${tagId ?: "*"}"

    companion object {
        const val SORT_DUE = "DUE"
        const val SORT_PRIORITY = "PRIORITY"
        const val SORT_MANUAL = "MANUAL"

        private const val PREFERENCES_NAME = "task_list_visual"
        private const val KEY_PROJECT = "selectedProject"
        private const val KEY_TAG = "selectedTag"
        private const val KEY_SORT = "sort"
        private const val KEY_SHOW_DONE = "showDone"
        private const val ORDER_KEY_PREFIX = "order\u0001"
        private const val SUBTASK_ORDER_PREFIX = "subtasks\u0001"
        private const val ORDER_SEPARATOR = "\n"
        private val SORTS = setOf(SORT_DUE, SORT_PRIORITY, SORT_MANUAL)
    }
}
