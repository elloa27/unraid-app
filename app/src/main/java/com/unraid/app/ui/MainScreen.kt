package com.unraid.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unraid.app.data.local.AppPreferencesRepository
import com.unraid.app.ui.dashboard.DashboardScreen
import com.unraid.app.ui.dashboard.DashboardViewModel
import com.unraid.app.ui.navigation.AppDestination
import com.unraid.app.ui.settings.SettingsScreen
import com.unraid.app.ui.settings.SettingsViewModel
import com.unraid.app.ui.webview.DockerWebViewScreen
import com.unraid.app.ui.webview.WebViewPoolManager
import kotlinx.coroutines.launch

/**
 * 하단 내비게이션 바를 중심으로 각 기능 탭을 전환하는 최상위 컨테이너 화면.
 * 추후 3단계에서 'Emby 플레이어' 탭 추가 시 AppDestination 및 when 분기만 확장하면 됩니다.
 */
@Composable
fun MainScreen(
    dashboardViewModel: DashboardViewModel,
    settingsViewModel: SettingsViewModel,
    preferencesRepository: AppPreferencesRepository,
    webViewPoolManager: WebViewPoolManager
) {
    var currentTab by rememberSaveable { mutableStateOf(AppDestination.DASHBOARD) }
    val dockerServices by preferencesRepository.dockerServicesFlow
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val selectedServiceId by preferencesRepository.selectedServiceIdFlow
        .collectAsStateWithLifecycle(initialValue = null)
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // 각 탭 화면이 상단 상태바 패딩을 직접 제어하도록 설정 (웹뷰 전체화면 몰입감 확보)
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                AppDestination.entries.forEach { destination ->
                    val selected = currentTab == destination
                    NavigationBarItem(
                        selected = selected,
                        onClick = { currentTab = destination },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.label
                            )
                        },
                        label = { Text(text = destination.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            when (currentTab) {
                AppDestination.DASHBOARD -> {
                    DashboardScreen(
                        viewModel = dashboardViewModel,
                        onNavigateToSettings = { currentTab = AppDestination.SETTINGS }
                    )
                }

                AppDestination.DOCKER_WEBVIEW -> {
                    DockerWebViewScreen(
                        services = dockerServices,
                        selectedServiceId = selectedServiceId,
                        webViewPoolManager = webViewPoolManager,
                        isTabActive = true,
                        onSelectService = { serviceId ->
                            coroutineScope.launch {
                                preferencesRepository.setSelectedServiceId(serviceId)
                            }
                        },
                        onNavigateToSettings = { currentTab = AppDestination.SETTINGS }
                    )
                }

                AppDestination.SETTINGS -> {
                    SettingsScreen(
                        viewModel = settingsViewModel
                    )
                }
            }
        }
    }
}
