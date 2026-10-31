package com.timeagent.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.timeagent.attachments.AttachmentOcr
import com.timeagent.attachments.AttachmentService
import com.timeagent.data.AttachmentEntity
import com.timeagent.data.LocalSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentScreen(model: TimeViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val data by model.data.collectAsState()
    val busy by model.busy.collectAsState()
    val error by model.error.collectAsState()
    val service = remember(model, context) { AttachmentService(context, model.repository) }
    val ocr = remember(context) { AttachmentOcr(context.applicationContext) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var trash by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var extracting by remember { mutableStateOf<String?>(null) }
    val selected = data.attachments.find { it.id == selectedId }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.perform {
            val imported = service.importDocument(uri)
            trash = false
            selectedId = imported.id
        }
    }
    if (extracting != null) {
        ChatExtractionScreen(model, extracting!!, onClose = { extracting = null })
        return
    }
    BackHandler(enabled = !busy) { if (selectedId != null) selectedId = null else onClose() }
    Scaffold(topBar = { TopAppBar(title = { Text(if (selected != null) "附件详情" else "附件收件箱") }, navigationIcon = {
        TextButton(onClick = { if (selectedId != null) selectedId = null else onClose() }, enabled = !busy) { Text("返回") }
    }) }) { padding ->
        if (selected != null) {
            AttachmentDetails(selected, data, service, ocr, model, busy, error,
                modifier = Modifier.padding(padding), onExtract = { extracting = it }, onRemoved = { selectedId = null })
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                item {
                    Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.testTag("attachment-import")) { Text("选择文件或截图") }
                    Text("主动选取后复制到应用私有存储。单件最多8 MiB，总空间12 MiB。图片可识别为可修正草稿。", style = MaterialTheme.typography.bodySmall)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!trash, { trash = false }, label = { Text("全部附件") })
                        FilterChip(trash, { trash = true }, label = { Text("回收站") })
                    }
                    OutlinedTextField(query, { query = it }, label = { Text("筛选文件名或草稿") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (error != null) item { Text(error!!, color = MaterialTheme.colorScheme.error) }
                val visible = data.attachments.filter { (it.deletedAt != null) == trash && (query.isBlank() || it.originalName.contains(query, true) || it.ocrText.contains(query, true)) }.sortedByDescending { it.createdAt }
                if (visible.isEmpty()) item { Text(if (trash) "回收站为空" else "还没有匹配的附件") }
                items(visible, key = { it.id }) { item ->
                    Card(onClick = { selectedId = item.id }, modifier = Modifier.fillMaxWidth().testTag("attachment-${item.id}")) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(item.originalName, style = MaterialTheme.typography.titleMedium)
                            Text("${item.mimeType} · ${item.sizeBytes / 1024} KiB", style = MaterialTheme.typography.bodySmall)
                            Text(attachmentLinkLabel(item, data), style = MaterialTheme.typography.bodySmall)
                            if (item.purgedAt != null) Text("原件已清除 · 文字保留", style = MaterialTheme.typography.bodySmall)
                            if (item.ocrText.isNotBlank()) Text(item.ocrText, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentDetails(item: AttachmentEntity, data: LocalSnapshot, service: AttachmentService, ocr: AttachmentOcr, model: TimeViewModel, busy: Boolean, error: String?, modifier: Modifier, onExtract: (String) -> Unit, onRemoved: () -> Unit) {
    val context = LocalContext.current
    var draft by remember(item.id, item.version) { mutableStateOf(item.ocrText) }
    var fileAvailable by remember(item.id, item.version) { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    var ocrBusy by remember(item.id) { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    var purge by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    val dirty = draft != item.ocrText
    BackHandler(enabled = !busy && !ocrBusy && dirty) { discard = true }
    LaunchedEffect(item.id, item.version) { fileAvailable = service.readableFile(item) != null }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(item.originalName, style = MaterialTheme.typography.titleLarge)
        Text(attachmentLinkLabel(item, data))
        if (fileAvailable == false) Text(if (item.purgedAt != null) "原件已清除；文字草稿和条目信息仍保留。" else "原件缺失或损坏；仍可查看已保存的文字草稿。", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("attachment-missing"))
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        if (notice != null) Text(notice!!)
        if (item.deletedAt == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { model.perform {
                    val file = service.readableFile(item) ?: kotlin.error("原件缺失或损坏")
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)
                    try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    catch (_: ActivityNotFoundException) { model.report("没有可打开此文件的应用") }
                } }, enabled = !busy && fileAvailable == true) { Text("查看原件") }
                OutlinedButton(onClick = { linking = true }, enabled = !busy && !ocrBusy) { Text("修改关联") }
            }
            if (AttachmentOcr.supports(item.mimeType)) {
                Button(onClick = {
                    ocrBusy = true
                    scope.launch {
                        try {
                            val file = service.readableFile(item) ?: kotlin.error("原件缺失或损坏，无法识别")
                            draft = ocr.recognize(file)
                            notice = if (draft.isBlank()) "图片中未识别到文字，可手动填写。" else "已生成草稿，请核对修正后保存。"
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            model.report(failure.message ?: "图片文字识别失败，请重试")
                        } finally { ocrBusy = false }
                    }
                }, enabled = !busy && !ocrBusy && fileAvailable == true, modifier = Modifier.testTag("attachment-ocr")) { Text(if (ocrBusy) "识别中…" else "识别图片文字") }
            } else Text("此类型可保存附件；文字识别仅支持图片。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(draft, { draft = it }, label = { Text("文字草稿（可修正）") }, minLines = 5, modifier = Modifier.fillMaxWidth().testTag("attachment-draft"), enabled = !busy && !ocrBusy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.perform(onSuccess = { notice = "草稿已保存" }) { service.saveDraft(item.id, draft, item.version) } }, enabled = !busy && !ocrBusy && dirty, modifier = Modifier.testTag("attachment-save-draft")) { Text("保存草稿") }
                OutlinedButton(onClick = { onExtract(draft) }, enabled = !busy && !ocrBusy && draft.isNotBlank()) { Text("提取候选事项") }
            }
            Text("提取后还需逐条确认，附件不会自动归属到项目或任务。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { model.perform(onSuccess = onRemoved) { service.trash(item.id, item.version) } }, enabled = !busy && !ocrBusy) { Text("移入回收站") }
        } else {
            Text(if (item.purgedAt != null) "原件已清除；恢复仅恢复条目与文字。" else "回收站保留原件；恢复后可继续编辑。")
            Button(onClick = { model.perform(onSuccess = onRemoved) { service.restore(item.id, item.version) } }, enabled = !busy) { Text("恢复附件") }
            TextButton(onClick = { purge = true }, enabled = !busy && item.purgedAt == null) { Text("彻底清除原件") }
        }
    }
    if (linking) AttachmentLinkDialog(item, data, busy, onDismiss = { linking = false }) { projectId, taskId ->
        model.perform(onSuccess = { linking = false }) { service.link(item.id, projectId, taskId, item.version) }
    }
    if (purge) AlertDialog(onDismissRequest = { purge = false }, title = { Text("彻底清除附件原件？") }, text = { Text("应用内的原文件将永久删除，无法撤销。文字草稿和条目信息仍保留。重新导入或恢复含此原件的备份才能找回原文件；原选取位置的文件不受影响。") }, confirmButton = { TextButton(onClick = { model.perform(onSuccess = { purge = false; onRemoved() }) { service.purge(item.id, item.version) } }, enabled = !busy) { Text("永久清除") } }, dismissButton = { TextButton(onClick = { purge = false }) { Text("取消") } })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的草稿？") }, text = { Text("识别或修正的文字尚未保存。") }, confirmButton = { TextButton(onClick = { discard = false; onRemoved() }) { Text("放弃并返回") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
}

private fun attachmentLinkLabel(item: AttachmentEntity, data: LocalSnapshot): String = when {
    item.taskId != null -> "任务：${data.tasks.find { it.id == item.taskId && it.deletedAt == null }?.title ?: "原任务已删除"}"
    item.projectId != null -> "项目：${data.projects.find { it.id == item.projectId && it.deletedAt == null }?.name ?: "原项目已删除"}"
    else -> "未关联"
}

@Composable
private fun AttachmentLinkDialog(item: AttachmentEntity, data: LocalSnapshot, busy: Boolean, onDismiss: () -> Unit, onSave: (String?, String?) -> Unit) {
    var projectId by remember { mutableStateOf(item.projectId) }
    var taskId by remember { mutableStateOf(item.taskId) }
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("明确选择关联对象") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("选择项目或任务中的一个，保存后生效。")
            OutlinedTextField(query, { query = it }, label = { Text("筛选名称") }, singleLine = true)
            FilterChip(projectId == null && taskId == null, { projectId = null; taskId = null }, label = { Text("未关联") })
            data.projects.filter { it.deletedAt == null && it.name.contains(query, true) }.forEach { project ->
                FilterChip(projectId == project.id, { projectId = project.id; taskId = null }, label = { Text("项目 · ${project.name}") })
            }
            data.tasks.filter { it.deletedAt == null && it.status != "CANCELLED" && it.title.contains(query, true) }.forEach { task ->
                FilterChip(taskId == task.id, { taskId = task.id; projectId = null }, label = { Text("任务 · ${task.title}") })
            }
        }
    }, confirmButton = { TextButton(onClick = { onSave(projectId, taskId) }, enabled = !busy) { Text("保存关联") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
}


