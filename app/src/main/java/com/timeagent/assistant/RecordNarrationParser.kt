package com.timeagent.assistant

import com.timeagent.data.TimeRecordEntity
import java.time.*

/** Splits record narration while carrying its date and daypart into subsequent ranges. */
internal object RecordNarrationParser {
    private val prefixDate = Regex("^\\s*(\\d{1,2})\\s*[.．/月]\\s*(\\d{1,2})\\s*[日号]?\\s*")
    private val blockedStart = Regex("^(?:安排|新建|创建|计划|提醒|取消|删除|移动|推迟|查询|查看|开始计时|停止计时|新建任务|创建任务)")
    private val correctionHint = Regex("修改|修正|更正|改正|校准|调整|改成|改为|(?:真实|实际)(?:的)?.{0,12}(?:时长|时间)")
    private val noteMarker = Regex("(?:备注|附注|说明)\\s*(?:为|是|:|：)\\s*")
    private val labelNoise = Regex("(?:修改|修正|更正|改正|校准|调整|改成|改为|真实|实际|对应|相应|这段|该段|这条|这个|记录|时长|时间)")
    private val temporalPrefix = Regex("^\\s*(?:(?:今天|今日|当天|昨天|昨日|前天|明天|今晚|昨晚|\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}|\\d{1,2}\\s*[.．/月]\\s*\\d{1,2}\\s*[日号]?|\\d{1,2}\\s*[日号])\\s*)+")
    private val daypartOnly = Regex("^(?:上午|下午|晚上|中午|凌晨|早晨|早上|傍晚|夜里|半夜|夜间|今晚|昨晚|昨天|今日|今天|前天|明天)$")
    private val leading = Regex("^(?:从|到|至|在|是|为|把|做了|进行了|做|进行|然后|接着|再|那|我|的|了|，|,|。|、|:|：|\\s)+")
    private val trailing = Regex("(?:从|到|至|在|是|为|做|了|的|，|,|。|、|:|：|\\s)+$")
    private val separators = Regex("[，,、；;]")
    private val nightWords = setOf("晚上", "夜里", "夜间", "傍晚", "半夜", "今晚", "昨晚")
    private val groups = listOf(
        setOf("训练", "健身", "锻炼", "运动", "撸铁", "力量", "跑步", "workout", "gym", "fitness"),
        setOf("科研", "研究", "实验", "论文"),
    )

