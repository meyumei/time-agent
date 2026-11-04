package com.timeagent.ledger

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.timeagent.data.OperationEntity
import com.timeagent.data.StaleChange
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.data.TimeRepository
import com.timeagent.domain.command.LedgerMutation
import com.timeagent.domain.command.Origin
import com.timeagent.domain.command.WriteLedger
import com.timeagent.domain.command.throwIfRejected
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class LedgerSnapshot(
    val accounts: List<LedgerAccountEntity> = emptyList(),
    val categories: List<LedgerCategoryEntity> = emptyList(),
    val captures: List<PaymentCaptureEntity> = emptyList(),
    val transactions: List<LedgerTransactionEntity> = emptyList(),
    val sources: List<LedgerTransactionSourceEntity> = emptyList(),
    val usedAccountIds: List<String> = emptyList(),
) {
    val activeAccounts: List<LedgerAccountEntity> get() = accounts.filter { it.archivedAt == null }
    val activeCategories: List<LedgerCategoryEntity> get() = categories.filter { it.archivedAt == null }
    val pendingCaptures: List<PaymentCaptureEntity> get() = captures.filter { it.status == PaymentCaptureEntity.Status.PENDING }
}

data class LedgerTotals(
    val expenseMinor: Long,
    val incomeMinor: Long,
    val refundMinor: Long,
    val count: Int,
) {
    /** Refunds reduce spending instead of inflating income. */
    val netExpenseMinor: Long get() = expenseMinor - refundMinor
    val netMinor: Long get() = incomeMinor - netExpenseMinor
}

data class CaptureConfirmation(
    val accountId: String,
    val categoryId: String,
    val amountMinor: Long,
    val direction: String,
    val merchant: String = "",
    val note: String = "",
    val occurredAt: Long,
)

data class LedgerMergeInput(
    val categoryId: String,
    val merchant: String,
    val note: String,
    val occurredAt: Long,
)

data class LedgerSplitPart(
    val id: String,
    val amountMinor: Long,
    val categoryId: String,
    val merchant: String,
    val note: String,
)

data class BillImportResult(
    val totalRows: Int,
    val imported: Int,
    val duplicates: Int,
    val skipped: Int,
    val errors: List<String> = emptyList(),
) {
    fun summary(): String = buildString {
        append("读取 ").append(totalRows).append(" 行：新增 ").append(imported).append(" 条待确认")
        if (duplicates > 0) append("，重复 ").append(duplicates).append(" 条")
        if (skipped > 0) append("，跳过 ").append(skipped).append(" 条")
        if (errors.isNotEmpty()) append("。").append(errors.first())
    }
}

private data class DraftOutcome(val capture: PaymentCaptureEntity, val created: Boolean)

/**
 * Local-first ledger. Every confirmed write goes through [TimeRepository.transaction] so it shares
 * the app's single data gate; user changes are journaled for the existing undo/redo history.
 * Captures that merely arrive from notifications are intake data and are not journaled.
 */
