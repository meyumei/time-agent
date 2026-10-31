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
    var query by remember { mutableStateOf("") }
    LaunchedEffect(session) { session.loadMemories() }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        TextButton(onClick=onClose) { Text("返回管家") }
        Text("共同记忆",style=MaterialTheme.typography.headlineSmall)
        Text("这里是同一个角色保存的交流与经历。日程事实仍以今天、计划中的原记录为准。",style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query,{query=it},modifier=Modifier.weight(1f),placeholder={Text("寻找一段共同经历")},singleLine=true)
            Button(onClick={session.loadMemories(query)},enabled=!loading) { Text("查找") }
        }
        if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(discoveries.isNotEmpty()) item { Text("最近的 B站见闻 · 公开来源",style=MaterialTheme.typography.titleSmall) }
            items(discoveries) { source ->
                Text(buildAnnotatedString { withLink(LinkAnnotation.Url(source.url)) { append(source.title) } })
            }
            if(!loading && memories.isEmpty()) item { Text("还没有找到记忆。先和它聊聊，真实的共同经历会逐渐留下来。") }
            items(memories,key={it.id}) { memory ->
                Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) { Text(memory.text);Text(memory.tier,style=MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}
