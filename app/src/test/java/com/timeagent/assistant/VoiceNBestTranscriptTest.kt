package com.timeagent.assistant

import org.junit.Assert.*
import org.junit.Test

/** Vosk emits alternatives-only results; the first candidate must feed the raw transcript. */
class VoiceNBestTranscriptTest {
    @Test fun `alternatives only result keeps first candidate text not later domain command`() {
        val transcript = VoiceTranscript()
        val result = transcript.acceptResult(
            """{"alternatives":[{"text":"停止 进食","confidence":9.0},{"text":"停止 计时","confidence":8.0}]}"""
        )
        assertEquals("停止进食", result)
    }

    @Test fun `hold across pauses accumulates text and alternatives segments before partial update`() {
        val transcript = VoiceTranscript()
        assertEquals("提醒明天", transcript.acceptResult("""{"text":"提醒 明天"}"""))
        assertEquals("提醒明天停止进食", transcript.acceptResult(
            """{"alternatives":[{"text":"停止 进食","confidence":9.0},{"text":"停止 计时","confidence":8.0}]}"""
        ))
        assertEquals("提醒明天停止进食下午三点陈到24分钟", transcript.acceptPartial(
            """{"partial":"下午 三点 陈到 24 分钟"}"""
        ))
    }

    @Test fun `first candidate negation and date digits survive a more confident alternative`() {
        val transcript = VoiceTranscript()
        val result = transcript.acceptResult(
            """{"alternatives":[{"text":"不要 提醒 明天 下午 三点 健身","confidence":9.0},{"text":"提醒 明天 下午 四点 健身","confidence":8.0}]}"""
        )
        assertEquals("不要提醒明天下午三点健身", result)
    }

    @Test fun `empty or non string alternatives never promote a later executable candidate`() {
        val transcript = VoiceTranscript()
        assertEquals("提醒明天", transcript.acceptResult("""{"text":"提醒 明天"}"""))
        assertEquals("提醒明天", transcript.acceptResult("""{"alternatives":[]}"""))
        assertEquals("提醒明天", transcript.acceptResult("""{"alternatives":[{"text":42},{"text":"提醒 后天"}]}"""))
        assertEquals("提醒明天", transcript.acceptResult("""{"alternatives":[{"text":"提醒 后天"}"""))
    }

    @Test fun `standard text stays primary when alternatives are also present`() {
        val transcript = VoiceTranscript()
        val result = transcript.acceptResult(
            """{"text":"提醒 明天 下午 三点","alternatives":[{"text":"提醒 明天 下午 四点","confidence":9.0}]}"""
        )
        assertEquals("提醒明天下午三点", result)
    }
}
