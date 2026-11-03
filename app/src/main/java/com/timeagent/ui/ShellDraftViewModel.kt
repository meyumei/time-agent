package com.timeagent.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** Owns open editor references while the Activity is recreated; fields use SaveableStateHolder. */
internal class ShellDraftViewModel : ViewModel() {
    var editor by mutableStateOf<EditorRequest?>(null)
    val editorDrafts = mutableStateMapOf<String, Pair<Int, EditorRequest>>()
    val noteDrafts = mutableStateMapOf<String, Pair<Int, NoteTarget>>()
}
