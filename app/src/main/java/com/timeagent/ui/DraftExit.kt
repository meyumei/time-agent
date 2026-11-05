package com.timeagent.ui

import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

@Composable
internal fun rememberDraftExit(
    id: String, fields: List<Any?>, busy: Boolean, onDiscard: () -> Unit, onKeep: (() -> Unit)?,
    initialFields: List<Any?> = fields,
): () -> Unit {
    val gson = remember { com.google.gson.Gson() }
    val baseline = rememberSaveable(id) { gson.toJson(initialFields) }
    var asking by rememberSaveable(id) { mutableStateOf(false) }
    if (asking) AlertDialog(onDismissRequest = { asking = false }, title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_draft_exit_001)) },
        text = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_draft_exit_002)) },
        confirmButton = { Column {
            if (onKeep != null) TextButton(onClick = { asking = false; onKeep() }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_draft_exit_003)) }
            TextButton(onClick = { asking = false; onDiscard() }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_draft_exit_004)) }
        } },
        dismissButton = { TextButton(onClick = { asking = false }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_attachment_screen_023)) } })
    return { if (!busy) { if (gson.toJson(fields) != baseline) asking = true else onDiscard() } }
}
