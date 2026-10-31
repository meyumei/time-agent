package com.timeagent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timeagent.data.TimeRecordEntity
import com.timeagent.data.healthOriginLabel
import com.timeagent.health.*
import java.time.Instant
import java.time.ZoneId

@Composable fun HealthSyncScreen(
    model: TimeViewModel,
    reader: HealthDataReader,
    settings: HealthSyncSettings,
    zone: ZoneId,
    onClose: () -> Unit,
) {
    val data by model.data.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    var connectState by remember { mutableStateOf<HealthConnectState?>(null) }
    var message by remember { mutableStateOf(settings.lastResult().takeIf { it.isNotBlank() }) }
    var auto by remember { mutableStateOf(settings.enabled()) }
    var sleepEnabled by remember { mutableStateOf(settings.sleepEnabled()) }
    var exerciseEnabled by remember { mutableStateOf(settings.exerciseEnabled()) }
    var windowDays by remember { mutableStateOf(settings.windowDays()) }
    var sleepPreferred by remember { mutableStateOf(settings.preferredSleepOrigin()) }
    var gbUri by remember { mutableStateOf(settings.gbExportUri()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val gbPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.contentResolver.openInputStream(uri)?.use { } ?: error("导出文件不可读")
            }.onSuccess {
                settings.setGbExportUri(uri.toString())
                gbUri = uri.toString()
                message = "Gadgetbridge 数据文件已连接，点击“立即同步”读取。"
            }.onFailure { message = "文件授权或读取失败，请重新选择 Gadgetbridge 导出文件：${it.message.orEmpty()}" }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        val allowed = granted.containsAll(HealthConnectReader.permissionsFor(sleepEnabled, exerciseEnabled))
        connectState = if (allowed) HealthConnectState.Ready else HealthConnectState.NeedsPermission
        message = if (allowed) null else "所选数据尚未全部授权；已连接的 Gadgetbridge 文件仍可独立同步。"
    }
    LaunchedEffect(reader, sleepEnabled, exerciseEnabled) { connectState = reader.state(sleepEnabled, exerciseEnabled) }
    fun requestPermissions() { permissionLauncher.launch(HealthConnectReader.permissionsFor(sleepEnabled, exerciseEnabled)) }
    fun syncNow() {
        message = null
        model.runHealthSync(reader, settings) { outcome ->
            message = when {
                outcome != null -> outcome.summary()
                else -> "同步失败：${model.error.value ?: "未知错误"}"
            }
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).testTag("health-sync-screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("健康数据同步", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("睡眠和健身记录自动进入时间轴", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onClose) { Text("关闭") }
        }
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag("health-sync-status")) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val statusText = when (connectState) {
                    null -> "正在检测 Health Connect…"
                    HealthConnectState.Unavailable -> "本机 Health Connect 不可用：需要 Android 14 以上系统或安装 Health Connect by Android。"
                    HealthConnectState.NeedsPermission -> "需要读取授权后才能同步睡眠与健身记录。"
                    HealthConnectState.Ready -> "Health Connect 已就绪，可读取睡眠与健身记录。"
                }
                Text(statusText, style = MaterialTheme.typography.bodyMedium)
                Text("来源应用无关：小米运动健康、华为、Keep、训记等写入 Health Connect 的记录都会同步。数据只在本机读取，不上传。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (connectState == HealthConnectState.NeedsPermission || connectState == HealthConnectState.Ready) {
                    TextButton(onClick = ::requestPermissions) { Text(if (connectState == HealthConnectState.NeedsPermission) "去授权读取" else "管理授权") }
                }
            }
        }
        SettingSwitch("自动同步", "打开应用时静默同步一次；失败不打扰", auto, Modifier.testTag("health-sync-auto")) { value ->
            auto = value
            settings.setEnabled(value)
            if (value && connectState == HealthConnectState.NeedsPermission) requestPermissions()
        }
        SettingSwitch("同步睡眠", "按 Health Connect 睡眠时段生成“睡眠”记录", sleepEnabled, Modifier.testTag("health-sync-sleep")) { value -> sleepEnabled = value; settings.setSleepEnabled(value) }
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().testTag("gb-file-card")) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Gadgetbridge 数据文件（推荐）", fontWeight = FontWeight.Medium)
                Text("让 Gadgetbridge 把数据库导出到文件后，本应用直接读取，睡眠经夜间聚合后进入时间轴——不依赖 Health Connect。在 Gadgetbridge 设置 → 自动化 → 自动导出里启用并把位置设为同一文件。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (gbUri.isBlank()) "未连接数据文件" else "已连接数据文件", style = MaterialTheme.typography.bodySmall, color = if (gbUri.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("gb-file-state"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { gbPicker.launch(arrayOf("*/*")) }, enabled = !busy) { Text(if (gbUri.isBlank()) "选择 GB 导出文件" else "更换文件") }
                    if (gbUri.isNotBlank()) TextButton(onClick = { settings.setGbExportUri(""); gbUri = "" }, enabled = !busy) { Text("断开") }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("睡眠来源偏好", style = MaterialTheme.typography.labelLarge)
            Text("选中的来源会替换其他来源记录的同一晚睡眠；手工修改过的记录始终保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("" to "先到先得", "华为运动健康" to "华为", "小米运动健康" to "小米", "Gadgetbridge" to "Gadgetbridge").forEach { (value, label) ->
                    FilterChip(
                        selected = sleepPreferred == value,
                        onClick = { sleepPreferred = value; settings.setPreferredSleepOrigin(value) },
                        label = { Text(label) },
                        modifier = Modifier.testTag("sleep-preference-${label}"),
                    )
                }
            }
        }
        SettingSwitch("同步健身记录", "训练课自动成为实际记录，标题优先用来源训练名", exerciseEnabled, Modifier.testTag("health-sync-exercise")) { value -> exerciseEnabled = value; settings.setExerciseEnabled(value) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("同步范围（最近）", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 14, 30).forEach { days ->
                    FilterChip(selected = windowDays == days, onClick = { windowDays = days; settings.setWindowDays(days) }, label = { Text("${days} 天") }, modifier = Modifier.testTag("health-sync-window-$days"))
                }
            }
        }
        Button(
            onClick = ::syncNow, enabled = !busy && (sleepEnabled || exerciseEnabled) && (connectState == HealthConnectState.Ready || (sleepEnabled && gbUri.isNotBlank())),
            modifier = Modifier.fillMaxWidth().testTag("health-sync-now"),
        ) { Text(if (busy) "同步中…" else "立即同步") }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("health-sync-message")) }
        if (settings.lastSyncAt() > 0) Text("最近读取：${Instant.ofEpochMilli(settings.lastSyncAt()).label(zone)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("已同步条目", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (data.healthItems.isEmpty()) Text("还没有同步过记录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        data.healthItems.forEach { item ->
            val record = item.recordId?.let { id -> data.records.firstOrNull { it.id == id } }
            val stateText = when {
                record == null -> "已删除 · 不再自动恢复"
                record.isManuallyEdited -> "已手工修改 · 同步保护"
                else -> "跟随来源"
            }
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${item.title} · ${healthOriginLabel(item.originPackage)}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text("${Instant.ofEpochMilli(item.startAt).label(zone)} — ${Instant.ofEpochMilli(item.endAt).label(zone)} · $stateText", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(70.dp))
    }
}

@Composable private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Surface(onClick = { onChange(!checked) }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
