package com.timeagent.data

data class TaskClassification(
    val tags: List<TagEntity> = emptyList(),
    val taskTagIds: Map<String, Set<String>> = emptyMap(),
    val loading: Boolean = true,
)
