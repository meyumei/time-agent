package com.timeagent.business

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.ledger.CaptureConfirmation
import com.timeagent.ledger.LedgerAccountEntity
import com.timeagent.ledger.LedgerCategoryEntity
import com.timeagent.ledger.LedgerDirection
import com.timeagent.ledger.LedgerRepository
import com.timeagent.ledger.PaymentCaptureEntity
import com.timeagent.ledger.PaymentParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Ledger edge-case regression tests on a real device. Everything runs against a private in-memory
 * Room/SQLite database opened via [TimeAgentDatabase.openInMemory]; no real user database or files.
 * All synthetic timestamps are strictly before the fixed test clock.
 */
class LedgerEdgeDeviceTest {
    private lateinit var db: TimeAgentDatabase
    private lateinit var ledger: LedgerRepository
    private val now = Instant.parse("2026-10-30T02:00:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("Asia/Shanghai"))
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        db = TimeAgentDatabase.openInMemory(context)
        ledger = LedgerRepository(db, clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun csvImportCreatesTwoPendingAndUndoRedoCoversWholeImport() = runBlocking {
        ledger.saveAccount(LedgerAccountEntity("cash", "现金"))
        ledger.saveCategory(LedgerCategoryEntity("food", "餐饮"))

        val first = ledger.importBills(WECHAT_TWO_ROWS.toByteArray(Charsets.UTF_8))
        assertEquals(2, first.totalRows)
        assertEquals(2, first.imported)
        assertEquals(0, first.duplicates)
        assertEquals(0, first.skipped)

        val imported = ledger.snapshot()
        assertEquals(2, imported.captures.size)
        assertEquals(setOf(REF_ONE, REF_TWO), imported.captures.map { it.externalTransactionId }.toSet())
        assertEquals(setOf(1250L, 2800L), imported.captures.map { it.amountMinor }.toSet())
        assertTrue(imported.captures.all { it.status == PaymentCaptureEntity.Status.PENDING })
        val idsBeforeUndo = imported.captures.map { it.id }.toSet()

        ledger.undoLastAction()
        val undone = ledger.snapshot()
        assertTrue(undone.captures.isEmpty())
        assertTrue(undone.pendingCaptures.isEmpty())
        assertTrue(undone.accounts.any { it.id == "cash" })
        assertTrue(undone.categories.any { it.id == "food" })

        ledger.redoLastAction()
        val redone = ledger.snapshot()
        assertEquals(2, redone.captures.size)
        assertEquals(idsBeforeUndo, redone.captures.map { it.id }.toSet())
        assertEquals(setOf(REF_ONE, REF_TWO), redone.captures.map { it.externalTransactionId }.toSet())
        assertTrue(redone.captures.all { it.status == PaymentCaptureEntity.Status.PENDING })

        val again = ledger.importBills(WECHAT_TWO_ROWS.toByteArray(Charsets.UTF_8))
        assertEquals(0, again.imported)
        assertEquals(2, again.duplicates)
        assertEquals(2, ledger.snapshot().captures.size)
    }

    @Test
    fun notificationKeysKeepDistinctPaymentsApartWhileSameKeyDeduplicates() = runBlocking {
        ledger.saveAccount(LedgerAccountEntity("cash", "现金"))
        ledger.saveCategory(LedgerCategoryEntity("food", "餐饮"))
        val postedAt = now.minusSeconds(1800).toEpochMilli()
        val parsed = requireNotNull(
            PaymentParser.parseNotification(
                PaymentParser.ALIPAY_PACKAGE,
                "支付宝",
                "支付成功 ¥12.34 收款方：小林面馆",
                postedAt,
            ),
        )
        assertEquals(1234L, parsed.amountMinor)
        assertNull(parsed.referenceId)
        assertEquals("小林面馆", parsed.merchant)

        val first = requireNotNull(ledger.recordCapture(parsed, "key-A"))
        assertEquals(PaymentCaptureEntity.Status.PENDING, first.status)
        val duplicate = requireNotNull(ledger.recordCapture(parsed.copy(occurredAt = postedAt + 10_000), "key-A"))
        assertEquals(first.id, duplicate.id)
        assertEquals(1, ledger.snapshot().captures.size)

        val second = requireNotNull(ledger.recordCapture(parsed.copy(occurredAt = postedAt + 30_000), "key-B"))
        assertNotEquals(first.id, second.id)
        assertEquals(PaymentCaptureEntity.Status.PENDING, second.status)
        assertEquals(2, ledger.snapshot().captures.size)

        val pendingFirst = ledger.snapshot().captures.first { it.id == first.id }
        ledger.confirmCapture(
            first.id,
            CaptureConfirmation("cash", "food", 1234, LedgerDirection.EXPENSE, "小林面馆", "", postedAt),
            pendingFirst.version,
        )
        assertEquals(PaymentCaptureEntity.Status.CONFIRMED, ledger.snapshot().captures.first { it.id == first.id }.status)
        assertEquals(1, ledger.snapshot().transactions.size)

        val third = requireNotNull(ledger.recordCapture(parsed.copy(occurredAt = postedAt + 60_000), "key-C"))
        assertNotEquals(first.id, third.id)
        assertNotEquals(second.id, third.id)
        assertEquals(PaymentCaptureEntity.Status.PENDING, third.status)
        assertEquals(3, ledger.snapshot().captures.size)

        val pendingThird = ledger.snapshot().captures.first { it.id == third.id }
        ledger.ignoreCapture(third.id, pendingThird.version)

        val fourth = requireNotNull(ledger.recordCapture(parsed.copy(occurredAt = postedAt + 90_000), "key-D"))
        assertNotEquals(third.id, fourth.id)
        assertEquals(PaymentCaptureEntity.Status.PENDING, fourth.status)

        val snapshot = ledger.snapshot()
        assertEquals(4, snapshot.captures.size)
        assertEquals(setOf(second.id, fourth.id), snapshot.pendingCaptures.map { it.id }.toSet())
        assertEquals(PaymentCaptureEntity.Status.CONFIRMED, snapshot.captures.first { it.id == first.id }.status)
        assertEquals(PaymentCaptureEntity.Status.IGNORED, snapshot.captures.first { it.id == third.id }.status)
        assertEquals(1, snapshot.transactions.size)
    }

    @Test
    fun merchantlessConfirmedCaptureReconcilesUniqueCsvRowAndStillUndoes() = runBlocking {
        ledger.saveAccount(LedgerAccountEntity("cash", "现金"))
        ledger.saveCategory(LedgerCategoryEntity("food", "餐饮"))
        val occurredAt = now.minusSeconds(600)
        val parsed = requireNotNull(
            PaymentParser.parseNotification(
                PaymentParser.ALIPAY_PACKAGE,
                "支付宝",
                "支付成功 ¥12.34",
                occurredAt.toEpochMilli(),
            ),
        )
        assertEquals(1234L, parsed.amountMinor)
        assertEquals("", parsed.merchant)
        assertNull(parsed.referenceId)

        val capture = requireNotNull(ledger.recordCapture(parsed, "notif-merchantless"))
        ledger.confirmCapture(
            capture.id,
            CaptureConfirmation("cash", "food", 1234, LedgerDirection.EXPENSE, "", "", occurredAt.toEpochMilli()),
            capture.version,
        )
        assertEquals(1, ledger.snapshot().transactions.size)
        assertEquals(PaymentCaptureEntity.Status.CONFIRMED, ledger.snapshot().captures.single().status)

        val imported = ledger.importBills(ALIPAY_MATCH_ROW.toByteArray(Charsets.UTF_8))
        assertEquals(1, imported.totalRows)
        assertEquals(0, imported.imported)
        assertEquals(1, imported.duplicates)
        assertEquals(0, imported.skipped)

        val reconciled = ledger.snapshot()
        assertEquals(1, reconciled.captures.size)
        assertTrue(reconciled.pendingCaptures.isEmpty())
        assertEquals(PaymentCaptureEntity.Status.CONFIRMED, reconciled.captures.single().status)
        assertEquals(1, reconciled.transactions.size)
        assertEquals(1234L, reconciled.transactions.single().amountMinor)
        assertEquals(capture.id, reconciled.transactions.single().captureId)

        ledger.undoLastAction()
        val undone = ledger.snapshot()
        assertTrue(undone.transactions.isEmpty())
        assertEquals(1, undone.captures.size)
        assertEquals(PaymentCaptureEntity.Status.PENDING, undone.captures.single().status)
        assertNull(undone.captures.single().transactionId)
    }

    @Test
    fun parserHandlesRefundExpenseBalanceTransferAndIncome() {
        val refund = requireNotNull(
            PaymentParser.parseNotification(
                PaymentParser.WECHAT_PACKAGE,
                "微信支付",
                "退款成功，退款金额¥10.00，原支付金额¥35.00",
                now.minusSeconds(3600).toEpochMilli(),
            ),
        )
        assertEquals(LedgerDirection.REFUND, refund.direction)
        assertEquals(1000L, refund.amountMinor)

        val paid = requireNotNull(
            PaymentParser.parseNotification(
                PaymentParser.WECHAT_PACKAGE,
                "微信支付",
                "已支付¥35.00，对方已收款",
                now.minusSeconds(7200).toEpochMilli(),
            ),
        )
        assertEquals(LedgerDirection.EXPENSE, paid.direction)
        assertEquals(3500L, paid.amountMinor)

        assertNull(
            PaymentParser.parseNotification(
                PaymentParser.ALIPAY_PACKAGE,
                "支付宝",
                "余额宝转出¥1000.00已到账",
                now.minusSeconds(9000).toEpochMilli(),
            ),
        )

        val income = requireNotNull(
            PaymentParser.parseNotification(
                PaymentParser.ALIPAY_PACKAGE,
                "支付宝",
                "收款到账 ¥88.00",
                now.minusSeconds(600).toEpochMilli(),
            ),
        )
        assertEquals(LedgerDirection.INCOME, income.direction)
        assertEquals(8800L, income.amountMinor)

        val referenced = requireNotNull(
            PaymentParser.parseNotification(
                PaymentParser.ALIPAY_PACKAGE,
                "支付宝",
                "支付成功 ¥6.66 收款方：早餐铺 交易单号：2026103022001234567890123",
                now.minusSeconds(120).toEpochMilli(),
            ),
        )
        assertEquals(LedgerDirection.EXPENSE, referenced.direction)
        assertEquals(666L, referenced.amountMinor)
        assertEquals("早餐铺", referenced.merchant)
        assertEquals("2026103022001234567890123", referenced.referenceId)
    }

    private companion object {
        const val REF_ONE = "4200000000000000000000000101"
        const val REF_TWO = "4200000000000000000000000102"

        val WECHAT_TWO_ROWS = """
            微信支付账单明细,,,,,,,,,,
            微信昵称：[测试],,,,,,,,,,
            ----------------------微信支付账单明细列表--------------------
            交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
            2026-10-20 12:00:00,商户消费,老王便利店,饮料,支出,¥12.50,零钱,支付成功,$REF_ONE,M101,
            2026-10-21 09:30:00,商户消费,小林面馆,午餐,支出,¥28.00,零钱,支付成功,$REF_TWO,M102,
        """.trimIndent()

        val ALIPAY_MATCH_ROW = """
            支付宝交易记录明细查询
            ---------------------------------交易记录明细列表------------------------------------
            交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注
            2026-10-30 09:50:20,餐饮美食,小林面馆,,午餐,支出,12.34,余额宝,交易成功,2026103022001234567890123456,,
        """.trimIndent()
    }
}
