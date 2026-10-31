package com.timeagent.health

enum class HealthReadState { DISABLED, NOT_CONNECTED, UNAUTHORIZED, UNAVAILABLE, FAILED, READY, NO_DATA }
data class HealthChannelReport(val channel: String, val state: HealthReadState, val count: Int = 0, val latestAt: Long? = null, val error: String = "")
data class HealthReadReport(val attemptedAt: Long = 0, val channels: List<HealthChannelReport> = emptyList())