    fun parse(input: String, context: AssistantContext): Interpretation? {
        val text = input.trim()
        if (text.length < 4 || text.contains('?') || text.contains('？')) return null
        if (blockedStart.containsMatchIn(text)) return null

        val today = context.now.atZone(context.zone).toLocalDate()
        val prefix = prefixDate.find(text)
        val prefixDay = prefix?.let { runCatching { LocalDate.of(today.year, it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.getOrNull() }

        var body = text
        if (prefix != null) {
            body = text.removeRange(prefix.range).trimStart(' ', '，', ',')
                .removePrefix("今天").removePrefix("今日").removePrefix("当天").trimStart(' ', '，', ',')
        }

        val note = noteMarker.find(body)
        val notes = note?.let { body.substring(it.range.last + 1).trim().trimEnd('。', '.', '，', ',').trim() }
        val narration = if (note == null) body else body.substring(0, note.range.first)

        val clocks = LocalAssistantParser.clockPattern.findAll(narration).toList()
        if (clocks.size < 2 || clocks.size % 2 != 0) return null
        if (Regex("安排|计划|打算|准备|想要|提醒|取消|删除|查询|查看").containsMatchIn(narration.substring(0, clocks.first().range.first))) return null
        if (clocks.size < 4 && !correctionHint.containsMatchIn(narration)) return null
        if (prefix != null && prefixDay == null) return Interpretation.Clarify("日期无效，请核对月日。")

        val tokens = SpokenTime.dates(narration)
        val fallback = prefixDay ?: today
        val count = clocks.size / 2
        val trailingLabel = arrayOfNulls<String>(count)
        val leadingLabel = arrayOfNulls<String>(count)
        val after = arrayOfNulls<String>(count)

        leadingLabel[0] = narration.substring(0, clocks[0].range.first)
            .split(separators).map { cleanLabel(it) }.lastOrNull { it.isNotEmpty() }

        for (i in 0 until count) {
            val endIdx = clocks[i * 2 + 1].range.last + 1
            val stop = clocks.getOrNull(i * 2 + 2)?.range?.first ?: narration.length
            if (stop <= endIdx) continue
            val gap = narration.substring(endIdx, stop)
            after[i] = gap
            val raw = gap.split(separators)
            val cleaned = raw.map { cleanLabel(it) }
            val filled = cleaned.indices.filter { cleaned[it].isNotEmpty() }
            if (filled.isEmpty()) continue
            when {
                i == count - 1 -> trailingLabel[i] = cleaned[filled.first()]
                filled.size >= 2 -> {
                    trailingLabel[i] = cleaned[filled.first()]
                    leadingLabel[i + 1] = cleaned[filled.last()]
                }
                else -> {
                    val only = filled.single()
                    val lastRaw = raw.indexOfLast { it.isNotBlank() }
                    if (raw.size > 1 && only == lastRaw && only > 0) leadingLabel[i + 1] = cleaned[only]
                    else trailingLabel[i] = cleaned[only]
                }
            }
        }

        val commands = mutableListOf<AssistantCommand>()
        var night = false
        for (index in 0 until count) {
            val start = clocks[index * 2]
            val end = clocks[index * 2 + 1]
            val label = (trailingLabel[index] ?: leadingLabel[index]).orEmpty().trim()
            if (Regex("^(?:没有|没|未|不|为什么|为何|怎么|怎样|是否)").containsMatchIn(label)) return null
            if (label.isEmpty()) return Interpretation.Clarify("请说明这段记录是什么活动，例如训练、科研。")
            if (label.length > 120) return Interpretation.Clarify("活动名称太长，请精简后重试。")

            val from = if (index == 0) 0 else clocks[index * 2 - 1].range.last + 1
            val segmentDate = tokens.lastOrNull { it.range.first < start.range.first }
                ?.let { SpokenTime.date(it.value, context) } ?: fallback
            val newDate = tokens.any { it.range.first in from until start.range.first }

            val times = SpokenTime.times(narration, listOf(start, end))
                ?: return Interpretation.Clarify("“$label”的时间无效，请说明起止时间。")
            var startTime = times[0]
            var endTime = times[1]
            if (night && !newDate && start.groupValues[1].isBlank() && startTime.hour in 1..11) {
                startTime = startTime.plusHours(12)
                if (end.groupValues[1].isBlank() && endTime.hour in 1..11 && endTime.plusHours(12) > startTime) endTime = endTime.plusHours(12)
            }
            night = when {
                start.groupValues[1].isNotBlank() -> start.groupValues[1] in nightWords
                newDate -> false
                else -> startTime.hour >= 18
            }

            val startAt = segmentDate.atTime(startTime).atZone(context.zone).toInstant()
            val endAt = segmentDate.atTime(endTime).atZone(context.zone).toInstant()
            if (endAt <= startAt) return Interpretation.Clarify("“$label”的结束时间不晚于开始时间，请说明跨天或改用记录编辑器。")
            if (Duration.between(startAt, endAt).toMinutes() > 1440) return Interpretation.Clarify("“$label”的时间段超过一天，请核对。")

            val fixing = correctionHint.containsMatchIn(after[index].orEmpty()) ||
                (index == 0 && correctionHint.containsMatchIn(narration.substring(0, start.range.first)))

            if (fixing) {
                val sameDay = dayRecords(segmentDate, context).filter { matches(label, keys(it, context)) }
                val hits = if (sameDay.size > 1) sameDay.filter { record ->
                    record.startAt < endAt.toEpochMilli() && (record.endAt ?: record.startAt) > startAt.toEpochMilli()
                } else sameDay
                when {
                    hits.size == 1 -> commands += AssistantCommand.EditRecord(
                        recordId = hits.single().id,
                        startAt = startAt.toEpochMilli(),
                        endAt = endAt.toEpochMilli(),
                    )
                    hits.isEmpty() -> return Interpretation.Clarify("没有找到${dateText(segmentDate)}的“$label”实际记录，未做修改。")
                    else -> return Interpretation.Clarify("${dateText(segmentDate)}有 ${hits.size} 条“$label”记录，请说明要修改哪一条。")
                }
            } else {
                commands += AssistantCommand.ManualRecord(
                    label = label,
                    startAt = startAt.toEpochMilli(),
                    endAt = endAt.toEpochMilli(),
                    notes = if (index == count - 1) notes ?: "" else "",
                )
            }
        }
        return when (commands.size) {
            0 -> null
            1 -> Interpretation.Parsed(commands.single())
            else -> Interpretation.Parsed(AssistantCommand.Batch(commands))
        }
    }

    private fun cleanLabel(raw: String): String {
        val withoutDate = raw.trim().let { text ->
            SpokenTime.datePattern.find(text)?.takeIf { it.range.first == 0 }?.let { text.removeRange(it.range) } ?: text
        }
        var value = withoutDate.replace(temporalPrefix, " ").replace(labelNoise, " ")
        repeat(3) { value = value.replace(leading, " ").replace(trailing, " ") }
        value = value.replace(Regex("\\s+"), " ").trim(' ', '，', ',', '。', '、', '：', ':', '；', ';')
        return if (daypartOnly.matches(value)) "" else value
    }

    private fun dayRecords(date: LocalDate, context: AssistantContext): List<TimeRecordEntity> {
        val from = date.atStartOfDay(context.zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(context.zone).toInstant().toEpochMilli()
        return context.snapshot.records.filter { record ->
            record.startAt < to && (record.endAt ?: record.startAt) > from
        }
    }

    private fun keys(record: TimeRecordEntity, context: AssistantContext): List<String> {
        val keys = mutableListOf<String>()
        record.title.orEmpty().trim().takeIf { it.isNotEmpty() }?.let { keys += it }
        if (keys.isEmpty()) record.notes.orEmpty().trim().takeIf { it.isNotEmpty() }?.let { keys += it }
        if (record.source == "WORKOUT") keys += "训练"
        record.taskId?.let { id -> context.snapshot.tasks.firstOrNull { it.id == id }?.title?.let { keys += it } }
        record.categoryId?.let { id -> context.snapshot.categories.firstOrNull { it.id == id }?.name?.let { keys += it } }
        return keys
    }

    private fun matches(label: String, keys: List<String>): Boolean {
        val value = label.lowercase()
        if (value.isEmpty()) return false
        if (keys.any { key -> key.length >= 2 && (key.lowercase().contains(value) || value.contains(key.lowercase())) }) return true
        val group = groups.firstOrNull { words -> words.any { value.contains(it) } } ?: return false
        return keys.any { key -> group.any { key.lowercase().contains(it) } }
    }

    private fun dateText(date: LocalDate) = "${date.monthValue}月${date.dayOfMonth}日"
}
