package com.timeagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.timeagent.calendar.CalendarHandoff
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarHandoffScreen(title: String, startAt: Long, endAt: Long, onClose: () -> Unit) {
    val context = LocalContext.current
    var description by remember(title, startAt, endAt) { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val format = remember { DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.systemDefault()) }
    BackHandler { onClose() }
    Scaffold(topBar = { TopAppBar(title = { Text("交给系统日历") }, navigationIcon = { TextButton(onClick = onClose) { Text("返回") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text("${format.format(Instant.ofEpochMilli(startAt))} — ${format.format(Instant.ofEpochMilli(endAt))}")
            Text("打开后请在系统日历中确认并保存。此处无法核实是否保存。")
            OutlinedTextField(description, { description = it }, label = { Text("日历说明（选填）") }, modifier = Modifier.fillMaxWidth().testTag("calendar-description"), minLines = 3)
            Button(onClick = { message = CalendarHandoff.open(context, title, startAt, endAt, description) }, modifier = Modifier.testTag("calendar-open")) { Text("打开系统日历确认") }
            message?.let { Text(it, modifier = Modifier.testTag("calendar-handoff-result")) }
        }
    }
}
