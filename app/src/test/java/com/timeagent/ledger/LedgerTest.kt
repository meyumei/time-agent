package com.timeagent.ledger

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.TimeAgentDatabase
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.Charset
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LedgerTest {
    private lateinit var db: TimeAgentDatabase
    private lateinit var repo: LedgerRepository
    private val now = Instant.parse("2026-10-30T02:00:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("Asia/Shanghai"))
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setup() {
        context.deleteDatabase("ledger-test.db")
        db = Room.databaseBuilder(context, TimeAgentDatabase::class.java, "ledger-test.db")
            .allowMainThreadQueries()
            .build()
        repo = LedgerRepository(db, clock)
    }

    @After
    fun teardown() {
        db.close()
        context.deleteDatabase("ledger-test.db")
    }

    private suspend fun baseData() {
        repo.saveAccount(LedgerAccountEntity("cash", "现金"))
        repo.saveCategory(LedgerCategoryEntity("food", "餐饮"))
    }

    @Test
    fun `draft dedup survives repeats and confirmation undo redo`() = runBlocking {
        baseData()
        val parsed = requireNotNull(
            PaymentParser.parseNotification(
                PaymentParser.ALIPAY_PACKAGE,
                "支付宝",
                "支付成功 ¥35.00 收款方：老王便利店 交易单号：2026103022001234567890123456",
                now.toEpochMilli(),
            ),
        )
        val first = requireNotNull(repo.recordCapture(parsed, "key-1"))
        val repeat = requireNotNull(repo.recordCapture(parsed.copy(occurredAt = now.plusSeconds(60).toEpochMilli()), "key-1-repeat"))
        assertEquals(first.id, repeat.id)
        assertEquals(1, repo.snapshot().captures.size)

        val updated = requireNotNull(repo.recordCapture(parsed.copy(merchant = "老王便利店（新）", occurredAt = now.plusSeconds(90).toEpochMilli()), "key-1-update"))
        assertEquals(first.id, updated.id)
        assertEquals("老王便利店（新）", updated.merchant)

        val pending = repo.snapshot().captures.single()
        val confirmed = repo.confirmCapture(
            pending.id,
            CaptureConfirmation("cash", "food", 3500, LedgerDirection.EXPENSE, "老王便利店（新）", "午餐", now.toEpochMilli()),
            pending.version,
        )
        assertEquals(1, repo.snapshot().transactions.size)
        assertEquals(PaymentCaptureEntity.Status.CONFIRMED, repo.snapshot().captures.single().status)

        assertNull(repo.recordCapture(parsed.copy(occurredAt = now.plusSeconds(120).toEpochMilli()), "key-1-again"))
        assertEquals(1, repo.snapshot().captures.size)
        assertEquals(1, repo.snapshot().transactions.size)

        repo.undoLastAction()
        assertTrue(repo.snapshot().transactions.isEmpty())
        assertEquals(PaymentCaptureEntity.Status.PENDING, repo.snapshot().captures.single().status)

        repo.redoLastAction()
        assertEquals(1, repo.snapshot().transactions.size)
        assertEquals(PaymentCaptureEntity.Status.CONFIRMED, repo.snapshot().captures.single().status)
        assertEquals(confirmed.id, repo.snapshot().transactions.single().id)
    }

    @Test
    fun `different order references stay distinct while identical repeated text dedups`() = runBlocking {
        baseData()
        val first = ParsedPayment(
            PaymentParser.WECHAT_PACKAGE, "微信", 1200, LedgerDirection.EXPENSE, "咖啡店", "ORDER-A",
            now.toEpochMilli(), "same-normalized-text", "微信 · 支出 ¥12.00",
        )
        val a = requireNotNull(repo.recordCapture(first, "a"))
        val b = requireNotNull(repo.recordCapture(first.copy(referenceId = "ORDER-B", occurredAt = now.plusSeconds(30).toEpochMilli()), "b"))
        assertNotEquals(a.id, b.id)
        assertEquals(2, repo.snapshot().captures.size)

        val c = requireNotNull(repo.recordCapture(first.copy(occurredAt = now.plusSeconds(60).toEpochMilli()), "c"))
        assertEquals(a.id, c.id)
        assertEquals(2, repo.snapshot().captures.size)
    }

    @Test
    fun `integer money and refund adjusted totals`() = runBlocking {
        baseData()
        assertEquals(123456L, Money.parseToMinor("¥1,234.56"))
        assertNull(Money.parseToMinor("12.345"))
        assertEquals("¥1234.56", Money.format(123456L))

        repo.saveTransaction(LedgerTransactionEntity("expense", "cash", "food", amountMinor = 1000, direction = LedgerDirection.EXPENSE, occurredAt = now.toEpochMilli()))
        repo.saveTransaction(LedgerTransactionEntity("refund", "cash", "food", amountMinor = 250, direction = LedgerDirection.REFUND, occurredAt = now.toEpochMilli()))
        repo.saveTransaction(LedgerTransactionEntity("income", "cash", "food", amountMinor = 500, direction = LedgerDirection.INCOME, occurredAt = now.toEpochMilli()))
        repo.saveTransaction(LedgerTransactionEntity("deleted", "cash", "food", amountMinor = 999, direction = LedgerDirection.EXPENSE, occurredAt = now.toEpochMilli()))
        val deleted = repo.snapshot().transactions.first { it.id == "deleted" }
        repo.deleteTransaction(deleted.id, deleted.version)

        val totals = repo.totals(repo.snapshot().transactions)
        assertEquals(1000L, totals.expenseMinor)
        assertEquals(500L, totals.incomeMinor)
        assertEquals(250L, totals.refundMinor)
        assertEquals(750L, totals.netExpenseMinor)
        assertEquals(-250L, totals.netMinor)
        assertEquals(3, totals.count)
    }

    @Test
    fun `wechat csv skips transfers and failures and reads quoted merchants`() {
        val result = PaymentParser.parseBillCsv(WECHAT_CSV.toByteArray(Charsets.UTF_8), clock.zone)
        assertEquals(5, result.totalRows)
        assertEquals(2, result.skippedRows)
        assertEquals(3, result.rows.size)
        assertEquals(1250L, result.rows.first { it.referenceId == "4200000000000000000000000001" }.amountMinor)
        assertEquals(2800L, result.rows.first { it.referenceId == "4200000000000000000000000002" }.amountMinor)
        assertEquals("某商户", result.rows.first { it.referenceId == "4200000000000000000000000002" }.merchant)
        assertEquals(LedgerDirection.REFUND, result.rows.first { it.referenceId == "4200000000000000000000000005" }.direction)
    }

    @Test
    fun `gb18030 alipay csv decodes`() {
        val bytes = ALIPAY_CSV.toByteArray(Charset.forName("GB18030"))
        val result = PaymentParser.parseBillCsv(bytes, clock.zone)
        assertEquals(1, result.rows.size)
        assertEquals(2000L, result.rows.single().amountMinor)
        assertEquals("支付宝账单", result.rows.single().sourceLabel)
    }

    @Test
    fun `bill import creates pending drafts once and reconciles by reference`() = runBlocking {
        baseData()
        val first = repo.importBills(WECHAT_CSV.toByteArray(Charsets.UTF_8))
        assertEquals(3, first.imported)
        assertEquals(0, first.duplicates)
        val second = repo.importBills(WECHAT_CSV.toByteArray(Charsets.UTF_8))
        assertEquals(0, second.imported)
        assertEquals(3, second.duplicates)
        assertEquals(3, repo.snapshot().captures.size)
        assertTrue(repo.snapshot().captures.all { it.status == PaymentCaptureEntity.Status.PENDING })
    }

    @Test
    fun `notification parser rejects balances failures pending and red packets`() {
        assertNull(PaymentParser.parseNotification("com.example", "支付", "支付成功 ¥35.00", now.toEpochMilli()))
        assertNull(PaymentParser.parseNotification(PaymentParser.WECHAT_PACKAGE, "微信支付", "支付成功，账户余额 ¥1000.00", now.toEpochMilli()))
        assertNull(PaymentParser.parseNotification(PaymentParser.WECHAT_PACKAGE, "微信支付", "支付成功，订单号 4200001234567890123456，账户余额 ¥1000.00", now.toEpochMilli()))
        assertNull(PaymentParser.parseNotification(PaymentParser.WECHAT_PACKAGE, "微信支付", "支付失败 ¥35.00", now.toEpochMilli()))
        assertNull(PaymentParser.parseNotification(PaymentParser.WECHAT_PACKAGE, "微信支付", "待支付 ¥35.00", now.toEpochMilli()))
        assertNull(PaymentParser.parseNotification(PaymentParser.WECHAT_PACKAGE, "微信支付", "微信红包 已存入零钱 ¥8.88", now.toEpochMilli()))

        val parsed = requireNotNull(
            PaymentParser.parseNotification(
                PaymentParser.WECHAT_PACKAGE,
                "微信支付",
                "支付成功 ¥12.50 商户：瑞幸咖啡 交易单号：4200001234567890123456",
                now.toEpochMilli(),
            ),
        )
        assertEquals(1250L, parsed.amountMinor)
        assertEquals("瑞幸咖啡", parsed.merchant)
        assertEquals("4200001234567890123456", parsed.referenceId)
        assertEquals(LedgerDirection.EXPENSE, parsed.direction)
    }

    private companion object {
        val WECHAT_CSV = """
            微信支付账单明细,,,,,,,,,,
            微信昵称：[测试],,,,,,,,,,
            ----------------------微信支付账单明细列表--------------------
            交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
            2026-10-01 12:00:00,商户消费,老王便利店,饮料,支出,¥12.50,零钱,支付成功,4200000000000000000000000001,M001,
            2026-10-01 13:00:00,商户消费,某商户,"咖啡,大杯",支出,¥28.00,零钱,支付成功,4200000000000000000000000002,M002,
            2026-10-02 10:00:00,商户消费,失败商户,商品,支出,¥99.00,零钱,支付失败,4200000000000000000000000003,M003,
            2026-10-03 10:00:00,转账,朋友,转账,不计收支,¥100.00,零钱,已收钱,4200000000000000000000000004,M004,
            2026-10-04 10:00:00,商户消费,退款商户,商品,收入,¥5.00,零钱,退款成功,4200000000000000000000000005,M005,
        """.trimIndent()

        val ALIPAY_CSV = """
            支付宝交易记录明细查询
            ---------------------------------交易记录明细列表------------------------------------
            交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注
            2026-10-01 12:00:00,餐饮美食,面馆,,牛肉面,支出,20.00,余额宝,交易成功,2026100122001234567890123456,,
        """.trimIndent()
    }
}
