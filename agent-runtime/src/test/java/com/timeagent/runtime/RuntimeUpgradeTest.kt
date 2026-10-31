package com.timeagent.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RuntimeUpgradeTest {
    @Test fun `updating owned adapter preserves the installed base and conversation data`() {
        val base="a".repeat(64)
        val old=JSONArray().put(JSONObject().put("name","offline-rootfs.bin").put("sha256",base).put("strip",0))
            .put(JSONObject().put("name","time-agent-overlay.bin").put("sha256","b".repeat(64)).put("strip",0))
        val lock=RuntimeLock(listOf(RuntimeAsset("offline-rootfs.bin",base,0),RuntimeAsset("time-agent-overlay.bin","c".repeat(64),0)))
        assertTrue(lock.baseMatches(old))
        assertFalse(RuntimeLock(listOf(RuntimeAsset("offline-rootfs.bin","d".repeat(64),0))).baseMatches(old))
    }
}
