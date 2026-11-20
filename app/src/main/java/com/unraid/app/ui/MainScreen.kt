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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unraid.app.data.local.AppPreferencesRepository
import com.unraid.app.data.model.DockerContainerItem
import com.unraid.app.data.model.WebServiceItem
import com.unraid.app.data.model.WebServiceSource
import com.unraid.app.ui.dashboard.DashboardScreen
import com.unraid.app.ui.dashboard.DashboardViewModel
import com.unraid.app.ui.navigation.AppDestination
import com.unraid.app.ui.settings.SettingsScreen
import com.unraid.app.ui.settings.SettingsViewModel
import com.unraid.app.ui.webview.DockerWebLauncherScreen
import com.unraid.app.ui.webview.FullScreenDockerWebView
import com.unraid.app.ui.webview.WebViewPoolManager
import kotlinx.coroutines.launch

/**
 * 하단 내비게이션 바 및 100% 전체 화면(PWA 모드) 도커 웹뷰 전환을 총괄하는 최상위 화면.
 * - 도커 웹뷰가 열려 있을 때는 상단 바/하단 내비게이션 바를 완전히 숨기고 플로팅 메뉴로 전환합니다.
 * - 추후 3단계 'Emby 플레이어' 탭 추가 시 AppDestination과 when 분기만 확장하면 됩니다.
 */
@Composable
fun MainScreen(
    dashboardViewModel: DashboardViewModel,
    settingsViewModel: SettingsViewModel,
    preferencesRepository: AppPreferencesRepository,
    webViewPoolManager: WebViewPoolManager
) {
    var currentTab by rememberSaveable { mutableStateOf(AppDestination.DASHBOARD) }
    var activeFullScreenServiceId by rememberSaveable { mutableStateOf<String?>(null) }
    var activeServiceSnapshot by remember { mutableStateOf<WebServiceItem?>(null) }

    val dashboardUiState by dashboardViewModel.uiState.collectAsStateWithLifecycle()
    val manualBookmarks by preferencesRepository.dockerServicesFlow
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val coroutineScope = rememberCoroutineScope()

    // Unraid 자동 감지 도커 웹 UI + 설정 수동 등록 북마크 통합 (동일 이름은 수동 등록 우선 적용)
    val allWebServices = remember(dashboardUiState.data?.containers, manualBookmarks) {
        val manualItems = manualBookmarks.map { bookmark ->
            WebServiceItem(
                id = bookmark.id,
                name = bookmark.name,
                url = bookmark.url,
                source = WebServiceSource.MANUAL,
                isRunning = true
            )
        }
        val manualNamesLower = manualItems.map { it.name.trim().lowercase() }.toSet()

        val autoItems = dashboardUiState.data?.containers
            .orEmpty()
            .filter { it.hasWebUi && it.name.trim().lowercase() !in manualNamesLower }
            .map { container ->
                WebServiceItem(
                    id = container.autoServiceId,
                    name = container.name,
                    url = container.webUiUrl.orEmpty(),
                    source = WebServiceSource.AUTO_DOCKER,
                    isRunning = container.isRunning,
                    iconUrl = container.iconUrl
                )
            }

        autoItems + manualItems
    }

    val openFullScreenService: (WebServiceItem) -> Unit = { service ->
        activeServiceSnapshot = service
        activeFullScreenServiceId = service.id
        currentTab = AppDestination.DOCKER_WEBVIEW
        coroutineScope.launch {
            preferencesRepository.setSelectedServiceId(service.id)
        }
    }

    val openContainerWebUi: (DockerContainerItem) -> Unit = { container ->
        // 동일 이름의 수동 커스텀 항목이 있으면 해당 항목을 우선 실행
        val matched = allWebServices.firstOrNull {
            it.name.equals(container.name, ignoreCase = true)
        } ?: container.webUiUrl?.let { url ->
            WebServiceItem(
                id = container.autoServiceId,
                name = container.name,
                url = url,
                source = WebServiceSource.AUTO_DOCKER,
                isRunning = container.isRunning,
                iconUrl = container.iconUrl
            )
        }
        if (matched != null) {
            openFullScreenService(matched)
        }
    }

    // 현재 전체 화면 웹뷰에 표시할 활성 서비스 해석 (대시보드 갱신 중에도 깜박이지 않도록 스냅샷 유지)
    val activeFullScreenService = remember(activeFullScreenServiceId, allWebServices, activeServiceSnapshot) {
        val targetId = activeFullScreenServiceId ?: return@remember null
        allWebServices.firstOrNull { it.id == targetId }
            ?: activeServiceSnapshot?.takeIf { it.id == targetId }
    }

    if (activeFullScreenService != null) {
        // 100% 전체 화면(PWA 모드): 상단 앱 바 및 하단 내비게이션 바 완전히 숨김
        FullScreenDockerWebView(
            activeService = activeFullScreenService,
            allServices = allWebServices,
            webViewPoolManager = webViewPoolManager,
            onSelectService = { target -> openFullScreenService(target) },
            onNavigateToDashboard = {
                activeFullScreenServiceId = null
                currentTab = AppDestination.DASHBOARD
            },
            onNavigateToLauncher = {
                activeFullScreenServiceId = null
                currentTab = AppDestination.DOCKER_WEBVIEW
            },
            onNavigateToSettings = {
                activeFullScreenServiceId = null
                currentTab = AppDestination.SETTINGS
            }
        )
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
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
                            onNavigateToSettings = { currentTab = AppDestination.SETTINGS },
                            onOpenDockerWebUi = openContainerWebUi
                        )
                    }

                    AppDestination.DOCKER_WEBVIEW -> {
                        DockerWebLauncherScreen(
                            allServices = allWebServices,
                            webViewPoolManager = webViewPoolManager,
                            isRefreshingDashboard = dashboardUiState.isRefreshing || dashboardUiState.isLoading,
                            onOpenService = openFullScreenService,
                            onRefreshAutoDetected = { dashboardViewModel.refresh(isPullToRefresh = true) },
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
}
