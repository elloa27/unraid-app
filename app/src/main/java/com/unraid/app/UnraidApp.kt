package com.unraid.app

import android.app.Application
import com.unraid.app.data.local.AppErrorLogger
import com.unraid.app.data.local.AppPreferencesRepository
import com.unraid.app.data.local.SecureCredentialStore
import com.unraid.app.data.remote.UnraidGraphQlClient
import com.unraid.app.network.NetworkModule

/**
 * 앱 전역 싱글톤 의존성과 에러 로거를 초기화하는 Application 클래스.
 */
class UnraidApp : Application() {

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
        unraidGraphQlClient = UnraidGraphQlClient(NetworkModule.okHttpClient)
    }
}
