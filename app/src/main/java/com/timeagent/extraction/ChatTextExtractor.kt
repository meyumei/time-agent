package com.timeagent.extraction

import com.timeagent.assistant.AssistantContext
import com.timeagent.assistant.ConversationIntentGuard
import com.timeagent.assistant.LocalAssistantParser
import com.timeagent.assistant.SpokenTime
import com.timeagent.data.LocalSnapshot
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class ExtractionKind { INBOX, TASK, REMINDER, PLAN }

data class ExtractionCandidate(
    val id: String, val original: String, val title: String, val kind: ExtractionKind,
    val startAt: Long? = null, val endAt: Long? = null, val dueAt: Long? = null,
    val needsCommitment: Boolean = false, val acceptedAsMine: Boolean = false,
)

data class ExtractionPreview(
    val sourceText: String, val sourceHash: String, val referenceDate: LocalDate,
    val items: List<ExtractionCandidate>, val ignored: List<String>,
)

internal fun hashText(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

object ChatTextExtractor {
    private val speaker = Regex("^\\s*([^:：\\n\\d]{1,24})[:：]\\s*")
    private val question = Regex("[?？]|(?:吗|呢|是否|是不是|有没有)\\s*[。；;]?$|^(?:怎么|为什么|为何)")
    private val negation = Regex("(?:不要|不用|别|暂不|先不|不想|无需|勿|没有|没|未)(?:.{0,8})(?:安排|提醒|记录|创建|保存|添加|去|做)|(?<![特区别告分级识类离性锈])(?:不|没|未)(?:想|要|会|能|去|练|做|参加|上|完成|交|学习|安排|提醒|记录|创建|保存|添加)")
    private val quote = Regex("[‘'\"“「『].+[’'\"”」』]|(?:他说|她说|引用|原话)[:：]")
    private val invitation = Regex("邀请|约我|叫我|让我们|一起|能来|可以来|要不要|建议|希望")
    private val negativeNarration = Regex("^(?:我|我们)?\\s*(?:不(?!锈钢)|没|未)")

    fun extract(text: String, now: Instant, zone: ZoneId): ExtractionPreview {
        require(text.length <= 20_000) { "聊天文字最多20000字" }
        val today = now.atZone(zone).toLocalDate()
        val context = AssistantContext(now, zone, today, LocalSnapshot())
        val items = mutableListOf<ExtractionCandidate>()
        val ignored = mutableListOf<String>()
        val quoted = BooleanArray(text.length)
        val closing = mapOf('“' to '”', '‘' to '’', '「' to '」', '『' to '』', '"' to '"', '\'' to '\'')
        var open: Char? = null
        for (index in text.indices) {
            val char = text[index]
            val started = open == null && char in closing
            if (started) open = closing.getValue(char)
            if (open != null) quoted[index] = true
            if (!started && open != null && char == open) open = null
            if (open == null && char in setOf('”', '’', '」', '』')) quoted[index] = true
        }
        val split = Regex("[^\\n。；;！？!?]+[。；;！？!?]?|\\n").findAll(text).map { it.value }.filter { it.isNotBlank() && it != "\n" }
        var offset = 0
        for (raw in split) {
            val position = text.indexOf(raw, offset)
            offset = position + raw.length
            if (items.size >= 30) { ignored += "数量超过30条：$raw"; continue }
            val prefix = speaker.find(raw)?.takeIf { match ->
                SpokenTime.datePattern.matchEntire(match.groupValues[1].trim()) == null
            }
            val body = (if (prefix != null) raw.removeRange(prefix.range) else raw).trim()
            if (body.isBlank()) continue
            val reason = when {
                position >= 0 && (position until offset).any { quoted[it] } -> "引用语句"
                question.containsMatchIn(body) -> "问句"
                negation.containsMatchIn(body) || ConversationIntentGuard.refusesWrite(body) -> "否定或暂不执行"
                quote.containsMatchIn(body) -> "引用语句"
                else -> null
            }
            if (reason != null) { ignored += "$reason：$raw"; continue }
            val dates = SpokenTime.dates(body)
            if (SpokenTime.datePattern.findAll(body).count() > 1 && LocalAssistantParser.clockPattern.findAll(body).count() != 2) {
                ignored += "多个日期需要明确的起止时间：$raw"; continue
            }
            val date = dates.firstOrNull()?.let { SpokenTime.date(it.value, context) }
            if (dates.isNotEmpty() && date == null) { ignored += "日期无效：$raw"; continue }
            val clocks = LocalAssistantParser.clockPattern.findAll(body).toList()
            val times = SpokenTime.times(body, clocks)
            if (clocks.isNotEmpty() && times == null) { ignored += "时间无效：$raw"; continue }
            val durations = LocalAssistantParser.durationPattern.findAll(body).toList()
            if (negativeNarration.containsMatchIn(SpokenTime.content(body, clocks, dates, durations))) {
                ignored += "否定叙述：$raw"; continue
            }
            var start: Long? = null
            var end: Long? = null
            var due: Long? = null
            val kind = when {
                date == null -> ExtractionKind.INBOX
                clocks.size >= 2 || (clocks.size == 1 && durations.size == 1) -> {
                    if (clocks.size > 2 || durations.size > 1) { ignored += "时间范围不明确：$raw"; continue }
                    if (clocks.size == 2 && !Regex("[到至~～—–-]").containsMatchIn(body.substring(clocks[0].range.last + 1, clocks[1].range.first))) {
                        ignored += "缺少明确的时间范围：$raw"; continue
                    }
                    start = date.atTime(times!![0]).atZone(zone).toInstant().toEpochMilli()
                    val endDate = dates.getOrNull(1)?.let { SpokenTime.date(it.value, context) } ?: date
                    end = if (times.size == 2) endDate.atTime(times[1]).atZone(zone).toInstant().toEpochMilli()
                        else start + (LocalAssistantParser.parseDuration(body) ?: 0) * 60_000L
                    if (end <= start || end - start > 7L * 24 * 60 * 60 * 1000) { ignored += "时间范围无效：$raw"; continue }
                    ExtractionKind.PLAN
                }
                clocks.size == 1 -> {
                    start = date.atTime(times!![0]).atZone(zone).toInstant().toEpochMilli()
                    ExtractionKind.REMINDER
                }
                clocks.isEmpty() -> {
                    due = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
                    ExtractionKind.TASK
                }
                else -> ExtractionKind.INBOX
            }
            val title = if (date == null) body.trim(' ', '。', '；', ';') else
                SpokenTime.content(body, clocks, dates, durations).trim(' ', '。', '；', ';').ifBlank { body.trim(' ', '。', '；', ';') }
            items += ExtractionCandidate(hashText("${items.size}:$raw"), raw, title.take(200), kind, start, end, due,
                prefix != null || invitation.containsMatchIn(body))
        }
        return ExtractionPreview(text, hashText(text), today, items, ignored)
    }
}
