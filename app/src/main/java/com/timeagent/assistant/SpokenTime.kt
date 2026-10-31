package com.timeagent.assistant

import java.time.*
import java.time.temporal.TemporalAdjusters

/** Shared temporal tokens retain source ranges so time grammar never deletes words inside an activity. */
internal object SpokenTime {
    private const val number = "[0-9零〇一二两三四五六七八九十百]+"
    val datePattern = Regex("\\d{4}-\\d{2}-\\d{2}|(?:${number}年)?${number}月${number}[日号]|大前天|大后天|前天|后天|昨天|今天|明天|明早|明晚|今晚|今早|昨晚|昨夜|(?:上上|下下|上|下|本|这)?(?:个)?(?:周|星期|礼拜)[一二三四五六日天]")

    fun dates(text: String): List<MatchResult> {
        val lastClock = LocalAssistantParser.clockPattern.findAll(text).lastOrNull()?.range?.last
        return datePattern.findAll(text).filter { match ->
            if (lastClock != null && match.range.first > lastClock) return@filter false
            val before = text.substring(0, match.range.first)
            before.isBlank() || before.last() in " 从到至在，,、" ||
                listOf("提醒", "提醒我", "安排", "记录", "补记", "睡眠", "睡觉", "学习", "我").any(before::endsWith)
        }.toList()
    }

    fun date(word: String, context: AssistantContext, selectedWeek: Boolean = false): LocalDate? {
        val today = context.now.atZone(context.zone).toLocalDate()
        if (Regex("\\d{4}-\\d{2}-\\d{2}").matches(word)) return runCatching { LocalDate.parse(word) }.getOrNull()
        val offsets = mapOf("大前天" to -3L, "前天" to -2L, "昨天" to -1L, "昨晚" to -1L, "昨夜" to -1L,
            "今天" to 0L, "今早" to 0L, "今晚" to 0L, "明天" to 1L, "明早" to 1L, "明晚" to 1L, "后天" to 2L, "大后天" to 3L)
        offsets[word]?.let { return today.plusDays(it) }
        Regex("(?:($number)年)?($number)月($number)[日号]").matchEntire(word)?.let { match ->
            val year = match.groupValues[1].takeIf(String::isNotBlank)?.let(LocalAssistantParser::chineseNumber) ?: today.year
            val month = LocalAssistantParser.chineseNumber(match.groupValues[2]) ?: return null
            val day = LocalAssistantParser.chineseNumber(match.groupValues[3]) ?: return null
            return runCatching { LocalDate.of(year, month, day) }.getOrNull()
        }
        Regex("(上上|下下|上|下|本|这)?(?:个)?(?:周|星期|礼拜)([一二三四五六日天])").matchEntire(word)?.let { match ->
            val offset = when (match.groupValues[1]) { "上上" -> -2L; "上" -> -1L; "下" -> 1L; "下下" -> 2L; else -> 0L }
            val monday = if (selectedWeek && match.groupValues[1].isEmpty()) context.selectedWeekStart
                else today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset)
            return monday.plusDays("一二三四五六日".indexOf(match.groupValues[2].replace("天", "日")).toLong())
        }
        return null
    }

    fun namedNight(text: String, clock: MatchResult): Boolean =
        dates(text).lastOrNull { it.range.first < clock.range.first }?.value in setOf("昨晚", "昨夜", "今晚", "明晚")

    fun isNight(text: String, clock: MatchResult): Boolean {
        val period = clock.groupValues[1]
        return if (period.isNotBlank()) period in setOf("晚上", "夜里", "半夜", "午夜") else namedNight(text, clock)
    }

    fun times(text: String, clocks: List<MatchResult>, sleep: Boolean = false): List<LocalTime>? {
        val result = clocks.map { LocalAssistantParser.parseClock(it) ?: return null }.toMutableList()
        val first = clocks.firstOrNull() ?: return result
        if (isNight(text, first) && first.groupValues[1] !in setOf("上午", "下午", "早上", "中午", "凌晨", "清晨")) {
            val rawHour = first.groupValues[2].toIntOrNull() ?: LocalAssistantParser.chineseNumber(first.groupValues[4]) ?: return null
            if (rawHour in 0..6) result[0] = result[0].withHour(rawHour)
            else if (rawHour in 7..11) result[0] = result[0].withHour(rawHour + 12)
            else if (sleep && rawHour == 12) result[0] = result[0].withHour(0)
        }
        val hasEndDate = clocks.size == 2 && dates(text).any { it.range.first > clocks[0].range.last && it.range.first < clocks[1].range.first }
        if (clocks.size == 2 && !hasEndDate && clocks[1].groupValues[1].isBlank() && result[0].hour >= 12 && result[1].hour in 1..11) {
            val afternoonEnd = result[1].plusHours(12)
            if (afternoonEnd > result[0]) result[1] = afternoonEnd
        }
        return result
    }

    fun content(text: String, clocks: List<MatchResult>, dates: List<MatchResult>, durations: List<MatchResult> = emptyList()): String {
        val drop = BooleanArray(text.length)
        fun mark(range: IntRange) { for (index in range) if (index in text.indices) drop[index] = true }
        (clocks + dates + durations).forEach { mark(it.range) }
        // Only connectors BEFORE temporal tokens are grammar. 到书店/从零开始 AFTER the last clock is content.
        (clocks + dates).forEach { token ->
            var before = token.range.first - 1
            while (before >= 0 && text[before].isWhitespace()) before--
            if (before >= 0 && text[before] in "从到至在~～—–-") mark(before..before)
        }
        return buildString { for (index in text.indices) if (!drop[index]) append(text[index]) }
            .replace(Regex("\\s+"), " ").trim(' ', '，', ',', '。', '.', '、', '：', ':')
    }
}
