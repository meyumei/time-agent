package com.timeagent.assistant

import com.google.gson.JsonParser

/** Vosk finalizes multiple utterances during one hold. Preserve each rather than just the last. */
class VoiceTranscript {
    private val segments = mutableListOf<String>()
    private var partial = ""
    private var candidates = emptyList<String>()

    fun acceptResult(json: String): String {
        val result = parse(json)
        if (result.primary.isNotBlank()) segments += result.primary
        // Only other N-best candidates may become an optional hint; the first candidate is the transcript itself.
        candidates = if (result.primary.isNotBlank()) result.alternatives.drop(1) else emptyList()
        partial = ""
        return text()
    }
    fun acceptPartial(json: String): String { partial = field(json, "partial"); return text() }
    fun text(): String = normalize((segments + partial).filter { it.isNotBlank() }.joinToString(" ")).take(2000)

    /** Suggestion-only candidates from the latest final result; never a replacement for [text]. */
    fun alternatives(): List<String> = candidates

    /**
     * Vosk returns either {"text":…} or an N-best {"alternatives":[{"text":…},…]}.
     * The standard text wins; an alternatives-only result uses the FIRST candidate, so a blank or
     * malformed first candidate can never silently promote a later command-like candidate.
     */
    private fun parse(json: String): Parsed = runCatching {
        val obj = JsonParser.parseString(json).asJsonObject
        val text = obj.get("text")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val alternativeElements = obj.get("alternatives")?.takeIf { it.isJsonArray }?.asJsonArray
        val alternatives = alternativeElements
            ?.map { element -> element.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("text")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString }
            .orEmpty()
        val primary = if (!text.isNullOrBlank()) text else alternatives.firstOrNull().orEmpty()
        Parsed(primary, alternatives.filterNotNull().filter { it.isNotBlank() })
    }.getOrDefault(Parsed("", emptyList()))

    private class Parsed(val primary: String, val alternatives: List<String>)

    private fun field(json: String, key: String): String = runCatching {
        JsonParser.parseString(json).asJsonObject.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    }.getOrDefault("")
    private fun normalize(value: String) = value.trim().replace(Regex("(?<=[\\p{IsHan}0-9])\\s+(?=[\\p{IsHan}0-9])"), "")
}
