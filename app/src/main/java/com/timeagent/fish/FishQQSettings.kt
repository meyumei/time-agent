package com.timeagent.fish

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun FishQQSettings(session: FishSession) {
    val event by session.qq.collectAsStateWithLifecycle()
    val busy by session.qqBusy.collectAsStateWithLifecycle()
    val runtime by session.runtime.state.collectAsStateWithLifecycle()
    val voice by session.qqVoice.collectAsStateWithLifecycle()
    var loaded by remember { mutableStateOf(false) }
    var endpoint by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var hasToken by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(false) }
    var allowSend by remember { mutableStateOf(false) }
    var voiceEnabled by remember { mutableStateOf(true) }
    var smart by remember { mutableStateOf(false) }
    var gap by remember { mutableStateOf("30") }
    var limit by remember { mutableStateOf("6") }
    var start by remember { mutableStateOf("8") }
    var end by remember { mutableStateOf("23") }
    var status by remember { mutableStateOf("尚未连接QQ。需要你已有的 NapCat / OneBot HTTP 服务。") }
    LaunchedEffect(runtime.ready) { if (runtime.ready) session.qqAction() }
    LaunchedEffect(event) {
        val payload = event.optJSONObject("payload") ?: return@LaunchedEffect
        val config = payload.optJSONObject("config")
        if (config != null) {
            endpoint = config.optString("endpoint")
            hasToken = config.optBoolean("hasToken")
            val ids = config.optJSONArray("groups") ?: JSONArray()
            groups = (0 until ids.length()).joinToString("，") { ids.optString(it) }
            enabled = config.optBoolean("enabled")
            allowSend = config.optBoolean("allowSend")
            voiceEnabled = config.optBoolean("voiceEnabled", true)
            smart = config.optString("mode") == "smart"
            gap = config.optInt("minGapSeconds", 30).toString()
            limit = config.optInt("maxPerHour", 6).toString()
            start = config.optInt("startHour", 8).toString()
            end = config.optInt("endHour", 23).toString()
            loaded = true
        }
        status = when {
            config != null && config.optString("endpoint").isBlank() -> "尚未配置QQ连接，填写已有的 OneBot HTTP 地址后检查。"
            payload.has("error") -> "连接未通过：${payload.optString("error")}"
            payload.optBoolean("connected") -> "QQ在线核验通过 · ${payload.optString("nickname")}。仅在允许的群内按设置接话。"
            event.optString("operation") == "save" -> "设置已保存。可检查连接；启用接收后每15秒读取授权群的新消息。"
            else -> "尚未在线核验QQ连接。"
        }
        if (event.optString("operation") == "save" && !payload.has("error")) token = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_platform_screen_004), style = MaterialTheme.typography.titleLarge)
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_q_q_settings_001))
        Text(status, style = MaterialTheme.typography.bodySmall)
        if (!runtime.ready) Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_q_q_settings_002), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_q_q_settings_003)) },
            placeholder = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_q_q_settings_004)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text(if (hasToken) "访问令牌（留空保留）" else "访问令牌") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(groups, { groups = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_q_q_settings_005)) }, modifier = Modifier.fillMaxWidth())
        Row {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_q_q_settings_006), Modifier.weight(1f))
            Switch(enabled, { enabled = it })
        }
        Row {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_q_q_settings_007), Modifier.weight(1f))
            Switch(allowSend, { allowSend = it })
        }
        Row {
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_q_q_settings_008), Modifier.weight(1f))
            Switch(voiceEnabled, { voiceEnabled = it })
        }
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_q_q_settings_009),
            style = MaterialTheme.typography.bodySmall)
        when {
            voice.optBoolean("processing") -> Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_q_q_settings_010))
            voice.has("error") -> Text("语音转写未完成：${voice.optString("error")}", color = MaterialTheme.colorScheme.error)
            voice.has("text") -> Text("最近语音：${voice.optString("text").ifBlank { "没有识别到文字" }}")
        }
        Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_q_q_settings_011),
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!smart, { smart = false }, { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_q_q_settings_012)) })
            FilterChip(smart, { smart = true }, { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_q_q_settings_013)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(gap, { gap = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_q_q_settings_014)) }, modifier = Modifier.weight(1f))
            OutlinedTextField(limit, { limit = it }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_q_q_settings_015)) }, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(start, { start = it.take(2) }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_q_q_settings_016)) }, modifier = Modifier.weight(1f))
            OutlinedTextField(end, { end = it.take(2) }, label = { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_q_q_settings_017)) }, modifier = Modifier.weight(1f))
        }
        Text("时区 ${session.zone.id}；可跨午夜，起止相同表示全天。最近30分钟、最多30条群消息用于判断上下文。",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = runtime.ready && loaded && !busy, onClick = {
                val seconds = gap.toIntOrNull()
                val maximum = limit.toIntOrNull()
                val from = start.toIntOrNull()
                val until = end.toIntOrNull()
                val ids = groups.split(Regex("[,，\\s]+")).filter { it.isNotBlank() }
                if (seconds == null || seconds !in 5..3600 || maximum == null || maximum !in 1..60 ||
                    from == null || until == null || from !in 0..23 || until !in 0..23 ||
                    ids.any { !it.matches(Regex("[1-9]\\d*")) } || (enabled && ids.isEmpty())) {
                    status = "请填写有效群号、5–3600秒间隔、1–60条上限及0–23小时。"
                } else session.qqAction("save", JSONObject().put("endpoint", endpoint.trim()).put("token", token)
                    .put("groups", JSONArray(ids)).put("enabled", enabled).put("allowSend", allowSend).put("voiceEnabled", voiceEnabled)
                    .put("mode", if (smart) "smart" else "mention").put("minGapSeconds", seconds)
                    .put("maxPerHour", maximum).put("startHour", from).put("endHour", until))
            }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_q_q_settings_018)) }
            TextButton(enabled = runtime.ready && loaded && !busy, onClick = { session.qqAction("status") }) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_fish_q_q_settings_019)) }
        }
    }
}
