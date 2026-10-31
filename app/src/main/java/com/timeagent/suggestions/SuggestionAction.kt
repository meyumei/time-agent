package com.timeagent.suggestions

sealed interface SuggestionAction {
    data class Schedule(val startAt: Long, val endAt: Long) : SuggestionAction
    data class Postpone(val dueAt: Long) : SuggestionAction
    data class Snooze(val until: Long) : SuggestionAction
    data object StartTimer : SuggestionAction
}
