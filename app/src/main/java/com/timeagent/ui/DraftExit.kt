package com.timeagent.ui

import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

@Composable
internal fun rememberDraftExit(
    id: String, fields: List<Any?>, busy: Boolean, onDiscard: () -> Unit, onKeep: (() -> Unit)?,
): () -> Unit {
    val baseline = rememberSaveable(id) { fields.toString() }
    var asking by rememberSaveable(id) { mutableStateOf(false) }
    if (asking) AlertDialog(onDismissRequest = { asking = false }, title = { Text("保留未保存的修改？") },
        text = { Text("保留后可回到当前页面继续编辑，尚未写入记录。") },
        confirmButton = { Column {
            if (onKeep != null) TextButton(onClick = { asking = false; onKeep() }) { Text("保留草稿") }
            TextButton(onClick = { asking = false; onDiscard() }) { Text("丢弃修改") }
        } },
        dismissButton = { TextButton(onClick = { asking = false }) { Text("继续编辑") } })
    return { if (!busy) { if (fields.toString() != baseline) asking = true else onDiscard() } }
}
