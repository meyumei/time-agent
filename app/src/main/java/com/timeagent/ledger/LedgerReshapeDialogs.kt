package com.timeagent.ledger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.timeagent.ui.components.DateField
import com.timeagent.ui.components.TimeField
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

internal data class LedgerMergeDraft(
    val transactionIds: List<String> = emptyList(),
    val versions: Map<String, Long> = emptyMap(),
    val accountId: String = "",
    val currency: String = "CNY",
    val direction: String = LedgerDirection.EXPENSE,
    val amountMinor: Long = 0,
    val categoryId: String = "",
    val merchant: String = "",
    val note: String = "",
    val occurredAt: Long = 0,
    val requestId: String = UUID.randomUUID().toString(),
)

internal data class LedgerSplitPartDraft(
    val id: String = UUID.randomUUID().toString(),
    val amount: String = "",
    val categoryId: String = "",
    val merchant: String = "",
    val note: String = "",
)

internal data class LedgerSplitDraft(
    val transactionId: String = "",
    val expectedVersion: Long = 0,
    val amountMinor: Long = 0,
    val accountId: String = "",
    val direction: String = LedgerDirection.EXPENSE,
    val occurredAt: Long = 0,
    val requestId: String = UUID.randomUUID().toString(),
    val parts: List<LedgerSplitPartDraft> = emptyList(),
)

internal fun newSplitDraft(transaction: LedgerTransactionEntity): LedgerSplitDraft {
    val first = LedgerSplitPartDraft(
        id = UUID.randomUUID().toString(),
        amount = Money.plain(transaction.amountMinor),
        categoryId = transaction.categoryId,
        merchant = transaction.merchant,
        note = transaction.note,
    )
    val second = LedgerSplitPartDraft(
        id = UUID.randomUUID().toString(),
        categoryId = transaction.categoryId,
    )
    return LedgerSplitDraft(
        transactionId = transaction.id,
        expectedVersion = transaction.version,
        amountMinor = transaction.amountMinor,
        accountId = transaction.accountId,
        direction = transaction.direction,
        occurredAt = transaction.occurredAt,
        requestId = UUID.randomUUID().toString(),
        parts = listOf(first, second),
    )
}

/**
 * Reads the new many-to-many sources first, then falls back to the old single captureId. A single
 * source is always shown in full; multiple sources can be expanded instead of truncating numbers.
 */
