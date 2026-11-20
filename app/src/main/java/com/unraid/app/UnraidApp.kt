package com.unraid.app

import android.app.Application
import com.unraid.app.data.local.AppErrorLogger
import com.unraid.app.data.local.AppPreferencesRepository
import com.unraid.app.data.local.SecureCredentialStore
import com.unraid.app.data.remote.UnraidGraphQlClient
import com.unraid.app.network.NetworkModule
import com.unraid.app.network.TailscaleConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 앱 전역 싱글톤 의존성, 에러 로거, 내장 Tailscale(tsnet) 자동 전환 엔진을 초기화하는 Application 클래스.
 */
class UnraidApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var preferencesRepository: AppPreferencesRepository
        private set

    lateinit var secureCredentialStore: SecureCredentialStore
        private set

    lateinit var unraidGraphQlClient: UnraidGraphQlClient
        private set

    lateinit var tailscaleConnectionManager: TailscaleConnectionManager
        private set

    override fun onCreate() {
        super.onCreate()
        // 전역 에러 로거 및 미처리 예외 핸들러 최우선 초기화
        AppErrorLogger.init(this)

        preferencesRepository = AppPreferencesRepository(this)
        secureCredentialStore = SecureCredentialStore(this)
        unraidGraphQlClient = UnraidGraphQlClient { NetworkModule.okHttpClient }
        tailscaleConnectionManager = TailscaleConnectionManager(
            context = this,
            preferencesRepository = preferencesRepository,
            credentialStore = secureCredentialStore
        )

        // 저장된 자체 서명 SSL 허용 옵션을 네트워크 레이어와 실시간 동기화
        appScope.launch {
            preferencesRepository.allowSelfSignedSslFlow.collectLatest { enabled ->
                NetworkModule.setAllowSelfSignedSsl(enabled)
            }
        }

        // 홈 Wi-Fi 감지 및 내장 Tailscale(tsnet) 자동 전환 엔진 시작
        tailscaleConnectionManager.startMonitoring()
    }
}
