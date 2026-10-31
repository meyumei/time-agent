package com.timeagent.runtime

/**
 * Observable state of the embedded DSH runtime.
 *
 * [ready] becomes true only after the JS host prints a stdout frame with type=ready.
 * [progress] is non-null only while the first rootfs preparation is running.
 */
data class RuntimeState(
    val stage: String = "未准备",
    val progress: Float? = null,
    val ready: Boolean = false,
    val error: String? = null,
)
