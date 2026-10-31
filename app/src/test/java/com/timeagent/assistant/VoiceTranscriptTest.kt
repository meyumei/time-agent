package com.timeagent.assistant

import org.junit.Assert.*
import org.junit.Test

class VoiceTranscriptTest {
    @Test fun `partial text is replaced while final segments survive pauses`() {
        val transcript = VoiceTranscript()
        assertEquals("新建任务", transcript.acceptPartial("""{"partial":"新建 任务"}"""))
        assertEquals("新建任务阅读", transcript.acceptPartial("""{"partial":"新建 任务 阅读"}"""))
        assertEquals("新建任务阅读", transcript.acceptResult("""{"text":"新建 任务 阅读"}"""))
        assertEquals("新建任务阅读三十分钟", transcript.acceptPartial("""{"partial":"三十分钟"}"""))
        assertEquals("新建任务阅读三十分钟", transcript.acceptResult("""{"text":"三十 分钟"}"""))
    }
    @Test fun `English word spaces survive and malformed result never becomes executable text`() {
        val transcript = VoiceTranscript()
        assertEquals("新建任务 Read paper 30分钟", transcript.acceptResult("""{"text":"新建 任务 Read paper 30 分钟"}"""))
        assertEquals("新建任务 Read paper 30分钟", transcript.acceptPartial("not json"))
    }
    @Test fun `raw transcript keeps homophones name characters and negative instructions`() {
        val transcript = VoiceTranscript()
        assertEquals("提醒明天下午三店和陈到讨论", transcript.acceptResult("""{"text":"提醒 明天 下午 三店 和 陈到 讨论"}"""))
        assertEquals("提醒明天下午三店和陈到讨论不要保存", transcript.acceptResult("""{"text":"不要 保存"}"""))
    }
}
