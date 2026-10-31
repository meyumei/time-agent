package com.timeagent.ledger

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Exact integer-cent money helpers. Parsing refuses fractions smaller than one cent. */
object Money {
    fun parseToMinor(input: String): Long? = toMinor(input, allowNegative = false)

    fun parseSignedToMinor(input: String): Long? = toMinor(input, allowNegative = true)

    fun format(minor: Long): String {
        val digits = BigDecimal.valueOf(kotlin.math.abs(minor), 2).setScale(2, RoundingMode.UNNECESSARY).toPlainString()
        return if (minor < 0) "-¥$digits" else "¥$digits"
    }

    fun plain(minor: Long): String = BigDecimal.valueOf(minor, 2).setScale(2, RoundingMode.UNNECESSARY).toPlainString()

    private fun toMinor(input: String, allowNegative: Boolean): Long? {
        val cleaned = input.trim()
            .replace("，", "")
            .replace(",", "")
            .replace(" ", "")
            .removePrefix("¥")
            .removePrefix("￥")
            .removeSuffix("元")
            .trim()
        if (cleaned.isEmpty()) return null
        val value = try {
            BigDecimal(cleaned)
        } catch (_: NumberFormatException) {
            return null
        }
        if (!allowNegative && value.signum() <= 0) return null
        if (value.abs() >= BigDecimal("1000000000000")) return null
        return try {
            value.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }
}

data class ParsedPayment(
    val sourcePackage: String,
    val sourceLabel: String,
    val amountMinor: Long,
    val direction: String,
    val merchant: String,
    val referenceId: String?,
    val occurredAt: Long,
    /** SHA-256 of the normalized source text; the source text itself is never stored. */
    val rawTextHash: String,
    val summary: String,
)

data class BillCsvResult(
    val rows: List<ParsedPayment>,
    val totalRows: Int,
    val skippedRows: Int,
    val errors: List<String> = emptyList(),
)

/**
 * Conservative local parser for WeChat/Alipay payment notifications and bill exports.
 * It never guesses when a message could describe a transfer, red packet, balance change or a
 * failed/pending order: those return null and are therefore not stored.
 */
object PaymentParser {
    const val VERSION = 1
    const val WECHAT_PACKAGE = "com.tencent.mm"
    const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
    val ALLOWED_PACKAGES = setOf(WECHAT_PACKAGE, ALIPAY_PACKAGE)

    fun sourceLabel(packageName: String): String = when (packageName) {
        WECHAT_PACKAGE -> "微信"
        ALIPAY_PACKAGE -> "支付宝"
        else -> "账单"
    }

    private val IRRELEVANT = listOf(
        "待支付", "等待付款", "待付款", "支付失败", "付款失败", "交易失败", "已取消", "已关闭",
        "交易关闭", "未支付", "支付超时", "处理中", "验证码", "身份验证", "安全验证", "风险提醒", "登录",
    )
    private val TRANSFER = listOf("红包", "转账", "提现", "充值", "还款", "亲属卡", "转入", "转出")
    private val SKIP_BILL_STATUS = listOf("失败", "关闭", "取消", "待付款", "等待付款", "未支付", "处理中", "已撤销")

