package com.timeagent.reply

import com.timeagent.core.Scheduling
import com.timeagent.core.TimeSpan
import com.timeagent.data.*
import com.timeagent.extraction.*
import com.timeagent.memory.PersonalMemoryRules
import java.time.*
import java.time.format.DateTimeFormatter

enum class ReplyKind { ACCEPT, ALTERNATE, CLARIFY }
data class ReplyAvailability(
    val source: ExtractionPreview, val candidate: ExtractionCandidate?, val day: LocalDate,
    val minutes: Int, val from: LocalTime, val until: LocalTime,
    val accepted: TimeSpan?, val alternatives: List<TimeSpan>, val runningTimer: Boolean,
)
data class ReplyDraft(val check: ReplyAvailability, val kind: ReplyKind, val slot: TimeSpan?, val text: String) {
    val title: String get() = check.candidate?.title ?: "待确认事项"
    val requestId: String get() = "REPLY:" + hashText("${check.source.sourceHash}|${check.source.referenceDate}|${check.candidate?.id}|$slot")
}

/** Drafting has no database writes; saving an arrangement is a separate explicit action. */
object ReplyCopilot {
    fun check(source: String, candidateId: String?, day: LocalDate, minutes: Int, from: LocalTime, until: LocalTime,
              data: LocalSnapshot, now: Instant, zone: ZoneId): ReplyAvailability {
        require(minutes in 1..480 && until > from) { "请填写1至480分钟和有效查询时段" }
        val extracted = ChatTextExtractor.extract(source, now, zone)
        val candidate = candidateId?.let { id -> requireNotNull(extracted.items.find { it.id == id }) { "候选事项已变化，请重新提取" } }
        val window = TimeSpan(day.atTime(from).atZone(zone).toInstant(), day.atTime(until).atZone(zone).toInstant())
        val busy = data.plans.filter { it.status == "SCHEDULED" }.map { TimeSpan(Instant.ofEpochMilli(it.startAt), Instant.ofEpochMilli(it.endAt)) } +
            data.occurrences.filter { it.status != "CANCELLED" }.map { TimeSpan(Instant.ofEpochMilli(it.startAt), Instant.ofEpochMilli(it.endAt)) } +
            data.records.mapNotNull { record ->
                val end = record.endAt?.let(Instant::ofEpochMilli) ?: window.end
                Instant.ofEpochMilli(record.startAt).takeIf { it < end }?.let { TimeSpan(it, end) }
            }
        val start = maxOf(window.start, Instant.ofEpochMilli((now.toEpochMilli() / 60000 + 1) * 60000))
        val task = TaskEntity("reply-preview", candidate?.title ?: "待确认事项", estimatedMinutes = minutes)
        fun allowed(slot: TimeSpan) = busy.none { it.overlaps(slot) } && PersonalMemoryRules.allows(data.memories, task, slot.start, slot.end, zone)
        val alternatives = if (start >= window.end || candidate == null) emptyList() else
            Scheduling.findFreeSlots(TimeSpan(start, window.end), busy, minutes).mapNotNull { gap ->
                var cursor = gap.start
                var found: TimeSpan? = null
                while (cursor.plusSeconds(minutes * 60L) <= gap.end && found == null) {
                    val slot = TimeSpan(cursor, cursor.plusSeconds(minutes * 60L))
                    if (allowed(slot)) found = slot else cursor = cursor.plusSeconds(60)
                }
                found
            }.take(12)
        val proposed = candidate?.takeIf { it.startAt != null && it.endAt != null }?.let {
            TimeSpan(Instant.ofEpochMilli(it.startAt!!), Instant.ofEpochMilli(it.endAt!!))
        }?.takeIf { it.start > now && it.start >= window.start && it.end <= window.end && allowed(it) }
        return ReplyAvailability(extracted, candidate, day, minutes, from, until, proposed, alternatives, data.records.any { it.endAt == null })
    }

    fun draft(check: ReplyAvailability, kind: ReplyKind, alternative: Int = 0, zone: ZoneId): ReplyDraft {
        val slot = when (kind) {
            ReplyKind.ACCEPT -> requireNotNull(check.accepted) { "原文没有可接受的明确空闲时段，请另约或先确认" }
            ReplyKind.ALTERNATE -> requireNotNull(check.alternatives.getOrNull(alternative)) { "请先选择已核对的空档" }
            ReplyKind.CLARIFY -> null
        }
        val formatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
        val period = slot?.let { "${formatter.format(it.start.atZone(zone))}–${it.end.atZone(zone).toLocalTime()}" }
        val text = when (kind) {
            ReplyKind.ACCEPT -> "可以，我这边 $period 方便，${check.candidate!!.title}就按这个时间。"
            ReplyKind.ALTERNATE -> "我这边 $period 方便，${check.candidate!!.title}改到这个时间可以吗？"
            ReplyKind.CLARIFY -> "我先确认一下${check.candidate?.title?.let { "「$it」的" }.orEmpty()}具体日期、开始时间和预计时长，再回复是否方便。"
        }
        return ReplyDraft(check, kind, slot, text)
    }

    suspend fun save(repo: TimeRepository, draft: ReplyDraft, confirmedAsMine: Boolean): Boolean {
        require(confirmedAsMine) { "请确认这是我的安排" }
        val slot = requireNotNull(draft.slot) { "确认问题的草稿没有安排可保存" }
        require(draft.check.source.referenceDate == repo.now().atZone(repo.zone).toLocalDate()) { "日期已变化，请重新核对空档" }
        return repo.assistantTransaction(draft.requestId) {
            val fresh = check(draft.check.source.sourceText, draft.check.candidate?.id, draft.check.day, draft.check.minutes,
                draft.check.from, draft.check.until, repo.snapshot(), repo.now(), repo.zone)
            val valid = when (draft.kind) {
                ReplyKind.ACCEPT -> slot == fresh.accepted
                ReplyKind.ALTERNATE -> fresh.alternatives.any { it.start <= slot.start && slot.end <= it.end } || run {
                    // A newly split gap may still contain the chosen interval; check that exact window again.
                    val exact = check(fresh.source.sourceText, fresh.candidate?.id, slot.start.atZone(repo.zone).toLocalDate(),
                        (slot.seconds / 60).toInt(), slot.start.atZone(repo.zone).toLocalTime(), slot.end.atZone(repo.zone).toLocalTime(),
                        repo.snapshot(), repo.now(), repo.zone)
                    exact.alternatives.any { it == slot }
                }
                ReplyKind.CLARIFY -> false
            }
            require(valid) { "这个时段已变化或有冲突，请重新核对空档；回复文字仍保留" }
            val taskId = "reply-task:${draft.requestId}"
            require(repo.database.dao().task(taskId) == null) { "此安排已经保存或撤销，请在原事项中处理" }
            repo.saveTask(TaskEntity(taskId, requireNotNull(fresh.candidate).title, description = fresh.candidate.original,
                estimatedMinutes = (slot.seconds / 60).toInt(), source = "REPLY_COPILOT"))
            repo.savePlan(ScheduleBlockEntity("reply-plan:${draft.requestId}", taskId, slot.start.toEpochMilli(), slot.end.toEpochMilli()), expectedConflicts = emptyList())
        }
    }
}
