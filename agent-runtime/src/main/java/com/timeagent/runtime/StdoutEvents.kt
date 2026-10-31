package com.timeagent.runtime

import org.json.JSONException
import org.json.JSONObject

/** One stdout line -> zero or one event. Non-JSON diagnostics are deliberately dropped. */
internal object StdoutEvents {
    fun parse(line: String): JSONObject? {
        val trimmed = line.trim()
        if (trimmed.length < 2 || trimmed[0] != '{' || trimmed[trimmed.length - 1] != '}') {
            return null
        }
        return try {
            JSONObject(trimmed)
        } catch (_: JSONException) {
            null
        }
    }
}
