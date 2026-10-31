package com.timeagent.assistant

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Coordinates one cloud interpretation request for its screen. */
class AssistantRequestController(private val scope: CoroutineScope) {
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private var job: Job? = null
    private var generation = 0L

    fun start(action: suspend () -> CloudResult, deliver: (CloudResult) -> Unit): Boolean {
        if (_busy.value) return false
        val token = ++generation
        _busy.value = true
        job = scope.launch {
            try { val result = action(); ensureActive(); if (token == generation) deliver(result) }
            finally { if (token == generation) { _busy.value = false; job = null } }
        }
        return true
    }

    fun cancel() {
        generation++
        val previous = job
        job = null
        _busy.value = false
        previous?.cancel()
    }
}
