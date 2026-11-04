package com.timeagent.assistant

import com.timeagent.domain.command.Origin

/**
 * Result of [InputRouter.dispatch].
 *
 * [local] is true only when the input was a high-confidence local interpretation and therefore
 * handled by the shared [AssistantConversation] without a cloud round-trip. [reason] is a stable,
 * log-friendly tag and is never shown to the user.
 */
data class InputRouteResult(val local: Boolean, val reason: String)

/**
 * Single local router used by TimeAgentApp before text reaches FishSession.
 *
 * Parsed interpretations are local queries or local write commands and always go through the one
 * shared [AssistantConversation]. Clarifications and unsupported input are remote when a
 * [ConversationCloud] is supplied; without a cloud the same assistant answers locally so the user
 * still gets a clear message instead of silence.
 *
 * Routing never executes a command on its own and calls submit once per dispatch, so nothing runs
 * twice and there is exactly one assistant state, one memory and one session.
 */
class InputRouter(private val local: AssistantConversation) {
    private val parser = LocalAssistantParser()

    suspend fun dispatch(
        input: String,
        context: AssistantContext,
        origin: Origin = Origin.VOICE,
        requestId: String? = null,
        cloud: ConversationCloud? = null
    ): InputRouteResult {
        if (local.matchingQuickEntries(input).isNotEmpty()) return submitLocal(input, context, origin, requestId)
        val interpretation = parser.parse(input, context)
        return when (interpretation) {
            is Interpretation.Parsed -> submitLocal(input, context, origin, requestId)
            is Interpretation.Clarify -> submitOther(input, context, origin, requestId, cloud, CLARIFY)
            else -> submitOther(input, context, origin, requestId, cloud, UNSUPPORTED)
        }
    }

    private suspend fun submitLocal(
        input: String,
        context: AssistantContext,
        origin: Origin,
        requestId: String?
    ): InputRouteResult {
        local.submit(input, context, cloud = null, requestId = requestId, origin = origin)
        return InputRouteResult(local = true, reason = LOCAL)
    }

    private suspend fun submitOther(
        input: String,
        context: AssistantContext,
        origin: Origin,
        requestId: String?,
        cloud: ConversationCloud?,
        reason: String
    ): InputRouteResult {
        local.submit(input, context, cloud = cloud, requestId = requestId, origin = origin)
        val destination = if (cloud == null) LOCAL_SUFFIX else CLOUD_SUFFIX
        return InputRouteResult(local = false, reason = reason + destination)
    }

    private companion object {
        const val LOCAL = "local.command"
        const val CLARIFY = "clarify"
        const val UNSUPPORTED = "unsupported"
        const val LOCAL_SUFFIX = ".local-assistant"
        const val CLOUD_SUFFIX = ".cloud"
    }
}
