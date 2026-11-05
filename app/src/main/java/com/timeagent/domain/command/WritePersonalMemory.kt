package com.timeagent.domain.command

import com.timeagent.data.PersonalMemoryEntity

data class WritePersonalMemory(
    override val requestId: String,
    val mutation: PersonalMemoryMutation,
    override val origin: Origin = Origin.UI,
) : Command

sealed interface PersonalMemoryMutation {
    data class Save(val item: PersonalMemoryEntity, val expectedVersion: Long?) : PersonalMemoryMutation
    data class Delete(val id: String, val expectedVersion: Long) : PersonalMemoryMutation
    data class ProposeCandidate(val memoryId: String) : PersonalMemoryMutation
}
