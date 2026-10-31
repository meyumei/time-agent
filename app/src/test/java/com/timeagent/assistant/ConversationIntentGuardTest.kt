package com.timeagent.assistant

import org.junit.Assert.*
import org.junit.Test

class ConversationIntentGuardTest {
    @Test fun `spoken refusals cover reminders ideas and intervening courtesy words`() {
        for (text in listOf("不用提醒明天下午三点健身", "别提醒我明天下午三点健身", "先别帮我安排明天19点阅读", "我不想记录睡眠一点半到八点半", "这个想法不要保存", "不要记想法去骑车"))
            assertTrue(text, ConversationIntentGuard.preventsWrite(text))
    }

    @Test fun `completed reminder question never becomes a fresh reminder`() {
        assertTrue(ConversationIntentGuard.preventsWrite("提醒明天下午三点健身了吗？"))
        assertTrue(ConversationIntentGuard.isRecordQuestion("帮我记录睡眠一点半到八点半了吗？"))
    }

    @Test fun `polite requests and meaningful title words remain executable`() {
        for (text in listOf("能不能帮我提醒明天下午三点健身", "提醒明天下午三点和陈到讨论方案", "记个想法做一个无声提醒应用", "记录今天八点到九点学习不要分心的方法"))
            assertFalse(text, ConversationIntentGuard.preventsWrite(text))
    }
}
