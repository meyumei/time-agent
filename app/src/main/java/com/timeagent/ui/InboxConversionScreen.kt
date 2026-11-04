package com.timeagent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.timeagent.data.*
import com.timeagent.domain.command.ConvertInbox
import com.timeagent.domain.command.InboxTarget
import com.timeagent.domain.command.throwIfRejected
import com.timeagent.ledger.LedgerRepository
import com.timeagent.ledger.LedgerSnapshot
import com.timeagent.ledger.TransactionDialog
import kotlinx.coroutines.flow.catch
import java.util.UUID

@Composable
internal fun InboxConversionScreen(
    model: TimeViewModel,
    idea: IdeaEntity,
    target: String,
    ledger: LedgerRepository,
    onClose: () -> Unit,
    onManageLedger: () -> Unit,
) {
    val data by model.data.collectAsState()
    val busy by model.busy.collectAsState()
    val error by model.error.collectAsState()
    val id = rememberSaveable(idea.id, idea.version, target) { UUID.randomUUID().toString() }
    fun commit(destination: InboxTarget) {
        model.perform(onSuccess = onClose) {
            model.repository.commands.execute(
                ConvertInbox("inbox:$id", idea.id, idea.version, destination),
            ).throwIfRejected()
        }
    }
    when (target) {
        IdeaConversionTarget.TASK, IdeaConversionTarget.SCHEDULE -> {
            val kind = if (target == IdeaConversionTarget.TASK) EditorKind.TASK else EditorKind.PLAN
            val request = remember(id) {
                EditorRequest(kind, id = id, title = idea.title, notes = idea.content, scheduleRange = true)
            }
            MinuteClockContent(model) { now ->
                EditorScreen(request, data, now, model.zone, busy, onClose, onSave = { values ->
                    if (kind == EditorKind.TASK) {
                        commit(InboxTarget.Task(TaskEntity(
                            id, values.title, description = values.notes,
                            estimatedMinutes = values.estimatedMinutes, priority = values.priority,
                            dueAt = values.dueAt, categoryId = values.categoryId, activityId = values.activityId,
                        )))
                    } else {
                        commit(InboxTarget.Schedule(ScheduleBlockEntity(
                            id, values.taskId, values.start, values.end, title = values.title, notes = values.notes,
                            allDayStartDate = values.allDayStartDate, allDayEndDate = values.allDayEndDate,
                            kind = if (values.allDayStartDate != null) "ALL_DAY" else "TIMED",
                            timezoneId = model.zone.id,
                        )))
                    }
                }, saveError = error)
            }
        }
        IdeaConversionTarget.MEMORY -> {
            MemoryEditorDialog(
                model, null, busy, initialText = idea.content.ifBlank { idea.title },
                initialSource = idea.title, onSaveDraft = { commit(InboxTarget.Memory(it.copy(id = id))) },
                onClose = onClose,
            )
        }
        IdeaConversionTarget.LEDGER -> {
            var snapshot by remember { mutableStateOf<LedgerSnapshot?>(null) }
            var loadError by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(ledger) {
                ledger.observe().catch { loadError = it.message ?: "账目读取失败" }.collect { snapshot = it }
            }
            val current = snapshot
            if (current == null || current.accounts.none { it.archivedAt == null } ||
                current.categories.none { it.archivedAt == null }) {
                AlertDialog(
                    onDismissRequest = onClose,
                    title = { Text("转为记账") },
                    text = { Column {
                        Text(loadError ?: if (current == null) "正在读取账户与分类…" else "请先在记账中添加账户和分类；这条想法仍保留在收件箱。")
                        error?.let { Text(it) }
                    } },
                    confirmButton = { TextButton(onClick = onManageLedger) { Text("管理账户与分类") } },
                    dismissButton = { TextButton(onClick = onClose) { Text("返回") } },
                )
            } else {
                TransactionDialog(
                    existing = null, accounts = current.accounts, categories = current.categories,
                    sources = current.sources, captures = current.captures, zone = model.zone,
                    nowMillis = model.repository.now().toEpochMilli(), busy = busy, externalError = error,
                    onDismiss = onClose, onSave = { item, _, _ -> commit(InboxTarget.Ledger(item.copy(id = id))) },
                    initialMerchant = idea.title, initialNote = idea.content,
                )
            }
        }
    }
}
