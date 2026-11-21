package com.unraid.app.network

import android.os.Handler
import android.os.Looper
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.unraid.app.data.local.AppErrorLogger
import com.unraid.app.plugin.InstalledPlugin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.Executor

/**
 * 앱 전체(OkHttp + Android WebView)의 프록시 라우팅을 중앙에서 일원화하여 관리하는 매니저.
 * - OkHttp: 동적 ProxySelector로 백그라운드 스레드에서도 즉시 프록시 전환
 * - Android WebView: ProxyController 호출을 반드시 메인(UI) 스레드에서 실행하여 Chromium 크래시 방지
 */
object NetworkProxyManager {

    private val _proxyConfig = MutableStateFlow(LocalProxyConfig.DISABLED)
    val proxyConfig: StateFlow<LocalProxyConfig> = _proxyConfig.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { runnable -> mainHandler.post(runnable) }

    @Volatile
    private var webViewProxyApplied: Boolean = false

    private var onProxyChangedListener: (() -> Unit)? = null

    /**
     * OkHttpClient 에 주입되는 동적 ProxySelector.
     * 매 요청 시 현재 활성화된 LocalProxyConfig를 참조하므로 클라이언트 재생성 없이 즉시 전환됩니다.
     */
    val okHttpProxySelector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val current = _proxyConfig.value
            if (!current.isActive) {
                return listOf(Proxy.NO_PROXY)
            }

            // 로컬 루프백 자기 자신 호출은 프록시 루프를 방지하기 위해 우회
            val host = uri?.host.orEmpty()
            if (host.equals("127.0.0.1", ignoreCase = true) ||
                host.equals("localhost", ignoreCase = true)
            ) {
                return listOf(Proxy.NO_PROXY)
            }

            val address = InetSocketAddress.createUnresolved(current.host, current.port)
            return listOf(Proxy(current.toJavaProxyType(), address))
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            AppErrorLogger.logError(
                tag = "NetworkProxy",
                message = "프록시 연결 실패 (대상: ${uri ?: "알 수 없음"}, 프록시: ${sa ?: "알 수 없음"})",
                throwable = ioe
            )
        }
    }

    /**
     * NetworkModule 초기화 시 커넥션 풀 정리 콜백을 등록합니다.
     */
    internal fun registerOnProxyChangedListener(listener: () -> Unit) {
        onProxyChangedListener = listener
    }

    /**
     * 2단계(tsnet)에서 로컬 프록시를 활성화할 때 호출하는 진입점.
     */
    fun enableLocalProxy(
        port: Int,
        host: String = LocalProxyConfig.DEFAULT_HOST,
        scheme: LocalProxyScheme = LocalProxyScheme.HTTP
    ) {
        applyProxy(
            LocalProxyConfig(
                enabled = true,
                host = host,
                port = port,
                scheme = scheme
            )
        )
    }

    /**
     * 로컬 프록시를 해제하고 기본 직결 네트워크로 복구합니다.
     */
    fun disableLocalProxy() {
        applyProxy(LocalProxyConfig.DISABLED)
    }

    /**
     * OkHttp와 WebView(ProxyController) 양쪽에 프록시 설정을 일괄 반영합니다.
     */
    fun applyProxy(config: LocalProxyConfig) {
        _proxyConfig.value = config
        onProxyChangedListener?.invoke()
        applyToWebViewOnMainThread(config)
    }

    private fun applyToWebViewOnMainThread(config: LocalProxyConfig) {
        // 프록시가 한 번도 적용된 적 없는 초기 상태에서 불필요한 WebView 엔진 초기화를 방지
        if (!config.isActive && !webViewProxyApplied) {
            return
        }

        mainHandler.post {
            try {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                    if (config.isActive) {
                        AppErrorLogger.logError(
                            tag = "WebViewProxy",
                            message = "현재 기기의 WebView가 PROXY_OVERRIDE 기능을 지원하지 않습니다."
                        )
                    }
                    return@post
                }

                val controller = ProxyController.getInstance()
                if (config.isActive) {
                    val webViewProxyConfig = ProxyConfig.Builder()
                        .addProxyRule(config.toWebViewProxyRule())
                        .addBypassRule("127.0.0.1")
                        .addBypassRule("localhost")
                        .addBypassRule(InstalledPlugin.PLUGIN_VIRTUAL_HOST)
                        .build()

                    controller.setProxyOverride(webViewProxyConfig, mainExecutor) {
                        webViewProxyApplied = true
                    }
                } else {
                    controller.clearProxyOverride(mainExecutor) {
                        webViewProxyApplied = false
                    }
                }
            } catch (t: Throwable) {
                AppErrorLogger.logError(
                    tag = "WebViewProxy",
                    message = "WebView 프록시 설정 반영 중 오류가 발생했습니다: ${t.localizedMessage ?: t.javaClass.simpleName}",
                    throwable = t
                )
            }
        }
    }
}
