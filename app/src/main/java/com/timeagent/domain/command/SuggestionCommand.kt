package com.timeagent.domain.command

import com.timeagent.suggestions.SuggestionAction

data class ApplySuggestion(
    override val requestId: String,
    val id: String,
    val expectedVersion: Long,
    val action: SuggestionAction,
    override val origin: Origin = Origin.UI,
) : Command

data class SetSuggestionPolicy(
    override val requestId: String,
    val type: String,
    val suppressed: Boolean,
    override val origin: Origin = Origin.UI,
) : Command
