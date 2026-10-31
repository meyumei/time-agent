package com.timeagent.assistant

import org.junit.Test
import org.junit.Assert.*

class HoldToTalkSessionTest {
    @Test fun `early recognition stays private until release`() {
        val delivered = mutableListOf<String>()
        var starts = 0; var finishes = 0
        val session = HoldToTalkSession({ starts++ }, { finishes++ }, {}, delivered::add)
        session.begin(); session.receive("新建任务 阅读 半小时")
        assertEquals(1, starts); assertTrue(delivered.isEmpty())
        session.release()
        assertEquals(listOf("新建任务 阅读 半小时"), delivered)
        assertEquals(0, finishes)
    }
    @Test fun `release waits for final text and delivers only once`() {
        val delivered = mutableListOf<String>(); var finishes = 0
        val session = HoldToTalkSession({}, { finishes++ }, {}, delivered::add)
        session.begin(); session.release()
        assertEquals(1, finishes); assertTrue(delivered.isEmpty())
        session.receive("今天有什么安排"); session.receive("重复结果")
        assertEquals(listOf("今天有什么安排"), delivered)
    }
    @Test fun `swipe cancel discards both queued and late results`() {
        val delivered = mutableListOf<String>(); var cancellations = 0
        val session = HoldToTalkSession({}, {}, { cancellations++ }, delivered::add)
        session.begin(); session.receive("不会保存"); session.cancel(); session.release(); session.receive("延迟结果")
        assertEquals(1, cancellations); assertTrue(delivered.isEmpty())
    }
    @Test fun `cancelling after release prevents a late result from opening the assistant`() {
        val delivered = mutableListOf<String>()
        val session = HoldToTalkSession({}, {}, {}, delivered::add)
        session.begin(); session.release(); session.cancel(); session.receive("延迟结果")
        assertTrue(delivered.isEmpty())
    }
}
