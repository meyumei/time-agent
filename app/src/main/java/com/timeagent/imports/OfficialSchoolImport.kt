package com.timeagent.imports

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.net.URI

/** A user-operated, ephemeral school session. Only the explicit extract action reads table clones. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OfficialSchoolImport(onExtracted: (CourseImportDraft) -> Unit, onClose: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var host by remember { mutableStateOf("sso.scut.edu.cn") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var extracting by remember { mutableStateOf(false) }
    BackHandler { if (webView?.canGoBack() == true) webView?.goBack() else onClose() }
    DisposableEffect(Unit) {
        onDispose {
            webView?.let { view ->
                view.stopLoading()
                view.webViewClient = WebViewClient()
                view.loadUrl("about:blank")
                view.clearHistory()
                view.clearCache(true)
                view.clearFormData()
                view.removeAllViews()
                view.destroy()
            }
            webView = null
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("华工教务导入", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClose) { Text("关闭") }
        }
        Text("请自行登录学校账号，再进入本学期完整课表，看到课程后点击提取。学校可能要求校内网络或 VPN。", style = MaterialTheme.typography.bodySmall)
        Text("当前学校登录后的课表尚待真机验收。若连接失败，可返回使用教务导出的 PDF。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { error = null; webView?.loadUrl(OfficialSchoolPolicy.LOGIN_URL) }) { Text("统一认证") }
            OutlinedButton(onClick = { error = null; webView?.loadUrl(OfficialSchoolPolicy.TIMETABLE_URL) }) { Text("学生教务") }
        }
        Text("学校页面：$host", style = MaterialTheme.typography.labelSmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (loading || extracting) LinearProgressIndicator(Modifier.fillMaxWidth())
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { context ->
                WebView(context).apply {
                    webView = this
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        setGeolocationEnabled(false)
                        safeBrowsingEnabled = true
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    setDownloadListener { _, _, _, _, _ -> error = "此处只读取课表网页。如需 PDF，请在学校浏览器中导出后返回选择 PDF。" }
                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val blocked = !OfficialSchoolPolicy.isAllowed(request.url.toString())
                            if (blocked && request.isForMainFrame) error = "学校跳转的地址不在已核验的 HTTPS 入口内，已停止。请使用学校安全入口，或返回导入 PDF。"
                            return blocked
                        }
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                            if (OfficialSchoolPolicy.isAllowed(request.url.toString())) null
                            else WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            if (!OfficialSchoolPolicy.isAllowed(url)) { view.stopLoading(); error = "该页面不属于已核验的学校 HTTPS 域名。"; return }
                            loading = true
                            host = runCatching { URI(url).host }.getOrNull() ?: "学校页面"
                        }
                        override fun onPageFinished(view: WebView, url: String) { loading = false }
                        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, sslError: SslError) {
                            handler.cancel(); loading = false
                            error = "学校 HTTPS 证书连接未通过验证，已停止。可切换学校网络后重试，或返回导入 PDF。"
                        }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: android.webkit.WebResourceError) {
                            if (request.isForMainFrame) { loading = false; error = "学校页面暂时连接失败。请检查校园网络或学校 VPN，或返回导入 PDF。" }
                        }
                    }
                    loadUrl(OfficialSchoolPolicy.LOGIN_URL)
                }
            },
        )
        Button(
            modifier = Modifier.fillMaxWidth(), enabled = !loading && !extracting,
            onClick = {
                val view = webView
                if (view == null || !OfficialSchoolPolicy.isAllowed(view.url.orEmpty())) {
                    error = "请先打开学校 HTTPS 课表页。"
                } else {
                    extracting = true
                    error = null
                    // This constant only clones timetable tables and same-origin frame tables; it reads no forms, cookies, or credentials.
                    view.evaluateJavascript(TABLE_CAPTURE_SCRIPT) { encoded ->
                        extracting = false
                        runCatching {
                            val payload = JsonParser.parseString(JsonParser.parseString(encoded).asString).asJsonObject
                            val captured = payload.get("html")?.asString.orEmpty()
                            require(captured.isNotBlank()) { "当前页未找到课表表格。请进入本学期完整课表后重试；跨域框架页面需先打开课表本身。" }
                            val parsed = OfficialTimetableParser.parseHtml(captured)
                            val blocked = payload.get("blockedFrames")?.asInt ?: 0
                            if (blocked > 0) parsed.copy(warnings = parsed.warnings + "部分跨域框架未读取，请核对学校课表是否完整。") else parsed
                        }.onSuccess(onExtracted).onFailure { failure -> error = failure.message ?: "网页提取失败，请重试或导入 PDF。" }
                    }
                }
            },
        ) { Text(if (extracting) "正在提取…" else "提取当前课表") }
    }
}

private const val TABLE_CAPTURE_SCRIPT = """(function() {
  var out = [], blocked = 0, size = 0, visited = [];
  function collect(doc, depth) {
    if (depth > 3 || visited.indexOf(doc) >= 0) return;
    visited.push(doc);
    Array.prototype.forEach.call(doc.querySelectorAll('table'), function(table) {
      if (table.parentElement && table.parentElement.closest('table')) return;
      var text = table.innerText || table.textContent || '';
      if (!/(星期[一二三四五六日天]|周数|节次)/.test(text)) return;
      var copy = table.cloneNode(true);
      Array.prototype.forEach.call(copy.querySelectorAll('input,textarea,select,button,script,style,iframe,object,embed'), function(n) { n.remove(); });
      Array.prototype.forEach.call(copy.querySelectorAll('form'), function(f) { while(f.firstChild) f.parentNode.insertBefore(f.firstChild, f); f.remove(); });
      var html = copy.outerHTML;
      size += html.length;
      if (size <= 2000000) out.push(html);
    });
    Array.prototype.forEach.call(doc.querySelectorAll('iframe,frame'), function(frame) {
      try { var inner = frame.contentDocument; if(inner) collect(inner, depth+1); else blocked++; } catch(e) { blocked++; }
    });
  }
  collect(document, 0);
  return JSON.stringify({html: size > 2000000 ? '' : out.join('\n'), blockedFrames: blocked});
})()
"""
