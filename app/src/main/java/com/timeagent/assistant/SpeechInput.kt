package com.timeagent.assistant

import android.content.Context

enum class SpeechInputStatus { IDLE, PREPARING, STARTING, LISTENING, PROCESSING }

interface SpeechInput {
    val available: Boolean
    fun start()
    fun stop()
    fun cancel()
    fun close()
}

data class SpeechCallbacks(
    val onText: (String) -> Unit,
    val onState: (SpeechInputStatus, String?) -> Unit,
    val onFailure: () -> Unit,
    val onPartial: (String) -> Unit = {},
    val onSuggestion: (String?) -> Unit = {},
)

typealias SpeechInputFactory = (Context, SpeechCallbacks) -> SpeechInput

val OfflineSpeechInputFactory: SpeechInputFactory = { context, callbacks -> OfflineSpeechInput(context, callbacks) }
