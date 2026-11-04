package com.timeagent.ui

internal data class TaskSubtaskDraft(
    val id: String,
    val title: String,
    val completed: Boolean = false,
    val isNew: Boolean = false,
)
