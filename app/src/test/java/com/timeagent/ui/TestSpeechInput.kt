package com.timeagent.ui

import com.timeagent.assistant.*

/** UI fixtures inject a controlled device boundary; native model packaging is checked separately. */
class TestSpeechInput(private val callbacks: SpeechCallbacks) : SpeechInput {
    override val available = true
    init { callbacks.onState(SpeechInputStatus.IDLE, null) }
    override fun start() { callbacks.onState(SpeechInputStatus.LISTENING, null) }
    override fun stop() { callbacks.onState(SpeechInputStatus.PROCESSING, null) }
    override fun cancel() { callbacks.onState(SpeechInputStatus.IDLE, null) }
    override fun close() {}
}
