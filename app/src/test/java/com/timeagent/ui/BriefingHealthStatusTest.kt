package com.timeagent.ui

import com.timeagent.health.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class BriefingHealthStatusTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-26T03:00:00Z")
    @Test fun `no attempt is distinct from denied authorization`() {
        assertEquals(BriefingSyncState.NOT_READ, briefingHealthStatus(true, HealthReadReport(), zone, now).state)
        val report = HealthReadReport(now.toEpochMilli(), listOf(HealthChannelReport("HC", HealthReadState.UNAUTHORIZED)))
        assertEquals(BriefingSyncState.UNAUTHORIZED, briefingHealthStatus(true, report, zone, now).state)
    }
    @Test fun `old source cannot be labelled current after fresh read`() {
        val report = HealthReadReport(now.toEpochMilli(), listOf(HealthChannelReport("GB 睡眠", HealthReadState.READY, 20, now.minusSeconds(86400).toEpochMilli())))
        val status = briefingHealthStatus(true, report, zone, now)
        assertEquals(BriefingSyncState.STALE, status.state)
        assertTrue(status.latestSourceDateText.contains("2026-10-25"))
    }
    @Test fun `failed channel is explicit even with another successful source`() {
        val report = HealthReadReport(now.toEpochMilli(), listOf(HealthChannelReport("GB", HealthReadState.FAILED, error = "文件已撤销授权"), HealthChannelReport("HC", HealthReadState.READY, 2, now.toEpochMilli())))
        val status = briefingHealthStatus(true, report, zone, now)
        assertEquals(BriefingSyncState.FAILED, status.state)
        assertTrue(status.detail.contains("文件已撤销授权"))
        assertTrue(status.detail.contains("2 条"))
    }
    @Test fun `successful zero read is no data and disabled is separate`() {
        val report = HealthReadReport(now.toEpochMilli(), listOf(HealthChannelReport("HC", HealthReadState.NO_DATA)))
        assertEquals(BriefingSyncState.NO_DATA, briefingHealthStatus(true, report, zone, now).state)
        assertEquals(BriefingSyncState.DISABLED, briefingHealthStatus(false, report, zone, now).state)
    }
    @Test fun `recent exercise does not make old sleep source look current`() {
        val report = HealthReadReport(now.toEpochMilli(), listOf(
            HealthChannelReport("GB 睡眠", HealthReadState.READY, 2, now.minusSeconds(86400).toEpochMilli()),
            HealthChannelReport("HC 运动", HealthReadState.READY, 1, now.toEpochMilli())))
        val status = briefingHealthStatus(true, report, zone, now)
        assertEquals(BriefingSyncState.STALE, status.state)
        assertTrue(status.latestSourceDateText.contains("睡眠来源数据日期 2026-10-25"))
    }
}
