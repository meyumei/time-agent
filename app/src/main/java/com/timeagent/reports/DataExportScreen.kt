package com.timeagent.reports

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.timeagent.ui.components.AppTopBar
import com.timeagent.ui.components.StatusBanner
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class ExportRangePreset(val label: String) {
    LAST_7("最近 7 天"),
    LAST_30("最近 30 天"),
    LAST_90("最近 90 天"),
    LAST_YEAR("最近 1 年"),
    CUSTOM("自定义"),
}

enum class ExportPhase { IDLE, RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class ExportUiState(
    val format: ExportFormat = ExportFormat.CSV_ZIP,
    val preset: ExportRangePreset = ExportRangePreset.LAST_30,
    val start: LocalDate,
    val end: LocalDate,
    val types: Set<ExportDataType> = ExportDataType.entries.toSet(),
    val phase: ExportPhase = ExportPhase.IDLE,
    val progress: Int = 0,
    val stage: String = "",
    val message: String? = null,
    val error: String? = null,
    val workId: UUID? = null,
    val fileName: String = "",
)

class ExportModel(
    private val context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context),
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val zone: ZoneId = clock.zone
    private val today: LocalDate = Instant.ofEpochMilli(clock.millis()).atZone(zone).toLocalDate()
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(
        ExportUiState(
            start = today.minusDays(29),
            end = today,
            fileName = defaultFileName(ExportFormat.CSV_ZIP, today.minusDays(29), today),
        ),
    )
    val state = _state.asStateFlow()

    fun setFormat(format: ExportFormat) {
        _state.update {
            it.copy(
                format = format,
                types = if (format == ExportFormat.ICS) setOf(ExportDataType.SCHEDULE) else it.types,
                fileName = defaultFileName(format, it.start, it.end),
                error = null,
            )
        }
    }

    fun setPreset(preset: ExportRangePreset) {
        if (preset == ExportRangePreset.CUSTOM) {
            _state.update { it.copy(preset = preset) }
            return
        }
        val end = today
        val start = when (preset) {
            ExportRangePreset.LAST_7 -> end.minusDays(6)
            ExportRangePreset.LAST_30 -> end.minusDays(29)
            ExportRangePreset.LAST_90 -> end.minusDays(89)
            ExportRangePreset.LAST_YEAR -> end.minusDays(364)
            ExportRangePreset.CUSTOM -> end.minusDays(29)
        }
        _state.update {
            it.copy(
                preset = preset,
                start = start,
                end = end,
                fileName = defaultFileName(it.format, start, end),
                error = null,
            )
        }
    }

    fun setCustomRange(start: LocalDate, end: LocalDate) {
        if (end < start) {
            _state.update { it.copy(error = "结束日期不能早于开始日期。") }
            return
        }
        _state.update {
            it.copy(
                preset = ExportRangePreset.CUSTOM,
                start = start,
                end = end,
                fileName = defaultFileName(it.format, start, end),
                error = null,
            )
        }
    }

    fun toggleType(type: ExportDataType) {
        val current = _state.value
        if (current.format == ExportFormat.ICS) return
        val next = if (type in current.types) current.types - type else current.types + type
        if (next.isEmpty()) return
        _state.update { it.copy(types = next, error = null) }
    }

    fun suggestedFileName(): String = _state.value.fileName

    fun startExport(uri: Uri) {
        val current = _state.value
        if (current.phase == ExportPhase.RUNNING) return
        if (current.types.isEmpty()) {
            _state.update { it.copy(error = "请至少选择一种数据类型。") }
            return
        }
        val startAt = current.start.atStartOfDay(zone).toInstant().toEpochMilli()
        val endAt = current.end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val request = OneTimeWorkRequestBuilder<ExportWorker>()
            .setInputData(
                workDataOf(
                    ExportWorker.KEY_URI to uri.toString(),
                    ExportWorker.KEY_FORMAT to current.format.name,
                    ExportWorker.KEY_TYPES to current.types.map { it.name }.toTypedArray(),
                    ExportWorker.KEY_START_AT to startAt,
                    ExportWorker.KEY_END_AT to endAt,
                    ExportWorker.KEY_FILE_NAME to current.fileName,
                ),
            )
            .addTag("report-export")
            .build()
        workManager.enqueue(request)
        _state.update {
            it.copy(
                phase = ExportPhase.RUNNING,
                progress = 0,
                stage = "已加入队列",
                message = null,
                error = null,
                workId = request.id,
            )
        }
        observe(request.id)
    }

