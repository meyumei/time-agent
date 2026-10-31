package com.timeagent.assistant

import java.time.*

/** Completed activity narration and explicit records share date, clock and content extraction. */
internal object NaturalRecordParser {
    fun parse(input: String, context: AssistantContext): Interpretation? {
        if (ConversationIntentGuard.preventsWrite(input) || Regex("[?？]|(?:吗|呢)$").containsMatchIn(input)) return null
        val explicitRecord = Regex("^(?:记录|补记)").containsMatchIn(input)
        var body = input.replace(Regex("^(?:记录|补记)(?:一下)?"), "").trim()
            .trimEnd('。', '.', '，', ',')
        if (!explicitRecord) body = body.removePrefix("我").trim()
        body = body.replace(Regex("([零一二两三四五六七八九十0-9]+)点\\1点半"), "$1点半")
            .replace(Regex("^而(?=睡眠|睡觉)"), "")
        val clocks = LocalAssistantParser.clockPattern.findAll(body).toList()
        if (clocks.size != 2) return null
        val dates = SpokenTime.dates(body)
        var label = SpokenTime.content(body, clocks, dates, LocalAssistantParser.durationPattern.findAll(body).toList())
            .replace(Regex("^(?:进行了|进行)\\s*"), "").trim(' ', '，', ',', '。', '.')
        if (!explicitRecord) {
            // Only dated completed narration: future intentions and other commands keep their own grammar.
            if (dates.firstOrNull()?.range?.first != 0 || Regex("^(?:想|打算|准备|计划|提醒|安排|取消|删除|查询|查看|为什么|为何|怎么|怎样|是否|是不是|有没有|没有|没|未|不)").containsMatchIn(label)) return null
            val day = SpokenTime.date(dates.first().value, context) ?: return Interpretation.Clarify("请说明有效的日期。")
            if (day > context.now.atZone(context.zone).toLocalDate()) return null
        }
        if (label in setOf("睡觉", "睡了", "入睡", "睡着", "睡眠")) label = "睡眠"
        val sleep = label == "睡眠"
        if (!sleep && clocks.any { SpokenTime.isNight(body, it) &&
                (it.groupValues[2] == "12" || it.groupValues[4] in setOf("十二", "12")) })
            return Interpretation.Clarify("请说明午夜是哪一天，或使用明确的 00:00。")
        val times = SpokenTime.times(body, clocks, sleep) ?: return Interpretation.Clarify("时间无效，请说明有效的开始和结束时间。")
        val today = context.now.atZone(context.zone).toLocalDate()
        val days = dates.map { SpokenTime.date(it.value, context) ?: return Interpretation.Clarify("请说明有效的日期。") }
        val startNightMorning = (SpokenTime.isNight(body, clocks[0]) || SpokenTime.namedNight(body, clocks[0])) && times[0].hour < 12
        val endDateOnly = dates.size == 1 && dates[0].range.first > clocks[0].range.last && dates[0].range.first < clocks[1].range.first
        val startDate: LocalDate
        val endDate: LocalDate
        when {
            dates.size > 2 -> return Interpretation.Clarify("有多个日期，请说明这一条记录的起止日期。")
            dates.size == 2 -> {
                startDate = if (startNightMorning) days[0].plusDays(1) else days[0]
                endDate = days[1]
            }
            endDateOnly -> {
                endDate = days.single()
                startDate = if (times[0] > times[1]) endDate.minusDays(1) else endDate
            }
            dates.size == 1 -> {
                startDate = if (startNightMorning) days.single().plusDays(1) else days.single()
                endDate = if (times[1] < times[0]) startDate.plusDays(1) else startDate
            }
            else -> {
                val candidateEnd = today.atTime(times[1]).atZone(context.zone).toInstant()
                endDate = if (candidateEnd > context.now) today.minusDays(1) else today
                startDate = if (times[0] > times[1]) endDate.minusDays(1) else endDate
            }
        }
        if (times[0] == times[1] && dates.size < 2) return Interpretation.Clarify("开始和结束时间相同，请说明时长。")
        if (!sleep && dates.size < 2 && times[1] < times[0] && times[0].hour < 18)
            return Interpretation.Clarify("结束时间早于开始时间，请说明是否跨天及起止日期。")
        val start = startDate.atTime(times[0]).atZone(context.zone).toInstant()
        val end = endDate.atTime(times[1]).atZone(context.zone).toInstant()
        if (end <= start || Duration.between(start, end).toMinutes() > 10080) return Interpretation.Clarify("请说明有效的起止时间。")
        val stated = LocalAssistantParser.parseDuration(body)
        if (stated != null && Duration.between(start, end).toMinutes() != stated.toLong())
            return Interpretation.Clarify("起止时间和时长不一致，请说明以哪一个为准。")
        if (label.isBlank()) return Interpretation.Clarify("请补充这段时间做了什么。")
        val study = Regex("^(学习|复习|预习)(?:了)?\\s*(.*)$").matchEntire(label)
        val activity = if (study != null) "学习" else label
        val notes = study?.groupValues?.get(2)?.trim(' ', '，', ',', '：', ':')
        if (activity.length > 120 || (notes?.length ?: 0) > 20000) return Interpretation.Clarify("活动名称或备注太长，请精简后重试。")
        return Interpretation.Parsed(AssistantCommand.ManualRecord(label = activity, startAt = start.toEpochMilli(), endAt = end.toEpochMilli(), notes = notes))
    }
}