@Composable
internal fun TransactionSourceDetails(
    transaction: LedgerTransactionEntity?,
    sources: List<LedgerTransactionSourceEntity>,
    captures: List<PaymentCaptureEntity>,
    initiallyExpanded: Boolean = false,
) {
    if (transaction == null) return
    val captureIds = remember(transaction.id, transaction.captureId, sources) {
        sources.asSequence()
            .filter { it.transactionId == transaction.id }
            .map { it.captureId }
            .distinct()
            .toList()
            .ifEmpty { listOfNotNull(transaction.captureId) }
    }
    if (captureIds.isEmpty()) {
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_ledger_reshape_dialogs_001), style = MaterialTheme.typography.labelSmall)
        return
    }
    var expanded by rememberSaveable(transaction.id) { mutableStateOf(initiallyExpanded) }
    val items = remember(captureIds, captures) {
        captureIds.map { id -> captures.firstOrNull { it.id == id } }
    }
    val firstLabel = items.firstOrNull()?.sourceLabel ?: "来源记录不可用"
    Text(
        if (items.size == 1) "来源：$firstLabel" else "来源：$firstLabel 等 ${items.size} 个",
        style = MaterialTheme.typography.labelSmall,
    )
    if (items.size > 1) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起来源" else "展开来源")
        }
    }
    if (expanded || items.size == 1) {
        items.forEachIndexed { index, capture ->
            val prefix = if (items.size > 1) "来源 ${index + 1}：" else ""
            if (capture == null) {
                Text("${prefix}原始来源记录不可用", style = MaterialTheme.typography.bodySmall)
            } else {
                val reference = capture.externalTransactionId?.takeIf { it.isNotBlank() } ?: "无交易号"
                Text(
                    "$prefix${capture.sourceLabel} · 交易号 $reference",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
internal fun LedgerMergeDialog(
    draft: LedgerMergeDraft,
    account: LedgerAccountEntity?,
    categories: List<LedgerCategoryEntity>,
    zone: ZoneId,
    busy: Boolean,
    externalError: String?,
    onDismiss: () -> Unit,
    onSubmit: (LedgerMergeInput, Map<String, Long>, String) -> Unit,
) {
    val selectable = categories.filter {
        (it.direction == draft.direction && it.archivedAt == null) || it.id == draft.categoryId
    }
    var categoryId by rememberSaveable(draft.requestId) { mutableStateOf(draft.categoryId) }
    var merchant by rememberSaveable(draft.requestId) { mutableStateOf(draft.merchant) }
    var note by rememberSaveable(draft.requestId) { mutableStateOf(draft.note) }
    val initialTime = remember(draft.occurredAt, zone) {
        Instant.ofEpochMilli(draft.occurredAt).atZone(zone).toLocalDateTime()
    }
    var date by rememberSaveable(draft.requestId) { mutableStateOf(initialTime.toLocalDate()) }
    var time by rememberSaveable(draft.requestId) { mutableStateOf(initialTime.toLocalTime()) }
    var picker by remember { mutableStateOf(false) }
    var validation by remember { mutableStateOf<String?>(null) }

    FormDialog(
        title = "合并账目",
        busy = busy,
        onDismiss = onDismiss,
        confirmLabel = "确认合并",
        confirmEnabled = selectable.isNotEmpty(),
        onConfirm = {
            validation = if (categoryId.isBlank()) "请选择合并后的分类" else null
            if (validation == null && categoryId.isNotBlank()) {
                val occurredAt = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
                onSubmit(
                    LedgerMergeInput(categoryId, merchant.trim(), note.trim(), occurredAt),
                    draft.versions,
                    draft.requestId,
                )
            }
        },
    ) {
        Text(
            "${directionLabel(draft.direction)} · ${account?.name.orEmpty()} · ${draft.currency} · ${draft.transactionIds.size} 笔",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text("合并后金额 ${Money.format(draft.amountMinor)}（只读，按所见账目合计）", style = MaterialTheme.typography.titleMedium)
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_ledger_reshape_dialogs_002), style = MaterialTheme.typography.bodySmall)
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_ledger_reshape_dialogs_003), style = MaterialTheme.typography.labelLarge)
        OutlinedButton(
            onClick = { picker = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = selectable.isNotEmpty() && !busy,
        ) {
            Text(selectable.firstOrNull { it.id == categoryId }?.name ?: "选择合并后的分类")
        }
        if (selectable.isEmpty()) {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_ledger_reshape_dialogs_004), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(
            merchant,
            { merchant = it },
            label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_ledger_reshape_dialogs_005)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        )
        OutlinedTextField(
            note,
            { note = it },
            label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.field_notes)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        )
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_ledger_reshape_dialogs_007), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateField(
                label = "日期",
                value = date,
                onValueChange = { date = it },
                modifier = Modifier.weight(1f),
            )
            TimeField(
                label = "时间",
                value = time,
                onValueChange = { time = it },
                modifier = Modifier.weight(1f),
            )
        }
        externalError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        validation?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_ledger_reshape_dialogs_008), style = MaterialTheme.typography.bodySmall)
    }

    if (picker) {
        ChoiceDialog(
            title = "选择合并后的分类",
            options = selectable.map { it.id to it.name },
            onDismiss = { picker = false },
            onPick = {
                categoryId = it
                picker = false
            },
        )
    }
}

