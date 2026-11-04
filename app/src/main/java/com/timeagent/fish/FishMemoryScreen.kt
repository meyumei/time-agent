package com.timeagent.fish

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink

@Composable
fun FishMemoryScreen(session: FishSession, onClose: () -> Unit) {
    BackHandler(onBack=onClose)
    val memories by session.memories.collectAsStateWithLifecycle()
    val discoveries by session.discoveries.collectAsStateWithLifecycle()
    val loading by session.memoryLoading.collectAsStateWithLifecycle()
    val message by session.message.collectAsStateWithLifecycle()
    val runtime by session.runtime.state.collectAsStateWithLifecycle()
    val readOnly by session.memoryReadOnly.collectAsStateWithLifecycle()
    val history by session.memoryHistory.collectAsStateWithLifecycle()
    val lastChange by session.memoryLastChange.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showHistory by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<FishMemory?>(null) }
    var deleting by remember { mutableStateOf<FishMemory?>(null) }
    var editedText by remember { mutableStateOf("") }
    LaunchedEffect(session) { session.loadMemories() }
    LaunchedEffect(lastChange) {
        val change = lastChange ?: return@LaunchedEffect
        val result = kotlinx.coroutines.withTimeoutOrNull(8_000) {
            snackbar.showSnackbar(if (change.second) "共同记忆已恢复" else "共同记忆已更新",
                if (change.second) "重做" else "撤销", duration = SnackbarDuration.Indefinite)
        }
        if (result == SnackbarResult.ActionPerformed) session.restoreMemory(change.first, !change.second)
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        TextButton(onClick=onClose) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_memory_screen_001)) }
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_memory_screen_002),style=MaterialTheme.typography.headlineSmall)
        TextButton(onClick = { showHistory = true }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_memory_screen_003)) }
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_memory_screen_004),style=MaterialTheme.typography.bodySmall)
        if (readOnly && !runtime.ready) Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_memory_screen_005),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query,{query=it},modifier=Modifier.weight(1f),placeholder={Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_memory_screen_006))},singleLine=true)
            Button(onClick={session.loadMemories(query)},enabled=!loading) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_memory_screen_007)) }
        }
        if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(discoveries.isNotEmpty()) item { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_memory_screen_008),style=MaterialTheme.typography.titleSmall) }
            items(discoveries) { source ->
                Text(buildAnnotatedString { withLink(LinkAnnotation.Url(source.url)) { append(source.title) } })
            }
            if(!loading && memories.isEmpty()) item { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_memory_screen_009)) }
            items(memories,key={it.id}) { memory ->
                Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(memory.text);Text(memory.tier,style=MaterialTheme.typography.labelSmall)
                        Row {
                            TextButton(enabled = runtime.ready && !loading, onClick = { editing = memory; editedText = memory.text }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_edit)) }
                            TextButton(enabled = runtime.ready && !loading, onClick = { deleting = memory }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_delete)) }
                        }
                    }
                }
            }
        }
    }
    SnackbarHost(snackbar, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).navigationBarsPadding())
    }
    if (showHistory) AlertDialog(onDismissRequest = { showHistory = false },
        title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_memory_screen_012)) },
        text = { LazyColumn(Modifier.heightIn(max = 420.dp)) {
            if (history.isEmpty()) item { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_memory_screen_013)) }
            items(history, key = { it.token }) { change ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(change.text)
                    Text(if (change.undone) "已撤销" else if (change.deleted) "已删除" else "已编辑",
                        style = MaterialTheme.typography.labelSmall)
                    TextButton(enabled = runtime.ready && !loading, onClick = {
                        session.restoreMemory(change.token, !change.undone)
                    }) { Text(if (change.undone) "重做" else "恢复原记忆") }
                }
            }
        } }, confirmButton = { TextButton(onClick = { showHistory = false }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_close)) } })
    editing?.let { memory ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_memory_screen_014)) },
            text = { OutlinedTextField(editedText, { editedText = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_memory_screen_015)) }) },
            confirmButton = { TextButton(enabled = runtime.ready && editedText.isNotBlank(), onClick = {
                session.editMemory(memory, editedText); editing = null
            }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } })
    }
    deleting?.let { memory ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_memory_screen_016)) },
            text = { Text(memory.text) },
            confirmButton = { TextButton(enabled = runtime.ready, onClick = { session.deleteMemory(memory); deleting = null }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_cancel)) } })
    }
}
