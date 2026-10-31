package com.timeagent.fish

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject

@Composable
fun FishProactiveScreen(session: FishSession, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val event by session.proactive.collectAsStateWithLifecycle()
    val loading by session.proactiveLoading.collectAsStateWithLifecycle()
    val message by session.message.collectAsStateWithLifecycle()
    var loaded by remember { mutableStateOf(false) }
    var enabled by rememberSaveable { mutableStateOf(false) }
    var talk by rememberSaveable { mutableStateOf(true) }
    var start by rememberSaveable { mutableStateOf("8") }
    var end by rememberSaveable { mutableStateOf("23") }
    var minimum by rememberSaveable { mutableStateOf("30") }
    var maximum by rememberSaveable { mutableStateOf("180") }
    var feedback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session) { session.proactiveAction() }
    LaunchedEffect(event) {
        val payload = event.optJSONObject("payload") ?: return@LaunchedEffect
        when (event.optString("operation")) {
            "get", "save" -> {
                enabled = payload.optBoolean("enabled")
                talk = payload.optBoolean("talkEnabled", true)
                start = payload.optInt("startHour", 8).toString()
                end = payload.optInt("endHour", 23).toString()
                minimum = payload.optInt("minGapMinutes", 30).toString()
                maximum = payload.optInt("maxGapMinutes", 180).toString()
                loaded = true
                if (event.optString("operation") == "save") feedback = "已保存，运行中的同一个角色会使用这些设置。"
            }
            "nudge" -> feedback = "话题已交给同一会话，返回管家查看。"
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onClose) { Text("返回管家") }
        Text("主动话题", style = MaterialTheme.typography.headlineSmall)
        Text("沿用共同记忆和当前会话。运行环境开启时，它可以在允许的时间里主动聊聊；关闭后不再自动唤醒或探索 B站。",
            style = MaterialTheme.typography.bodyMedium)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row {
            Text("允许主动活动", Modifier.weight(1f))
            Switch(enabled = loaded && !loading, checked = enabled, onCheckedChange = { enabled = it })
        }
        Row {
            Text("允许主动找我聊天", Modifier.weight(1f))
            Switch(enabled = loaded && !loading, checked = talk, onCheckedChange = { talk = it })
        }
        Text("允许活动的时间（${session.zone.id}，0–23时）", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(start, { start = it.take(2) }, label = { Text("开始") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(end, { end = it.take(2) }, label = { Text("结束") }, modifier = Modifier.weight(1f), singleLine = true)
        }
        Text("可跨午夜；开始与结束相同表示全天。时间窗外保持安静。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(minimum, { minimum = it.take(4) }, label = { Text("最短间隔（分）") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(maximum, { maximum = it.take(4) }, label = { Text("最长间隔（分）") }, modifier = Modifier.weight(1f), singleLine = true)
        }
        feedback?.let { Text(it) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(enabled = loaded && !loading, onClick = {
            val from = start.toIntOrNull()
            val until = end.toIntOrNull()
            val min = minimum.toIntOrNull()
            val max = maximum.toIntOrNull()
            if (from == null || until == null || from !in 0..23 || until !in 0..23 ||
                min == null || max == null || min !in 5..1440 || max !in min..1440) {
                feedback = "小时请填 0–23，间隔请填 5–1440 分钟，最长间隔不能小于最短间隔。"
            } else {
                session.proactiveAction("save", JSONObject().put("enabled", enabled).put("talkEnabled", talk)
                    .put("startHour", from).put("endHour", until).put("minGapMinutes", min).put("maxGapMinutes", max))
            }
        }) { Text("保存主动设置") }
        Row {
            TextButton(enabled = !loading, onClick = { session.proactiveAction() }) { Text("重新读取") }
            TextButton(enabled = !loading, onClick = { session.proactiveAction("nudge") }) { Text("让它先聊一句") }
        }
        Text("B站的浏览开关在平台连接中；公开发布与评论没有随此开关启用。持续后台的实际时长以本机运行情况为准。",
            style = MaterialTheme.typography.bodySmall)
    }
}
