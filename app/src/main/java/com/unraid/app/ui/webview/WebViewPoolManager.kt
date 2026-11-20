package com.unraid.app.ui.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.unraid.app.data.local.AppErrorLogger
import com.unraid.app.data.model.DockerServiceBookmark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WebViewUiMeta(
    val progress: Int = 100,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false
)

/**
 * 도커 웹뷰(Kavita 등) 인스턴스를 서비스 ID별로 메모리에 캐싱하는 풀 매니저.
 * 다른 하단 탭에 다녀오거나 도커 서비스를 전환해도 WebView가 파괴/새로고침되지 않도록 상태를 유지합니다.
 */
class WebViewPoolManager {

    private data class CachedEntry(
        val webView: WebView,
        var configuredUrl: String
    )

    private val pool = LinkedHashMap<String, CachedEntry>()

    private val _metaByServiceId = MutableStateFlow<Map<String, WebViewUiMeta>>(emptyMap())
    val metaByServiceId: StateFlow<Map<String, WebViewUiMeta>> = _metaByServiceId.asStateFlow()

    /**
     * 서비스 ID에 해당하는 캐싱된 WebView를 반환하거나 최초 1회 생성합니다.
     * 이미 생성된 인스턴스가 있다면 절대 reload하지 않고 기존 상태를 그대로 유지합니다.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreate(
        context: Context,
        service: DockerServiceBookmark
    ): WebView {
        val existing = pool[service.id]
        if (existing != null) {
            // 설정 화면에서 사용자가 URL 자체를 변경한 경우에만 새 주소 로드
            if (existing.configuredUrl != service.url) {
                existing.configuredUrl = service.url
                existing.webView.loadUrl(service.url)
            }
            return existing.webView
        }

        val webView = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            // PWA와 동일한 사용감 및 최초 1회 로그인 세션 유지를 위한 설정
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION")
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
                javaScriptCanOpenWindowsAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            // 쿠키 및 서드파티 쿠키 활성화
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    updateMeta(service.id) {
                        it.copy(
                            isLoading = true,
                            canGoBack = view?.canGoBack() == true
                        )
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // 로그인 쿠키를 디스크에 즉시 반영하여 앱 재실행 후에도 로그인 유지
                    CookieManager.getInstance().flush()
                    updateMeta(service.id) {
                        it.copy(
                            isLoading = false,
                            progress = 100,
                            canGoBack = view?.canGoBack() == true
                        )
                    }
                }

                override fun doUpdateVisitedHistory(
                    view: WebView?,
                    url: String?,
                    isReload: Boolean
                ) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    // SPA(Kavita 등) 라우팅 변경 시에도 뒤로가기 가능 여부를 즉시 반영
                    updateMeta(service.id) {
                        it.copy(canGoBack = view?.canGoBack() == true)
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        AppErrorLogger.logError(
                            tag = "DockerWebView",
                            message = "[${service.name}] 웹페이지 로드 실패 (${error?.errorCode}): ${error?.description}",
                            extraDetails = "요청 URL: ${request.url}"
                        )
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    updateMeta(service.id) {
                        it.copy(
                            progress = newProgress,
                            isLoading = newProgress < 100,
                            canGoBack = view?.canGoBack() == true
                        )
                    }
                }
            }

            loadUrl(service.url)
        }

        pool[service.id] = CachedEntry(webView = webView, configuredUrl = service.url)
        updateMeta(service.id) { WebViewUiMeta(progress = 0, isLoading = true, canGoBack = false) }
        return webView
    }

    fun getCachedWebView(serviceId: String): WebView? = pool[serviceId]?.webView

    fun reload(serviceId: String) {
        pool[serviceId]?.webView?.reload()
    }

    fun goHome(service: DockerServiceBookmark) {
        pool[service.id]?.webView?.loadUrl(service.url)
    }

    fun goBack(serviceId: String): Boolean {
        val webView = pool[serviceId]?.webView ?: return false
        return if (webView.canGoBack()) {
            webView.goBack()
            true
        } else {
            false
        }
    }

    /**
     * 설정에서 삭제된 서비스의 WebView 캐시만 정리합니다.
     */
    fun retainOnly(validServiceIds: Set<String>) {
        val iterator = pool.entries.iterator()
        while (iterator.hasNext()) {
            val (id, entry) = iterator.next()
            if (id !in validServiceIds) {
                destroyWebViewSafely(entry.webView)
                iterator.remove()
                _metaByServiceId.value = _metaByServiceId.value - id
            }
        }
    }

    /**
     * Activity 완전 종료 시 모든 WebView 리소스를 안전하게 해제합니다.
     */
    fun destroyAll() {
        pool.values.forEach { destroyWebViewSafely(it.webView) }
        pool.clear()
        _metaByServiceId.value = emptyMap()
    }

    private fun destroyWebViewSafely(webView: WebView) {
        runCatching {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
    }

    private fun updateMeta(serviceId: String, transform: (WebViewUiMeta) -> WebViewUiMeta) {
        val currentMap = _metaByServiceId.value
        val updated = transform(currentMap[serviceId] ?: WebViewUiMeta())
        _metaByServiceId.value = currentMap + (serviceId to updated)
    }
}