    private val REFUND_AMOUNT = Regex("""(?:退款(?:金额|到账|成功)?|退回金额)[\s：:]*(?:人民币)?[\s：:]*[¥￥]?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val ACTUAL_AMOUNT = Regex("""(?:实付|实收|实际支付|实际付款|支付金额|付款金额)[\s：:]*(?:人民币)?[\s：:]*[¥￥]?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val KEYWORD_AMOUNT = Regex("""(?:实付|实收|付款|支付|消费|扣款|退款|到账|入账|收款|金额)[\s：:]*(?:人民币)?[\s：:]*[¥￥]?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val CURRENCY_AMOUNT = Regex("""[¥￥]\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")

    private val MERCHANT_PATTERNS = listOf(
        Regex("""(?:付款给|支付给|向)\s*([^\s，,。；;：:]{1,40})"""),
        Regex("""(?:商户名称|商户|收款方|收款人|商家|对方)\s*[：:]\s*([^\s，,。；;]{1,40})"""),
        Regex("""在\s*([^\s，,。；;]{1,40})\s*(?:消费|支付|付款)"""),
        Regex("""([^\s，,。；;]{1,30})\s*[-—·]\s*(?:消费|支付|付款)"""),
    )
    private val REFERENCE = Regex("""(?:交易单号|交易订单号|转账单号|订单编号|订单号|交易号|流水号|凭证号)\s*[：:]?\s*([A-Za-z0-9][A-Za-z0-9_-]{5,63})""")

    private val CSV_TIME_FORMATS = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd HH:mm",
        "yyyy-MM-dd'T'HH:mm:ss",
    )

    fun parseNotification(packageName: String, title: String, text: String, postedAt: Long): ParsedPayment? {
        if (packageName !in ALLOWED_PACKAGES) return null
        val body = text.trim()
        if (body.isBlank()) return null
        val all = "$title $body"
        if (IRRELEVANT.any { all.contains(it) }) return null
        if (TRANSFER.any { all.contains(it) }) return null
        val direction = detectDirection(body) ?: run {
            val successTitle = listOf("支付成功", "付款成功", "退款成功", "退款到账", "已支付", "收款到账", "到账")
                .any { title.contains(it) }
            if (successTitle) detectDirection(all) else null
        } ?: return null
        val amount = extractAmount(body, direction) ?: extractAmount(title, direction) ?: return null
        val merchant = extractMerchant(body)
        val reference = REFERENCE.find(body)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        val normalized = (title + "\n" + body).replace(Regex("""\s+"""), " ").trim()
        val hash = sha256("$packageName|$normalized")
        val summary = buildString {
            append(sourceLabel(packageName)).append(" · ").append(directionLabel(direction)).append(' ').append(Money.format(amount))
            if (merchant.isNotBlank()) append(" · ").append(merchant)
        }
        return ParsedPayment(packageName, sourceLabel(packageName), amount, direction, merchant, reference, postedAt, hash, summary)
    }

    fun parseBillCsv(bytes: ByteArray, zone: ZoneId): BillCsvResult {
        val text = decodeText(bytes)
        val rows = readCsv(text)
        if (rows.isEmpty()) return BillCsvResult(emptyList(), 0, 0, listOf("账单文件为空"))
        val headerIndex = rows.indexOfFirst { isBillHeader(it) }
        if (headerIndex < 0) {
            return BillCsvResult(emptyList(), 0, 0, listOf("未找到微信/支付宝账单表头（需要交易时间、金额和交易单号）"))
        }
        val header = rows[headerIndex].map(::normalizeHeader)
        val source = detectBillSource(rows.take(headerIndex), header)
        val timeIdx = findColumn(header, "交易时间", "交易创建时间", "付款时间")
        val amountIdx = findColumn(header, "金额(元)", "金额")
        val directionIdx = findColumn(header, "收/支", "收支", "方向")
        val statusIdx = findColumn(header, "当前状态", "交易状态", "状态")
        val typeIdx = findColumn(header, "交易类型", "交易分类")
        val itemIdx = findColumn(header, "商品说明", "商品")
        val merchantIdx = findColumn(header, "交易对方", "对方")
        val refIdx = findColumn(header, "交易单号", "交易订单号", "订单号", "交易号")
        val merchantRefIdx = findColumn(header, "商户单号", "商家订单号")
        val noteIdx = findColumn(header, "备注")
        if (timeIdx < 0 || amountIdx < 0 || directionIdx < 0) {
            return BillCsvResult(emptyList(), 0, 0, listOf("账单表头缺少交易时间、金额或收/支列"))
        }

        val parsed = mutableListOf<ParsedPayment>()
        val errors = mutableListOf<String>()
        var total = 0
        var skipped = 0
        rows.drop(headerIndex + 1).forEachIndexed { offset, row ->
            if (row.all { it.isBlank() }) return@forEachIndexed
            total++
            val timeRaw = cell(row, timeIdx)
            val amountRaw = cell(row, amountIdx)
            val directionRaw = cell(row, directionIdx).replace(" ", "")
            val statusRaw = cell(row, statusIdx).replace(" ", "")
            val typeRaw = cell(row, typeIdx)
            val itemRaw = cell(row, itemIdx)
            val noteRaw = cell(row, noteIdx)
            if (SKIP_BILL_STATUS.any { statusRaw.contains(it) }) {
                skipped++
                return@forEachIndexed
            }
            val income = directionRaw.contains("收入")
            val expense = directionRaw.contains("支出")
            if (!income && !expense) {
                skipped++
                return@forEachIndexed
            }
            val contextText = typeRaw + itemRaw + noteRaw
            if (TRANSFER.any { contextText.contains(it) }) {
                skipped++
                return@forEachIndexed
            }
            val refund = (typeRaw + statusRaw + itemRaw + noteRaw).contains("退款")
            val direction = when {
                income && refund -> LedgerDirection.REFUND
                income -> LedgerDirection.INCOME
                else -> LedgerDirection.EXPENSE
            }
            val amount = Money.parseSignedToMinor(amountRaw)?.let { kotlin.math.abs(it) }?.takeIf { it > 0 }
            if (amount == null) {
                skipped++
                if (errors.size < 5) errors += "第 ${headerIndex + offset + 2} 行金额无法识别"
                return@forEachIndexed
            }
            val occurredAt = parseBillTime(timeRaw, zone)
            if (occurredAt == null) {
                skipped++
                if (errors.size < 5) errors += "第 ${headerIndex + offset + 2} 行时间无法识别"
                return@forEachIndexed
            }
            val reference = cell(row, refIdx).ifBlank { cell(row, merchantRefIdx) }
                .replace(" ", "")
                .trim('\'', '\t')
                .take(80)
                .ifBlank { null }
            val merchant = (cell(row, merchantIdx).ifBlank { itemRaw })
                .replace(Regex("""\s+"""), " ")
                .trim()
                .take(100)
            val hash = sha256("CSV|${source.packageName}|${reference.orEmpty()}|$timeRaw|$directionRaw|$amountRaw|$statusRaw")
            val summary = buildString {
                append(source.label).append(" · ").append(directionLabel(direction)).append(' ').append(Money.format(amount))
                if (merchant.isNotBlank()) append(" · ").append(merchant)
            }
            parsed += ParsedPayment(source.packageName, source.label, amount, direction, merchant, reference, occurredAt, hash, summary)
        }
        return BillCsvResult(parsed, total, skipped, errors)
    }

    private fun detectDirection(text: String): String? {
        if (text.contains("退款失败")) return null
        if (text.contains("退款") || text.contains("退回")) return LedgerDirection.REFUND
        if (listOf("支付成功", "付款成功", "已支付", "已付款", "扣款", "消费").any { text.contains(it) }) {
            return LedgerDirection.EXPENSE
        }
        if (listOf("收款到账", "收款成功", "已收款", "收到", "到账", "入账", "收入").any { text.contains(it) }) {
            return LedgerDirection.INCOME
        }
        if (text.contains("支付") || text.contains("付款")) return LedgerDirection.EXPENSE
        return null
    }

    private fun extractAmount(text: String, direction: String): Long? {
        if (direction == LedgerDirection.REFUND) {
            amountFrom(REFUND_AMOUNT, text)?.let { return it }
            // A refund notice can also contain the original payment: never pick that amount.
            val currencies = CURRENCY_AMOUNT.findAll(text).filterNot { nearBalance(text, it.range.first) }.toList()
            return currencies.singleOrNull()?.let { Money.parseToMinor(it.groupValues[1]) }
        }
        return amountFrom(ACTUAL_AMOUNT, text) ?: amountFrom(KEYWORD_AMOUNT, text) ?: amountFrom(CURRENCY_AMOUNT, text)
    }

    private fun amountFrom(pattern: Regex, text: String): Long? {
        pattern.findAll(text).forEach { match ->
            if (!nearBalance(text, match.range.first)) {
                Money.parseToMinor(match.groupValues[1])?.let { return it }
            }
        }
        return null
    }

    private fun nearBalance(text: String, index: Int): Boolean {
        val prefix = text.substring(kotlin.math.max(0, index - 12), index)
        return listOf("余额", "剩余", "可用", "额度", "积分", "优惠", "原价", "红包").any { prefix.contains(it) }
    }

    private fun extractMerchant(text: String): String {
        for (pattern in MERCHANT_PATTERNS) {
            val raw = pattern.find(text)?.groupValues?.get(1) ?: continue
            var value = raw.trim()
                .trim('，', ',', '。', '；', ';', '：', ':', ' ', '的')
            listOf("付款", "支付", "消费").forEach { value = value.removeSuffix(it) }
            value = value.trim()
            if (value.length in 1..40 && value !in setOf("你", "您", "对方", "商家", "商户") &&
                !value.contains('¥') && !value.contains('￥')
            ) return value
        }
        return ""
    }

    private fun decodeText(bytes: ByteArray): String {
        val strictUtf8 = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
        val text = strictUtf8 ?: String(bytes, Charset.forName("GB18030"))
        return text.removePrefix("\uFEFF")
    }

    private fun isBillHeader(row: List<String>): Boolean {
        val joined = row.joinToString("|")
        return joined.contains("交易时间") && joined.contains("金额") &&
            (joined.contains("交易单号") || joined.contains("交易订单号") || joined.contains("订单号"))
    }

    private fun normalizeHeader(value: String): String =
        value.replace("\uFEFF", "").replace("\u00A0", "").replace(" ", "").trim()

    private fun findColumn(header: List<String>, vararg names: String): Int {
        for (name in names) {
            val exact = header.indexOf(name)
            if (exact >= 0) return exact
        }
        for (name in names) {
            val contained = header.indexOfFirst { it.contains(name) }
            if (contained >= 0) return contained
        }
        return -1
    }

    private fun cell(row: List<String>, index: Int): String =
        if (index in row.indices) row[index].trim() else ""

    private fun detectBillSource(preamble: List<List<String>>, header: List<String>): BillSource {
        val leading = preamble.joinToString(" ") { it.joinToString(" ") }
        if (leading.contains("微信")) return BillSource(WECHAT_PACKAGE, "微信账单")
        if (leading.contains("支付宝")) return BillSource(ALIPAY_PACKAGE, "支付宝账单")
        val joined = header.joinToString("|")
        return when {
            joined.contains("交易订单号") || joined.contains("商家订单号") -> BillSource(ALIPAY_PACKAGE, "支付宝账单")
            joined.contains("交易单号") || joined.contains("商户单号") -> BillSource(WECHAT_PACKAGE, "微信账单")
            else -> BillSource("import", "账单导入")
        }
    }

    private fun parseBillTime(raw: String, zone: ZoneId): Long? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        for (pattern in CSV_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(value, DateTimeFormatter.ofPattern(pattern)).atZone(zone).toInstant().toEpochMilli()
            } catch (_: Exception) {
                // Try the next known export layout.
            }
        }
        return null
    }

    private fun readCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        fun endField() {
            row.add(field.toString())
            field.setLength(0)
        }
        fun endRow() {
            endField()
            if (row.any { it.isNotBlank() }) rows.add(row)
            row = mutableListOf()
        }
        while (index < text.length) {
            val char = text[index]
            if (char == '"') {
                if (quoted && index + 1 < text.length && text[index + 1] == '"') {
                    field.append('"')
                    index += 2
                    continue
                }
                quoted = !quoted
                index++
                continue
            }
            if (char == ',' && !quoted) {
                endField()
                index++
                continue
            }
            if ((char == '\n' || char == '\r') && !quoted) {
                if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                endRow()
                index++
                continue
            }
            field.append(char)
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private data class BillSource(val packageName: String, val label: String)
}
