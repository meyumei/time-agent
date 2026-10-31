package com.timeagent.assistant

import com.timeagent.data.*
import java.time.*
import java.time.temporal.TemporalAdjusters

/** Adds conversational references and sensible defaults around the explicit local grammar. */
internal class ConversationInterpreter {
    var lastMutation: AssistantMutation? = null
    private val parser = LocalAssistantParser()

    fun parse(input: String, context: AssistantContext): Interpretation {
        val text = normalize(input)
        if (ConversationIntentGuard.preventsWrite(input)) {
            val query = parser.parse(text.trimEnd('?', '？'), context)
            if (query is Interpretation.Parsed && query.command is AssistantCommand.Query) return query
            return Interpretation.Clarify("这是询问、暂不执行或先预览的要求，尚未修改数据。")
        }
        val parseContext = if (Regex("^(?:安排|新建计划|创建计划)").containsMatchIn(text))
            context.copy(selectedWeekStart = context.now.atZone(context.zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) else context
        val base = parser.parse(text, parseContext)
        if (base is Interpretation.Parsed) return promote(base, context)
        if (text.length > 2000 || blocksWrite(text, input)) return base
        ideaFollowUp(text, context)?.let { return it }
        reminderFollowUp(text, context)?.let { return it }
        idea(input.trim(), text)?.let { return it }
        reminder(text, context)?.let { return it }
        Regex("^(?:删除|取消)(.+?)任务$").matchEntire(text)?.let { match ->
            return Interpretation.Parsed(AssistantCommand.CancelTask(taskTitle = match.groupValues[1].trim()))
        }
        Regex("^删除任务\\s*(.+)$").matchEntire(text)?.let { return Interpretation.Parsed(AssistantCommand.CancelTask(taskTitle = it.groupValues[1].trim())) }
        val isReference = Regex("刚才|刚刚|这条|这个|上一个|上一条").containsMatchIn(text)
        if (Regex("^(?:删除|取消|移除|把|修改|调整|更改)").containsMatchIn(text)) {
            val lastRecordId = (lastMutation as? AssistantMutation.SaveRecord)?.record?.id
            val recordTarget = text.contains("记录") || !Regex("任务|计划|课程|课表").containsMatchIn(text)
            val records = context.snapshot.records.filter { recordTarget && it.endAt != null &&
                (if (isReference && lastRecordId != null) it.id == lastRecordId else {
                    val title = it.title.ifBlank { it.notes.ifBlank { context.snapshot.tasks.find { t -> t.id == it.taskId }?.title.orEmpty() } }
                    title.isNotBlank() && text.contains(title) && (parser.parseDate(text, context)?.let { date -> Instant.ofEpochMilli(it.startAt).atZone(context.zone).toLocalDate() == date } ?: true)
                }) }
            if (records.size == 1 && (isReference || text.contains("记录") || text.contains("睡眠") || text.contains("睡觉"))) {
                val record = records.single()
                if (Regex("删除|取消|移除").containsMatchIn(text)) return Interpretation.Parsed(AssistantCommand.DeleteRecord(record.id))
                val clocks = LocalAssistantParser.clockPattern.findAll(text).toList()
                if (clocks.size == 2) {
                    val times = SpokenTime.times(text, clocks, record.title == "睡眠" || record.notes == "睡眠")
                        ?: return Interpretation.Clarify("请说明有效时间。")
                    val day = parser.parseDate(text, context) ?: Instant.ofEpochMilli(record.startAt).atZone(context.zone).toLocalDate()
                    val endDay = if (times[1] <= times[0]) day.plusDays(1) else day
                    return Interpretation.Parsed(AssistantCommand.EditRecord(record.id, day.atTime(times[0]).atZone(context.zone).toInstant().toEpochMilli(), endDay.atTime(times[1]).atZone(context.zone).toInstant().toEpochMilli()))
                }
            }
            if (records.size > 1 && Regex("记录|睡眠|睡觉").containsMatchIn(text)) return Interpretation.Clarify("找到 ${records.size} 条相关记录，请说是哪一天，或先点击对应时间块。")
            if (isReference) when (val last = lastMutation) {
                is AssistantMutation.SavePlan -> if (Regex("取消|删除").containsMatchIn(text)) return Interpretation.Parsed(AssistantCommand.CancelPlan(planId = last.plan.id))
                is AssistantMutation.SaveEvent -> if (Regex("取消|删除").containsMatchIn(text)) return Interpretation.Parsed(AssistantCommand.CancelPlan(planId = last.plan.id))
                is AssistantMutation.SaveTask -> if (Regex("取消|删除").containsMatchIn(text)) return Interpretation.Parsed(AssistantCommand.CancelTask(taskId = last.task.id))
                else -> Unit
            }
        }
        Regex("^(?:把)?(.+?)(?:标记为完成|标记完成|完成了|已完成)$").matchEntire(text)?.let { match ->
            val candidates = context.snapshot.tasks.filter { it.deletedAt == null && it.title == match.groupValues[1].removePrefix("任务").trim() }
            if (candidates.size == 1) return Interpretation.Parsed(AssistantCommand.CompleteTask(candidates.single().id))
        }
        if (Regex("^(?:取消|删除|调整|移动|修改).*课").containsMatchIn(text) && !text.contains("示例")) {
            val date = parser.parseDate(text, context)
            val courseIds = context.snapshot.courses.filter { text.contains(it.name) }.map { it.id }.toSet()
            var candidates = context.snapshot.occurrences.filter { it.status != "CANCELLED" && it.courseId in courseIds && (date == null || it.date == date.toString()) }
            if (text.contains("下一节")) candidates = candidates.filter { it.endAt > context.now.toEpochMilli() }.sortedBy { it.startAt }.take(1)
            if (candidates.size == 1) {
                val item = candidates.single()
                if (Regex("取消|删除").containsMatchIn(text)) return Interpretation.Parsed(AssistantCommand.CancelCourse(item.id))
                val clocks = LocalAssistantParser.clockPattern.findAll(text).mapNotNull(LocalAssistantParser::parseClock).toList()
                if (clocks.size == 2) {
                    val day = date ?: LocalDate.parse(item.date)
                    return Interpretation.Parsed(AssistantCommand.EditCourse(item.id, day.atTime(clocks[0]).atZone(context.zone).toInstant().toEpochMilli(), day.atTime(clocks[1]).atZone(context.zone).toInstant().toEpochMilli()))
                }
            }
            if (candidates.size > 1) return Interpretation.Clarify("这门课有 ${candidates.size} 次，请说具体日期，例如“取消今天的${context.snapshot.courses.find { it.id in courseIds }?.name}”。")
        }
        // A future plan without a stated day means the next occurrence of that time.
        if (Regex("^(?:安排|新建计划|创建计划)").containsMatchIn(text) && parser.parseDate(text, context) == null) {
            var inferred = parser.parse(text.replaceFirst(Regex("^(安排|新建计划|创建计划)"), "$1 今天 "), parseContext)
            val command = (inferred as? Interpretation.Parsed)?.command as? AssistantCommand.CreatePlan
            if (command != null && command.startAt < context.now.toEpochMilli()) inferred = parser.parse(text.replaceFirst(Regex("^(安排|新建计划|创建计划)"), "$1 明天 "), parseContext)
            if (inferred is Interpretation.Parsed) return promote(inferred, context)
        }
        return base
    }

    private fun promote(parsed: Interpretation.Parsed, context: AssistantContext): Interpretation.Parsed {
        val command = parsed.command
        if (command is AssistantCommand.CreatePlan && command.taskId == null && !command.taskTitle.isNullOrBlank() && context.snapshot.tasks.none { it.deletedAt == null && it.status != "CANCELLED" && it.title == command.taskTitle })
            return Interpretation.Parsed(AssistantCommand.CreateEvent(command.taskTitle, command.startAt, command.endAt))
        return parsed
    }

    /** Removes spoken request frames without touching words inside the activity itself. */
    private fun normalize(input: String): String {
        var text = input.trim().replace('：', ':').replace(Regex("[，,。！!？?、\\s]+$"), "")
        val filler = Regex("^(?:那个|嗯|呃|哦|好的|请问|你好|能不能|可不可以|可以|麻烦你|麻烦|请你|请|帮忙|帮我|给我)[，,。！!、\\s]*")
        while (true) {
            val next = text.replace(filler, "")
            if (next == text) break
            text = next
        }
        val politeVerb = Regex("^(安排|提醒|记录|补记|新建|创建|取消|删除)(?:一下|下(?=明天|今天|后天|昨天|下周|下星期|下礼拜|[0-9零一二三四五六七八九十]))")
        while (true) {
            val next = text.replace(politeVerb, "$1")
            if (next == text) break
            text = next
        }
        return text
    }

    private fun blocksWrite(text: String, input: String) =
        ConversationIntentGuard.preventsWrite(input) || ConversationIntentGuard.preventsWrite(text) ||
            Regex("并且|然后|同时|并(?:开始|新建|安排|取消|记录)|[；;\\n]").containsMatchIn(text)

    /** A wish or an inspiration is stored verbatim as an unscheduled idea, never as a task or a block. */
    private fun idea(original: String, text: String): Interpretation? {
        val trigger = listOf(
            Regex("^[^，,。！!？?]{0,8}?灵感(?:来了|来了个|忽然来了|一闪)?[，,]?(?:想要|想|要|打算)?"),
            Regex("^(?:记个|记一下|记录|记|有(?:个)?|说个)?想法[：:，,]?"),
            Regex("^有空(?:的时候)?(?:想要|想|要|打算)?")
        ).firstNotNullOfOrNull { it.find(text) } ?: return null
        if (Regex("[?？]|吗$|么$").containsMatchIn(text)) return null
        var title = text.substring(trigger.range.last + 1).trim()
        title = title.replace(Regex("(?:[，,]?(?:但|不过|就是))?(?:还)?(?:没|没有)(?:想好|定|确定|决定)?(?:时间|具体时间|下来|什么时候|几点)?$"), "").trim()
        title = title.trim(' ', '，', ',', '。', '.', '！', '!', '、', '：', ':')
        if (title.isBlank()) return Interpretation.Clarify("请说明想记录的想法内容，例如：记个想法周末去骑车。")
        if (title.length > 200) return Interpretation.Clarify("想法最多200字，请精简后再记录。")
        return Interpretation.Parsed(AssistantCommand.CreateIdea(title, original))
    }

    /** 修改/取消刚才的提醒 keeps the same reminder ID and title; another kind of item is never touched. */
    private fun reminderFollowUp(text: String, context: AssistantContext): Interpretation? {
        if (!text.contains("提醒")) return null
        if (!Regex("刚才|刚刚|上一条|这条|这个").containsMatchIn(text)) return null
        val body = text.replaceFirst(Regex("^(?:把|将)"), "")
        val cancel = Regex("^(?:取消|删除|移除|停用|关掉)").containsMatchIn(body)
        val edit = !cancel && Regex("修改|调整|更改|改到|改|推迟|提前|延后").containsMatchIn(body)
        if (!cancel && !edit) return null
        val last = (lastMutation as? AssistantMutation.SaveReminder)?.reminder
            ?.takeIf { reminder -> context.snapshot.reminders.any { it.id == reminder.id } }
        val target = last ?: context.snapshot.reminders.filter { it.enabled }.singleOrNull()?.takeIf { text.contains(it.title) }
            ?: return Interpretation.Clarify("请说明要${if (cancel) "取消" else "修改"}哪一条提醒，例如：取消刚才的提醒。")
        if (cancel) return Interpretation.Parsed(AssistantCommand.CancelReminder(reminderId = target.id))
        val point = try { point(text, context) } catch (e: NeedPoint) { return Interpretation.Clarify(e.message ?: "请说明新的提醒时间。") }
        return Interpretation.Parsed(AssistantCommand.EditReminder(reminderId = target.id, at = point.at))
    }

    /** Only an explicit reference to 想法 may delete an idea; names, notes and tasks are never touched. */
    private fun ideaFollowUp(text: String, context: AssistantContext): Interpretation? {
        if (!text.contains("想法")) return null
        if (!Regex("^(?:删除|取消|移除|去掉)").containsMatchIn(text)) return null
        if (!Regex("刚才|刚刚|上一条|这条|这个").containsMatchIn(text)) return null
        val last = (lastMutation as? AssistantMutation.SaveIdea)?.idea
            ?.takeIf { idea -> context.snapshot.ideas.any { it.id == idea.id && it.deletedAt == null } }
        val target = last ?: context.snapshot.ideas.filter { it.deletedAt == null }.singleOrNull()?.takeIf { text.contains(it.title) }
        return if (target != null) Interpretation.Parsed(AssistantCommand.DeleteIdea(target.id))
        else Interpretation.Clarify("请说明要删除哪一条想法，例如：删除刚才的想法。")
    }

    /** One explicit clock point becomes a reminder; a range or a stated duration stays a schedule entry. */
    private fun reminder(text: String, context: AssistantContext): Interpretation? {
        if (!text.contains("提醒") && !Regex("^(?:安排|新建计划|创建计划)").containsMatchIn(text)) return null
        // Editing or cancelling an existing reminder is a follow-up on saved data, not a new point reminder.
        if (Regex("^(?:修改|调整|更改|把|取消|删除|移除)").containsMatchIn(text) || Regex("刚才|刚刚|上一条|这条").containsMatchIn(text)) return null
        val relative = Regex("(${LocalAssistantParser.durationPattern.pattern})(?:以?后|之后)").find(text)
        if (relative != null && text.contains("提醒")) {
            if (LocalAssistantParser.clockPattern.containsMatchIn(text) || SpokenTime.dates(text).isNotEmpty() ||
                LocalAssistantParser.durationPattern.findAll(text).count() != 1)
                return Interpretation.Clarify("请只说明一个提醒时间，例如：半小时后提醒我喝水。")
            val minutes = LocalAssistantParser.parseDuration(relative.value)
            if (minutes == null || minutes !in 1..10080) return Interpretation.Clarify("请说明有效的提醒间隔。")
            val rest = text.removeRange(relative.range)
            val frame = frameRange(rest)
            val title = (if (frame == null) rest else rest.removeRange(frame)).trim(' ', '，', ',', '。', '.', '：', ':')
            if (title.isBlank() || title.length > 120) return Interpretation.Clarify("请说明提醒内容，例如：半小时后提醒我喝水。")
            return Interpretation.Parsed(AssistantCommand.CreateReminder(title, context.now.plusSeconds(minutes * 60L).toEpochMilli()))
        }
        if (Regex("周末|晚点|过一会|过会儿|回头|改天|最近|这几天").containsMatchIn(text))
            return Interpretation.Clarify("请说明具体的日期和时钟时间，例如：提醒明天下午三点健身。")
        // Two clocks describe a span, which 提醒 must not invent; let the schedule grammar keep handling 安排.
        if (LocalAssistantParser.clockPattern.findAll(text).count() > 1 && !text.contains("提醒")) return null
        if (LocalAssistantParser.parseDuration(text) != null) {
            // A stated duration describes a span: 提醒 must ask, while 安排 may still become a schedule entry.
            if (!text.contains("提醒")) return null
            return Interpretation.Clarify("提醒只需要一个时间点；如需时长，请改用日程安排。")
        }
        val point = try { point(text, context) } catch (e: NeedPoint) { return Interpretation.Clarify(e.message ?: "请说明具体时间。") }
        val title = reminderTitle(text, point.clock, point.quarterLength, point.dates)
        if (title.isBlank()) return Interpretation.Clarify("请说明提醒的内容，例如：提醒明天下午三点健身。")
        if (title.length > 120) return Interpretation.Clarify("提醒内容最多120字，请精简后再提醒。")
        return Interpretation.Parsed(AssistantCommand.CreateReminder(title, point.at))
    }

    private data class Point(val at: Long, val clock: MatchResult, val quarterLength: Int, val dates: List<MatchResult>)
    private class NeedPoint(message: String) : Exception(message)

    /** Resolves exactly one clock plus at most one date; every uncertain case asks instead of guessing. */
    private fun point(text: String, context: AssistantContext): Point {
        val clocks = LocalAssistantParser.clockPattern.findAll(text).toList()
        if (clocks.isEmpty()) throw NeedPoint("请说明具体的时钟时间，例如：提醒明天下午三点健身。")
        if (clocks.size > 1) throw NeedPoint("提醒只需要一个时间点；如需一段时间，请改用日程安排。")
        val clock = clocks.single()
        val quarter = quarterSuffix(text, clock)
        val base = LocalAssistantParser.parseClock(clock) ?: throw NeedPoint("时间无效，请使用 00:00–23:59 的时钟时间。")
        val added = when (quarter) { "一刻" -> 15; "两刻", "二刻" -> 30; "三刻" -> 45; else -> null }
        var clockTime = if (added == null) base else if (base.minute + added <= 59) base.withMinute(base.minute + added)
            else throw NeedPoint("时间无效，请使用 00:00–23:59 的时钟时间。")
        // 明晚/今晚 eight o'clock is the evening, not the morning.
        val night = clock.groupValues[1] == "晚上" ||
            Regex("(?:晚上|夜里|半夜|午夜|明晚|今晚|昨晚)$").containsMatchIn(text.substring(0, clock.range.first).trimEnd())
        if (night && clockTime.hour in 1..11) clockTime = clockTime.withHour(clockTime.hour + 12)
        if (night && clockTime.hour == 12)
            throw NeedPoint("“晚上十二点”可能指当天午夜或次日零点；请说明是哪一天，或改用明确的 00:00 或 12:00。")
        val today = context.now.atZone(context.zone).toLocalDate()
        val nowLocal = context.now.atZone(context.zone).toLocalDateTime()
        val dates = SpokenTime.dates(text)
        if (dates.size > 1) throw NeedPoint("请为一个时间点只说明一个日期。")
        val token = dates.firstOrNull()?.value
        val bare = token != null && Regex("^(?:周|星期|礼拜)[一二三四五六日天]$").matches(token)
        val day = token?.let { SpokenTime.date(it, context) ?: throw NeedPoint("请说明有效的日期，例如明天或 2026-10-22。") }
        val at = when {
            token == null -> {
                val candidate = today.atTime(clockTime)
                if (candidate.isAfter(nowLocal)) candidate else today.plusDays(1).atTime(clockTime)
            }
            bare -> {
                val candidate = requireNotNull(day).atTime(clockTime)
                if (candidate.isAfter(nowLocal)) candidate else candidate.plusDays(7)
            }
            else -> {
                val candidate = requireNotNull(day).atTime(clockTime)
                if (!candidate.isAfter(nowLocal)) throw NeedPoint("这个时间已经过去，请说明将来的日期和时间。")
                candidate
            }
        }
        return Point(at.atZone(context.zone).toInstant().toEpochMilli(), clock, quarter.length, dates)
    }

    private fun quarterSuffix(text: String, clock: MatchResult): String {
        var index = clock.range.last + 1
        while (index < text.length && text[index] == ' ') index++
        val suffix = text.substring(index, minOf(text.length, index + 2))
        return if (suffix in setOf("一刻", "两刻", "二刻", "三刻")) suffix else ""
    }

    /** Removes only the request frame, so a body starting with 下/我 (下楼拿快递, 我和陈到见面) keeps its first word. */
    private fun reminderTitle(text: String, clock: MatchResult, quarterLength: Int, dates: List<MatchResult>): String {
        val drop = BooleanArray(text.length)
        for (index in clock.range.first until minOf(text.length, clock.range.last + 1 + quarterLength)) drop[index] = true
        dates.forEach { match -> for (index in match.range) if (index in text.indices) drop[index] = true }
        frameRange(text)?.let { frame -> for (index in frame) if (index in text.indices) drop[index] = true }
        val rest = buildString { for (index in text.indices) if (!drop[index]) append(text[index]) }
        return rest.trim().trim(' ', '，', ',', '。', '.', '！', '!', '？', '?', '、', '：', ':').trim()
    }

    /** The request verb plus an adjacent 我/你 (提醒我健身); a 我 after the time phrase is content, not a frame. */
    private fun frameRange(text: String): IntRange? {
        val match = Regex("新建计划|创建计划|提醒|安排").find(text) ?: return null
        var end = match.range.last
        var index = end + 1
        if (index < text.length && (text[index] == '我' || text[index] == '你')) {
            end = index
            index = end + 1
            for (polite in listOf("一下", "吧")) if (text.startsWith(polite, index)) { end = index + polite.length - 1; break }
        }
        return match.range.first..end
    }

}