@Composable
internal fun LedgerSplitDialog(
    draft: LedgerSplitDraft,
    account: LedgerAccountEntity?,
    categories: List<LedgerCategoryEntity>,
    zone: ZoneId,
    busy: Boolean,
    externalError: String?,
    onDismiss: () -> Unit,
    onSubmit: (List<LedgerSplitPart>, Long, String) -> Unit,
) {
    val gson = remember(draft.requestId) { Gson() }
    var working by rememberSaveable(
        draft.requestId,
        stateSaver = Saver<LedgerSplitDraft, String>(
            save = { gson.toJson(it) },
            restore = { saved -> gson.fromJson(saved as String, LedgerSplitDraft::class.java) },
        ),
    ) { mutableStateOf(draft) }
    var pickerPartId by remember { mutableStateOf<String?>(null) }

    fun updatePart(id: String, change: (LedgerSplitPartDraft) -> LedgerSplitPartDraft) {
        working = working.copy(parts = working.parts.map { if (it.id == id) change(it) else it })
    }

    val parsed = working.parts.map { Money.parseToMinor(it.amount) }
    var allocated = 0L
    var overflow = false
    parsed.forEach { amount ->
        if (amount != null && amount > 0) {
            try {
                allocated = Math.addExact(allocated, amount)
            } catch (_: ArithmeticException) {
                overflow = true
            }
        }
    }
    val allPositive = parsed.all { it != null && it > 0 }
    val allCategorized = working.parts.all { it.categoryId.isNotBlank() }
    val remaining = if (overflow) null else working.amountMinor - allocated
    val canSubmit = working.parts.size >= 2 && allPositive && !overflow && allCategorized &&
        allocated == working.amountMinor
    val hint = when {
        working.parts.size < 2 -> "至少保留两个分项"
        !allPositive -> "每个分项金额必须是正数，最多两位小数"
        overflow -> "分项合计超出可处理范围"
        !allCategorized -> "每个分项都需要选择分类"
        allocated != working.amountMinor -> "分项合计需等于原金额 ${Money.format(working.amountMinor)}"
        else -> null
    }

    FormDialog(
        title = "拆分账目",
        busy = busy,
        onDismiss = onDismiss,
        confirmLabel = "确认拆分",
        confirmEnabled = canSubmit && !busy,
        onConfirm = {
            if (canSubmit) {
                onSubmit(
                    working.parts.map { part ->
                        LedgerSplitPart(
                            id = part.id,
                            amountMinor = Money.parseToMinor(part.amount) ?: 0L,
                            categoryId = part.categoryId,
                            merchant = part.merchant.trim(),
                            note = part.note.trim(),
                        )
                    },
                    working.expectedVersion,
                    working.requestId,
                )
            }
        },
    ) {
        Text(
            "${directionLabel(working.direction)} · ${account?.name.orEmpty()} · ${account?.currency.orEmpty()}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "账户、方向、发生时间沿用它：${formatEditorTime(working.occurredAt, zone)}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "总额 ${Money.format(working.amountMinor)} · 已分配 ${Money.format(allocated)} · 剩余 ${remaining?.let { Money.format(it) } ?: "超出范围"}",
            style = MaterialTheme.typography.titleSmall,
        )
        working.parts.forEachIndexed { index, part ->
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("分项 ${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        if (working.parts.size > 1) {
                            TextButton(
                                onClick = { working = working.copy(parts = working.parts.filterNot { it.id == part.id }) },
                                enabled = !busy,
                            ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_delete)) }
                        }
                    }
                    MoneyAmountField(
                        value = part.amount,
                        onValueChange = { value -> updatePart(part.id) { it.copy(amount = value) } },
                        label = "金额（元）",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        imeAction = ImeAction.Next,
                    )
                    OutlinedButton(
                        onClick = { pickerPartId = part.id },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    ) {
                        Text(categories.firstOrNull { it.id == part.categoryId }?.name ?: "选择分类")
                    }
                    OutlinedTextField(
                        part.merchant,
                        { value -> updatePart(part.id) { it.copy(merchant = value) } },
                        label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_ledger_reshape_dialogs_005)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    )
                    OutlinedTextField(
                        part.note,
                        { value -> updatePart(part.id) { it.copy(note = value) } },
                        label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.field_notes)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    )
                }
            }
        }
        OutlinedButton(
            onClick = {
                working = working.copy(
                    parts = working.parts + LedgerSplitPartDraft(categoryId = working.parts.firstOrNull()?.categoryId.orEmpty()),
                )
            },
            enabled = !busy,
        ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_ledger_reshape_dialogs_009)) }
        externalError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        hint?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_ledger_reshape_dialogs_010), style = MaterialTheme.typography.bodySmall)
    }

    pickerPartId?.let { partId ->
        val part = working.parts.firstOrNull { it.id == partId }
        val options = categories.filter {
            (it.direction == working.direction && it.archivedAt == null) || it.id == draft.parts.firstOrNull()?.categoryId
        }
        ChoiceDialog(
            title = "选择分项分类",
            options = options.map { it.id to it.name },
            onDismiss = { pickerPartId = null },
            onPick = { categoryId ->
                updatePart(partId) { it.copy(categoryId = categoryId) }
                pickerPartId = null
            },
        )
    }
}
