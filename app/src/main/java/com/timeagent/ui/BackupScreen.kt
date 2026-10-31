package com.timeagent.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.timeagent.MainActivity
import com.timeagent.backup.*
import com.timeagent.reminders.ReminderCoordinator
import com.timeagent.workout.WorkoutNotifications
import com.timeagent.workout.data.WorkoutRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(model: TimeViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val busy by model.busy.collectAsState()
    val service = remember(model, context) { PersonalBackup(context, model.repository.database) }
    var exportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var exportSummary by remember { mutableStateOf("") }
    var restoreBytes by remember { mutableStateOf<ByteArray?>(null) }
    var preview by remember { mutableStateOf<BackupPreview?>(null) }
    var approved by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf(context.getSharedPreferences("backup_status", 0).getString("result", null)) }
    var restored by remember { mutableStateOf(false) }
    fun close() {
        if (restored) context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) else onClose()
    }
    BackHandler { if (!busy) close() }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val bytes = exportBytes
        exportBytes = null
        if (uri != null && bytes != null) model.perform {
            withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes); it.flush() } }
            result = "本地备份已保存（${bytes.size} 字节），包含主库、训练库、自定义动作和个人设置。$exportSummary 文件内含个人记录与附件，请妥善保存。"
        }
    }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.perform {
            val bytes = withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= PersonalBackup.MAX_BYTES) { "备份超过 32 MiB" }; output.write(buffer, 0, count) }
                    output.toByteArray()
                }
            }
            val checked = withContext(Dispatchers.IO) { service.preview(bytes) }
            restoreBytes = bytes; preview = checked; approved = false; result = null
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("完整本地备份 / 恢复") }, navigationIcon = { IconButton(onClick = ::close, enabled = !busy) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("任务、项目、周目标、周期实例、实际、课程、想法、笔记、提醒、附件与操作历史，连同训练记录、自定义动作和个人设置一起保存。")
            Text("动作媒体和 3D 模型随安装包提供。密钥、云端授权和手机权限不写进个人备份；恢复后需重选 GB 文件，并核对健康、通知和闹钟授权。", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { model.perform {
                val bytes = service.create()
                val checked = withContext(Dispatchers.IO) { service.preview(bytes) }
                exportSummary = "包含附件原件 ${checked.attachmentFileCount} 份；缺失 ${checked.missingAttachmentNames.size} 份。" + if (checked.missingAttachmentNames.isEmpty()) "" else "缺失项只保存条目与文字：${checked.missingAttachmentNames.joinToString("、")}。"
                exportBytes = bytes
                export.launch("time-agent-personal-${model.repository.now().atZone(model.zone).toLocalDate()}.json")
            } }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("backup-export")) { Text("导出本地备份") }
            OutlinedButton(onClick = { choose.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("backup-select")) { Text("选择备份，先校验和预览") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            preview?.let { item ->
                HorizontalDivider()
                Text("文件完整性、版本、字段及数据关联校验通过", style = MaterialTheme.typography.titleMedium)
                Text("备份日期 ${Instant.ofEpochMilli(item.createdAt).label(model.zone)} · 应用 ${item.appVersion}\n主库 v${item.mainVersion} · 训练库 v${item.workoutVersion}", style = MaterialTheme.typography.bodySmall)
                val labels = mapOf("personal_memories" to "个人记忆", "tasks" to "任务（含历史状态）", "schedule_blocks" to "计划（含取消项）", "time_records" to "实际记录", "course_occurrences" to "课程实例", "notes" to "笔记", "ideas" to "想法", "reminders" to "提醒", "operation_history" to "操作历史", "health_sync_items" to "同步与保护标记", "workout_sessions" to "训练记录", "workout_templates" to "训练模板")
                (item.mainCounts + item.workoutCounts).forEach { (table, count) -> labels[table]?.let { Text("$it：$count", style = MaterialTheme.typography.bodySmall) } }
                Text("自定义动作：${item.customExerciseCount}", style = MaterialTheme.typography.bodySmall)
                Text("附件原件：${item.attachmentFileCount} 份；缺失：${item.missingAttachmentNames.size} 份", style = MaterialTheme.typography.bodySmall)
                if (item.missingAttachmentNames.isNotEmpty()) Text("仅恢复条目与文字，原件缺失：${item.missingAttachmentNames.joinToString("、")}", color = MaterialTheme.colorScheme.error)
                if (item.unlinkedNoteCount > 0) Text("暂未关联笔记：${item.unlinkedNoteCount} 条，将完整保留；撤销恢复原事项后会重新关联。", style = MaterialTheme.typography.bodySmall)
                Text("恢复会把本机两套数据库、附件原件和个人设置替换为这份备份的内容。建议先导出当前副本；恢复不会合并记录。", color = MaterialTheme.colorScheme.error)
                Row { Checkbox(approved, { approved = it }, enabled = !busy, modifier = Modifier.testTag("backup-approve")); Text("我已核对内容，确认替换本机数据", Modifier.padding(top = 12.dp)) }
                Button(onClick = {
                    val bytes = restoreBytes ?: return@Button
                    model.perform {
                        model.prepareForRestore()
                        val outcome = service.restore(bytes)
                        result = outcome.message; restored = true; preview = null; restoreBytes = null; approved = false
                        try {
                            ReminderCoordinator(context, model.repository).refresh()
                            val active = WorkoutRepository(context, model.repository).activeSession()
                            WorkoutNotifications.sync(context, active)
                        } catch (failure: Exception) {
                            if (failure is kotlinx.coroutines.CancellationException) throw failure
                            result = outcome.message + "\n数据已恢复，提醒重新登记未完成：${failure.message}。返回应用后会再次登记。"
                        }
                    }
                }, enabled = approved && !busy, modifier = Modifier.fillMaxWidth().testTag("backup-restore")) { Text("确认恢复") }
            }
            result?.let { Text(it, Modifier.testTag("backup-result"), color = MaterialTheme.colorScheme.primary) }
            if (restored) Button(onClick = ::close) { Text("完成并返回") }
        }
    }
}