    fun cancelExport() {
        val id = _state.value.workId ?: return
        workManager.cancelWorkById(id)
        _state.update { it.copy(stage = "正在取消，完成后会清理半成品文件…") }
    }

    fun dismissResult() {
        _state.update { it.copy(message = null, error = null) }
    }

    private fun observe(id: UUID) {
        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(id).collect { info ->
                if (info == null) return@collect
                val progress = info.progress.getInt(ExportWorker.KEY_PROGRESS, _state.value.progress)
                val stage = info.progress.getString(ExportWorker.KEY_STAGE).orEmpty()
                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING -> {
                        _state.update {
                            it.copy(
                                phase = ExportPhase.RUNNING,
                                progress = progress.coerceIn(0, 100),
                                stage = stage.ifBlank { "正在准备…" },
                            )
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        _state.update {
                            it.copy(
                                phase = ExportPhase.SUCCEEDED,
                                progress = 100,
                                stage = "导出完成",
                                message = "已写入所选位置。导出文件不包含 API Key、平台凭据或通知监听原文。",
                                error = null,
                            )
                        }
                        return@collect
                    }
                    WorkInfo.State.FAILED -> {
                        _state.update {
                            it.copy(
                                phase = ExportPhase.FAILED,
                                stage = "导出失败",
                                error = info.outputData.getString(ExportWorker.KEY_ERROR)
                                    ?: "导出失败，请检查所选位置及清理提示后重试。",
                            )
                        }
                        return@collect
                    }
                    WorkInfo.State.CANCELLED -> {
                        _state.update {
                            it.copy(
                                phase = ExportPhase.CANCELLED,
                                stage = "已取消",
                                message = "已取消，正在清理未完成文件；若文件提供方拒绝清理，会显示处理提示。",
                                error = null,
                            )
                        }
                        return@collect
                    }
                }
            }
        }
    }

    private fun defaultFileName(format: ExportFormat, start: LocalDate, end: LocalDate): String {
        val dates = if (start == end) start.toString() else "${start}_${end}"
        return "time-agent-export-$dates.${format.extension}"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DataExportScreen(model: ExportModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val exportPrefs = remember(context) { context.getSharedPreferences("export_status", Context.MODE_PRIVATE) }
    var cleanupError by remember { mutableStateOf(exportPrefs.getString("cleanup_error", null)) }
    DisposableEffect(exportPrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
            if (key == "cleanup_error") cleanupError = preferences.getString("cleanup_error", null)
        }
        exportPrefs.registerOnSharedPreferenceChangeListener(listener)
        cleanupError = exportPrefs.getString("cleanup_error", null)
        onDispose { exportPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val state by model.state.collectAsStateWithLifecycle()
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(state.format.mimeType)) { uri ->
        if (uri != null) model.startExport(uri)
    }

    Scaffold(topBar = { AppTopBar("导出数据", onClose) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusBanner(
                "导出用于查看或迁移到别的工具，不保证可完整恢复；需要恢复请使用“备份”。" +
                    "导出在本机流式生成，期间可随时取消。",
            )

            Text("格式", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportFormat.entries.forEach { format ->
                    FilterChip(
                        selected = state.format == format,
                        onClick = { model.setFormat(format) },
                        enabled = state.phase != ExportPhase.RUNNING,
                        label = { Text(format.label) },
                    )
                }
            }
            Text(
                when (state.format) {
                    ExportFormat.CSV_ZIP -> "每类数据一个 CSV，全部打包为一个 zip；记账 CSV 沿用现有账本导出格式。"
                    ExportFormat.JSON -> "完整结构写入单个 JSON，含 schemaVersion 和导出范围。"
                    ExportFormat.ICS -> "ICS 仅导出日程，可在系统日历中打开；其他数据类型不参与。"
                },
                style = MaterialTheme.typography.bodySmall,
            )

            Text("时间范围", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportRangePreset.entries.forEach { preset ->
                    FilterChip(
                        selected = state.preset == preset,
                        onClick = { model.setPreset(preset) },
                        enabled = state.phase != ExportPhase.RUNNING,
                        label = { Text(preset.label) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { showStartPicker = true },
                    enabled = state.phase != ExportPhase.RUNNING,
                ) {
                    Text("开始 ${state.start}")
                }
                OutlinedButton(
                    onClick = { showEndPicker = true },
                    enabled = state.phase != ExportPhase.RUNNING,
                ) {
                    Text("结束 ${state.end}")
                }
            }
            Text("包含首尾两天；实际抓取按设备时区从开始日 00:00 到结束日次日 00:00。", style = MaterialTheme.typography.bodySmall)

            Text("数据类型", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportDataType.entries.forEach { type ->
                    val disabled = state.format == ExportFormat.ICS && type != ExportDataType.SCHEDULE
                    FilterChip(
                        selected = type in state.types,
                        onClick = { model.toggleType(type) },
                        enabled = !disabled && state.phase != ExportPhase.RUNNING,
                        label = { Text(type.label) },
                    )
                }
            }
            Text(
                "已选 ${state.types.size} 类 · 不包含 API Key、平台凭据、通知监听原文；记账只导出已解析账目。",
                style = MaterialTheme.typography.bodySmall,
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("将生成", style = MaterialTheme.typography.labelLarge)
                    Text(state.fileName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        "${state.format.label} · ${state.start} 至 ${state.end} · ${state.types.size} 类数据",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (state.phase == ExportPhase.RUNNING) {
                        LinearProgressIndicator(
                            progress = { state.progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${state.progress}% · ${state.stage}", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = model::cancelExport) { Text("取消导出") }
                    } else {
                        Button(
                            onClick = { launcher.launch(model.suggestedFileName()) },
                            enabled = state.types.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("选择保存位置并开始导出")
                        }
                    }
                }
            }

            state.message?.let { StatusBanner(it) }
            state.error?.let { StatusBanner(it) }
            cleanupError?.let {
                StatusBanner(it)
                TextButton(onClick = { cleanupError = null; exportPrefs.edit().remove("cleanup_error").apply() }) {
                    Text("已知晓清理提示")
                }
            }
            if (state.phase == ExportPhase.SUCCEEDED || state.phase == ExportPhase.FAILED || state.phase == ExportPhase.CANCELLED) {
                TextButton(onClick = model::dismissResult) { Text("知道了") }
            }
            HorizontalDivider()
            Text(
                "取消时会清理缓存和未完成文件。若文件提供方拒绝清理，会提示你到所选位置手动删除。" +
                    "大于 2 秒的导出会显示进度通知，可在通知中取消。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showStartPicker) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = state.start.toEpochMillisUtc())
        DatePickerDialog(
            onDismissRequest = { showStartPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { model.setCustomRange(it.toLocalDateUtc(), state.end) }
                    showStartPicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showStartPicker = false }) { Text("取消") } },
        ) {
            DatePicker(state = picker)
        }
    }
    if (showEndPicker) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = state.end.toEpochMillisUtc())
        DatePickerDialog(
            onDismissRequest = { showEndPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { model.setCustomRange(state.start, it.toLocalDateUtc()) }
                    showEndPicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showEndPicker = false }) { Text("取消") } },
        ) {
            DatePicker(state = picker)
        }
    }
}

private fun LocalDate.toEpochMillisUtc(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toLocalDateUtc(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

private val exportDateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
