package com.timeagent.domain.command

/** Writes that create or remove the onboarding sample week. */
sealed interface SampleDataMutation {
    /**
     * Creates one example week. [weekStart] is an ISO local date (Monday); null means the current
     * week in the repository time zone. Loading is rejected while another sample batch exists.
     */
    data class LoadWeek(val weekStart: String? = null) : SampleDataMutation

    /**
     * Removes sample rows associated with [batchId] (null = every batch). When [keepEdited] is
     * true, rows whose revision moved past `initialVersion` stay and only their markers are
     * removed, turning them into ordinary user data.
     */
    data class Cleanup(
        val keepEdited: Boolean = false,
        val batchId: String? = null,
    ) : SampleDataMutation
}

/**
 * Deliberately a plain [Command], not a [MutationCommand]: the executor delegates to
 * SampleDataRepository.applyMutation inside its existing requestId/version/undo envelope.
 */
data class WriteSampleData(
    override val requestId: String,
    val mutation: SampleDataMutation,
    override val origin: Origin = Origin.UI,
) : Command
