package com.timeagent.quickentry

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.timeagent.data.ScheduleBlockEntity
import com.timeagent.data.TimeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun CaptureTemplateDialog(repository: TimeRepository, plans: List<ScheduleBlockEntity>, onClose: () -> Unit) {
    val context = LocalContext.current
    val quick = remember(repository) { QuickEntryRepository(repository, context.applicationContext) }
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var requestId by rememberSaveable { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onClose() },
        title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_capture_template_dialog_001)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_capture_template_dialog_002)) }, modifier = Modifier.fillMaxWidth())
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_capture_template_dialog_003))
                plans.forEach { Text(it.title.ifBlank { "日程" }) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank(), onClick = {
                scope.launch {
                    busy = true; error = null
                    try { quick.saveSelectionAsTemplate(name, plans, requestId = requestId); onClose() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message }
                    finally { busy = false }
                }
            }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_capture_template_dialog_004)) }
        }, dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } })
}
