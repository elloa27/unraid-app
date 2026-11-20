package com.unraid.app.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 앱 전역에서 재사용하는 네트워크 싱글톤 모듈.
 * 모든 HTTP(S) 통신은 NetworkProxyManager.okHttpProxySelector를 통해 일괄 프록시 제어를 받습니다.
 */
object NetworkModule {

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxySelector(NetworkProxyManager.okHttpProxySelector)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
            .also { client ->
                // 프록시 상태가 변경되면 기존 유휴 소켓을 즉시 정리하여 새 라우팅으로 전환
                NetworkProxyManager.registerOnProxyChangedListener {
                    client.connectionPool.evictAll()
                }
            }
    }
}
