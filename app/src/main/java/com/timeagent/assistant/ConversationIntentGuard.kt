package com.timeagent.assistant

internal object ConversationIntentGuard {
    private val recordQuestion = Regex("^(?:请帮我|帮我|请|麻烦)?(?:记录|补记).*(?:了吗|过吗|是否|[?？])")
    private val anyQuestion = Regex("(?:提醒|安排|记录|补记|保存|添加|新建|创建|删除|取消|修改).*(?:了吗|过吗|是否|吗|呢)[?？]?\\s*$")
    /** Spoken refusals may put courtesy words between the negation and the verb, e.g. 先别帮我安排. */
    private val refusal = Regex("(?<![特区别告分级识类离性])(?:不用|不要|不想|不需要|别|先别|先不|暂不|勿|无需|没必要)[帮我给你麻烦请再就先一下把个这那]{0,6}(?:提醒|安排|记录|补记|保存|执行|添加|新建|创建|删除|取消|修改|记个?想法|想法|撤销|回撤|撤回|前撤|重做)")
    /** "先预览/先看一下" asks to stop before writing; the preview stays pending until the user confirms. */
    private val previewFirst = Regex("先预览|预览一下|先给我看|先看一下|先看看再|先确认一下")

    fun isRecordQuestion(text: String) = recordQuestion.containsMatchIn(text.trim())
    fun refusesWrite(text: String) = Regex("[?？]").containsMatchIn(text) || isRecordQuestion(text) || anyQuestion.containsMatchIn(text.trim()) || refusal.containsMatchIn(text) || narrationRefusal(text)
    fun preventsWrite(text: String) = refusesWrite(text) || requestsPreview(text)
    fun requestsPreview(text: String) = previewFirst.containsMatchIn(text.trim())
    fun withoutPreviewRequest(text: String) = text.replace(previewFirst, "").trim(' ', '，', ',', '。', '.')

    /** ASR often omits question marks: “昨天九点到十二点我没有学习” is still not a record. */
    private fun narrationRefusal(text: String): Boolean {
        val body = text.trim().removePrefix("我").trim()
        val dates = SpokenTime.dates(body)
        if (dates.firstOrNull()?.range?.first != 0) return false
        val clocks = LocalAssistantParser.clockPattern.findAll(body).toList()
        if (clocks.size != 2) return false
        val content = SpokenTime.content(body, clocks, dates).replace(Regex("^(?:我|自己)\\s*"), "")
        return Regex("^(?:没有|没|未|不|为什么|为何|怎么|怎样|是否|是不是|有没有)").containsMatchIn(content)
    }
}
