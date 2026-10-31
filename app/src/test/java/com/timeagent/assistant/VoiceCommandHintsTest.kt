package com.timeagent.assistant

import org.junit.Assert.*
import org.junit.Test

class VoiceCommandHintsTest {
    @Test fun `homophone correction is offered as a suggestion without overwriting original speech`() {
        assertEquals("停止计时", VoiceCommandHints.suggestion("停止进食", "停止 计时"))
        assertEquals("取消所有示例课程", VoiceCommandHints.suggestion("取消所有势力课程", "取消 所有 示例 课程"))
    }
    @Test fun `negation questions unknown speech and unrelated requests get no forced command`() {
        for (text in listOf("不要停止计时", "不取消示例课程", "停止计时了吗", "帮我安排一下明天", "我在吃饭", "取消今天的示例课程"))
            assertNull(text, VoiceCommandHints.suggestion(text, "停止 计时"))
        assertNull(VoiceCommandHints.suggestion("停止计时", "停止 计时"))
        assertNull(VoiceCommandHints.suggestion("停止进食", "[unk] 停止 计时"))
    }
    @Test fun `clock homophones can be offered as a candidate while numbers and date stay fixed`() {
        assertEquals("提醒明天下午三点健身", VoiceCommandHints.suggestion("提醒明天下午三店健身", "提醒 明天 下午 三点 健身"))
        assertEquals("安排明天十九点阅读", VoiceCommandHints.suggestion("安排明天十九点月读", "安排 明天 十九点 阅读"))
        assertNull(VoiceCommandHints.suggestion("提醒明天下午四点健身", "提醒 明天 下午 三点 健身"))
        assertNull(VoiceCommandHints.suggestion("提醒后天下午三点健身", "提醒 明天 下午 三点 健身"))
        assertNull(VoiceCommandHints.suggestion("提醒明天下午健身", "提醒 明天 下午 三点 健身"))
    }
}
