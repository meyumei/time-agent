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
        TextButton(onClick = onClose) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_memory_screen_001)) }
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_proactive_screen_001), style = MaterialTheme.typography.headlineSmall)
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_proactive_screen_002),
            style = MaterialTheme.typography.bodyMedium)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_proactive_screen_003), Modifier.weight(1f))
            Switch(enabled = loaded && !loading, checked = enabled, onCheckedChange = { enabled = it })
        }
        Row {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_proactive_screen_004), Modifier.weight(1f))
            Switch(enabled = loaded && !loading, checked = talk, onCheckedChange = { talk = it })
        }
        Text("允许活动的时间（${session.zone.id}，0–23时）", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(start, { start = it.take(2) }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_proactive_screen_005)) }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(end, { end = it.take(2) }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_proactive_screen_006)) }, modifier = Modifier.weight(1f), singleLine = true)
        }
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_proactive_screen_007), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(minimum, { minimum = it.take(4) }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_proactive_screen_008)) }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(maximum, { maximum = it.take(4) }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_proactive_screen_009)) }, modifier = Modifier.weight(1f), singleLine = true)
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
        }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_proactive_screen_010)) }
        Row {
            TextButton(enabled = !loading, onClick = { session.proactiveAction() }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_proactive_screen_011)) }
            TextButton(enabled = !loading, onClick = { session.proactiveAction("nudge") }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_proactive_screen_012)) }
        }
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_proactive_screen_013),
            style = MaterialTheme.typography.bodySmall)
    }
}
