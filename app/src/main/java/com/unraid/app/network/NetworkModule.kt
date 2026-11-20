package com.unraid.app.network

import android.annotation.SuppressLint
import com.unraid.app.data.local.AppErrorLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 앱 전역에서 재사용하는 네트워크 싱글톤 모듈.
 * - 모든 HTTP(S) 통신은 NetworkProxyManager.okHttpProxySelector를 통해 일괄 프록시 제어를 받습니다.
 * - 개인 NAS 환경의 자체 서명(Self-signed) SSL 인증서 허용 옵션을 중앙에서 제어합니다.
 */
object NetworkModule {

    private val _allowSelfSignedSsl = MutableStateFlow(true)
    val allowSelfSignedSslFlow: StateFlow<Boolean> = _allowSelfSignedSsl.asStateFlow()

    fun isSelfSignedSslAllowed(): Boolean = _allowSelfSignedSsl.value

    /**
     * 설정 화면 스위치 변경 또는 앱 시작 시 DataStore 값을 즉시 네트워크 레이어에 반영합니다.
     */
    fun setAllowSelfSignedSsl(enabled: Boolean) {
        _allowSelfSignedSsl.value = enabled
    }

    /**
     * 표준 시스템 인증서 검증을 수행하는 OkHttpClient.
     */
    private val strictOkHttpClient: OkHttpClient by lazy {
        baseBuilder().build()
    }

    /**
     * 개인 NAS의 자체 서명 SSL 인증서 및 호스트명 불일치를 허용하는 OkHttpClient.
     */
    private val trustAllOkHttpClient: OkHttpClient by lazy {
        createTrustAllClient()
    }

    /**
     * 현재 SSL 허용 옵션에 맞는 활성 OkHttpClient를 반환합니다.
     */
    val okHttpClient: OkHttpClient
        get() = if (_allowSelfSignedSsl.value) trustAllOkHttpClient else strictOkHttpClient

    init {
        // 프록시 상태가 변경되면 두 클라이언트의 유휴 소켓 풀을 모두 정리해 새 라우팅으로 전환
        NetworkProxyManager.registerOnProxyChangedListener {
            strictOkHttpClient.connectionPool.evictAll()
            trustAllOkHttpClient.connectionPool.evictAll()
        }
    }

    private fun baseBuilder(): OkHttpClient.Builder {
        return OkHttpClient.Builder()
            .proxySelector(NetworkProxyManager.okHttpProxySelector)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
    }

    @SuppressLint("TrustAllX509TrustManager", "CustomX509TrustManager")
    private fun createTrustAllClient(): OkHttpClient {
        return try {
            val trustAllManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }

            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
            }

            val trustAllHostnameVerifier = HostnameVerifier { _, _ -> true }

            baseBuilder()
                .sslSocketFactory(sslContext.socketFactory, trustAllManager)
                .hostnameVerifier(trustAllHostnameVerifier)
                .build()
        } catch (e: Exception) {
            AppErrorLogger.logError(
                tag = "NetworkSSL",
                message = "자체 서명 SSL 허용 OkHttpClient 초기화 실패 — 기본 클라이언트로 폴백합니다.",
                throwable = e
            )
            strictOkHttpClient
        }
    }
}
