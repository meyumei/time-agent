package com.timeagent.health

import com.timeagent.data.HealthSample
import java.time.Instant

sealed interface HealthConnectState {
    data object Unavailable : HealthConnectState
    data object NeedsPermission : HealthConnectState
    data object Ready : HealthConnectState
}

/** Abstraction so the sync flow is testable without a device Health Connect service. */
interface HealthDataReader {
    suspend fun state(): HealthConnectState
    suspend fun state(sleep: Boolean, exercise: Boolean): HealthConnectState = state()
    suspend fun readSleep(since: Instant, until: Instant): List<HealthSample>
    suspend fun readExercise(since: Instant, until: Instant): List<HealthSample>
}
