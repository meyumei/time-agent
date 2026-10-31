package com.timeagent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** stdout carries JSON objects only; proot/diagnostic text must never become an event. */
class StdoutEventsTest {

    @Test
    fun parsesJsonObjectLinesAndDropsDiagnostics() {
        val ready = StdoutEvents.parse("""{"type":"ready","session":"abc"}""")
        assertEquals("ready", ready?.optString("type"))
        assertEquals("abc", ready?.optString("session"))

        assertNull(StdoutEvents.parse("[dsh] listening on 127.0.0.1"))
        assertNull(StdoutEvents.parse("{not json"))
        assertNull(StdoutEvents.parse("""["ready"]"""))
        assertNull(StdoutEvents.parse("   "))
    }

    @Test
    fun distinctFramesProduceDistinctEventsInsteadOfAFixedEcho() {
        val ready = StdoutEvents.parse("""{"type":"ready","session":"one"}""")!!
        val error = StdoutEvents.parse("""{"type":"error","message":"permission denied"}""")!!
        val other = StdoutEvents.parse("""{"type":"ready","session":"two"}""")!!

        assertNotEquals(ready.toString(), error.toString())
        assertNotEquals(ready.optString("session"), other.optString("session"))
        assertEquals("permission denied", error.optString("message"))
    }
}
