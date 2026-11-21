package com.unraid.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import com.unraid.app.data.local.AppErrorLogger
import com.unraid.app.data.local.AppPreferencesRepository
import com.unraid.app.data.local.SecureCredentialStore
import com.unraid.app.data.remote.UnraidGraphQlClient
import com.unraid.app.network.NetworkModule
import com.unraid.app.network.TailscaleConnectionManager
import com.unraid.app.player.PlayerSettingsStore
import com.unraid.app.plugin.ExternalPluginManager
import com.unraid.app.plugin.PluginStorageVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.Call

/**
 * 앱 전역 싱글톤 의존성, 에러 로거, 내장 Tailscale(tsnet) 자동 전환 엔진,
 * 그리고 Tailscale 프록시/사설 SSL 설정이 자동 반영되는 Coil ImageLoader를 초기화합니다.
 */
class UnraidApp : Application(), ImageLoaderFactory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var preferencesRepository: AppPreferencesRepository
        private set

    lateinit var secureCredentialStore: SecureCredentialStore
        private set

    lateinit var unraidGraphQlClient: UnraidGraphQlClient
        private set

    lateinit var tailscaleConnectionManager: TailscaleConnectionManager
        private set

    lateinit var pluginStorageVault: PluginStorageVault
        private set

    lateinit var externalPluginManager: ExternalPluginManager
        private set

    lateinit var playerSettingsStore: PlayerSettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        // 전역 에러 로거 및 미처리 예외 핸들러 최우선 초기화
        AppErrorLogger.init(this)

        preferencesRepository = AppPreferencesRepository(this)
        secureCredentialStore = SecureCredentialStore(this)
        unraidGraphQlClient = UnraidGraphQlClient { NetworkModule.okHttpClient }
        pluginStorageVault = PluginStorageVault(this)
        externalPluginManager = ExternalPluginManager(
            context = this,
            storageVault = pluginStorageVault,
            httpClientProvider = { NetworkModule.okHttpClient }
        )
        playerSettingsStore = PlayerSettingsStore(this)
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

    /**
     * 모든 Coil 아이콘/이미지 요청이 현재 활성화된 OkHttpClient(Tailscale 프록시 + 자체 서명 SSL 허용)를
     * 실시간으로 경유하도록 전역 ImageLoader를 구성합니다. (SVG 도커 아이콘도 함께 지원)
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .callFactory(Call.Factory { request -> NetworkModule.okHttpClient.newCall(request) })
            .components {
                add(SvgDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}
