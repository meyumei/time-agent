package com.timeagent.assistant

import java.time.*

/** Deliberately narrow grammar: an uncertain sentence never becomes a guessed write. */
class LocalAssistantParser {
    fun parse(input: String, context: AssistantContext): Interpretation {
        val text = input.trim().replace('：', ':').replace(Regex("[，,。]$"), "")
            .replace(Regex("^(?:请帮我|帮我|麻烦你|麻烦|请)"), "").trim()
        if (text.isBlank()) return Interpretation.Clarify("请输入想查询或修改的内容。")
        if (text.length > 2000) return Interpretation.Clarify("这段文字太长，请一次描述一个操作。")
        if (ConversationIntentGuard.preventsWrite(text)) return Interpretation.Clarify("这是询问或暂不执行的要求，尚未修改数据。")
        RecordNarrationParser.parse(text, context)?.let { return it }
        if (Regex("并且|然后|同时|并(?:开始|新建|安排|取消|记录)|[；;\\n]").containsMatchIn(text))
            return Interpretation.Clarify("请一次处理一个操作，确认完成后再处理下一个。")
        if (Regex("\\d+[.．]\\d+\\s*(?:个)?(?:小时|分钟)").containsMatchIn(text))
            return Interpretation.Clarify("本地暂不接受小数时长，请改用整数分钟，例如 90分钟。")
        if (durationPattern.findAll(text).count() > 1)
            return Interpretation.Clarify("检测到多个时长，请明确一个总时长，例如 一小时三十分钟。")
        NaturalRecordParser.parse(text, context)?.let { return it }
        val compact = text.replace(Regex("\\s+"), "")
        if (Regex("^(?:请|帮我|请帮我)?(?:把)?(?:取消|删除|清理|清除|移除)(?:一下)?(?:所有的?|全部的?)?示例(?:课程|课表|课)(?:全部|所有)?(?:取消|删除|清理|清除|移除)?(?:掉)?$").matches(compact))
            return parsed(AssistantCommand.CancelSampleCourses)
        if (text in setOf("停止计时", "结束计时", "停止当前计时")) return parsed(AssistantCommand.StopTimer)
        if (text.startsWith("开始计时")) {
            val target = text.removePrefix("开始计时").trim()
            return if (target.startsWith("#")) parsed(AssistantCommand.StartTimer(taskId = target.drop(1)))
            else parsed(AssistantCommand.StartTimer(taskTitle = target.ifBlank { null }))
        }
        if (text in setOf("查看任务", "我的任务", "有哪些任务", "任务列表", "待办任务")) return parsed(AssistantCommand.Query(QueryScope.TASKS))
        if (Regex("空闲|空档|有空").containsMatchIn(text) && !wishful(text)) {
            val date = parseDate(text, context) ?: return Interpretation.Clarify("请指定哪天的空闲，例如：明天有什么空闲。")
            val duration = parseDuration(text) ?: 30
            if (duration !in 1..10080) return Interpretation.Clarify("空闲时长需为 1–10080 分钟。")
            return parsed(AssistantCommand.Query(QueryScope.FREE_TIME, date, duration))
        }
        if (Regex("安排|日程|课|计划").containsMatchIn(text) && Regex("什么|哪些|查看|查询|多少").containsMatchIn(text)) {
            return when {
                Regex("本周|这周|所选周|选中周").containsMatchIn(text) -> parsed(AssistantCommand.Query(QueryScope.SELECTED_WEEK))
                text.contains("明天") -> parsed(AssistantCommand.Query(QueryScope.TOMORROW))
                text.contains("今天") -> parsed(AssistantCommand.Query(QueryScope.TODAY))
                else -> parseDate(text, context)?.let { parsed(AssistantCommand.Query(QueryScope.TODAY, it)) }
                    ?: Interpretation.Clarify("请指定今天、明天或所选周。")
            }
        }
        if (text.startsWith("新建任务") || text.startsWith("创建任务")) {
            val body = text.drop(4).trim()
            if (Regex("截止|到期|优先级").containsMatchIn(body)) return Interpretation.Clarify("本地新建任务支持名称和时长；截止时间或优先级请在任务编辑中设置，或使用 DeepSeek 理解。")
            val minutes = parseDuration(body) ?: 30
            val title = stripDuration(body).trim().removeSuffix("预计").trim()
            if (title.isBlank() || title.length > 120 || minutes !in 1..10080) return Interpretation.Clarify("请给任务名称及有效时长，例如：新建任务 阅读 30分钟。")
            return parsed(AssistantCommand.CreateTask(title, minutes))
        }
        for ((prefix, kind) in listOf("取消任务" to "task", "取消计划" to "plan")) if (text.startsWith(prefix)) {
            val target = text.removePrefix(prefix).trim()
            if (target.isBlank()) return Interpretation.Clarify("请说明要取消的具体${if (kind == "task") "任务" else "计划"}名称，或 #ID。")
            return parsed(if (kind == "task") {
                if (target.startsWith("#")) AssistantCommand.CancelTask(taskId = target.drop(1)) else AssistantCommand.CancelTask(taskTitle = target)
            } else {
                if (target.startsWith("#")) AssistantCommand.CancelPlan(planId = target.drop(1)) else AssistantCommand.CancelPlan(taskTitle = target)
            })
        }
        if (text.startsWith("修改任务")) {
            val body = text.removePrefix("修改任务").trim()
            val renamed = Regex("^(.+?)\\s+(?:为|改名为)\\s+(.+)$").matchEntire(body)
            val duration = Regex("^(.+?)\\s+时长\\s*(.+)$").matchEntire(body)
            val target = renamed?.groupValues?.get(1) ?: duration?.groupValues?.get(1)
                ?: return Interpretation.Clarify("例如：修改任务 阅读 为 文献阅读，或：修改任务 阅读 时长 45分钟。")
            val newTitle = renamed?.groupValues?.get(2)?.trim()
            val minutes = duration?.groupValues?.get(2)?.let(::parseDuration)
            if (newTitle?.isBlank() == true || newTitle?.length?.let { it > 120 } == true || (duration != null && (minutes == null || minutes !in 1..10080)))
                return Interpretation.Clarify("任务名称或时长无效，请重新说明。")
            return parsed(AssistantCommand.EditTask(taskId = target.takeIf { it.startsWith("#") }?.drop(1), taskTitle = target.takeUnless { it.startsWith("#") }, title = newTitle, minutes = minutes))
        }
        val move = text.startsWith("移动计划") || text.startsWith("推迟计划")
        val plan = text.startsWith("安排") || text.startsWith("新建计划") || text.startsWith("创建计划")
        val record = text.startsWith("记录") || text.startsWith("补记")
        if (move || plan || record) {
            val prefix = when { move -> text.take(4); text.startsWith("安排") || record -> text.take(2); else -> text.take(4) }
            val body = text.removePrefix(prefix).trim()
            val dates = SpokenTime.dates(body)
            if (dates.size !in 1..2)
                return Interpretation.Clarify("请为一个时间段指定一个日期；跨天请在编辑器中明确起止日期。")
            val date = parseDate(body, context) ?: return Interpretation.Clarify("请补充日期，例如：安排 明天 19:00 到 20:00 阅读。")
            val clockMatches = clockPattern.findAll(body).toList()
            if (clockMatches.size !in 1..2) return Interpretation.Clarify("请提供开始时间及结束时间或时长，例如 19:00 到 20:00，或 19:00 阅读 半小时。")
            if (clockMatches.any { isAmbiguousMidnight(body, it) })
                return Interpretation.Clarify("“晚上十二点”可能指当天午夜或次日零点，日期无法确定；请改用明确的 00:00 并说明是哪一天，或改写为 12:00。")
            val times = SpokenTime.times(body, clockMatches) ?: return Interpretation.Clarify("时间无效，请使用 00:00–23:59。")
            val start = date.atTime(times.first()).atZone(context.zone).toInstant()
            val endDate = if (dates.size == 2) parseDate(dates[1].value, context)
                ?: return Interpretation.Clarify("请说明有效的结束日期。") else date
            val end = if (times.size == 2) endDate.atTime(times[1]).atZone(context.zone).toInstant()
                else start.plusSeconds((parseDuration(body) ?: return Interpretation.Clarify("请补充结束时间或时长。")) * 60L)
            val statedMinutes = parseDuration(body)
            if (times.size == 2 && statedMinutes != null && Duration.between(start, end).toMinutes() != statedMinutes.toLong())
                return Interpretation.Clarify("起止时间与所说时长不一致，请保留一个明确的时间段。")
            if (end <= start || Duration.between(start, end).toMinutes() > 10080) return Interpretation.Clarify("结束时间必须晚于开始时间；跨天请用编辑器明确日期。")
            // Remove only time grammar: clocks, the date, a spoken duration, and the connectors attached to them.
            // A title that merely begins with 到/从 (到书店, 从零开始) keeps its first word.
            val target = SpokenTime.content(body, clockMatches, dates, durationPattern.findAll(body).toList())
            if (target.isBlank()) return Interpretation.Clarify("请补充具体任务名称或记录内容。")
            return parsed(when {
                move -> AssistantCommand.MovePlan(planId = target.takeIf { it.startsWith("#") }?.drop(1), taskTitle = target.takeUnless { it.startsWith("#") }, startAt = start.toEpochMilli(), endAt = end.toEpochMilli())
                record -> AssistantCommand.ManualRecord(label = target, startAt = start.toEpochMilli(), endAt = end.toEpochMilli())
                else -> AssistantCommand.CreatePlan(taskId = target.takeIf { it.startsWith("#") }?.drop(1), taskTitle = target.takeUnless { it.startsWith("#") }, startAt = start.toEpochMilli(), endAt = end.toEpochMilli())
            })
        }
        return Interpretation.Unsupported("本地暂未理解。可参考下方示例修改文字，或配置并选择 DeepSeek 理解；尚未修改数据。")
    }

