package com.timeagent.ledger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.backup.recoverPersonalDataBeforeOpen
import com.timeagent.ui.TimeAgentTheme
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

@AndroidEntryPoint
class LedgerActivity : ComponentActivity() {
    @Inject lateinit var database: Lazy<TimeAgentDatabase>
    companion object {
        const val EXTRA_CAPTURE_ID = "ledger_capture_id"
        const val EXTRA_ACTION = "ledger_action"
        const val ACTION_IGNORE = "ignore"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!recoverPersonalDataBeforeOpen()) return
        val repo = LedgerRepository(database.get())
        val captureId = intent?.getStringExtra(EXTRA_CAPTURE_ID)
        val ignore = intent?.getStringExtra(EXTRA_ACTION) == ACTION_IGNORE
        setContent {
            TimeAgentTheme {
                LedgerApp(
                    repository = repo,
                    initialCaptureId = captureId,
                    initialIgnore = ignore,
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LedgerApp(
    repository: LedgerRepository,
    initialCaptureId: String?,
    initialIgnore: Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = repository.zone
    val currentMonth = remember(zone) { YearMonth.now(zone) }

    var snapshot by remember { mutableStateOf<LedgerSnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var statusTick by remember { mutableIntStateOf(0) }
    var captureEnabled by remember { mutableStateOf(LedgerSettings.enabled(context)) }

    var selectedMonth by rememberSaveable { mutableStateOf(currentMonth.toString()) }
    var allMonths by rememberSaveable { mutableStateOf(false) }

    var captureTarget by remember { mutableStateOf<PaymentCaptureEntity?>(null) }
    var ignoreTarget by remember { mutableStateOf<PaymentCaptureEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<LedgerTransactionEntity?>(null) }
    var transactionEditing by remember { mutableStateOf<LedgerTransactionEntity?>(null) }
    var transactionNew by remember { mutableStateOf(false) }
    var accountEditing by remember { mutableStateOf<LedgerAccountEntity?>(null) }
    var accountNew by remember { mutableStateOf(false) }
    var categoryEditing by remember { mutableStateOf<LedgerCategoryEntity?>(null) }
    var categoryNew by remember { mutableStateOf(false) }
    var archiveAccountTarget by remember { mutableStateOf<LedgerAccountEntity?>(null) }
    var archiveCategoryTarget by remember { mutableStateOf<LedgerCategoryEntity?>(null) }
    var deepLinkHandled by rememberSaveable { mutableStateOf(false) }

    val accessGranted = remember(statusTick) { LedgerSettings.notificationAccessGranted(context) }
    val postAllowed = remember(statusTick) { LedgerSettings.notificationsAllowed(context) }

    fun runAction(success: String? = null, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                block()
                if (success != null) notice = success
            } catch (e: Exception) {
                error = e.message?.takeIf { it.isNotBlank() } ?: "操作失败"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(repository) {
        repository.observe()
            .catch { e -> error = e.message ?: "读取账本失败" }
            .collect { snapshot = it }
    }

    val data = snapshot
    val visibleTransactions = remember(data, selectedMonth, allMonths) {
        val all = data?.transactions.orEmpty()
        if (allMonths) all else all.filter {
            YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zone)).toString() == selectedMonth
        }
    }
    val totals = remember(visibleTransactions) { repository.totals(visibleTransactions) }

    LaunchedEffect(initialCaptureId, data, initialIgnore) {
        if (deepLinkHandled || initialCaptureId == null) return@LaunchedEffect
        val current = data ?: return@LaunchedEffect
        val capture = current.captures.firstOrNull {
            it.id == initialCaptureId && it.status == PaymentCaptureEntity.Status.PENDING
        }
        if (capture != null) {
            deepLinkHandled = true
            if (initialIgnore) ignoreTarget = capture else captureTarget = capture
        } else if (current.captures.any { it.id == initialCaptureId }) {
            deepLinkHandled = true
        }
    }

    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        statusTick++
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        statusTick++
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runAction {
                val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                    val output = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val read = stream.read(chunk)
                        if (read <= 0) break
                        total += read
                        require(total <= 20 * 1024 * 1024) { "账单文件过大（上限 20MB）" }
                        output.write(chunk, 0, read)
                    }
                    output.toByteArray()
                } ?: throw IllegalStateException("无法读取所选文件")
                val result = repository.importBills(bytes)
                notice = result.summary()
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val current = snapshot
        if (uri != null && current != null) {
            runAction("已导出 CSV") {
                val rows = current.transactions.filter {
                    allMonths || YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zone)).toString() == selectedMonth
                }
                val csv = repository.exportCsv(rows, current.accounts, current.categories, current.captures)
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(csv) }
                    ?: throw IllegalStateException("无法写入所选文件")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账本") },
                navigationIcon = { TextButton(onClick = onClose, enabled = !busy) { Text("返回") } },
                actions = {
                    TextButton(onClick = { runAction("已撤销") { repository.undoLastAction() } }, enabled = !busy) { Text("撤销") }
                    TextButton(onClick = { runAction("已重做") { repository.redoLastAction() } }, enabled = !busy) { Text("重做") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                listOf("待确认", "账本", "账户与分类").forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            error?.let {
                NoticeBar(it, true) { error = null }
            }
            notice?.let {
                NoticeBar(it, false) { notice = null }
            }
            if (data == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("正在读取本地账本…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                when (tab) {
                    0 -> PendingTab(
                        data = data,
                        zone = zone,
                        busy = busy,
                        captureEnabled = captureEnabled,
                        accessGranted = accessGranted,
                        postAllowed = postAllowed,
                        onToggleCapture = { value ->
                            captureEnabled = value
                            LedgerSettings.setEnabled(context, value)
                            statusTick++
                            notice = when {
                                !value -> "自动捕获已关闭"
                                !accessGranted -> "本地开关已打开；还需在系统设置中授予通知使用权"
                                else -> "自动捕获已开启；不会回放历史通知"
                            }
                        },
                        onOpenSettings = {
                            settingsLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        onRequestPermission = {
                            if (Build.VERSION.SDK_INT >= 33 &&
                                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                settingsLauncher.launch(intent)
                            }
                        },
                        onImport = {
                            importLauncher.launch(arrayOf("text/*", "application/octet-stream", "application/vnd.ms-excel"))
                        },
                        onManual = {
                            transactionEditing = null
                            transactionNew = true
                        },
                        onConfirm = { captureTarget = it },
                        onIgnore = { ignoreTarget = it },
                        onSetup = { tab = 2 },
                    )

                    1 -> LedgerTab(
                        transactions = visibleTransactions,
                        accounts = data.accounts,
                        categories = data.categories,
                        totals = totals,
                        zone = zone,
                        busy = busy,
                        allMonths = allMonths,
                        selectedMonth = selectedMonth,
                        currentMonth = currentMonth.toString(),
                        onThisMonth = {
                            allMonths = false
                            selectedMonth = currentMonth.toString()
                        },
                        onAll = { allMonths = true },
                        onPreviousMonth = {
                            selectedMonth = YearMonth.parse(selectedMonth).minusMonths(1).toString()
                        },
                        onNextMonth = {
                            selectedMonth = YearMonth.parse(selectedMonth).plusMonths(1).toString()
                        },
                        onManual = {
                            transactionEditing = null
                            transactionNew = true
                        },
                        onEdit = {
                            transactionNew = false
                            transactionEditing = it
                        },
                        onDelete = { deleteTarget = it },
                        onExport = {
                            exportLauncher.launch("账本-${if (allMonths) "全部" else selectedMonth}.csv")
                        },
                    )

                    else -> SetupTab(
                        data = data,
                        busy = busy,
                        onAddAccount = {
                            accountEditing = null
                            accountNew = true
                        },
                        onEditAccount = {
                            accountNew = false
                            accountEditing = it
                        },
                        onArchiveAccount = { archiveAccountTarget = it },
                        onAddCategory = {
                            categoryEditing = null
                            categoryNew = true
                        },
                        onEditCategory = {
                            categoryNew = false
                            categoryEditing = it
                        },
                        onArchiveCategory = { archiveCategoryTarget = it },
                    )
                }
            }
        }
    }

    captureTarget?.let { capture ->
        CaptureDialog(
            capture = capture,
            accounts = data?.activeAccounts.orEmpty(),
            categories = data?.activeCategories.orEmpty(),
            zone = zone,
            busy = busy,
            externalError = error,
            onDismiss = { if (!busy) captureTarget = null },
            onConfirm = { confirmation, version ->
                runAction("已入账") {
                    repository.confirmCapture(capture.id, confirmation, version)
                    LedgerNotifications.cancel(context, capture.id)
                    captureTarget = null
                    error = null
                }
            },
        )
    }

    ignoreTarget?.let { capture ->
        AlertDialog(
            onDismissRequest = { if (!busy) ignoreTarget = null },
            title = { Text("忽略这笔草稿？") },
            text = { Text("不会写入账本。可用右上角“撤销”恢复为待确认。") },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        runAction("已忽略") {
                            repository.ignoreCapture(capture.id, capture.version)
                            LedgerNotifications.cancel(context, capture.id)
                            ignoreTarget = null
                        }
                    },
                ) { Text("忽略") }
            },
            dismissButton = { TextButton(onClick = { ignoreTarget = null }, enabled = !busy) { Text("取消") } },
        )
    }

