package com.unraid.app

import android.app.Application
import com.unraid.app.data.local.AppErrorLogger
import com.unraid.app.data.local.AppPreferencesRepository
import com.unraid.app.data.local.SecureCredentialStore
import com.unraid.app.data.remote.UnraidGraphQlClient
import com.unraid.app.network.NetworkModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 앱 전역 싱글톤 의존성과 에러 로거를 초기화하는 Application 클래스.
 */
class UnraidApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var preferencesRepository: AppPreferencesRepository
        private set

    lateinit var secureCredentialStore: SecureCredentialStore
        private set

    lateinit var unraidGraphQlClient: UnraidGraphQlClient
        private set

    override fun onCreate() {
        super.onCreate()
        // 전역 에러 로거 및 미처리 예외 핸들러 최우선 초기화
        AppErrorLogger.init(this)

        preferencesRepository = AppPreferencesRepository(this)
        secureCredentialStore = SecureCredentialStore(this)
        unraidGraphQlClient = UnraidGraphQlClient { NetworkModule.okHttpClient }

        // 저장된 자체 서명 SSL 허용 옵션을 네트워크 레이어와 실시간 동기화
        appScope.launch {
            preferencesRepository.allowSelfSignedSslFlow.collectLatest { enabled ->
                NetworkModule.setAllowSelfSignedSsl(enabled)
            }
        }
    }
}
