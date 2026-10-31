package com.timeagent.workout

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

internal class WorkoutAssetClient(private val context: Context) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse = response(request.url)
    internal fun response(uri: Uri): WebResourceResponse {
        val path = uri.path.orEmpty().removePrefix("/")
        if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net" || !path.startsWith("workout/anatomy/") ||
            path.split('/').any { it == ".." || it == "." } || path.contains('\\')) return rejected()
        val mime = when (path.substringAfterLast('.').lowercase()) {
            "html" -> "text/html"; "js" -> "application/javascript"; "json" -> "application/json"
            "wasm" -> "application/wasm"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; else -> "application/octet-stream"
        }
        return try { WebResourceResponse(mime, if (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json")) "UTF-8" else null,
            200, "OK", mapOf("Cache-Control" to "no-cache", "Access-Control-Allow-Origin" to "https://appassets.androidplatform.net"), context.assets.open(path)) }
        catch (_: Exception) { rejected() }
    }
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean = request.url.host != "appassets.androidplatform.net" || request.url.scheme != "https"
    private fun rejected() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun WorkoutAnatomyScreen(muscle: String, onBack: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) { onDispose { webView?.apply { stopLoading(); onPause(); removeAllViews(); destroy() }; webView = null } }
    Scaffold(topBar = { TopAppBar(title = { Text(if (muscle.isBlank()) "3D 肌群" else "3D · $muscle") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } }) }) { padding ->
        AndroidView(factory = { context -> WebView(context).apply {
            // WebView treats WRAP_CONTENT as a zero-height CSS layout viewport even when Compose fills the screen.
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = WorkoutAssetClient(context.applicationContext)
            webView = this
            loadUrl("https://appassets.androidplatform.net/workout/anatomy/index.html?muscle=${Uri.encode(muscle)}")
        } }, modifier = Modifier.padding(padding).fillMaxSize().testTag("workout-anatomy-view"))
    }
}
