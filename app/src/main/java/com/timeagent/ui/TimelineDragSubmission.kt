package com.timeagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.timeagent.R
import com.timeagent.data.*
import com.timeagent.domain.command.*
import com.timeagent.focus.FocusSessionEntity
import com.timeagent.recurring.RecurringService
import com.timeagent.recurring.RuleChangePreview
import com.timeagent.ui.components.InlineError
import com.timeagent.ui.timeline.TimelineDragResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

internal data class TimelineDragRequest(
    val item: TimelineItem,
    val result: TimelineDragResult,
    val task: TaskEntity?,
    val instance: RecurringInstanceEntity?,
    val rule: RecurringRuleEntity?,
    val focus: FocusSessionEntity?,
    val requestId: String = UUID.randomUUID().toString(),
) {
    companion object {
        fun capture(item: TimelineItem, result: TimelineDragResult, data: LocalSnapshot): TimelineDragRequest {
            val taskId = (item.entity as? ScheduleBlockEntity)?.taskId ?: (item.entity as? TimeRecordEntity)?.taskId
            val instance = data.recurringInstances.firstOrNull { "recurring-plan:${it.id}" == item.id }
            return TimelineDragRequest(item, result, data.tasks.firstOrNull { it.id == taskId }, instance,
                data.recurringRules.firstOrNull { it.id == instance?.ruleId },
                data.focusSessions.firstOrNull { "focus:${it.id}" == (item.entity as? TimeRecordEntity)?.id })
        }
    }
}

private fun ScheduleBlockEntity.adjusted(result: TimelineDragResult): ScheduleBlockEntity {
    val zone = ZoneId.of(timezoneId)
    return copy(startAt = result.startAt, endAt = if (kind == "POINT") result.startAt else result.endAt,
        allDayStartDate = if (kind == "ALL_DAY") Instant.ofEpochMilli(result.startAt).atZone(zone).toLocalDate().toString() else allDayStartDate,
        allDayEndDate = if (kind == "ALL_DAY") Instant.ofEpochMilli(result.endAt - 1).atZone(zone).toLocalDate().toString() else allDayEndDate)
}

@Composable
internal fun TimelineDragSubmission(
    request: TimelineDragRequest, repository: TimeRepository,
    onDismiss: () -> Unit, onSaved: (CommandResult) -> Unit, onShowLatest: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var saving by remember(request.requestId) { mutableStateOf(false) }
    var failure by remember(request.requestId) { mutableStateOf<CommandResult?>(null) }
    var preview by remember(request.requestId) { mutableStateOf<RuleChangePreview?>(null) }
    var previewError by remember(request.requestId) { mutableStateOf<String?>(null) }
    val recurring = request.item.id.startsWith("recurring-plan:")
    val future = request.item.kind == ItemKind.RECORD && request.result.endAt > repository.now().toEpochMilli()
    val longSpan = request.result.endAt - request.result.startAt > 7 * 86_400_000L
    suspend fun submit(includingFuture: Boolean = false, asPlan: Boolean = false) {
        saving = true
        try {
            val item = request.item
            val result = request.result
            val command: Command = when (val entity = item.entity) {
                is ScheduleBlockEntity -> if (recurring) {
                    val instance = requireNotNull(request.instance) { "周期实例已变化，请查看最新日程" }
                    AdjustRecurringTimeline(request.requestId, entity.adjusted(result), instance.id, entity.version,
                        instance.version, requireNotNull(request.task).version, requireNotNull(request.rule).version,
                        includingFuture, if (includingFuture) preview else null)
                } else UpdateSchedule(request.requestId, entity.adjusted(result), entity.version, request.task?.version)
                is TimeRecordEntity -> when {
                    asPlan -> CreateSchedule(request.requestId, ScheduleBlockEntity(
                        id = "timeline-proposal:${request.requestId}", taskId = entity.taskId, title = item.title,
                        startAt = result.startAt, endAt = result.endAt, notes = entity.notes, timezoneId = repository.zone.id),
                        request.task?.version)
                    entity.source == "FOCUS" -> {
                        val focus = requireNotNull(request.focus) { "专注已变化，请查看最新记录" }
                        WriteFocus(request.requestId, FocusMutation.AdjustSegment(focus.id, focus.version,
                            requireNotNull(item.id.substringAfter("#segment:", "").toIntOrNull()),
                            result.startAt, result.endAt, allowFuture = future))
                    }
                    else -> UpdateActual(request.requestId, entity.copy(startAt = result.startAt, endAt = result.endAt),
                        entity.version, allowFuture = future, expectedTaskVersion = request.task?.version)
                }
                else -> error("此来源不能拖动调整")
            }
            val saved = repository.commands.execute(command)
            if (saved.status in setOf(CommandStatus.APPLIED, CommandStatus.DUPLICATE)) onSaved(saved)
            else failure = saved
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failure = CommandResult(request.requestId, CommandStatus.FAILED,
                message = error.message ?: "调整尚未保存，请重新选择时间")
        } finally {
            saving = false
        }
    }
    LaunchedEffect(request.requestId) {
        if (recurring) {
            try {
                val instance = requireNotNull(request.instance)
                preview = RecurringService(repository).previewRuleChange(instance.ruleId, LocalDate.parse(instance.originalDate))
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { previewError = error.message ?: "无法读取周期影响范围" }
        } else if (!future && !longSpan) submit()
    }
    if (recurring || future || longSpan || failure != null) AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(when {
            failure?.status == CommandStatus.CONFLICT -> R.string.timeline_drag_conflict
            failure != null -> R.string.timeline_drag_failed
            recurring -> R.string.timeline_drag_scope
            future -> R.string.timeline_drag_future
            else -> R.string.timeline_drag_long_span
        })) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(request.item.title)
                Text("${Instant.ofEpochMilli(request.result.startAt).label(repository.zone)} – ${Instant.ofEpochMilli(request.result.endAt).label(repository.zone)}")
                if (future) Text(stringResource(R.string.timeline_drag_future_explanation))
                if (recurring) {
                    Text(stringResource(R.string.timeline_drag_scope_explanation))
                    preview?.let { Text(stringResource(R.string.timeline_drag_scope_count, it.editable, it.protectedCount)) }
                    InlineError(previewError)
                }
                InlineError(failure?.message)
            }
        },
        confirmButton = {
            if (failure != null) TextButton(onClick = onShowLatest) { Text(stringResource(R.string.timeline_drag_show_latest)) }
            else Column {
                Button(onClick = { scope.launch { submit() } }, enabled = !saving,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (recurring) R.string.timeline_drag_only_this else R.string.action_save))
                }
                if (recurring) TextButton(onClick = { scope.launch { submit(includingFuture = true) } },
                    enabled = !saving && preview != null && previewError == null) {
                    Text(stringResource(R.string.timeline_drag_this_and_future))
                }
                if (future) TextButton(onClick = { scope.launch { submit(asPlan = true) } }, enabled = !saving) {
                    Text(stringResource(R.string.timeline_drag_create_plan))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.action_cancel)) } },
    )
}
