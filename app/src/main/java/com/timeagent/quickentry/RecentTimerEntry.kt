package com.timeagent.quickentry

internal fun List<QuickEntryEntity>.recentTimerEntry(): QuickEntryEntity? =
    filter { it.actionType in listOf(QuickEntryAction.START_TIMER, QuickEntryAction.START_FOCUS) }
        .maxWithOrNull(compareBy<QuickEntryEntity> { it.lastUsedAt ?: Long.MIN_VALUE }
            .thenBy { it.usageCount }.thenByDescending { it.sortOrder }.thenByDescending { it.id })