class LedgerRepository(
    private val db: TimeAgentDatabase,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val core: TimeRepository = TimeRepository(db, clock),
) {
    private val dao = db.ledgerDao()
    private val gson = Gson()

    val zone: ZoneId get() = clock.zone

    fun now(): Instant = clock.instant()

    fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    suspend fun snapshot(): LedgerSnapshot = core.transaction { readSnapshot() }

    fun observe(): Flow<LedgerSnapshot> = db.invalidationTracker
        .createFlow("ledger_accounts", "ledger_categories", "payment_captures", "ledger_transactions", "ledger_transaction_sources")
        .map { core.transaction { readSnapshot() } }
        .flowOn(Dispatchers.IO)

    private suspend fun readSnapshot() = LedgerSnapshot(
        dao.accounts(), dao.categories(), dao.captures(), dao.transactions(), dao.sources(), dao.usedAccountIds(),
    )

    private suspend fun write(
        mutation: LedgerMutation,
        requestId: String,
        origin: Origin = Origin.UI,
    ): String? {
        val result = core.commands.executeForRepository(WriteLedger(requestId, mutation, origin))
        result.throwIfRejected()
        return result.payload
    }

    suspend fun saveAccount(item: LedgerAccountEntity, expectedVersion: Long? = null, requestId: String = "ledger-account:${item.id}:${expectedVersion ?: "new"}"): LedgerAccountEntity =
        gson.fromJson(write(LedgerMutation.SaveAccount(item, expectedVersion), requestId), LedgerAccountEntity::class.java)

    suspend fun archiveAccount(id: String, expectedVersion: Long? = null, requestId: String = "ledger-archive-account:$id:$expectedVersion") {
        write(LedgerMutation.ArchiveAccount(id, expectedVersion), requestId)
    }

    suspend fun saveCategory(item: LedgerCategoryEntity, expectedVersion: Long? = null, requestId: String = "ledger-category:${item.id}:${expectedVersion ?: "new"}"): LedgerCategoryEntity =
        gson.fromJson(write(LedgerMutation.SaveCategory(item, expectedVersion), requestId), LedgerCategoryEntity::class.java)

    suspend fun archiveCategory(id: String, expectedVersion: Long? = null, requestId: String = "ledger-archive-category:$id:$expectedVersion") {
        write(LedgerMutation.ArchiveCategory(id, expectedVersion), requestId)
    }

    suspend fun saveTransaction(item: LedgerTransactionEntity, expectedVersion: Long? = null, requestId: String = "ledger-transaction:${item.id}:${expectedVersion ?: "new"}"): LedgerTransactionEntity =
        gson.fromJson(write(LedgerMutation.SaveTransaction(item, expectedVersion), requestId), LedgerTransactionEntity::class.java)

    suspend fun deleteTransaction(id: String, expectedVersion: Long? = null, requestId: String = "ledger-delete-transaction:$id:$expectedVersion") {
        write(LedgerMutation.DeleteTransaction(id, expectedVersion), requestId)
    }

    suspend fun mergeTransactions(
        versions: Map<String, Long>,
        details: LedgerMergeInput,
        requestId: String,
    ): LedgerTransactionEntity = gson.fromJson(
        write(LedgerMutation.Merge(versions, details, "merge:$requestId"), requestId),
        LedgerTransactionEntity::class.java,
    )

    suspend fun splitTransaction(
        id: String,
        expectedVersion: Long,
        parts: List<LedgerSplitPart>,
        requestId: String,
    ) {
        write(LedgerMutation.Split(id, expectedVersion, parts), requestId)
    }

    suspend fun recordCapture(parsed: ParsedPayment, notificationKey: String, requestId: String = UUID.randomUUID().toString()): PaymentCaptureEntity? =
        gson.fromJson(write(LedgerMutation.Capture(parsed, notificationKey), requestId, Origin.NOTIFICATION), PaymentCaptureEntity::class.java)

    suspend fun ignoreCapture(id: String, expectedVersion: Long? = null, requestId: String = "ledger-ignore:$id:$expectedVersion") {
        write(LedgerMutation.Ignore(id, expectedVersion), requestId)
    }

    suspend fun confirmCapture(
        captureId: String,
        confirmation: CaptureConfirmation,
        expectedVersion: Long? = null,
        requestId: String? = null,
    ): LedgerTransactionEntity = gson.fromJson(
        write(LedgerMutation.Confirm(captureId, confirmation, expectedVersion), requestId ?: "capture:$captureId:$expectedVersion"),
        LedgerTransactionEntity::class.java,
    )

    suspend fun importBills(bytes: ByteArray, requestId: String = UUID.randomUUID().toString()): BillImportResult =
        gson.fromJson(write(LedgerMutation.Import(bytes), requestId), BillImportResult::class.java)

    internal suspend fun applyMutation(mutation: LedgerMutation): Any? = when (mutation) {
        is LedgerMutation.SaveAccount -> saveAccountRaw(mutation.item, mutation.expectedVersion)
        is LedgerMutation.ArchiveAccount -> { archiveAccountRaw(mutation.id, mutation.expectedVersion); null }
        is LedgerMutation.SaveCategory -> saveCategoryRaw(mutation.item, mutation.expectedVersion)
        is LedgerMutation.ArchiveCategory -> { archiveCategoryRaw(mutation.id, mutation.expectedVersion); null }
        is LedgerMutation.SaveTransaction -> saveTransactionInternal(mutation.item, mutation.expectedVersion)
        is LedgerMutation.DeleteTransaction -> { deleteTransactionRaw(mutation.id, mutation.expectedVersion); null }
        is LedgerMutation.Merge -> mergeTransactionsRaw(mutation)
        is LedgerMutation.Split -> { splitTransactionRaw(mutation); null }
        is LedgerMutation.Capture -> recordCaptureRaw(mutation.payment, mutation.notificationKey)
        is LedgerMutation.Ignore -> { ignoreCaptureRaw(mutation.id, mutation.expectedVersion); null }
        is LedgerMutation.Confirm -> confirmCaptureRaw(mutation.id, mutation.confirmation, mutation.expectedVersion)
        is LedgerMutation.Import -> importBillsRaw(mutation.bytes)
    }

    private suspend fun saveAccountRaw(item: LedgerAccountEntity, expectedVersion: Long?): LedgerAccountEntity = core.transaction {
        require(item.id.isNotBlank()) { "账户 ID 不能为空" }
        require(item.name.trim().length in 1..60) { "账户名称需为 1–60 个字符" }
        require(item.type in LedgerAccountType.ALL) { "账户类型无效" }
        val currency = item.currency.trim().uppercase()
        require(currency.matches(Regex("[A-Z]{3}"))) { "币种需为 3 位字母代码，例如 CNY" }
        val before = dao.account(item.id)
        checkVersion(before?.version, expectedVersion)
        require(before == null || currency == before.currency || dao.transactionCountForAccount(item.id) == 0) {
            "账户已有历史账目，不能更换币种；请为另一币种新增账户"
        }
        require(before?.archivedAt == null) { "账户已归档，请先撤销归档" }
        val stamp = now().toEpochMilli()
        val saved = item.copy(
            name = item.name.trim(),
            type = item.type,
            currency = currency,
            createdAt = before?.createdAt ?: stamp,
            updatedAt = stamp,
            version = nextVersion("LEDGER_ACCOUNT", item.id, before?.version),
            archivedAt = before?.archivedAt,
        )
        dao.putAccount(saved)
        core.journal("LEDGER_ACCOUNT", saved.id, before, saved, if (before == null) "新增账户" else "修改账户")
        saved
    }

    private suspend fun archiveAccountRaw(id: String, expectedVersion: Long?) = core.transaction {
        val before = requireNotNull(dao.account(id)) { "账户不存在" }
        checkVersion(before.version, expectedVersion)
        require(before.archivedAt == null) { "账户已经归档" }
        val stamp = now().toEpochMilli()
        val after = before.copy(archivedAt = stamp, updatedAt = stamp, version = nextVersion("LEDGER_ACCOUNT", id, before.version))
        dao.putAccount(after)
        core.journal("LEDGER_ACCOUNT", id, before, after, "归档账户")
    }

    private suspend fun saveCategoryRaw(item: LedgerCategoryEntity, expectedVersion: Long?): LedgerCategoryEntity = core.transaction {
        require(item.id.isNotBlank()) { "分类 ID 不能为空" }
        require(item.name.trim().length in 1..40) { "分类名称需为 1–40 个字符" }
        require(item.direction in LedgerDirection.ALL) { "分类方向无效" }
        val before = dao.category(item.id)
        checkVersion(before?.version, expectedVersion)
        require(before?.archivedAt == null) { "分类已归档，请先撤销归档" }
        val stamp = now().toEpochMilli()
        val saved = item.copy(
            name = item.name.trim(),
            direction = item.direction,
            icon = item.icon.trim().take(8),
            createdAt = before?.createdAt ?: stamp,
            updatedAt = stamp,
            version = nextVersion("LEDGER_CATEGORY", item.id, before?.version),
            archivedAt = before?.archivedAt,
        )
        dao.putCategory(saved)
        core.journal("LEDGER_CATEGORY", saved.id, before, saved, if (before == null) "新增分类" else "修改分类")
        saved
    }

    private suspend fun archiveCategoryRaw(id: String, expectedVersion: Long?) = core.transaction {
        val before = requireNotNull(dao.category(id)) { "分类不存在" }
        checkVersion(before.version, expectedVersion)
        require(before.archivedAt == null) { "分类已经归档" }
        val stamp = now().toEpochMilli()
        val after = before.copy(archivedAt = stamp, updatedAt = stamp, version = nextVersion("LEDGER_CATEGORY", id, before.version))
        dao.putCategory(after)
        core.journal("LEDGER_CATEGORY", id, before, after, "归档分类")
    }

    private suspend fun saveTransactionInternal(
        item: LedgerTransactionEntity,
        expectedVersion: Long? = null,
        inheritedAccountId: String? = null,
        inheritedCategoryIds: Set<String> = emptySet(),
    ): LedgerTransactionEntity {
        require(item.id.isNotBlank()) { "账目 ID 不能为空" }
        require(item.amountMinor > 0) { "金额必须大于 0" }
        require(item.direction in LedgerDirection.ALL) { "收支方向无效" }
        require(item.merchant.length <= 200) { "商户最多 200 字" }
        require(item.note.length <= 2000) { "备注最多 2000 字" }
        require(item.occurredAt > 0) { "请选择发生时间" }
        val before = dao.transaction(item.id)
        checkVersion(before?.version, expectedVersion)
        require(before?.deletedAt == null) { "账目已删除，请先撤销删除" }
        val account = requireNotNull(dao.account(item.accountId)) { "账户不存在" }
        require(account.archivedAt == null || before?.accountId == item.accountId || inheritedAccountId == item.accountId) {
            "账户已归档，请选择其他账户"
        }
        val category = requireNotNull(dao.category(item.categoryId)) { "分类不存在" }
        require(category.archivedAt == null || before?.categoryId == item.categoryId || item.categoryId in inheritedCategoryIds) {
            "分类已归档，请选择其他分类"
        }
        require(before == null || before.captureId == item.captureId) { "不能修改账目的支付来源" }
        item.captureId?.let { captureId ->
            val capture = requireNotNull(dao.capture(captureId)) { "来源草稿不存在" }
            require(capture.status != PaymentCaptureEntity.Status.CONFIRMED || capture.transactionId == item.id) {
                "这笔支付已经入账"
            }
        }
        val stamp = now().toEpochMilli()
        val saved = item.copy(
            merchant = item.merchant.trim(),
            note = item.note.trim(),
            createdAt = before?.createdAt ?: stamp,
            updatedAt = stamp,
            confirmedAt = item.confirmedAt ?: before?.confirmedAt ?: stamp,
            version = nextVersion("LEDGER_TRANSACTION", item.id, before?.version),
            deletedAt = before?.deletedAt,
        )
        dao.putTransaction(saved)
        core.journal("LEDGER_TRANSACTION", saved.id, before, saved, if (before == null) "新增账目" else "修改账目")
        return saved
    }

    private suspend fun deleteTransactionRaw(id: String, expectedVersion: Long?) = core.transaction {
        val before = requireNotNull(dao.transaction(id)) { "账目不存在" }
        checkVersion(before.version, expectedVersion)
        require(before.deletedAt == null) { "账目已经删除" }
        val stamp = now().toEpochMilli()
        val after = before.copy(deletedAt = stamp, updatedAt = stamp, version = nextVersion("LEDGER_TRANSACTION", id, before.version))
        dao.putTransaction(after)
        core.journal("LEDGER_TRANSACTION", id, before, after, "删除账目")
    }

    private suspend fun selectedTransaction(id: String, expectedVersion: Long): LedgerTransactionEntity {
        val row = requireNotNull(dao.transaction(id)) { "账目不存在，请重新选择" }
        checkVersion(row.version, expectedVersion)
        require(row.deletedAt == null) { "账目已删除或已合并/拆分，请重新选择" }
        return row
    }

    private fun sumAmounts(amounts: List<Long>): Long {
        require(amounts.all { it > 0 }) { "每项金额必须大于 0" }
        return try {
            amounts.fold(0L, Math::addExact)
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("金额合计超出支持范围，尚未保存")
        }
    }

    private suspend fun captureIds(row: LedgerTransactionEntity): Set<String> =
        (dao.sourcesFor(row.id).map { it.captureId } + listOfNotNull(row.captureId)).toSet()

    private suspend fun linkSources(transactionId: String, captures: Set<String>) {
        captures.forEach { captureId ->
            val link = LedgerTransactionSourceEntity(UUID.randomUUID().toString(), transactionId, captureId)
            dao.putSource(link)
            nextVersion("LEDGER_SOURCE", link.id)
            core.journal("LEDGER_SOURCE", link.id, null, link, "保留支付来源")
        }
    }

    private suspend fun mergeTransactionsRaw(mutation: LedgerMutation.Merge): LedgerTransactionEntity {
        require(mutation.versions.size >= 2) { "请选择至少两笔账目" }
        val rows = mutation.versions.map { (id, version) -> selectedTransaction(id, version) }
        val first = rows.first()
        require(rows.all { it.accountId == first.accountId && it.direction == first.direction }) {
            "仅可合并同账户、同币种、同收支方向的账目"
        }
        val amount = sumAmounts(rows.map { it.amountMinor })
        val captures = rows.flatMap { captureIds(it) }.toSet()
        val details = mutation.details
        val inheritedCategories = rows.map { it.categoryId }.toSet()
        require(details.categoryId in inheritedCategories || dao.category(details.categoryId)?.direction == first.direction) {
            "分类与收支方向不一致"
        }
        val merged = saveTransactionInternal(
            LedgerTransactionEntity(
                id = mutation.resultId, accountId = first.accountId, categoryId = details.categoryId,
                amountMinor = amount, direction = first.direction, merchant = details.merchant,
                note = details.note, occurredAt = details.occurredAt,
            ),
            inheritedAccountId = first.accountId,
            inheritedCategoryIds = inheritedCategories,
        )
        linkSources(merged.id, captures)
        rows.forEach { deleteTransactionRaw(it.id, it.version) }
        return merged
    }

    private suspend fun splitTransactionRaw(mutation: LedgerMutation.Split) {
        val original = selectedTransaction(mutation.id, mutation.expectedVersion)
        val parts = mutation.parts
        require(parts.size >= 2) { "请至少拆为两项" }
        require(parts.map { it.id }.distinct().size == parts.size && parts.none { it.id == original.id }) {
            "拆分项标识重复，请重新打开拆分"
        }
        require(sumAmounts(parts.map { it.amountMinor }) == original.amountMinor) { "拆分合计必须等于原账目金额" }
        val captures = captureIds(original)
        parts.forEach { part ->
            require(part.categoryId == original.categoryId || dao.category(part.categoryId)?.direction == original.direction) {
                "分类与收支方向不一致"
            }
            val saved = saveTransactionInternal(
                LedgerTransactionEntity(
                    id = part.id, accountId = original.accountId, categoryId = part.categoryId,
                    amountMinor = part.amountMinor, direction = original.direction,
                    merchant = part.merchant, note = part.note, occurredAt = original.occurredAt,
                ),
                inheritedAccountId = original.accountId,
                inheritedCategoryIds = setOf(original.categoryId),
            )
            linkSources(saved.id, captures)
        }
        deleteTransactionRaw(original.id, original.version)
    }

    /**
     * Stores a notification as a pending draft only. Returns null when the same confirmed/ignored
     * payment is seen again, so it can never be silently re-entered.
     */
    private suspend fun recordCaptureRaw(parsed: ParsedPayment, notificationKey: String): PaymentCaptureEntity? = core.transaction {
        require(parsed.amountMinor > 0) { "支付金额无效" }
        require(parsed.direction in LedgerDirection.ALL) { "支付方向无效" }
        upsertDraft(parsed, notificationKey, now().toEpochMilli(), updatePending = true,
            knownCaptures = dao.captureCandidates(parsed.sourcePackage, parsed.occurredAt - 300_000L, parsed.occurredAt + 300_000L, parsed.referenceId))
            ?.capture
            ?.takeIf { it.status == PaymentCaptureEntity.Status.PENDING }
    }

    private suspend fun ignoreCaptureRaw(id: String, expectedVersion: Long?) = core.transaction {
        val before = requireNotNull(dao.capture(id)) { "待确认记录不存在" }
        checkVersion(before.version, expectedVersion)
        require(before.status == PaymentCaptureEntity.Status.PENDING) { "这条草稿已经处理" }
        val stamp = now().toEpochMilli()
        val after = before.copy(status = PaymentCaptureEntity.Status.IGNORED, updatedAt = stamp, version = nextVersion("PAYMENT_CAPTURE", id, before.version))
        dao.putCapture(after)
        core.journal("PAYMENT_CAPTURE", id, before, after, "忽略支付草稿")
    }

    /**
     * The account/category are chosen by the user; this method never guesses them. Confirming and
     * booking are one assistant transaction, so undo returns the capture to the pending list.
     */
    private suspend fun confirmCaptureRaw(
        captureId: String,
        confirmation: CaptureConfirmation,
        expectedVersion: Long? = null,
    ): LedgerTransactionEntity = core.transaction {
        val capture = requireNotNull(dao.capture(captureId)) { "待确认记录不存在" }
        if (capture.status == PaymentCaptureEntity.Status.CONFIRMED) {
            val booked = capture.transactionId?.let { dao.transaction(it) }
            if (booked != null && booked.deletedAt == null) return@transaction booked
            throw IllegalStateException("这笔支付已经入账或已合并/拆分，请在账本中查看；如已删除可撤销删除")
        }
        require(capture.status == PaymentCaptureEntity.Status.PENDING) { "这条草稿已忽略，请先撤销忽略" }
        checkVersion(capture.version, expectedVersion)
        val transactionId = "capture:${capture.id}"
        val existing = dao.transaction(transactionId)
        if (existing != null && existing.deletedAt == null) return@transaction existing
        val saved = saveTransactionInternal(
            LedgerTransactionEntity(
                id = transactionId,
                accountId = confirmation.accountId,
                categoryId = confirmation.categoryId,
                captureId = capture.id,
                amountMinor = confirmation.amountMinor,
                direction = confirmation.direction,
                merchant = confirmation.merchant,
                note = confirmation.note,
                occurredAt = confirmation.occurredAt,
                confirmedAt = now().toEpochMilli(),
            ),
        )
        val stamp = now().toEpochMilli()
        val after = capture.copy(
            status = PaymentCaptureEntity.Status.CONFIRMED,
            transactionId = transactionId,
            updatedAt = stamp,
            version = nextVersion("PAYMENT_CAPTURE", capture.id, capture.version),
        )
        dao.putCapture(after)
        core.journal("PAYMENT_CAPTURE", capture.id, capture, after, "确认支付草稿")
        saved
    }

    /** WeChat/Alipay bill export -> pending drafts. Rows with an existing reference are reconciled, not duplicated. */
    private suspend fun importBillsRaw(bytes: ByteArray): BillImportResult = core.transaction {
        val parsed = PaymentParser.parseBillCsv(bytes, zone)
        val captures = dao.captures().toMutableList()
        val similarRows = parsed.rows.groupBy { Triple(it.sourcePackage, it.amountMinor, it.direction) }
        var imported = 0
        var duplicates = 0
        parsed.rows.forEach { row ->
            val uniqueTimeMatch = similarRows.getValue(Triple(row.sourcePackage, row.amountMinor, row.direction))
                .count { kotlin.math.abs(it.occurredAt - row.occurredAt) <= 120_000L } == 1
            val outcome = upsertDraft(row, notificationKey = "", stamp = now().toEpochMilli(), updatePending = false,
                knownCaptures = captures, allowMerchantless = uniqueTimeMatch)
            when {
                outcome == null -> duplicates++
                outcome.created -> { imported++; captures += outcome.capture }
                else -> {
                    duplicates++
                    val index = captures.indexOfFirst { it.id == outcome.capture.id }
                    if (index >= 0) captures[index] = outcome.capture
                }
            }
        }
        BillImportResult(parsed.totalRows, imported, duplicates, parsed.skippedRows, parsed.errors)
    }

    private suspend fun upsertDraft(
        parsed: ParsedPayment,
        notificationKey: String,
        stamp: Long,
        updatePending: Boolean,
        knownCaptures: List<PaymentCaptureEntity>? = null,
        allowMerchantless: Boolean = false,
    ): DraftOutcome? {
        val existing = findDuplicate(parsed, knownCaptures ?: dao.captures(), notificationKey, allowMerchantless)
        if (existing != null) {
            if (existing.status != PaymentCaptureEntity.Status.PENDING) return null
            if (!updatePending) {
                val reference = existing.externalTransactionId ?: parsed.referenceId
                if (reference != existing.externalTransactionId) {
                    val updated = existing.copy(externalTransactionId = reference, updatedAt = stamp,
                        version = nextVersion("PAYMENT_CAPTURE", existing.id, existing.version))
                    dao.putCapture(updated)
                    core.journal("PAYMENT_CAPTURE", existing.id, existing, updated, "核对导入账单交易号")
                    return DraftOutcome(updated, false)
                }
                return DraftOutcome(existing, false)
            }
            val reference = existing.externalTransactionId ?: parsed.referenceId
            val merchant = parsed.merchant.ifBlank { existing.merchant }
            val changed = reference != existing.externalTransactionId ||
                parsed.amountMinor != existing.amountMinor ||
                parsed.direction != existing.direction ||
                merchant != existing.merchant
            if (!changed) return DraftOutcome(existing, false)
            val updated = existing.copy(
                externalTransactionId = reference,
                amountMinor = parsed.amountMinor,
                direction = parsed.direction,
                merchant = merchant,
                parsedJson = parsedJson(parsed),
                updatedAt = stamp,
                version = nextVersion("PAYMENT_CAPTURE", existing.id, existing.version),
            )
            dao.putCapture(updated)
            return DraftOutcome(updated, false)
        }
        val id = UUID.randomUUID().toString()
        val capture = PaymentCaptureEntity(
            id = id,
            sourcePackage = parsed.sourcePackage,
            sourceLabel = parsed.sourceLabel,
            notificationKey = notificationKey,
            externalTransactionId = parsed.referenceId,
            occurredAt = parsed.occurredAt,
            amountMinor = parsed.amountMinor,
            direction = parsed.direction,
            merchant = parsed.merchant,
            rawTextHash = parsed.rawTextHash,
            parsedJson = parsedJson(parsed),
            parserVersion = PaymentParser.VERSION,
            status = PaymentCaptureEntity.Status.PENDING,
            createdAt = stamp,
            updatedAt = stamp,
            version = nextVersion("PAYMENT_CAPTURE", id),
        )
        dao.putCapture(capture)
        if (!updatePending) core.journal("PAYMENT_CAPTURE", capture.id, null, capture, "导入支付账单")
        return DraftOutcome(capture, true)
    }

    private fun findDuplicate(parsed: ParsedPayment, all: List<PaymentCaptureEntity>, notificationKey: String, allowMerchantless: Boolean): PaymentCaptureEntity? {
        if (parsed.referenceId != null) {
            all.firstOrNull { it.sourcePackage == parsed.sourcePackage && it.externalTransactionId == parsed.referenceId }
                ?.let { return it }
        }
        val window = all.filter {
            it.sourcePackage == parsed.sourcePackage &&
                kotlin.math.abs(it.occurredAt - parsed.occurredAt) <= 5 * 60_000L &&
                refsCompatible(it.externalTransactionId, parsed.referenceId) &&
                (notificationKey.isBlank() || it.notificationKey.isBlank() || it.notificationKey == notificationKey)
        }
        window.firstOrNull { it.rawTextHash == parsed.rawTextHash }?.let { return it }
        val merchant = normalizeMerchant(parsed.merchant)
        if (merchant.isNotEmpty()) {
            window.firstOrNull {
                it.amountMinor == parsed.amountMinor &&
                    it.direction == parsed.direction &&
                    kotlin.math.abs(it.occurredAt - parsed.occurredAt) <= 2 * 60_000L &&
                    normalizeMerchant(it.merchant) == merchant
            }?.let { return it }
        }
        if (allowMerchantless && parsed.referenceId != null) {
            return window.filter {
                it.externalTransactionId == null && it.merchant.isBlank() &&
                    it.amountMinor == parsed.amountMinor && it.direction == parsed.direction &&
                    kotlin.math.abs(it.occurredAt - parsed.occurredAt) <= 120_000L
            }.singleOrNull()
        }
        return null
    }

    private fun refsCompatible(existing: String?, incoming: String?): Boolean =
        existing == null || incoming == null || existing == incoming

    private fun normalizeMerchant(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    private fun parsedJson(parsed: ParsedPayment): String = gson.toJson(
        mapOf(
            "sourcePackage" to parsed.sourcePackage,
            "sourceLabel" to parsed.sourceLabel,
            "amountMinor" to parsed.amountMinor,
            "direction" to parsed.direction,
            "merchant" to parsed.merchant,
            "referenceId" to parsed.referenceId,
            "occurredAt" to parsed.occurredAt,
            "parserVersion" to PaymentParser.VERSION,
        ),
    )

    fun totals(rows: List<LedgerTransactionEntity>): LedgerTotals {
        var expense = 0L
        var income = 0L
        var refund = 0L
        var count = 0
        rows.forEach { row ->
            if (row.deletedAt != null) return@forEach
            count++
            when (row.direction) {
                LedgerDirection.EXPENSE -> expense += row.amountMinor
                LedgerDirection.INCOME -> income += row.amountMinor
                LedgerDirection.REFUND -> refund += row.amountMinor
            }
        }
        return LedgerTotals(expense, income, refund, count)
    }

    fun exportCsv(
        rows: List<LedgerTransactionEntity>,
        accounts: List<LedgerAccountEntity>,
        categories: List<LedgerCategoryEntity>,
        captures: List<PaymentCaptureEntity>,
        sources: List<LedgerTransactionSourceEntity> = emptyList(),
    ): String {
        val accountNames = accounts.associate { it.id to it.name }
        val categoryNames = categories.associate { it.id to it.name }
        val capturesById = captures.associateBy { it.id }
        val sourcesByTransaction = sources.groupBy { it.transactionId }
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val builder = StringBuilder("\uFEFF")
        builder.append("发生时间,方向,金额(元),账户,分类,商户,备注,来源,交易号\n")
        rows.filter { it.deletedAt == null }.sortedBy { it.occurredAt }.forEach { row ->
            val origins = (sourcesByTransaction[row.id].orEmpty().map { it.captureId } + listOfNotNull(row.captureId))
                .distinct().mapNotNull(capturesById::get)
            val values = listOf(
                Instant.ofEpochMilli(row.occurredAt).atZone(zone).format(formatter),
                directionLabel(row.direction),
                Money.plain(row.amountMinor),
                accountNames[row.accountId].orEmpty(),
                categoryNames[row.categoryId].orEmpty(),
                row.merchant,
                row.note,
                origins.map { it.sourceLabel }.distinct().joinToString("；").ifEmpty { "手动" },
                origins.mapNotNull { it.externalTransactionId }.distinct().joinToString("；"),
            )
            builder.append(values.joinToString(",") { csvCell(it) }).append('\n')
        }
        return builder.toString()
    }

    private fun csvCell(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value

    suspend fun undoLastAction() = core.undoLastAction()

    suspend fun undoAction(token: String) {
        core.commands.executeForRepository(
            com.timeagent.domain.command.UndoCommand(UUID.randomUUID().toString(), token),
        ).throwIfRejected()
    }

    suspend fun redoLastAction() = core.redoLastAction()

    /**
     * Called by TimeRepository.applyHistory after it has already opened the shared transaction.
     * Restores/removes rows without journaling again.
     */
    suspend fun applyHistory(action: OperationEntity, forward: Boolean) {
        val target = if (forward) action.afterSnapshot else action.beforeSnapshot
        val expected = if (forward) action.beforeSnapshot else action.afterSnapshot
        when (action.entityType) {
            "LEDGER_SOURCE" -> {
                val current = dao.source(action.entityId)
                matches(current, expected)
                nextVersion("LEDGER_SOURCE", action.entityId)
                if (target == null) dao.removeSource(action.entityId)
                else dao.putSource(gson.fromJson(target, LedgerTransactionSourceEntity::class.java))
            }

            "LEDGER_ACCOUNT" -> {
                val current = dao.account(action.entityId)
                matches(current, expected)
                val version = nextVersion("LEDGER_ACCOUNT", action.entityId, current?.version)
                if (target == null) {
                    require(dao.transactionCountForAccount(action.entityId) == 0) { "账户已有账目，不能移除" }
                    dao.removeAccount(action.entityId)
                } else {
                    val restored = gson.fromJson(target, LedgerAccountEntity::class.java)
                    dao.putAccount(restored.copy(version = version, updatedAt = now().toEpochMilli()))
                }
            }

            "LEDGER_CATEGORY" -> {
                val current = dao.category(action.entityId)
                matches(current, expected)
                val version = nextVersion("LEDGER_CATEGORY", action.entityId, current?.version)
                if (target == null) {
                    require(dao.transactionCountForCategory(action.entityId) == 0) { "分类已有账目，不能移除" }
                    dao.removeCategory(action.entityId)
                } else {
                    val restored = gson.fromJson(target, LedgerCategoryEntity::class.java)
                    dao.putCategory(restored.copy(version = version, updatedAt = now().toEpochMilli()))
                }
            }

            "PAYMENT_CAPTURE" -> {
                val current = dao.capture(action.entityId)
                matches(current, expected)
                val version = nextVersion("PAYMENT_CAPTURE", action.entityId, current?.version)
                if (target == null) {
                    dao.removeCapture(action.entityId)
                } else {
                    val restored = gson.fromJson(target, PaymentCaptureEntity::class.java)
                    dao.putCapture(restored.copy(version = version, updatedAt = now().toEpochMilli()))
                }
            }

            "LEDGER_TRANSACTION" -> {
                val current = dao.transaction(action.entityId)
                matches(current, expected)
                val version = nextVersion("LEDGER_TRANSACTION", action.entityId, current?.version)
                if (target == null) {
                    dao.removeTransaction(action.entityId)
                } else {
                    val restored = gson.fromJson(target, LedgerTransactionEntity::class.java)
                    dao.putTransaction(restored.copy(version = version, updatedAt = now().toEpochMilli()))
                }
            }

            else -> throw IllegalArgumentException("未知的账本操作：${action.entityType}")
        }
    }

    private fun matches(current: Any?, expected: String?) {
        fun payload(raw: String?) = raw?.let {
            JsonParser.parseString(it).asJsonObject.apply {
                remove("version")
                remove("updatedAt")
            }
        }
        if (payload(current?.let { gson.toJson(it) }) != payload(expected)) throw StaleChange()
    }

    private fun checkVersion(current: Long?, expected: Long?) {
        if (current != expected) throw StaleChange()
    }

    private suspend fun nextVersion(type: String, id: String, current: Long? = null): Long {
        val key = "$type:$id"
        val next = maxOf(dao.revision(key) ?: -1, current ?: -1) + 1
        dao.putRevision(key, next)
        return next
    }

}
