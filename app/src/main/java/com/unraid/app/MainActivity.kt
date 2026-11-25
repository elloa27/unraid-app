package com.unraid.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.unraid.app.ui.MainScreen
import com.unraid.app.ui.dashboard.DashboardViewModel
import com.unraid.app.ui.settings.SettingsViewModel
import com.unraid.app.ui.shares.SharesViewModel
import com.unraid.app.ui.theme.UnraidHubTheme
import com.unraid.app.ui.webview.WebViewPoolManager

/**
 * Unraid Hub 메인 액티비티.
 * 다크 모드 Edge-to-Edge UI와 메모리 기반 WebView 캐시 풀 생명주기를 관리합니다.
 */
class MainActivity : ComponentActivity() {

    private val unraidApp: UnraidApp
        get() = application as UnraidApp

    // 탭 전환 시 WebView 인스턴스를 파괴하지 않고 유지하는 액티비티 범위 캐시 풀
    private val webViewPoolManager = WebViewPoolManager()

    private val dashboardViewModel: DashboardViewModel by viewModels {
        DashboardViewModel.Factory(
            preferencesRepository = unraidApp.preferencesRepository,
            credentialStore = unraidApp.secureCredentialStore,
            graphQlClient = unraidApp.unraidGraphQlClient,
            connectionManager = unraidApp.tailscaleConnectionManager
        )
    }

    private val sharesViewModel: SharesViewModel by viewModels {
        SharesViewModel.Factory(
            preferencesRepository = unraidApp.preferencesRepository,
            credentialStore = unraidApp.secureCredentialStore,
            graphQlClient = unraidApp.unraidGraphQlClient,
            connectionManager = unraidApp.tailscaleConnectionManager,
            smbClient = unraidApp.smbShareClient
        )
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
        SettingsViewModel.Factory(
            preferencesRepository = unraidApp.preferencesRepository,
            credentialStore = unraidApp.secureCredentialStore,
            graphQlClient = unraidApp.unraidGraphQlClient,
            connectionManager = unraidApp.tailscaleConnectionManager,
            smbClient = unraidApp.smbShareClient
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        setContent {
            UnraidHubTheme {
                MainScreen(
                    dashboardViewModel = dashboardViewModel,
                    sharesViewModel = sharesViewModel,
                    settingsViewModel = settingsViewModel,
                    preferencesRepository = unraidApp.preferencesRepository,
                    webViewPoolManager = webViewPoolManager,
                    externalPluginManager = unraidApp.externalPluginManager,
                    pluginStorageVault = unraidApp.pluginStorageVault,
                    playerSettingsStore = unraidApp.playerSettingsStore
                )
            }
        }
    }

    override fun onDestroy() {
        webViewPoolManager.destroyAll()
        super.onDestroy()
    }
}