    deleteTarget?.let { transaction ->
        AlertDialog(
            onDismissRequest = { if (!busy) deleteTarget = null },
            title = { Text("删除这笔账目？") },
            text = { Text("会从统计中移除，并可用“撤销”恢复。") },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        runAction("已删除") {
                            repository.deleteTransaction(transaction.id, transaction.version)
                            deleteTarget = null
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }, enabled = !busy) { Text("取消") } },
        )
    }

    if (transactionNew || transactionEditing != null) {
        TransactionDialog(
            existing = transactionEditing,
            accounts = data?.accounts.orEmpty(),
            categories = data?.categories.orEmpty(),
            zone = zone,
            nowMillis = repository.now().toEpochMilli(),
            busy = busy,
            externalError = error,
            onDismiss = {
                if (!busy) {
                    transactionNew = false
                    transactionEditing = null
                }
            },
            onSave = { entity, expectedVersion ->
                runAction("已保存") {
                    repository.saveTransaction(entity, expectedVersion)
                    transactionNew = false
                    transactionEditing = null
                    error = null
                }
            },
        )
    }

    if (accountNew || accountEditing != null) {
        AccountDialog(
            existing = accountEditing,
            busy = busy,
            externalError = error,
            onDismiss = {
                if (!busy) {
                    accountNew = false
                    accountEditing = null
                }
            },
            onSave = { entity, expectedVersion ->
                runAction("已保存") {
                    repository.saveAccount(entity, expectedVersion)
                    accountNew = false
                    accountEditing = null
                    error = null
                }
            },
        )
    }

    if (categoryNew || categoryEditing != null) {
        CategoryDialog(
            existing = categoryEditing,
            busy = busy,
            externalError = error,
            onDismiss = {
                if (!busy) {
                    categoryNew = false
                    categoryEditing = null
                }
            },
            onSave = { entity, expectedVersion ->
                runAction("已保存") {
                    repository.saveCategory(entity, expectedVersion)
                    categoryNew = false
                    categoryEditing = null
                    error = null
                }
            },
        )
    }

    archiveAccountTarget?.let { account ->
        AlertDialog(
            onDismissRequest = { if (!busy) archiveAccountTarget = null },
            title = { Text("归档账户“${account.name}”？") },
            text = { Text("新账目不能再选择它，历史账目保留。可用“撤销”恢复。") },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        runAction("已归档") {
                            repository.archiveAccount(account.id, account.version)
                            archiveAccountTarget = null
                        }
                    },
                ) { Text("归档") }
            },
            dismissButton = { TextButton(onClick = { archiveAccountTarget = null }, enabled = !busy) { Text("取消") } },
        )
    }

    archiveCategoryTarget?.let { category ->
        AlertDialog(
            onDismissRequest = { if (!busy) archiveCategoryTarget = null },
            title = { Text("归档分类“${category.name}”？") },
            text = { Text("新账目不能再选择它，历史账目保留。可用“撤销”恢复。") },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        runAction("已归档") {
                            repository.archiveCategory(category.id, category.version)
                            archiveCategoryTarget = null
                        }
                    },
                ) { Text("归档") }
            },
            dismissButton = { TextButton(onClick = { archiveCategoryTarget = null }, enabled = !busy) { Text("取消") } },
        )
    }
}