    private fun parsed(command: AssistantCommand) = Interpretation.Parsed(command)

    /** "有空想看《明天也要在一起》" is an idea about a book title, not a request for free slots. */
    private fun wishful(text: String) = Regex("有空(?:的时候)?(?:想|要|打算|想看|想去)").containsMatchIn(text)

    internal fun parseDate(text: String, context: AssistantContext): LocalDate? {
        val token = SpokenTime.dates(text).firstOrNull() ?: return null
        return SpokenTime.date(token.value, context, selectedWeek = true)
    }

    companion object {
        private val number = "[0-9零〇一二两三四五六七八九十百]+"
        internal val durationPattern = Regex("(?:$number\\s*(?:个)?半小时|$number\\s*(?:个)?小时(?:半|(?:\\s*$number\\s*分钟?))?|半(?:个)?小时|$number\\s*分钟)")
        internal val clockPattern = Regex("(?<![0-9:：])(?:(上午|下午|晚上|早上|中午|凌晨|清晨|傍晚|夜里|半夜|午夜)\\s*)?(?:(\\d{1,2})[:：](\\d{1,2})(?![0-9])|($number)[点时](?:(一刻|两刻|二刻|三刻)|(半)|($number)分?)?)")
        internal fun parseClock(match: MatchResult): LocalTime? {
            val hour = clockHour(match) ?: return null
            val minute = match.groupValues[3].toIntOrNull() ?: when {
                match.groupValues[5].isNotEmpty() -> quarterMinutes(match.groupValues[5])
                match.groupValues[6] == "半" -> 30
                else -> chineseNumber(match.groupValues[7]) ?: 0
            }
            return runCatching { LocalTime.of(hour, minute) }.getOrNull()
        }
        /** 三点一刻 = 03:15, 七点三刻 = 07:45; the quarter word is part of the clock, never a stray digit. */
        private fun quarterMinutes(word: String) = when (word) { "一刻" -> 15; "两刻", "二刻" -> 30; "三刻" -> 45; else -> 0 }
        /** Naive 24h conversion. A "晚上十二点/晚上12:00" therefore lands on noon; callers must clarify instead. */
        private fun clockHour(match: MatchResult): Int? {
            val hour = match.groupValues[2].toIntOrNull() ?: chineseNumber(match.groupValues[4]) ?: return null
            return when {
                match.groupValues[1] in setOf("下午", "晚上", "傍晚", "夜里") && hour in 1..11 -> hour + 12
                match.groupValues[1] == "中午" && hour in 1..10 -> hour + 12
                match.groupValues[1] in setOf("上午", "凌晨", "早上") && hour == 12 -> 0
                else -> hour
            }
        }
        /** Night twelve is genuinely two calendar instants: the coming midnight or the following noon. */
        private fun isAmbiguousMidnight(body: String, match: MatchResult): Boolean {
            val before = body.substring(0, match.range.first).trimEnd()
            val night = match.groupValues[1] == "晚上" || listOf("晚上", "夜里", "半夜", "午夜").any { before.endsWith(it) }
            return night && clockHour(match) == 12
        }
        internal fun parseDuration(text: String): Int? {
            val match = durationPattern.find(text)?.value ?: return null
            return durationMinutes(match)
        }
        /** "一个半小时"=90, "一小时半"=90, "一小时三十分钟"=90, "半小时"=30; never truncates the leading number. */
        private fun durationMinutes(match: String): Int? {
            if (match.startsWith("半")) return 30
            val hours = Regex("($number)\\s*(?:个)?\\s*(?:半)?\\s*小时").find(match)?.groupValues?.get(1)?.let(::chineseNumber)
            val minutes = Regex("($number)\\s*分钟?").find(match)?.groupValues?.get(1)?.let(::chineseNumber)
            return (hours ?: 0) * 60 + (minutes ?: 0) + if (match.contains("半")) 30 else 0
        }
        private fun stripDuration(text: String) = text.replace(durationPattern, " ").replace(Regex("(?:预计|用时|时长)\\s*$"), "").trim()
        internal fun chineseNumber(text: String): Int? {
            if (text.isBlank()) return null
            text.toIntOrNull()?.let { return it.takeIf { value -> value in 0..10080 } }
            val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
            if (text.none { it == '十' || it == '百' }) return text.map { char -> char.digitToIntOrNull() ?: digits[char] ?: return null }
                .joinToString("").toIntOrNull()?.takeIf { it in 0..10080 }
            var result = 0
            var current = 0
            for (char in text) when (char) {
                '十' -> { result += (if (current == 0) 1 else current) * 10; current = 0 }
                '百' -> { result += (if (current == 0) 1 else current) * 100; current = 0 }
                else -> current = digits[char] ?: return null
            }
            return result + current
        }
    }
}
