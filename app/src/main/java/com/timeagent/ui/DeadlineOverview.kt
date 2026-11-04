package com.timeagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.timeagent.R
import com.timeagent.data.LocalSnapshot
import com.timeagent.data.TaskEntity
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class DeadlineUrgency(val label: Int) {
    OVERDUE(R.string.deadline_overdue),
    TODAY(R.string.deadline_today),
    THREE_DAYS(R.string.deadline_three_days),
    LATER(R.string.deadline_later),
}

private data class DeadlineOverviewItem(val task: TaskEntity, val recurring: DeadlineNext? = null)

@Composable
internal fun DeadlineOverview(
    data: LocalSnapshot,
    next: List<DeadlineNext>,
    now: Instant,
    zone: ZoneId,
    onTask: (TaskEntity) -> Unit,
    onSchedule: (TaskEntity) -> Unit,
    onToggle: (TaskEntity) -> Unit,
    onHandleRecurring: (DeadlineNext) -> Unit,
    onDelayRecurring: (DeadlineNext) -> Unit,
) {
    val recurringTaskIds = data.recurringInstances.filter { instance ->
        data.recurringRules.any { it.id == instance.ruleId && it.domain == "DEADLINE" }
    }.map { it.taskId }.toSet()
    val items = (
        data.tasks.filter {
            it.dueAt != null && it.deletedAt == null && it.status in setOf("TODO", "IN_PROGRESS") &&
                it.id !in recurringTaskIds
        }.map { DeadlineOverviewItem(it) } +
            next.mapNotNull { item -> item.task?.takeIf {
                it.deletedAt == null && it.status in setOf("TODO", "IN_PROGRESS") && it.dueAt != null
            }?.let { DeadlineOverviewItem(it, item) } }
        ).distinctBy { it.task.id }.sortedBy { it.task.dueAt }
    val today = now.atZone(zone).toLocalDate()
    fun urgency(item: DeadlineOverviewItem): DeadlineUrgency {
        val due = Instant.ofEpochMilli(requireNotNull(item.task.dueAt))
        val day = due.atZone(zone).toLocalDate()
        return when {
            due.isBefore(now) -> DeadlineUrgency.OVERDUE
            day == today -> DeadlineUrgency.TODAY
            !day.isAfter(today.plusDays(3)) -> DeadlineUrgency.THREE_DAYS
            else -> DeadlineUrgency.LATER
        }
    }
    val grouped = items.groupBy(::urgency)
    if (items.isEmpty()) Text(stringResource(R.string.deadline_overview_empty), Modifier.padding(vertical = 16.dp))
    for (bucket in DeadlineUrgency.entries) {
        val group = grouped[bucket].orEmpty()
        if (group.isEmpty()) continue
        Text(stringResource(R.string.deadline_group_count, stringResource(bucket.label), group.size),
            Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
        for (entry in group) {
            val task = entry.task
            val due = Instant.ofEpochMilli(requireNotNull(task.dueAt))
            val minutes = Duration.between(now, due).toMinutes()
            val children = data.placements.filter { it.parentTaskId == task.id }.mapNotNull { placement ->
                data.tasks.firstOrNull { it.id == placement.taskId && it.deletedAt == null && it.status != "CANCELLED" }
            }
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("deadline-task-${task.id}")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(task.title, style = MaterialTheme.typography.titleMedium)
                    Text(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(due),
                        style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(if (due.isBefore(now)) R.string.deadline_overdue_minutes else R.string.deadline_remaining_minutes,
                            if (due.isBefore(now)) -minutes else minutes),
                        color = if (due.isBefore(now)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (children.isNotEmpty()) {
                        val completed = children.count { it.status == "COMPLETED" }
                        Text(stringResource(R.string.deadline_subtask_progress, completed, children.size),
                            style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { completed.toFloat() / children.size }, modifier = Modifier.fillMaxWidth())
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        TextButton({ onTask(task) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.deadline_open_task)) }
                        TextButton({ onSchedule(task) }, Modifier.heightIn(min = 48.dp).testTag("deadline-schedule-${task.id}")) {
                            Text(stringResource(R.string.deadline_schedule))
                        }
                        TextButton({
                            entry.recurring?.let(onHandleRecurring) ?: onToggle(task)
                        }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.deadline_complete)) }
                    }
                    entry.recurring?.let { item ->
                        Text(item.rule.title, style = MaterialTheme.typography.labelSmall)
                        TextButton({ onDelayRecurring(item) }, Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.deadline_delay))
                        }
                    }
                }
            }
        }
    }
}
