package com.timeagent.assistant

object VoiceCommandHints {
    val phrases = listOf("今天 有 什么 安排", "明天 有 什么 安排", "本周 有 什么 安排", "明天 有 什么 空闲", "查看 任务",
        "取消 示例 课程", "取消 所有 示例 课程", "删除 所有 示例 课程", "停止 计时", "开始 计时",
        "提醒 明天 下午 三点 健身", "提醒 明天 十五点 交报告", "记个 想法 周末 去 骑车", "有空 想 看书")

    /** Only these confusions may be repaired; a number, a date or a brand new time is never invented. */
    private val homophones = setOf(
        '店' to '点', '电' to '点', '典' to '点', '时' to '点',
        '月' to '阅', '悦' to '阅',
        '进' to '计', '记' to '计', '食' to '时', '势' to '示', '力' to '例'
    )
    private val commandHint = Regex("^(?:提醒|安排|记录|补记|新建|创建|取消|删除|查看|停止|开始|我的)")
    private val dateWord = Regex("今天|明天|后天|昨天|前天|明早|明晚|今晚|今早|(?:下|本|这)?(?:个)?(?:周|星期|礼拜)[一二三四五六日天]")

    /** A domain decoder only offers a choice. It must never replace the original transcript. */
    fun suggestion(original: String, candidate: String): String? {
        fun compact(value: String) = value.replace(Regex("\\s+"), "").trimEnd('。', '，', '.', ',')
        val raw = compact(original)
        val hint = compact(candidate)
        if (raw == hint || raw.length !in 2..40 || hint.isEmpty()) return null
        if (Regex("不|别|没|取消今天|取消明天|[?？吗么呢]").containsMatchIn(raw) || "[unk]" in candidate) return null
        if (raw.take(2) != hint.take(2)) return null
        // Fixed phrase hints must protect dates, periods and both Chinese/Arabic numerals too.
        val temporal = Regex("${dateWord.pattern}|上午|下午|晚上|早上|中午|凌晨|[0-9零〇一二两三四五六七八九十百]+")
        if (temporal.findAll(raw).map { it.value }.toList() != temporal.findAll(hint).map { it.value }.toList()) return null
        if (hint in phrases.map(::compact)) {
            var previous = IntArray(hint.length + 1) { it }
            for ((index, char) in raw.withIndex()) {
                val next = IntArray(hint.length + 1)
                next[0] = index + 1
                for (j in hint.indices) next[j + 1] = minOf(next[j] + 1, previous[j + 1] + 1, previous[j] + if (char == hint[j]) 0 else 1)
                previous = next
            }
            return hint.takeIf { previous.last() <= 2 }
        }
        // Free-form candidates: character-for-character repair only, so a clock or a date can never be added or changed.
        if (!commandHint.containsMatchIn(hint)) return null
        if (raw.length != hint.length) return null
        if (dateWord.findAll(raw).map { it.value }.toList() != dateWord.findAll(hint).map { it.value }.toList()) return null
        var changed = 0
        for (index in raw.indices) {
            if (raw[index] == hint[index]) continue
            if (raw[index].isDigit() || hint[index].isDigit()) return null
            if ((raw[index] to hint[index]) !in homophones) return null
            changed++
        }
        return hint.takeIf { changed in 1..2 }
    }
}
