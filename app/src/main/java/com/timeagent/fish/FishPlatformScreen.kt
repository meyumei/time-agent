package com.timeagent.fish

import android.util.Base64
import android.util.Xml
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

private data class QrDrawing(val width:Float,val height:Float,val paths:List<Path>)
private fun qrDrawing(data:String):QrDrawing? = runCatching {
    val xml=String(Base64.decode(data.substringAfter(','),Base64.DEFAULT),Charsets.UTF_8)
    val parser=Xml.newPullParser().apply { setInput(StringReader(xml)) }
    var width=0f;var height=0f;val paths=mutableListOf<Path>()
    while(parser.eventType!=XmlPullParser.END_DOCUMENT) {
        if(parser.eventType==XmlPullParser.START_TAG) when(parser.name) {
            "svg" -> {val box=parser.getAttributeValue(null,"viewBox").trim().split(Regex("[ ,]+"));width=box[2].toFloat();height=box[3].toFloat()}
            "path" -> paths+=PathParser().parsePathString(parser.getAttributeValue(null,"d")).toPath()
        }
        parser.next()
    }
    require(width>0 && height>0 && paths.isNotEmpty());QrDrawing(width,height,paths)
}.getOrNull()

@Composable
fun FishPlatformScreen(session:FishSession,onClose:()->Unit) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler { if (selected != null) selected = null else onClose() }
    val event by session.platform.collectAsStateWithLifecycle()
    val message by session.message.collectAsStateWithLifecycle()
    val busy by session.platformBusy.collectAsStateWithLifecycle()
    val runtime by session.runtime.state.collectAsStateWithLifecycle()
    var qr by rememberSaveable { mutableStateOf("") };var key by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("尚未读取连接状态") }
    var lastError by rememberSaveable { mutableStateOf("") }
    var enabled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(selected, runtime.ready) { if (selected == "bili" && runtime.ready) session.platformAction("status") }
    LaunchedEffect(event) {
        val payload=event.optJSONObject("payload") ?: return@LaunchedEffect
        if(!payload.optBoolean("ok")) {lastError=payload.optString("error","平台连接失败，请稍后重试");return@LaunchedEffect}
        if (event.optString("operation") !in listOf("status", "enable")) lastError = ""
        when(event.optString("operation")) {
            "status","enable" -> {enabled=payload.optBoolean("enabled");status=if(payload.optBoolean("logged_in")) "本机已有登录信息，仍需在线核验" else "本机未保存登录信息，可以读取公开内容"}
            "qr" -> {qr=payload.optString("svg");key=payload.optString("key");status="请使用 B站扫一扫，确认后点击检查登录"}
            "poll" -> status=when(payload.optInt("code",-1)) {0 -> if(payload.optBoolean("verify_ok")) "登录已通过在线核验" else "登录信息已保存，在线核验未通过";86101 -> "等待扫码";86090 -> "已扫码，请在 B站确认";86038 -> "二维码已过期，请重新生成";else -> "登录状态尚未确认"}
            "discover" -> status=if(payload.has("nudgeError")) "已保存公开来源，话题暂未送达。可返回管家询问刚发现的内容。" else if ((payload.optJSONArray("items")?.length() ?: 0)>0) "已把真实公开来源交给同一条鱼，返回管家接着聊。" else payload.optString("note","这次没有发现新的内容。")
        }
    }
    val drawing=remember(qr) { if(qr.isBlank())null else qrDrawing(qr) }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        TextButton(onClick={if (selected != null) selected=null else onClose()}) {Text("返回")}
        Text("平台连接",style=MaterialTheme.typography.headlineSmall)
        if (selected == null) {
            ListItem(headlineContent={Text("B站")}, supportingContent={Text("扫码连接、读取公开内容和分享见闻")}, modifier=Modifier.clickable { selected="bili" })
            ListItem(headlineContent={Text("QQ群聊")}, supportingContent={Text("OneBot连接、授权群与语音转文字")}, modifier=Modifier.clickable { selected="qq" })
        }
        if (selected == "bili") {
        Text("B站",style=MaterialTheme.typography.titleLarge)
        Text("同一个角色把发现的内容自然带回聊天。会尝试读取简介、可用字幕和评论；画面理解尚未接入。",style=MaterialTheme.typography.bodySmall)
        Text(status)
        if (lastError.isNotBlank()) Text(lastError, color=MaterialTheme.colorScheme.error)
        if(busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在读取，请稍候…",style=MaterialTheme.typography.bodySmall) }
        message?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick={session.platformAction("qr")},enabled=runtime.ready && !busy) {Text("生成登录二维码")}
            TextButton(onClick={status="正在读取本机连接配置…";session.platformAction("status")},enabled=runtime.ready && !busy) {Text("刷新状态")}
        }
        Button(onClick={session.platformAction("discover")},enabled=runtime.ready && !busy) { Text("看看公开内容，和我聊聊") }
        if(event.optString("operation")=="discover") {
            val items=event.optJSONObject("payload")?.optJSONArray("items")
            if(items!=null) for(index in 0 until items.length()) {
                val source=items.getJSONObject(index)
                Text(buildAnnotatedString { withLink(LinkAnnotation.Url(source.optString("url"))) { append(source.optString("title")) } })
            }
        }
        drawing?.let { data -> Canvas(Modifier.size(260.dp).background(Color.White)) {
            scale(size.width/data.width,size.height/data.height,pivot=Offset.Zero) {data.paths.forEach {drawPath(it,Color.Black)}}
        } }
        if(key.isNotBlank()) TextButton(onClick={session.platformAction("poll",key=key)},enabled=runtime.ready && !busy) {Text("检查登录")}
        Row {Text("允许主动看看 B站",Modifier.weight(1f));Switch(checked=enabled,onCheckedChange={session.platformAction("enable",enabled=it)},enabled=runtime.ready && !busy)}
        }
        if (selected == "qq") FishQQSettings(session)
    }
}
