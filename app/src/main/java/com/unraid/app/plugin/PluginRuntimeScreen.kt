package com.unraid.app.plugin

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.unraid.app.data.local.AppErrorLogger
import com.unraid.app.network.NetworkModule
import com.unraid.app.player.MpvPlayRequest
import com.unraid.app.player.NativeMpvPlayerScreen
import com.unraid.app.player.PlayerImmersiveOrientationEffect
import com.unraid.app.player.PlayerSettingsStore
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 설치된 외부 웹 플러그인(HTML/CSS/JS)을 가상 HTTPS 도메인(https://plugins.unraidhub.local/<id>/)으로
 * 실행하고, window.UnraidHub 네이티브 브릿지 및 내장 libmpv 비디오 플레이어를 연동하는 런타임 화면.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginRuntimeScreen(
    plugin: InstalledPlugin,
    bridgeContext: PluginBridgeContext,
    pluginsRootDir: File,
    storageVault: PluginStorageVault,
    playerSettingsStore: PlayerSettingsStore,
    onOpenWebViewFallback: () -> Unit,
    onNavigateToDashboard: () -> Unit,
    onExitToLauncher: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val latestBridgeContext by rememberUpdatedState(bridgeContext)
    val latestOnOpenWebViewFallback by rememberUpdatedState(onOpenWebViewFallback)
    val latestOnExitToLauncher by rememberUpdatedState(onExitToLauncher)

    var activePlayRequest by remember { mutableStateOf<MpvPlayRequest?>(null) }
    var showQuickMenu by remember { mutableStateOf(false) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }

    val bridge = remember(plugin.id, plugin.version) {
        UnraidHubJsBridge(
            plugin = plugin,
            contextProvider = { latestBridgeContext },
            storageVault = storageVault,
            onPlayVideo = { request ->
                activePlayRequest = request
            },
            onClose = {
                activePlayRequest = null
                latestOnExitToLauncher()
            },
            onOpenWebView = {
                activePlayRequest = null
                latestOnOpenWebViewFallback()
            }
        )
    }

    val assetLoader = remember(pluginsRootDir) {
        WebViewAssetLoader.Builder()
            .setDomain(InstalledPlugin.PLUGIN_VIRTUAL_HOST)
            .addPathHandler("/", PluginAssetPathHandler(pluginsRootDir))
            .build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    val pluginWebView = remember(plugin.id, plugin.version, plugin.virtualEntryUrl) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION")
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(false)
                javaScriptCanOpenWindowsAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            addJavascriptInterface(bridge, UnraidHubJsBridge.NATIVE_INTERFACE_NAME)
            bridge.attachWebView(this)

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val uri = request?.url ?: return null
                    if (uri.host.equals(InstalledPlugin.PLUGIN_VIRTUAL_HOST, ignoreCase = true)) {
                        if (uri.path == UnraidHubJsBridge.PROXY_PATH_PREFIX) {
                            val targetUrl = uri.getQueryParameter("url").orEmpty().trim()
                            return proxySubresourceViaOkHttp(targetUrl, request.requestHeaders.orEmpty())
                        }
                        return assetLoader.shouldInterceptRequest(uri)
                    }

                    // 외부 도커 서비스의 이미지 서브 리소스도 OkHttp(tsnet + 사설 SSL 허용)로 자동 경유
                    if (!request.isForMainFrame &&
                        request.method.equals("GET", ignoreCase = true) &&
                        (uri.scheme.equals("http", ignoreCase = true) ||
                            uri.scheme.equals("https", ignoreCase = true))
                    ) {
                        val accept = request.requestHeaders?.entries
                            ?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
                            ?.value
                            .orEmpty()
                        val path = uri.path.orEmpty()
                        if (accept.contains("image/", ignoreCase = true) ||
                            path.contains("/Images/", ignoreCase = true)
                        ) {
                            return proxySubresourceViaOkHttp(uri.toString(), request.requestHeaders.orEmpty())
                        }
                    }

                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    isLoading = true
                    canGoBack = view?.canGoBack() == true
                    // HTML 주입 외에 추가 안전장치로 페이지 시작 시에도 브릿지 초기화 보장
                    view?.evaluateJavascript(UnraidHubJsBridge.BRIDGE_BOOTSTRAP_JS, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isLoading = false
                    loadProgress = 100
                    canGoBack = view?.canGoBack() == true
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    canGoBack = view?.canGoBack() == true
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        isLoading = false
                        AppErrorLogger.logError(
                            tag = "Plugin/${plugin.id}/WebView",
                            message = "플러그인 페이지 로드 오류: ${error?.description ?: "알 수 없는 오류"}",
                            extraDetails = "URL: ${request.url}"
                        )
                    }
                }

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    if (NetworkModule.isSelfSignedSslAllowed()) {
                        handler?.proceed()
                    } else {
                        handler?.cancel()
                        AppErrorLogger.logError(
                            tag = "Plugin/${plugin.id}/SSL",
                            message = "플러그인 WebView SSL 인증서 오류: ${error?.url ?: "알 수 없음"}"
                        )
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    loadProgress = newProgress
                    isLoading = newProgress < 100
                    canGoBack = view?.canGoBack() == true
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    if (consoleMessage?.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        AppErrorLogger.logError(
                            tag = "Plugin/${plugin.id}/Console",
                            message = consoleMessage.message().orEmpty(),
                            extraDetails = "${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
                        )
                    }
                    return super.onConsoleMessage(consoleMessage)
                }
            }

            loadUrl(plugin.virtualEntryUrl)
        }
    }

    DisposableEffect(pluginWebView, bridge) {
        onDispose {
            bridge.dispose()
            runCatching {
                pluginWebView.stopLoading()
                pluginWebView.removeJavascriptInterface(UnraidHubJsBridge.NATIVE_INTERFACE_NAME)
                (pluginWebView.parent as? ViewGroup)?.removeView(pluginWebView)
                pluginWebView.destroy()
            }
        }
    }

    // 플레이어가 열려 있지 않을 때만 플러그인 WebView 뒤로가기 처리
    BackHandler(enabled = activePlayRequest == null) {
        if (pluginWebView.canGoBack()) {
            pluginWebView.goBack()
        } else {
            onExitToLauncher()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 1) 플러그인 WebView (동영상 재생 중에도 파괴되지 않고 상태를 유지)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .systemBarsPadding()
        ) {
            val density = LocalDensity.current
            val maxHeightPx = with(density) { maxHeight.toPx() }
            val buttonHeightPx = with(density) { 52.dp.toPx() }
            val verticalMarginPx = with(density) { 24.dp.toPx() }
            val minY = verticalMarginPx
            val maxY = (maxHeightPx - buttonHeightPx - verticalMarginPx).coerceAtLeast(minY)

            var handleOffsetY by rememberSaveable(maxHeightPx) {
                mutableStateOf((maxHeightPx * 0.42f).coerceIn(minY, maxY))
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { pluginWebView },
                update = { view ->
                    canGoBack = view.canGoBack()
                }
            )

            if (isLoading && loadProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { loadProgress / 100f },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(2.dp)
                )
            }

            // 우측 가장자리 드래그 가능한 반투명 플로팅 메뉴 버튼 (플레이어 재생 중엔 숨김)
            if (activePlayRequest == null) {
                Surface(
                    onClick = { showQuickMenu = true },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(0, handleOffsetY.roundToInt()) }
                        .alpha(0.58f)
                        .pointerInput(minY, maxY) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                handleOffsetY = (handleOffsetY + dragAmount.y).coerceIn(minY, maxY)
                            }
                        },
                    shape = RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    ),
                    shadowElevation = 6.dp
                ) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(52.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Menu,
                            contentDescription = "플러그인 빠른 메뉴 열기",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // 2) window.UnraidHub.playVideo() 호출 시 전체 화면 가로 몰입 libmpv 플레이어 오버레이
        if (activePlayRequest != null) {
            PlayerImmersiveOrientationEffect()
        }
        activePlayRequest?.let { playReq ->
            key(playReq.sessionId) {
                NativeMpvPlayerScreen(
                    request = playReq,
                    playerSettingsStore = playerSettingsStore,
                    onProgress = { posSec, durSec ->
                        bridge.dispatchPlayerProgress(posSec, durSec)
                    },
                    onClosed = { posSec, durSec, completed ->
                        if (activePlayRequest?.sessionId == playReq.sessionId) {
                            activePlayRequest = null
                        }
                        bridge.dispatchPlayerClosed(posSec, durSec, completed)
                    },
                    onNavigateEpisode = { direction, posSec, durSec, completed ->
                        bridge.dispatchPlayerAction(direction, posSec, durSec, completed)
                    },
                    manageActivityOrientation = false
                )
            }
        }
    }

    // 플로팅 메뉴 바텀 시트
    if (showQuickMenu && activePlayRequest == null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showQuickMenu = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Extension,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .size(22.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = plugin.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "v${plugin.version}",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                text = "${bridgeContext.serviceName} · ${bridgeContext.activeBaseUrl}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                showQuickMenu = false
                                if (pluginWebView.canGoBack()) pluginWebView.goBack()
                            },
                            enabled = canGoBack
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "뒤로가기"
                            )
                        }
                        IconButton(
                            onClick = {
                                showQuickMenu = false
                                pluginWebView.loadUrl(plugin.virtualEntryUrl)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Home,
                                contentDescription = "플러그인 홈"
                            )
                        }
                        IconButton(
                            onClick = {
                                showQuickMenu = false
                                pluginWebView.reload()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "새로고침"
                            )
                        }
                    }
                }

                FilledTonalButton(
                    onClick = {
                        showQuickMenu = false
                        onOpenWebViewFallback()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("일반 웹뷰로 열기")
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PluginQuickNavTile(
                        icon = Icons.Filled.Apps,
                        label = "런처로 복귀",
                        onClick = {
                            showQuickMenu = false
                            onExitToLauncher()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    PluginQuickNavTile(
                        icon = Icons.Filled.Dashboard,
                        label = "Unraid",
                        onClick = {
                            showQuickMenu = false
                            onNavigateToDashboard()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    PluginQuickNavTile(
                        icon = Icons.Filled.Settings,
                        label = "설정",
                        onClick = {
                            showQuickMenu = false
                            onNavigateToSettings()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginQuickNavTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * WebViewAssetLoader용 커스텀 PathHandler.
 * - filesDir/plugins/ 하위 파일을 https://plugins.unraidhub.local/ 가상 HTTPS 도메인으로 안전하게 서빙합니다.
 * - canonicalPath 기반 경로 이탈 차단 및 HTML 응답에 window.UnraidHub 부트스트랩 스크립트 자동 주입을 수행합니다.
 */
private class PluginAssetPathHandler(
    private val pluginsRootDir: File
) : WebViewAssetLoader.PathHandler {

    private val canonicalRootPrefix: String = pluginsRootDir.canonicalPath + File.separator

    override fun handle(path: String): WebResourceResponse {
        val cleanRelative = path.trimStart('/').replace('\\', '/')
        if (cleanRelative.isBlank()) {
            return notFoundResponse()
        }

        val candidate = File(pluginsRootDir, cleanRelative)
        val resolvedFile = if (candidate.isDirectory) {
            File(candidate, "index.html")
        } else {
            candidate
        }

        val canonicalTarget = runCatching { resolvedFile.canonicalPath }.getOrNull()
            ?: return notFoundResponse()

        // 플러그인 루트 디렉터리 외부 파일 접근 완전 차단
        if (!canonicalTarget.startsWith(canonicalRootPrefix)) {
            return notFoundResponse()
        }
        if (!resolvedFile.exists() || !resolvedFile.isFile) {
            return notFoundResponse()
        }

        val mimeType = guessMimeType(resolvedFile.name)
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "no-cache, no-store, must-revalidate"
        )

        return try {
            if (mimeType == "text/html") {
                val rawHtml = resolvedFile.readText(StandardCharsets.UTF_8)
                val injectedHtml = injectBridgeIntoHtml(rawHtml)
                val bytes = injectedHtml.toByteArray(StandardCharsets.UTF_8)
                WebResourceResponse(
                    mimeType,
                    "utf-8",
                    200,
                    "OK",
                    headers,
                    ByteArrayInputStream(bytes)
                )
            } else {
                val encoding = if (mimeType.startsWith("text/") ||
                    mimeType == "application/javascript" ||
                    mimeType == "application/json" ||
                    mimeType == "image/svg+xml"
                ) {
                    "utf-8"
                } else {
                    null
                }
                WebResourceResponse(
                    mimeType,
                    encoding,
                    200,
                    "OK",
                    headers,
                    FileInputStream(resolvedFile)
                )
            }
        } catch (e: Exception) {
            AppErrorLogger.logError(
                tag = "PluginAssetLoader",
                message = "플러그인 리소스 서빙 실패: $cleanRelative",
                throwable = e
            )
            notFoundResponse()
        }
    }

    private fun injectBridgeIntoHtml(html: String): String {
        val bootstrapTag = "\n<script>${UnraidHubJsBridge.BRIDGE_BOOTSTRAP_JS}</script>\n"
        val headRegex = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE)
        val match = headRegex.find(html)
        return if (match != null) {
            html.substring(0, match.range.last + 1) +
                bootstrapTag +
                html.substring(match.range.last + 1)
        } else {
            bootstrapTag + html
        }
    }

    private fun notFoundResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            404,
            "Not Found",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream("Not Found".toByteArray(StandardCharsets.UTF_8))
        )
    }

    private fun guessMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase(Locale.US)) {
            "html", "htm" -> "text/html"
            "js", "mjs", "cjs" -> "application/javascript"
            "css" -> "text/css"
            "json", "map" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "wasm" -> "application/wasm"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "xml" -> "application/xml"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}

/**
 * 플러그인 WebView의 이미지/미디어 리소스를 앱의 NetworkModule.okHttpClient로 프록시합니다.
 * - 가상 HTTPS 도메인(https://plugins.unraidhub.local)에서 사설 HTTP/자체 서명 HTTPS/Tailscale(tsnet)
 *   도커 서버의 이미지를 Mixed Content, 사설 SSL, Private Network Access 차단 없이 안정적으로 로드합니다.
 */
private fun proxySubresourceViaOkHttp(
    targetUrl: String,
    requestHeaders: Map<String, String>
): WebResourceResponse {
    if (!targetUrl.startsWith("http://", ignoreCase = true) &&
        !targetUrl.startsWith("https://", ignoreCase = true)
    ) {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            400,
            "Bad Request",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    return try {
        val reqBuilder = Request.Builder()
            .url(targetUrl)
            .get()

        requestHeaders.forEach { (key, value) ->
            // 호스트/오리진/프록시 충돌을 유발할 수 있는 헤더 제외
            if (key.equals("Accept", ignoreCase = true) ||
                key.equals("Accept-Language", ignoreCase = true) ||
                key.equals("User-Agent", ignoreCase = true)
            ) {
                reqBuilder.header(key, value)
            }
        }

        NetworkModule.okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
            val statusCode = response.code.coerceIn(100, 599)
            val reasonPhrase = response.message.ifBlank { defaultHttpReason(statusCode) }
            val rawContentType = response.header("Content-Type").orEmpty()
            val mimeType = rawContentType
                .substringBefore(';')
                .trim()
                .ifBlank { "application/octet-stream" }
            val encoding = if (mimeType.startsWith("text/") ||
                mimeType == "application/json" ||
                mimeType == "image/svg+xml"
            ) {
                rawContentType.substringAfter("charset=", "")
                    .substringBefore(';')
                    .trim()
                    .ifBlank { "utf-8" }
            } else {
                null
            }

            val bytes = response.body?.bytes() ?: ByteArray(0)
            val responseHeaders = LinkedHashMap<String, String>()
            responseHeaders["Access-Control-Allow-Origin"] = "*"
            if (response.isSuccessful && mimeType.startsWith("image/", ignoreCase = true)) {
                responseHeaders["Cache-Control"] = "public, max-age=86400"
            }

            WebResourceResponse(
                mimeType,
                encoding,
                statusCode,
                reasonPhrase,
                responseHeaders,
                ByteArrayInputStream(bytes)
            )
        }
    } catch (_: Exception) {
        WebResourceResponse(
            "text/plain",
            "utf-8",
            502,
            "Bad Gateway",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(ByteArray(0))
        )
    }
}

private fun defaultHttpReason(code: Int): String {
    return when (code) {
        200 -> "OK"
        204 -> "No Content"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> "HTTP $code"
    }
}
