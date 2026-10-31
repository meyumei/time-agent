package com.timeagent.business

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.timeagent.backup.BackupTables
import com.timeagent.backup.backupSql
import com.timeagent.data.HealthSyncItemEntity
import com.timeagent.data.NoteEntity
import com.timeagent.data.TaskEntity
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.data.TimeRecordEntity
import com.timeagent.data.TimeRepository
import com.timeagent.ledger.CaptureConfirmation
import com.timeagent.ledger.LedgerAccountEntity
import com.timeagent.ledger.LedgerCategoryEntity
import com.timeagent.ledger.LedgerDirection
import com.timeagent.ledger.LedgerRepository
import com.timeagent.ledger.LedgerTransactionEntity
import com.timeagent.ledger.Money
import com.timeagent.ledger.PaymentCaptureEntity
import com.timeagent.ledger.PaymentParser
import com.timeagent.organization.OrganizationRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.Charset
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Real-device business acceptance tests. Every case runs against a genuine in-memory Room/SQLite
 * database opened with [TimeAgentDatabase.openInMemory]; nothing touches time-agent.db or files.
 */
class BusinessDeviceTest {
    private lateinit var db: TimeAgentDatabase
    private lateinit var time: TimeRepository
    private lateinit var org: OrganizationRepository
    private lateinit var ledger: LedgerRepository
    private val now = Instant.parse("2026-10-30T02:00:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("Asia/Shanghai"))
    private val hour = 3_600_000L
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        db = TimeAgentDatabase.openInMemory(context)
        time = TimeRepository(db, clock)
        org = OrganizationRepository(db, clock)
        ledger = LedgerRepository(db, clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun categoryAndTagLifecycleKeepsRelationsAcrossUndoRedo() = runBlocking {
        val reading = org.createCategory("阅读", 0xFF5756B7)
        val study = org.createCategory("学习", 0xFF3978B5)
        assertEquals("阅读", org.snapshot().categories.first { it.id == reading.id }.name)

        time.saveTask(TaskEntity("task-1", "章节笔记", categoryId = reading.id))
        time.saveRecord(
            TimeRecordEntity(
                "rec-1", "task-1",
                now.minusSeconds(7200).toEpochMilli(), now.minusSeconds(3600).toEpochMilli(),
                categoryId = reading.id, title = "章节笔记",
            ),
        )

        assertEquals(1, org.mergeCategories(listOf(reading.id), study.id))
        assertEquals(study.id, db.dao().task("task-1")!!.categoryId)
        assertEquals(study.id, db.dao().record("rec-1")!!.categoryId)
        assertNull(db.organizationDao().category(reading.id))

        time.undoLastAction()
        assertEquals(reading.id, db.dao().task("task-1")!!.categoryId)
        assertEquals(reading.id, db.dao().record("rec-1")!!.categoryId)
        assertEquals("阅读", db.organizationDao().category(reading.id)!!.name)

        time.redoLastAction()
        assertEquals(study.id, db.dao().task("task-1")!!.categoryId)
        assertNull(db.organizationDao().category(reading.id))

        val temporary = org.createCategory("临时", 0xFFB5642D)
        assertEquals(2, org.setCategoryFor(listOf("task-1"), listOf("rec-1"), temporary.id))
        assertEquals(1, org.deleteCategories(listOf(temporary.id), study.id))
        assertEquals(study.id, db.dao().task("task-1")!!.categoryId)
        assertEquals(study.id, db.dao().record("rec-1")!!.categoryId)
        assertNull(db.organizationDao().category(temporary.id))

        time.undoLastAction()
        assertEquals(temporary.id, db.dao().task("task-1")!!.categoryId)
        assertEquals(temporary.id, db.dao().record("rec-1")!!.categoryId)
        assertNotNull(db.organizationDao().category(temporary.id))

        time.redoLastAction()
        assertNull(db.organizationDao().category(temporary.id))
        assertEquals(study.id, db.dao().record("rec-1")!!.categoryId)

        val deep = org.createTag("深度", 0xFF26806A)
        val focus = org.createTag("专注", 0xFF5756B7)
        val extra = org.createTag("额外", 0xFFB5642D)
        suspend fun taskTags() = db.organizationDao().taskTags().filter { it.taskId == "task-1" }.map { it.tagId }.toSet()
        suspend fun recordTags() = db.organizationDao().recordTags().filter { it.recordId == "rec-1" }.map { it.tagId }.toSet()

        assertEquals(2, org.assignTags(listOf("task-1"), listOf("rec-1"), setOf(deep.id, extra.id), emptySet()))
        assertEquals(setOf(deep.id, extra.id), taskTags())
        assertEquals(setOf(deep.id, extra.id), recordTags())

        time.undoLastAction()
        assertTrue(taskTags().isEmpty())
        assertTrue(recordTags().isEmpty())
        time.redoLastAction()
        assertEquals(setOf(deep.id, extra.id), taskTags())

        assertEquals(1, org.mergeTags(listOf(extra.id), focus.id))
        assertEquals(setOf(deep.id, focus.id), taskTags())
        assertEquals(setOf(deep.id, focus.id), recordTags())
        assertNull(db.organizationDao().tag(extra.id))

        time.undoLastAction()
        assertEquals(setOf(deep.id, extra.id), taskTags())
        assertEquals(setOf(deep.id, extra.id), recordTags())
        assertEquals("额外", db.organizationDao().tag(extra.id)!!.name)

        time.redoLastAction()
        assertEquals(setOf(deep.id, focus.id), taskTags())
        assertNull(db.organizationDao().tag(extra.id))

        assertEquals(1, org.deleteTags(listOf(deep.id)))
        assertEquals(setOf(focus.id), taskTags())
        assertEquals(setOf(focus.id), recordTags())

        time.undoLastAction()
        assertEquals(setOf(deep.id, focus.id), recordTags())
        assertNotNull(db.organizationDao().tag(deep.id))

        time.redoLastAction()
        assertNull(db.organizationDao().tag(deep.id))
        assertEquals(setOf(focus.id), recordTags())
    }

    @Test
    fun overlappingRecordMergeThenSplitPreservesNotesTagsAndHealth() = runBlocking {
        time.saveTask(TaskEntity("task-1", "论文阅读"))
        val morning = org.createTag("早晨", 0xFF26806A)
        val deep = org.createTag("精读", 0xFF5756B7)
        val base = now.minusSeconds(10800).toEpochMilli()

        time.saveRecord(
            TimeRecordEntity(
                "r1", "task-1", base, base + hour,
                title = "论文阅读", notes = "第一节",
            ),
        )
        time.saveRecord(
            TimeRecordEntity(
                "r2", "task-1", base + hour / 2, base + hour * 5 / 2,
                title = "论文阅读", notes = "第二节",
            ),
        )
        org.assignTags(emptyList(), listOf("r1"), setOf(morning.id), emptySet())
        org.assignTags(emptyList(), listOf("r2"), setOf(deep.id), emptySet())
        db.dao().putNote(NoteEntity("note-1", "RECORD", "r2", title = "阅读笔记", body = "第二节细节"))
        db.dao().putHealthItem(
            HealthSyncItemEntity(
                id = "sync-keep", kind = "EXERCISE", title = "论文阅读",
                startAt = base, endAt = base + hour,
                originPackage = "com.example.health", recordId = "r1",
            ),
        )
        db.dao().putHealthItem(
            HealthSyncItemEntity(
                id = "sync-drop", kind = "EXERCISE", title = "论文阅读",
                startAt = base + hour / 2, endAt = base + hour * 5 / 2,
                originPackage = "com.example.health", recordId = "r2",
            ),
        )

        suspend fun recordTags(id: String) =
            db.organizationDao().recordTags().filter { it.recordId == id }.map { it.tagId }.toSet()

        val preview = org.previewMerge(listOf("r1", "r2"))
        assertEquals("r1", preview.firstId)
        assertEquals(10_800_000L, preview.originalTotalMillis)
        assertEquals(9_000_000L, preview.endAt - preview.startAt)
        assertEquals(setOf(morning.id, deep.id), preview.tagIds)
        assertTrue(preview.movesNote)

        assertEquals("r1", org.mergeRecords(listOf("r1", "r2"), preview.versions))
        val merged = db.dao().record("r1")!!
        assertEquals(base + 9_000_000L, merged.endAt)
        assertTrue(merged.notes.contains("第一节") && merged.notes.contains("第二节"))
        assertEquals(setOf(morning.id, deep.id), recordTags("r1"))
        assertEquals("r1", db.dao().note("note-1")!!.targetId)
        assertNull(db.dao().record("r2"))
        assertEquals("r1", db.dao().healthItem("sync-keep")!!.recordId)
        assertFalse(db.dao().healthItem("sync-keep")!!.userDeleted)
        assertNull(db.dao().healthItem("sync-drop")!!.recordId)
        assertTrue(db.dao().healthItem("sync-drop")!!.userDeleted)

        time.undoLastAction()
        assertEquals(base + hour, db.dao().record("r1")!!.endAt)
        assertNotNull(db.dao().record("r2"))
        assertEquals("r2", db.dao().note("note-1")!!.targetId)
        assertEquals(setOf(morning.id), recordTags("r1"))
        assertEquals(setOf(deep.id), recordTags("r2"))
        assertEquals("r2", db.dao().healthItem("sync-drop")!!.recordId)
        assertFalse(db.dao().healthItem("sync-drop")!!.userDeleted)

        time.redoLastAction()
        assertNull(db.dao().record("r2"))
        assertEquals("r1", db.dao().note("note-1")!!.targetId)
        assertNull(db.dao().healthItem("sync-drop")!!.recordId)
        assertEquals(base + 9_000_000L, db.dao().record("r1")!!.endAt)

        val mergedVersion = db.dao().record("r1")!!.version
        val splitAt = base + 4_500_000L
        val newId = org.splitRecord("r1", mergedVersion, splitAt)
        val firstHalf = db.dao().record("r1")!!
        val secondHalf = db.dao().record(newId)!!
        assertEquals(splitAt, firstHalf.endAt)
        assertEquals(splitAt, secondHalf.startAt)
        assertEquals(base + 9_000_000L, secondHalf.endAt)
        assertEquals(firstHalf.notes, secondHalf.notes)
        assertEquals(firstHalf.title, secondHalf.title)
        assertEquals(db.dao().note("note-1")!!.body, db.dao().notes().single { it.targetType == "RECORD" && it.targetId == newId }.body)
        assertEquals(setOf(morning.id, deep.id), recordTags("r1"))
        assertEquals(setOf(morning.id, deep.id), recordTags(newId))

        time.undoLastAction()
        assertEquals(base + 9_000_000L, db.dao().record("r1")!!.endAt)
        assertNull(db.dao().record(newId))
        assertEquals(setOf(morning.id, deep.id), recordTags("r1"))
        assertTrue(recordTags(newId).isEmpty())

        time.redoLastAction()
        assertNotNull(db.dao().record(newId))
        assertEquals(splitAt, db.dao().record("r1")!!.endAt)

        val late = base - 12 * hour
        time.saveRecord(TimeRecordEntity("r3", "task-1", late, late + hour, title = "论文阅读"))
        time.saveRecord(TimeRecordEntity("r4", "task-1", late + hour, late + 2 * hour, title = "论文阅读"))
        val contiguous = org.previewMerge(listOf("r3", "r4"))
        assertEquals(7_200_000L, contiguous.originalTotalMillis)
        assertEquals(7_200_000L, contiguous.endAt - contiguous.startAt)
        assertEquals("r3", org.mergeRecords(listOf("r3", "r4"), contiguous.versions))
        assertNull(db.dao().record("r4"))
        assertEquals(late + 2 * hour, db.dao().record("r3")!!.endAt)

        time.undoLastAction()
        assertNotNull(db.dao().record("r4"))
        assertEquals(late + hour, db.dao().record("r3")!!.endAt)
        time.redoLastAction()
        assertNull(db.dao().record("r4"))
        assertEquals(late + 2 * hour, db.dao().record("r3")!!.endAt)
    }

    @Test
    fun paymentCaptureDedupNeedsExplicitAccountAndCategoryThenUndoRedo() = runBlocking {
        ledger.saveAccount(LedgerAccountEntity("cash", "现金"))
        ledger.saveCategory(LedgerCategoryEntity("food", "餐饮"))

        val parsed = requireNotNull(
            PaymentParser.parseNotification(
                PaymentParser.ALIPAY_PACKAGE,
                "支付宝",
                "支付成功 ¥35.00 收款方：老王便利店 交易单号：2026103022001234567890123456",
                now.toEpochMilli(),
            ),
        )
        assertEquals(3500L, parsed.amountMinor)
        val first = requireNotNull(ledger.recordCapture(parsed, "notif-1"))
        assertEquals(PaymentCaptureEntity.Status.PENDING, first.status)
        assertNull(first.transactionId)
        assertTrue(ledger.snapshot().transactions.isEmpty())

        val repeat = requireNotNull(
            ledger.recordCapture(parsed.copy(occurredAt = now.plusSeconds(60).toEpochMilli()), "notif-2"),
        )
        assertEquals(first.id, repeat.id)
        assertEquals(1, ledger.snapshot().captures.size)

        val updated = requireNotNull(
            ledger.recordCapture(
                parsed.copy(merchant = "老王便利店（新）", occurredAt = now.plusSeconds(90).toEpochMilli()),
                "notif-3",
            ),
        )
        assertEquals(first.id, updated.id)
        assertEquals("老王便利店（新）", updated.merchant)
        assertEquals(1, ledger.snapshot().captures.size)

        val pending = ledger.snapshot().pendingCaptures.single()
        val confirmed = ledger.confirmCapture(
            pending.id,
            CaptureConfirmation(
                "cash", "food", 3500, LedgerDirection.EXPENSE,
                "老王便利店（新）", "午餐", now.toEpochMilli(),
            ),
            pending.version,
        )
        assertEquals("cash", confirmed.accountId)
        assertEquals("food", confirmed.categoryId)
        assertEquals(3500L, confirmed.amountMinor)
        assertEquals(1, ledger.snapshot().transactions.size)
        assertEquals(PaymentCaptureEntity.Status.CONFIRMED, ledger.snapshot().captures.single().status)
        assertEquals(confirmed.id, ledger.snapshot().transactions.single().id)

        assertNull(
            ledger.recordCapture(parsed.copy(occurredAt = now.plusSeconds(120).toEpochMilli()), "notif-4"),
        )
        assertEquals(1, ledger.snapshot().captures.size)
        assertEquals(1, ledger.snapshot().transactions.size)

        ledger.undoLastAction()
        assertTrue(ledger.snapshot().transactions.isEmpty())
        assertEquals(PaymentCaptureEntity.Status.PENDING, ledger.snapshot().captures.single().status)
        assertNull(ledger.snapshot().captures.single().transactionId)

        ledger.redoLastAction()
        assertEquals(confirmed.id, ledger.snapshot().transactions.single().id)
        assertEquals(PaymentCaptureEntity.Status.CONFIRMED, ledger.snapshot().captures.single().status)
    }

    @Test
    fun csvUtf8AndGb18030QuotedFieldsFilterAndImportOnce() = runBlocking {
        val utf8 = PaymentParser.parseBillCsv("\uFEFF$WECHAT_CSV".toByteArray(Charsets.UTF_8), clock.zone)
        assertEquals(5, utf8.totalRows)
        assertEquals(2, utf8.skippedRows)
        assertEquals(3, utf8.rows.size)
        assertEquals(1250L, utf8.rows.first { it.referenceId == "4200000000000000000000000001" }.amountMinor)
        val coffee = utf8.rows.first { it.referenceId == "4200000000000000000000000002" }
        assertEquals(2800L, coffee.amountMinor)
        assertEquals("咖啡,大杯 \"特调", coffee.merchant)
        val refund = utf8.rows.first { it.referenceId == "4200000000000000000000000005" }
        assertEquals(LedgerDirection.REFUND, refund.direction)
        assertEquals(500L, refund.amountMinor)

        val gbBytes = ALIPAY_CSV.toByteArray(Charset.forName("GB18030"))
        val gb = PaymentParser.parseBillCsv(gbBytes, clock.zone)
        assertEquals(1, gb.rows.size)
        assertEquals(2000L, gb.rows.single().amountMinor)
        assertEquals("面馆,老店", gb.rows.single().merchant)
        assertEquals("支付宝账单", gb.rows.single().sourceLabel)

        val firstImport = ledger.importBills("\uFEFF$WECHAT_CSV".toByteArray(Charsets.UTF_8))
        assertEquals(3, firstImport.imported)
        assertEquals(0, firstImport.duplicates)
        assertEquals(2, firstImport.skipped)
        val secondImport = ledger.importBills(WECHAT_CSV.toByteArray(Charsets.UTF_8))
        assertEquals(0, secondImport.imported)
        assertEquals(3, secondImport.duplicates)
        assertEquals(3, ledger.snapshot().captures.size)
        assertTrue(ledger.snapshot().captures.all { it.status == PaymentCaptureEntity.Status.PENDING })

        assertEquals(1, ledger.importBills(gbBytes).imported)
        assertEquals(4, ledger.snapshot().captures.size)
    }

    @Test
    fun centsAndRefundTotalsUseStoredIntegerMinorUnits() = runBlocking {
        assertEquals(123456L, Money.parseToMinor("¥1,234.56"))
        assertEquals(123456L, Money.parseToMinor("1234.56元"))
        assertNull(Money.parseToMinor("12.345"))
        assertEquals("¥1234.56", Money.format(123456L))
        assertEquals("-¥2.50", Money.format(-250L))

        ledger.saveAccount(LedgerAccountEntity("cash", "现金"))
        ledger.saveCategory(LedgerCategoryEntity("food", "餐饮"))
        val at = now.toEpochMilli()
        ledger.saveTransaction(
            LedgerTransactionEntity("expense", "cash", "food", amountMinor = 1000, direction = LedgerDirection.EXPENSE, occurredAt = at),
        )
        ledger.saveTransaction(
            LedgerTransactionEntity("refund", "cash", "food", amountMinor = 250, direction = LedgerDirection.REFUND, occurredAt = at),
        )
        ledger.saveTransaction(
            LedgerTransactionEntity("income", "cash", "food", amountMinor = 500, direction = LedgerDirection.INCOME, occurredAt = at),
        )
        ledger.saveTransaction(
            LedgerTransactionEntity("deleted", "cash", "food", amountMinor = 999, direction = LedgerDirection.EXPENSE, occurredAt = at),
        )
        val deleted = ledger.snapshot().transactions.first { it.id == "deleted" }
        ledger.deleteTransaction(deleted.id, deleted.version)

        val rows = ledger.snapshot().transactions
        assertEquals(3, rows.size)
        assertNotNull(db.ledgerDao().transaction("deleted")!!.deletedAt)
        assertEquals(1000L, rows.first { it.id == "expense" }.amountMinor)
        assertEquals(250L, rows.first { it.id == "refund" }.amountMinor)
        val totals = ledger.totals(rows)
        assertEquals(1000L, totals.expenseMinor)
        assertEquals(500L, totals.incomeMinor)
        assertEquals(250L, totals.refundMinor)
        assertEquals(750L, totals.netExpenseMinor)
        assertEquals(-250L, totals.netMinor)
        assertEquals(3, totals.count)
    }

    @Test
    fun backupRoundTripRestoresLedgerAndTagRelations() = runBlocking {
        val category = org.createCategory("日常", 0xFF3978B5)
        val tag = org.createTag("报销", 0xFF26806A)
        time.saveTask(TaskEntity("task-1", "报销整理", categoryId = category.id))
        time.saveRecord(
            TimeRecordEntity(
                "rec-1", "task-1",
                now.minusSeconds(3600).toEpochMilli(), now.toEpochMilli(),
                categoryId = category.id, title = "报销整理", notes = "发票两张",
            ),
        )
        org.assignTags(listOf("task-1"), listOf("rec-1"), setOf(tag.id), emptySet())

        ledger.saveAccount(LedgerAccountEntity("cash", "现金"))
        ledger.saveCategory(LedgerCategoryEntity("food", "餐饮"))
        ledger.saveTransaction(
            LedgerTransactionEntity(
                "txn-1", "cash", "food",
                amountMinor = 2599, direction = LedgerDirection.EXPENSE,
                merchant = "文具店", note = "含税", occurredAt = now.toEpochMilli(),
            ),
        )

        val tables = BackupTables.mainForVersion(12)
        val captured = BackupTables.capture(db.openHelper.writableDatabase.backupSql(), tables)
        assertEquals(1, captured.tables.getValue("ledger_transactions").rows.size)
        assertEquals(1, captured.tables.getValue("record_tags").rows.size)
        assertEquals(1, captured.tables.getValue("tags").rows.size)

        val restored = TimeAgentDatabase.openInMemory(context)
        try {
            BackupTables.replace(restored.openHelper.writableDatabase.backupSql(), captured, tables)

            val orgSnapshot = OrganizationRepository(restored, clock).snapshot()
            assertEquals(setOf("日常"), orgSnapshot.categories.map { it.name }.toSet())
            assertEquals(setOf("报销"), orgSnapshot.tags.map { it.name }.toSet())
            assertEquals(setOf(tag.id), orgSnapshot.taskTagIds["task-1"])
            assertEquals(setOf(tag.id), orgSnapshot.recordTagIds["rec-1"])
            assertEquals("发票两张", orgSnapshot.records.single().notes)
            assertEquals(category.id, orgSnapshot.records.single().categoryId)

            val ledgerSnapshot = LedgerRepository(restored, clock).snapshot()
            assertEquals("现金", ledgerSnapshot.accounts.single().name)
            assertEquals("餐饮", ledgerSnapshot.categories.single().name)
            val transaction = ledgerSnapshot.transactions.single()
            assertEquals(2599L, transaction.amountMinor)
            assertEquals("文具店", transaction.merchant)
            assertEquals("cash", transaction.accountId)
            assertEquals("food", transaction.categoryId)

            assertTrue(restored.dao().history().isNotEmpty())
        } finally {
            restored.close()
        }
    }

    private companion object {
        val WECHAT_CSV = """
            微信支付账单明细,,,,,,,,,,
            微信昵称：[测试],,,,,,,,,,
            ----------------------微信支付账单明细列表--------------------
            交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
            2026-10-01 12:00:00,商户消费,老王便利店,饮料,支出,¥12.50,零钱,支付成功,4200000000000000000000000001,M001,
            2026-10-01 13:00:00,商户消费,"咖啡,大杯 ""特调",饮品,支出,¥28.00,零钱,支付成功,4200000000000000000000000002,M002,
            2026-10-02 10:00:00,商户消费,失败商户,商品,支出,¥99.00,零钱,支付失败,4200000000000000000000000003,M003,
            2026-10-03 10:00:00,转账,朋友,转账,不计收支,¥100.00,零钱,已收钱,4200000000000000000000000004,M004,
            2026-10-04 10:00:00,商户消费,退款商户,商品,收入,¥5.00,零钱,退款成功,4200000000000000000000000005,M005,
        """.trimIndent()

        val ALIPAY_CSV = """
            支付宝交易记录明细查询
            ---------------------------------交易记录明细列表------------------------------------
            交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注
            2026-10-01 12:00:00,餐饮美食,"面馆,老店",,牛肉面,支出,20.00,余额宝,交易成功,2026100122001234567890123456,,
        """.trimIndent()
    }
}
