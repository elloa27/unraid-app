package com.unraid.app.network

import java.net.Proxy

/**
 * 2단계(Tailscale tsnet 내장)에서 로컬 프록시(127.0.0.1:포트)를 중앙 주입하기 위한 프록시 설정 모델.
 */
data class LocalProxyConfig(
    val enabled: Boolean = false,
    val host: String = DEFAULT_HOST,
    val port: Int = 0,
    val scheme: LocalProxyScheme = LocalProxyScheme.HTTP
) {
    val isActive: Boolean
        get() = enabled && host.isNotBlank() && port in 1..65535

    /**
     * androidx.webkit.ProxyConfig 에 전달할 프록시 규칙 문자열을 반환합니다.
     * 예: "http://127.0.0.1:1055" 또는 "socks5://127.0.0.1:1055"
     */
    fun toWebViewProxyRule(): String {
        return "${scheme.webViewPrefix}://$host:$port"
    }

    /**
     * OkHttp(java.net.Proxy)에 전달할 Proxy.Type을 반환합니다.
     */
    fun toJavaProxyType(): Proxy.Type {
        return when (scheme) {
            LocalProxyScheme.HTTP -> Proxy.Type.HTTP
            LocalProxyScheme.SOCKS5 -> Proxy.Type.SOCKS
        }
    }

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        val DISABLED = LocalProxyConfig(enabled = false)
    }
}

enum class LocalProxyScheme(val webViewPrefix: String) {
    HTTP("http"),
    SOCKS5("socks5")
}
