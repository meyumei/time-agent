package com.timeagent.extraction

import com.timeagent.data.EntityRevision
import com.timeagent.data.IdeaEntity
import com.timeagent.data.NoteEntity
import com.timeagent.data.ReminderEntity
import com.timeagent.data.ScheduleBlockEntity
import com.timeagent.data.TaskEntity
import com.timeagent.data.TimeRepository
import com.timeagent.core.TimeSpan
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class ExtractionSaveResult(val created: Int, val skipped: Int, val requestId: String)

private fun fingerprint(item: ExtractionCandidate): String = hashText(listOf(
    item.original, item.title.trim(), item.kind.name,
    item.startAt?.toString().orEmpty(), item.endAt?.toString().orEmpty(), item.dueAt?.toString().orEmpty(),
).joinToString("\u0000"))

private fun entityId(type: String, fingerprint: String): String =
    UUID.nameUUIDFromBytes("CHAT_EXTRACT:$type:$fingerprint".toByteArray(Charsets.UTF_8)).toString()

suspend fun saveExtraction(repo: TimeRepository, preview: ExtractionPreview, selected: List<ExtractionCandidate>): ExtractionSaveResult {
    require(preview.sourceText.length <= 20_000 && hashText(preview.sourceText) == preview.sourceHash) { "原文已变化，请重新提取" }
    require(preview.referenceDate == repo.now().atZone(repo.zone).toLocalDate()) { "日期已变化，请重新提取" }
    val trusted = ChatTextExtractor.extract(preview.sourceText, repo.now(), repo.zone)
    require(preview.sourceHash == trusted.sourceHash && preview.items == trusted.items && preview.ignored == trusted.ignored) { "提取结果已变化，请重新提取" }
    require(selected.isNotEmpty() && selected.size <= 30 && selected.map { it.id }.distinct().size == selected.size) { "请选择要保存的事项" }
    val originals = trusted.items.associateBy { it.id }
    for (item in selected) {
        val base = requireNotNull(originals[item.id]) { "事项不在当前预览中" }
        require(base.original == item.original && base.needsCommitment == item.needsCommitment && item.acceptedAsMine) { "请逐条确认这是我的事项" }
        require(item.title.trim().length in 1..200) { "标题长度需为1至200字" }
        when (item.kind) {
            ExtractionKind.INBOX -> require(item.startAt == null && item.endAt == null && item.dueAt == null) { "收件箱事项不能带时间" }
            ExtractionKind.TASK -> require(item.startAt == null && item.endAt == null && item.dueAt != null && item.dueAt > repo.now().toEpochMilli()) { "任务需要未来的明确截止日期；无日期请改为收件箱" }
            ExtractionKind.REMINDER -> require(item.startAt != null && item.startAt > repo.now().toEpochMilli() && item.endAt == null && item.dueAt == null) { "提醒需要未来的单一时刻" }
            ExtractionKind.PLAN -> require(item.startAt != null && item.endAt != null && item.startAt > repo.now().toEpochMilli() && item.dueAt == null && item.endAt > item.startAt && item.endAt - item.startAt <= 7L * 24 * 60 * 60 * 1000) { "计划需要未来的有效起止时间" }
        }
    }
    val fingerprints = selected.map(::fingerprint)
    val sourceKeys = selected.map { "EXTRACT_SOURCE:" + hashText("${preview.sourceHash}\u0000${preview.referenceDate}\u0000${it.id}") }
    val requestId = "CHAT_EXTRACT:" + hashText(sourceKeys.sorted().joinToString("|"))
    var created = 0
    var skipped = 0
    val wrote = repo.assistantTransaction(requestId) {
        val dao = repo.database.dao()
        for ((index, item) in selected.withIndex()) {
            val fp = fingerprints[index]
            val key = "EXTRACT:$fp"
            val sourceKey = sourceKeys[index]
            if (dao.revision(sourceKey) != null) { skipped++; continue }
            if (dao.revision(key) != null) {
                dao.putRevision(EntityRevision(sourceKey, 1))
                skipped++
                continue
            }
            val id = entityId(item.kind.name, fp)
            val planId = entityId("PLAN", fp)
            val noteId = entityId("NOTE", fp)
            require(when (item.kind) {
                ExtractionKind.INBOX -> dao.idea(id) == null
                ExtractionKind.TASK -> dao.task(id) == null
                ExtractionKind.REMINDER -> dao.reminder(id) == null && dao.note(noteId) == null
                ExtractionKind.PLAN -> dao.task(id) == null && dao.plan(planId) == null
            }) { "原事项编号已被使用，请重新提取；不会覆盖现有内容" }
            if (item.kind == ExtractionKind.PLAN) {
                val span = TimeSpan(Instant.ofEpochMilli(item.startAt!!), Instant.ofEpochMilli(item.endAt!!))
                require(repo.conflicts(span).isEmpty()) { "计划与已有日程冲突，请调整时间后保存" }
            }
            when (item.kind) {
                ExtractionKind.INBOX -> repo.saveIdea(IdeaEntity(id, item.title.trim(), content = item.original))
                ExtractionKind.TASK -> repo.saveTask(TaskEntity(id, item.title.trim(), description = item.original, dueAt = item.dueAt, source = "CHAT_EXTRACT"))
                ExtractionKind.REMINDER -> {
                    repo.saveReminder(ReminderEntity(id, item.title.trim(), item.startAt!!))
                    repo.saveNote(NoteEntity(noteId, "REMINDER", id, body = item.original))
                }
                ExtractionKind.PLAN -> {
                    val minutes = Duration.ofMillis(item.endAt!! - item.startAt!!).toMinutes().toInt().coerceAtLeast(1)
                    repo.saveTask(TaskEntity(id, item.title.trim(), description = item.original, estimatedMinutes = minutes, source = "CHAT_EXTRACT"))
                    repo.savePlan(ScheduleBlockEntity(planId, id, item.startAt, item.endAt))
                }
            }
            dao.putRevision(EntityRevision(key, 1))
            dao.putRevision(EntityRevision(sourceKey, 1))
            created++
        }
    }
    if (!wrote) return ExtractionSaveResult(0, selected.size, requestId)
    return ExtractionSaveResult(created, skipped, requestId)
}