@Composable
private fun NoticeBar(text: String, isError: Boolean, onClose: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onClose) { Text("关闭") }
        }
    }
}

@Composable
private fun PendingTab(
    data: LedgerSnapshot,
    zone: ZoneId,
    busy: Boolean,
    captureEnabled: Boolean,
    accessGranted: Boolean,
    postAllowed: Boolean,
    onToggleCapture: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onRequestPermission: () -> Unit,
    onImport: () -> Unit,
    onManual: () -> Unit,
    onConfirm: (PaymentCaptureEntity) -> Unit,
    onIgnore: (PaymentCaptureEntity) -> Unit,
    onSetup: () -> Unit,
) {
    val pending = data.pendingCaptures
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("支付自动捕获", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (captureEnabled) "已开启" else "已关闭（默认）", style = MaterialTheme.typography.labelLarge)
                        Text("只读取微信、支付宝的支付完成通知，先形成待确认草稿，不直接入账。", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = captureEnabled, onCheckedChange = onToggleCapture, enabled = !busy)
                }
                Text(
                    "系统通知使用权：${if (accessGranted) "已授予" else "未授予"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (accessGranted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                if (!accessGranted) TextButton(onClick = onOpenSettings, enabled = !busy) { Text("打开系统通知使用权设置") }
                Text(
                    "应用通知：${if (postAllowed) "允许" else "未允许"}（用于弹出待确认提醒）",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (postAllowed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                if (!postAllowed) TextButton(onClick = onRequestPermission, enabled = !busy) { Text("允许应用通知") }
                Text(
                    "累计捕获 ${data.captures.size} 条 · 待确认 ${pending.size} 条",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                if (data.activeAccounts.isEmpty() || data.activeCategories.isEmpty()) {
                    Text("确认前需要至少一个账户和一个分类。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onSetup) { Text("去添加账户与分类") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onImport, enabled = !busy) { Text("导入账单 CSV") }
                    Button(onClick = onManual, enabled = !busy) { Text("手动记一笔") }
                }
                Text("未开启通知使用权时仍可手动记账；开启后不会回放历史通知。", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (pending.isEmpty()) {
            Text("暂无待确认支付。", style = MaterialTheme.typography.bodyMedium)
        } else {
            pending.forEach { capture ->
                PaymentDraftRow(capture, zone, busy, onConfirm, onIgnore)
            }
        }
    }
}

@Composable
private fun PaymentDraftRow(
    capture: PaymentCaptureEntity,
    zone: ZoneId,
    busy: Boolean,
    onConfirm: (PaymentCaptureEntity) -> Unit,
    onIgnore: (PaymentCaptureEntity) -> Unit,
) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${directionLabel(capture.direction)} ${Money.format(capture.amountMinor)}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(formatListTime(capture.occurredAt, zone), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "${capture.sourceLabel} · ${capture.merchant.ifBlank { "未识别商户" }}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            capture.externalTransactionId?.let {
                Text("交易号 $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onIgnore(capture) }, enabled = !busy) { Text("忽略") }
                Button(onClick = { onConfirm(capture) }, enabled = !busy) { Text("选择分类并确认") }
            }
        }
    }
}

@Composable
private fun LedgerTab(
    transactions: List<LedgerTransactionEntity>,
    accounts: List<LedgerAccountEntity>,
    categories: List<LedgerCategoryEntity>,
    totals: LedgerTotals,
    zone: ZoneId,
    busy: Boolean,
    allMonths: Boolean,
    selectedMonth: String,
    currentMonth: String,
    onThisMonth: () -> Unit,
    onAll: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onManual: () -> Unit,
    onEdit: (LedgerTransactionEntity) -> Unit,
    onDelete: (LedgerTransactionEntity) -> Unit,
    onExport: () -> Unit,
) {
    val accountNames = remember(accounts) { accounts.associate { it.id to it.name } }
    val categoryNames = remember(categories) { categories.associate { it.id to it.name } }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !allMonths && selectedMonth == currentMonth, onClick = onThisMonth, label = { Text("本月") })
            FilterChip(selected = allMonths, onClick = onAll, label = { Text("全部") })
        }
        if (!allMonths) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPreviousMonth, enabled = !busy) { Text("‹") }
                Text(selectedMonth, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onNextMonth, enabled = !busy) { Text("›") }
            }
        }
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "支出（已扣退款） ${Money.format(totals.netExpenseMinor)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "收入 ${Money.format(totals.incomeMinor)} · 退款 ${Money.format(totals.refundMinor)} · 净额 ${Money.format(totals.netMinor)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("${totals.count} 笔", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onManual, enabled = !busy) { Text("记一笔") }
            OutlinedButton(onClick = onExport, enabled = !busy && transactions.isNotEmpty()) { Text("导出 CSV") }
        }
        if (transactions.isEmpty()) {
            Text("这个时间范围还没有账目。", style = MaterialTheme.typography.bodyMedium)
        } else {
            transactions.forEach { transaction ->
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                directionLabel(transaction.direction),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (transaction.direction == LedgerDirection.REFUND) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                transaction.merchant.ifBlank { categoryNames[transaction.categoryId].orEmpty().ifBlank { "未填写商户" } },
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(Money.format(transaction.amountMinor), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "${formatDateTime(transaction.occurredAt, zone)} · ${accountNames[transaction.accountId].orEmpty()} · ${categoryNames[transaction.categoryId].orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (transaction.note.isNotBlank()) Text(transaction.note, style = MaterialTheme.typography.bodySmall)
                        if (transaction.captureId != null) Text("来源：支付草稿", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onEdit(transaction) }, enabled = !busy) { Text("编辑") }
                            TextButton(onClick = { onDelete(transaction) }, enabled = !busy) { Text("删除") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupTab(
    data: LedgerSnapshot,
    busy: Boolean,
    onAddAccount: () -> Unit,
    onEditAccount: (LedgerAccountEntity) -> Unit,
    onArchiveAccount: (LedgerAccountEntity) -> Unit,
    onAddCategory: () -> Unit,
    onEditCategory: (LedgerCategoryEntity) -> Unit,
    onArchiveCategory: (LedgerCategoryEntity) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("账户", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Button(onClick = onAddAccount, enabled = !busy) { Text("新增账户") }
        val activeAccounts = data.accounts.filter { it.archivedAt == null }
        if (activeAccounts.isEmpty()) Text("还没有账户，确认支付草稿前请先添加。", style = MaterialTheme.typography.bodySmall)
        activeAccounts.forEach { account ->
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(account.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("${accountTypeLabel(account.type)} · ${account.currency}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onEditAccount(account) }, enabled = !busy) { Text("编辑") }
                    TextButton(onClick = { onArchiveAccount(account) }, enabled = !busy) { Text("归档") }
                }
            }
        }
        val archivedAccounts = data.accounts.filter { it.archivedAt != null }
        if (archivedAccounts.isNotEmpty()) {
            Text("已归档账户（历史账目保留，可用撤销恢复）", style = MaterialTheme.typography.labelMedium)
            archivedAccounts.forEach { Text("${it.name} · ${accountTypeLabel(it.type)}", style = MaterialTheme.typography.bodySmall) }
        }

        Spacer(Modifier.height(4.dp))
        Text("分类", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Button(onClick = onAddCategory, enabled = !busy) { Text("新增分类") }
        val activeCategories = data.categories.filter { it.archivedAt == null }
        if (activeCategories.isEmpty()) Text("还没有分类，确认支付草稿前请先添加。", style = MaterialTheme.typography.bodySmall)
        activeCategories.forEach { category ->
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(category.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(directionLabel(category.direction), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onEditCategory(category) }, enabled = !busy) { Text("编辑") }
                    TextButton(onClick = { onArchiveCategory(category) }, enabled = !busy) { Text("归档") }
                }
            }
        }
        val archivedCategories = data.categories.filter { it.archivedAt != null }
        if (archivedCategories.isNotEmpty()) {
            Text("已归档分类", style = MaterialTheme.typography.labelMedium)
            archivedCategories.forEach { Text("${it.name} · ${directionLabel(it.direction)}", style = MaterialTheme.typography.bodySmall) }
        }
        Text("归档只影响新建账目时的选择，不会改写历史账目。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CaptureDialog(
    capture: PaymentCaptureEntity,
    accounts: List<LedgerAccountEntity>,
    categories: List<LedgerCategoryEntity>,
    zone: ZoneId,
    busy: Boolean,
    externalError: String?,
    onDismiss: () -> Unit,
    onConfirm: (CaptureConfirmation, Long) -> Unit,
) {
    var amount by rememberSaveable(capture.id) { mutableStateOf(Money.plain(capture.amountMinor)) }
    var direction by rememberSaveable(capture.id) { mutableStateOf(capture.direction) }
    var merchant by rememberSaveable(capture.id) { mutableStateOf(capture.merchant) }
    var note by rememberSaveable(capture.id) { mutableStateOf("") }
    var occurredText by rememberSaveable(capture.id) { mutableStateOf(formatEditorTime(capture.occurredAt, zone)) }
    var accountId by rememberSaveable(capture.id) { mutableStateOf<String?>(null) }
    var categoryId by rememberSaveable(capture.id) { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }
    var validation by remember { mutableStateOf<String?>(null) }

    FormDialog(
        title = "确认支付草稿",
        busy = busy,
        onDismiss = onDismiss,
        confirmLabel = "确认入账",
        confirmEnabled = accounts.isNotEmpty() && categories.isNotEmpty(),
        onConfirm = {
            val minor = Money.parseToMinor(amount)
            val occurred = parseEditorTime(occurredText, zone)
            validation = when {
                minor == null -> "请输入正确金额（最多两位小数）"
                occurred == null -> "时间格式应为 yyyy-MM-dd HH:mm"
                accountId == null -> "请选择账户"
                categoryId == null -> "请选择分类"
                else -> null
            }
            if (validation == null && minor != null && occurred != null && accountId != null && categoryId != null) {
                onConfirm(
                    CaptureConfirmation(accountId!!, categoryId!!, minor, direction, merchant.trim(), note.trim(), occurred),
                    capture.version,
                )
            }
        },
    ) {
        Text("${capture.sourceLabel} · ${formatDateTime(capture.occurredAt, zone)}", style = MaterialTheme.typography.bodySmall)
        capture.externalTransactionId?.let { Text("交易号 $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Text("金额（元）", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(amount, { amount = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        DirectionChips(direction) { direction = it }
        Text("账户", style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { picker = "account" }, modifier = Modifier.fillMaxWidth(), enabled = accounts.isNotEmpty() && !busy) {
            Text(accounts.firstOrNull { it.id == accountId }?.let { "${it.name} · ${accountTypeLabel(it.type)}" } ?: "选择账户")
        }
        Text("分类", style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { picker = "category" }, modifier = Modifier.fillMaxWidth(), enabled = categories.isNotEmpty() && !busy) {
            Text(categories.firstOrNull { it.id == categoryId }?.let { "${it.name} · ${directionLabel(it.direction)}" } ?: "选择分类")
        }
        if (accounts.isEmpty() || categories.isEmpty()) {
            Text("请先在“账户与分类”中添加至少一个账户和一个分类。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(merchant, { merchant = it }, label = { Text("商户") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(occurredText, { occurredText = it }, label = { Text("发生时间 yyyy-MM-dd HH:mm") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        externalError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        validation?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Text("确认后写入账本，可用“撤销”回到待确认。", style = MaterialTheme.typography.bodySmall)
    }

    picker?.let { kind ->
        val options = if (kind == "account") {
            accounts.map { it.id to "${it.name} · ${accountTypeLabel(it.type)}" }
        } else {
            categories.map { it.id to "${it.name} · ${directionLabel(it.direction)}" }
        }
        ChoiceDialog(
            title = if (kind == "account") "选择账户" else "选择分类",
            options = options,
            onDismiss = { picker = null },
            onPick = {
                if (kind == "account") accountId = it else categoryId = it
                picker = null
            },
        )
    }
}

@Composable
private fun TransactionDialog(
    existing: LedgerTransactionEntity?,
    accounts: List<LedgerAccountEntity>,
    categories: List<LedgerCategoryEntity>,
    zone: ZoneId,
    nowMillis: Long,
    busy: Boolean,
    externalError: String?,
    onDismiss: () -> Unit,
    onSave: (LedgerTransactionEntity, Long?) -> Unit,
) {
    val key = existing?.id ?: "new"
    val accountOptions = accounts.filter { it.archivedAt == null || it.id == existing?.accountId }
    val categoryOptions = categories.filter { it.archivedAt == null || it.id == existing?.categoryId }
    var amount by rememberSaveable(key) { mutableStateOf(existing?.let { Money.plain(it.amountMinor) }.orEmpty()) }
    var direction by rememberSaveable(key) { mutableStateOf(existing?.direction ?: LedgerDirection.EXPENSE) }
    var merchant by rememberSaveable(key) { mutableStateOf(existing?.merchant.orEmpty()) }
    var note by rememberSaveable(key) { mutableStateOf(existing?.note.orEmpty()) }
    var occurredText by rememberSaveable(key) { mutableStateOf(formatEditorTime(existing?.occurredAt ?: nowMillis, zone)) }
    var accountId by rememberSaveable(key) { mutableStateOf(existing?.accountId) }
    var categoryId by rememberSaveable(key) { mutableStateOf(existing?.categoryId) }
    var picker by remember { mutableStateOf<String?>(null) }
    var validation by remember { mutableStateOf<String?>(null) }

    FormDialog(
        title = if (existing == null) "记一笔" else "编辑账目",
        busy = busy,
        onDismiss = onDismiss,
        confirmEnabled = accountOptions.isNotEmpty() && categoryOptions.isNotEmpty(),
        onConfirm = {
            val minor = Money.parseToMinor(amount)
            val occurred = parseEditorTime(occurredText, zone)
            validation = when {
                minor == null -> "请输入正确金额（最多两位小数）"
                occurred == null -> "时间格式应为 yyyy-MM-dd HH:mm"
                accountId == null -> "请选择账户"
                categoryId == null -> "请选择分类"
                else -> null
            }
            if (validation == null && minor != null && occurred != null && accountId != null && categoryId != null) {
                onSave(
                    LedgerTransactionEntity(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        accountId = accountId!!,
                        categoryId = categoryId!!,
                        captureId = existing?.captureId,
                        amountMinor = minor,
                        direction = direction,
                        merchant = merchant.trim(),
                        note = note.trim(),
                        occurredAt = occurred,
                        createdAt = existing?.createdAt ?: 0,
                        updatedAt = existing?.updatedAt ?: 0,
                        confirmedAt = existing?.confirmedAt,
                        version = existing?.version ?: 0,
                        deletedAt = existing?.deletedAt,
                    ),
                    existing?.version,
                )
            }
        },
    ) {
        Text("金额（元）", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(amount, { amount = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        DirectionChips(direction) { direction = it }
        Text("账户", style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { picker = "account" }, modifier = Modifier.fillMaxWidth(), enabled = accountOptions.isNotEmpty() && !busy) {
            Text(accountOptions.firstOrNull { it.id == accountId }?.let { "${it.name} · ${accountTypeLabel(it.type)}" } ?: "选择账户")
        }
        Text("分类", style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { picker = "category" }, modifier = Modifier.fillMaxWidth(), enabled = categoryOptions.isNotEmpty() && !busy) {
            Text(categoryOptions.firstOrNull { it.id == categoryId }?.let { "${it.name} · ${directionLabel(it.direction)}" } ?: "选择分类")
        }
        if (accountOptions.isEmpty() || categoryOptions.isEmpty()) {
            Text("请先添加账户和分类。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(merchant, { merchant = it }, label = { Text("商户") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(occurredText, { occurredText = it }, label = { Text("发生时间 yyyy-MM-dd HH:mm") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (existing?.captureId != null) Text("来源：支付通知 / 账单导入", style = MaterialTheme.typography.bodySmall)
        externalError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        validation?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }

    picker?.let { kind ->
        val options = if (kind == "account") {
            accountOptions.map { it.id to "${it.name} · ${accountTypeLabel(it.type)}" }
        } else {
            categoryOptions.map { it.id to "${it.name} · ${directionLabel(it.direction)}" }
        }
        ChoiceDialog(
            title = if (kind == "account") "选择账户" else "选择分类",
            options = options,
            onDismiss = { picker = null },
            onPick = {
                if (kind == "account") accountId = it else categoryId = it
                picker = null
            },
        )
    }
}

@Composable
private fun AccountDialog(
    existing: LedgerAccountEntity?,
    busy: Boolean,
    externalError: String?,
    onDismiss: () -> Unit,
    onSave: (LedgerAccountEntity, Long?) -> Unit,
) {
    val key = existing?.id ?: "new"
    var name by rememberSaveable(key) { mutableStateOf(existing?.name.orEmpty()) }
    var type by rememberSaveable(key) { mutableStateOf(existing?.type ?: LedgerAccountType.CASH) }
    var currency by rememberSaveable(key) { mutableStateOf(existing?.currency ?: "CNY") }
    var opening by rememberSaveable(key) {
        mutableStateOf(existing?.openingBalanceMinor?.let { Money.plain(it) }.orEmpty())
    }
    var validation by remember { mutableStateOf<String?>(null) }

    FormDialog(
        title = if (existing == null) "新增账户" else "编辑账户",
        busy = busy,
        onDismiss = onDismiss,
        onConfirm = {
            val balance = if (opening.isBlank()) 0L else Money.parseSignedToMinor(opening)
            validation = when {
                name.trim().isEmpty() -> "请填写账户名称"
                currency.trim().length != 3 -> "币种需为 3 位字母代码"
                balance == null -> "余额请输入正确金额（最多两位小数）"
                else -> null
            }
            if (validation == null && balance != null) {
                onSave(
                    LedgerAccountEntity(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        name = name.trim(),
                        type = type,
                        currency = currency.trim().uppercase(),
                        openingBalanceMinor = balance,
                        sortOrder = existing?.sortOrder ?: 0,
                        archivedAt = existing?.archivedAt,
                        createdAt = existing?.createdAt ?: 0,
                        updatedAt = existing?.updatedAt ?: 0,
                        version = existing?.version ?: 0,
                    ),
                    existing?.version,
                )
            }
        },
    ) {
        OutlinedTextField(name, { name = it }, label = { Text("账户名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("类型", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LedgerAccountType.ALL.forEach { value ->
                FilterChip(selected = type == value, onClick = { type = value }, label = { Text(accountTypeLabel(value)) })
            }
        }
        OutlinedTextField(currency, { currency = it }, label = { Text("币种（CNY）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(opening, { opening = it }, label = { Text("期初余额（可选，元）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        externalError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        validation?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun CategoryDialog(
    existing: LedgerCategoryEntity?,
    busy: Boolean,
    externalError: String?,
    onDismiss: () -> Unit,
    onSave: (LedgerCategoryEntity, Long?) -> Unit,
) {
    val key = existing?.id ?: "new"
    var name by rememberSaveable(key) { mutableStateOf(existing?.name.orEmpty()) }
    var direction by rememberSaveable(key) { mutableStateOf(existing?.direction ?: LedgerDirection.EXPENSE) }
    var icon by rememberSaveable(key) { mutableStateOf(existing?.icon.orEmpty()) }
    var validation by remember { mutableStateOf<String?>(null) }

    FormDialog(
        title = if (existing == null) "新增分类" else "编辑分类",
        busy = busy,
        onDismiss = onDismiss,
        onConfirm = {
            validation = if (name.trim().isEmpty()) "请填写分类名称" else null
            if (validation == null) {
                onSave(
                    LedgerCategoryEntity(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        name = name.trim(),
                        direction = direction,
                        icon = icon.trim(),
                        sortOrder = existing?.sortOrder ?: 0,
                        archivedAt = existing?.archivedAt,
                        createdAt = existing?.createdAt ?: 0,
                        updatedAt = existing?.updatedAt ?: 0,
                        version = existing?.version ?: 0,
                    ),
                    existing?.version,
                )
            }
        },
    ) {
        OutlinedTextField(name, { name = it }, label = { Text("分类名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        DirectionChips(direction) { direction = it }
        OutlinedTextField(icon, { icon = it }, label = { Text("图标标记（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        externalError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        validation?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun DirectionChips(direction: String, onChange: (String) -> Unit) {
    Text("方向", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(LedgerDirection.EXPENSE, LedgerDirection.INCOME, LedgerDirection.REFUND).forEach { value ->
            FilterChip(
                selected = direction == value,
                onClick = { onChange(value) },
                label = { Text(directionLabel(value)) },
            )
        }
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (options.isEmpty()) Text("暂无可选项")
                options.forEach { (id, label) ->
                    TextButton(onClick = { onPick(id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(label, Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun FormDialog(
    title: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmLabel: String = "保存",
    confirmEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .heightIn(max = 700.dp)
                        .padding(20.dp)
                        .imePadding()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    content()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
                        Button(onClick = onConfirm, enabled = !busy && confirmEnabled) {
                            Text(if (busy) "处理中…" else confirmLabel)
                        }
                    }
                }
            }
        }
    }
}

private val EDITOR_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val LIST_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private val FULL_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatEditorTime(at: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(at).atZone(zone).format(EDITOR_TIME)

private fun parseEditorTime(text: String, zone: ZoneId): Long? = try {
    LocalDateTime.parse(text.trim(), EDITOR_TIME).atZone(zone).toInstant().toEpochMilli()
} catch (_: Exception) {
    null
}

private fun formatListTime(at: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(at).atZone(zone).format(LIST_TIME)

private fun formatDateTime(at: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(at).atZone(zone).format(FULL_TIME)
