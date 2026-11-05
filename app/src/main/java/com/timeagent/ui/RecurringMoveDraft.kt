package com.timeagent.ui

import android.content.Context
import com.google.gson.Gson
import com.timeagent.data.Conflict
import com.timeagent.core.RecurringPattern
import com.timeagent.core.TimeSpan
import com.timeagent.data.RecurringInstanceEntity
import com.timeagent.data.RecurringRuleEntity
import com.timeagent.data.TaskEntity
import com.timeagent.recurring.RecurringMovePreview
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

internal data class MoveConflictDraft(val id: String, val title: String, val startAt: Long, val endAt: Long) {
    fun conflict() = Conflict(id, title, TimeSpan(Instant.ofEpochMilli(startAt), Instant.ofEpochMilli(endAt)))
}

internal data class MovePreviewDraft(
    val at: Long,
    val planned: Boolean,
    val taskVersion: Long,
    val planVersion: Long?,
    val conflicts: List<MoveConflictDraft>,
) {
    fun preview(instance: RecurringInstanceEntity) = RecurringMovePreview(
        instance.id, instance.version, at, planned, taskVersion, planVersion, conflicts.map { it.conflict() })

    companion object {
        fun from(check: RecurringMovePreview) = MovePreviewDraft(check.at, check.planned, check.taskVersion, check.planVersion,
            check.conflicts.map { MoveConflictDraft(it.id, it.title, it.span.start.toEpochMilli(), it.span.end.toEpochMilli()) })
    }
}

internal data class MoveFields(val dateText: String, val timeText: String, val planned: Boolean) {
    fun values(): List<Any?> = listOf(dateText, timeText, planned)
}

internal data class RecurringMoveDraft(
    val id: String,
    val instance: RecurringInstanceEntity,
    val domain: String,
    val title: String,
    val taskVersion: Long,
    val zoneId: String,
    val fields: MoveFields,
    val baseline: MoveFields,
    val requestId: String = UUID.randomUUID().toString(),
    val preview: MovePreviewDraft? = null,
    val saved: Boolean = false,
) {
    companion object {
        fun create(instance: RecurringInstanceEntity, task: TaskEntity?, zone: ZoneId): RecurringMoveDraft {
            val rule = Gson().fromJson(instance.ruleSnapshot, RecurringRuleEntity::class.java)
            val pattern = Gson().fromJson(rule.patternJson, RecurringPattern::class.java)
            val scheduled = instance.scheduledAt?.let { Instant.ofEpochMilli(it).atZone(zone) }
            val minute = pattern.minuteOfDay ?: 0
            val fields = MoveFields(scheduled?.toLocalDate()?.toString() ?: LocalDate.parse(instance.originalDate).toString(),
                scheduled?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "%02d:%02d".format(minute / 60, minute % 60),
                rule.domain != "DEADLINE" && pattern.minuteOfDay != null)
            return RecurringMoveDraft(instance.id, instance, rule.domain, task?.title ?: rule.title,
                task?.version ?: instance.taskVersion, zone.id, fields, fields)
        }
    }
}

internal class RecurringMoveDraftStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("recurring_move_drafts", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun read(id: String): RecurringMoveDraft? = preferences.getString(id, null)?.let { raw ->
        runCatching { gson.fromJson(raw, RecurringMoveDraft::class.java) }.getOrNull()
    }

    fun all(): List<RecurringMoveDraft> = preferences.all.keys.sorted().mapNotNull(::read)

    fun save(draft: RecurringMoveDraft): Boolean = runCatching {
        preferences.edit().putString(draft.id, gson.toJson(draft)).commit()
    }.getOrDefault(false)

    fun remove(id: String): Boolean = runCatching { preferences.edit().remove(id).commit() }.getOrDefault(false)
}
