package com.timeagent.assistant

/** Keeps an early speech result private until the finger is released; cancellation discards it. */
class HoldToTalkSession(
    private val start: () -> Unit,
    private val finish: () -> Unit,
    private val cancelInput: () -> Unit,
    private val deliver: (String) -> Unit,
) {
    private var holding = false
    private var accepting = false
    private var pending: String? = null
    fun begin() {
        if (accepting) cancel()
        holding = true; accepting = true; pending = null
        start()
    }
    fun release() {
        if (!holding) return
        holding = false
        val text = pending
        if (text != null) { accepting = false; pending = null; deliver(text) }
        else if (accepting) finish()
    }
    fun receive(text: String) {
        if (!accepting || text.isBlank()) return
        if (holding) pending = text
        else { accepting = false; pending = null; deliver(text) }
    }
    fun cancel() { holding = false; accepting = false; pending = null; cancelInput() }
}
