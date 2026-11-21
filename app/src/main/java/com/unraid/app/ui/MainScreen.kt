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
import com.unraid.app.data.model.AutoDockerOverride
import com.unraid.app.data.model.DockerContainerItem
import com.unraid.app.data.model.WebServiceSource
import com.unraid.app.plugin.LauncherService
import com.unraid.app.plugin.ServicePluginRegistry
import com.unraid.app.ui.dashboard.DashboardScreen
import com.unraid.app.ui.dashboard.DashboardViewModel
import com.unraid.app.ui.navigation.AppDestination
import com.unraid.app.ui.settings.SettingsScreen
import com.unraid.app.ui.settings.SettingsViewModel
import com.unraid.app.ui.webview.DockerWebLauncherScreen
import com.unraid.app.ui.webview.FullScreenDockerWebView
import com.unraid.app.ui.webview.WebViewPoolManager
import kotlinx.coroutines.launch

private enum class ActiveLaunchMode {
    WEBVIEW,
    NATIVE_PLUGIN
}

/**
 * 하단 내비게이션 바, 아이콘 그리드 런처, 100% 전체 화면(PWA) 웹뷰,
 * 그리고 서비스별 전용 네이티브 UI 플러그인 전환을 총괄하는 최상위 화면.
 */
@Composable
fun MainScreen(
    dashboardViewModel: DashboardViewModel,
    settingsViewModel: SettingsViewModel,
    preferencesRepository: AppPreferencesRepository,
    webViewPoolManager: WebViewPoolManager
) {
    var currentTab by rememberSaveable { mutableStateOf(AppDestination.DASHBOARD) }
    var activeSessionServiceId by rememberSaveable { mutableStateOf<String?>(null) }
    var activeLaunchMode by rememberSaveable { mutableStateOf(ActiveLaunchMode.WEBVIEW) }
    var activeServiceSnapshot by remember { mutableStateOf<LauncherService?>(null) }

    val dashboardUiState by dashboardViewModel.uiState.collectAsStateWithLifecycle()
    val manualBookmarks by preferencesRepository.dockerServicesFlow
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val pinnedAutoDockerIds by preferencesRepository.pinnedAutoDockerIdsFlow
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val autoDockerOverrides by preferencesRepository.autoDockerOverridesFlow
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    val coroutineScope = rememberCoroutineScope()

    val allDetectedContainers = dashboardUiState.data?.containers.orEmpty()

    // 사용자가 런처에 표시하도록 선택한 자동 감지 도커 + 수동 등록 앱만 통합 구성
    val launcherServices = remember(
        allDetectedContainers,
        manualBookmarks,
        pinnedAutoDockerIds,
        autoDockerOverrides
    ) {
        val manualItems = manualBookmarks.map { bookmark ->
            LauncherService(
                id = bookmark.id,
                name = bookmark.name,
                url = bookmark.url,
                source = WebServiceSource.MANUAL,
                isRunning = true,
                iconUrl = bookmark.iconUrl,
                preferNativeUi = bookmark.preferNativeUi
            )
        }
        val manualNamesLower = manualItems.map { it.name.trim().lowercase() }.toSet()

        val pinnedAutoItems = allDetectedContainers
            .filter { container ->
                val override = autoDockerOverrides[container.autoServiceId]
                val effectiveUrl = override?.customUrl ?: container.webUiUrl
                container.autoServiceId in pinnedAutoDockerIds &&
                    !effectiveUrl.isNullOrBlank() &&
                    container.name.trim().lowercase() !in manualNamesLower
            }
            .map { container ->
                val override = autoDockerOverrides[container.autoServiceId]
                LauncherService(
                    id = container.autoServiceId,
                    name = override?.customName ?: container.name,
                    url = override?.customUrl ?: container.webUiUrl.orEmpty(),
                    source = WebServiceSource.AUTO_DOCKER,
                    isRunning = container.isRunning,
                    iconUrl = container.iconUrl,
                    containerName = container.name,
                    imageName = container.image,
                    preferNativeUi = override?.preferNativeUi ?: true
                )
            }

        pinnedAutoItems + manualItems
    }

    val launchService: (LauncherService, ActiveLaunchMode) -> Unit = { service, mode ->
        activeServiceSnapshot = service
        activeSessionServiceId = service.id
        activeLaunchMode = mode
        currentTab = AppDestination.DOCKER_WEBVIEW
        coroutineScope.launch {
            preferencesRepository.setSelectedServiceId(service.id)
        }
    }

    val launchDefault: (LauncherService) -> Unit = { service ->
        val matchingPlugin = ServicePluginRegistry.findMatchingPlugin(service)
        val targetMode = if (matchingPlugin != null && service.preferNativeUi) {
            ActiveLaunchMode.NATIVE_PLUGIN
        } else {
            ActiveLaunchMode.WEBVIEW
        }
        launchService(service, targetMode)
    }

    val openContainerFromDashboard: (DockerContainerItem) -> Unit = { container ->
        val existingInLauncher = launcherServices.firstOrNull {
            it.id == container.autoServiceId || it.name.equals(container.name, ignoreCase = true)
        }
        val target = existingInLauncher ?: run {
            val override = autoDockerOverrides[container.autoServiceId]
            val effectiveUrl = override?.customUrl ?: container.webUiUrl
            effectiveUrl?.let { url ->
                LauncherService(
                    id = container.autoServiceId,
                    name = override?.customName ?: container.name,
                    url = url,
                    source = WebServiceSource.AUTO_DOCKER,
                    isRunning = container.isRunning,
                    iconUrl = container.iconUrl,
                    containerName = container.name,
                    imageName = container.image,
                    preferNativeUi = override?.preferNativeUi ?: true
                )
            }
        }
        if (target != null) {
            launchDefault(target)
        }
    }

    val activeSessionService = remember(activeSessionServiceId, launcherServices, activeServiceSnapshot) {
        val targetId = activeSessionServiceId ?: return@remember null
        launcherServices.firstOrNull { it.id == targetId }
            ?: activeServiceSnapshot?.takeIf { it.id == targetId }
    }

    val quickSwitchServices = remember(launcherServices, activeSessionService) {
        if (activeSessionService != null && launcherServices.none { it.id == activeSessionService.id }) {
            listOf(activeSessionService) + launcherServices
        } else {
            launcherServices
        }
    }

    if (activeSessionService != null) {
        val matchingPlugin = remember(activeSessionService) {
            ServicePluginRegistry.findMatchingPlugin(activeSessionService)
        }

        if (activeLaunchMode == ActiveLaunchMode.NATIVE_PLUGIN && matchingPlugin != null) {
            // 서비스 전용 네이티브 UI 플러그인 진입 (예: 3단계 Emby libmpv 플레이어)
            matchingPlugin.Content(
                service = activeSessionService,
                onOpenWebViewFallback = {
                    activeLaunchMode = ActiveLaunchMode.WEBVIEW
                },
                onExit = {
                    activeSessionServiceId = null
                    currentTab = AppDestination.DOCKER_WEBVIEW
                }
            )
        } else {
            // 100% 전체 화면(PWA 모드) 웹뷰: 상·하단 바 완전 숨김 + 우측 드래그 플로팅 메뉴
            FullScreenDockerWebView(
                activeService = activeSessionService,
                quickSwitchServices = quickSwitchServices,
                webViewPoolManager = webViewPoolManager,
                onSelectService = { target -> launchDefault(target) },
                onOpenNativePlugin = if (matchingPlugin != null) {
                    { activeLaunchMode = ActiveLaunchMode.NATIVE_PLUGIN }
                } else {
                    null
                },
                onNavigateToDashboard = {
                    activeSessionServiceId = null
                    currentTab = AppDestination.DASHBOARD
                },
                onNavigateToLauncher = {
                    activeSessionServiceId = null
                    currentTab = AppDestination.DOCKER_WEBVIEW
                },
                onNavigateToSettings = {
                    activeSessionServiceId = null
                    currentTab = AppDestination.SETTINGS
                }
            )
        }
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
                            onOpenDockerWebUi = openContainerFromDashboard
                        )
                    }

                    AppDestination.DOCKER_WEBVIEW -> {
                        DockerWebLauncherScreen(
                            launcherServices = launcherServices,
                            allDetectedContainers = allDetectedContainers,
                            pinnedAutoDockerIds = pinnedAutoDockerIds,
                            manualBookmarks = manualBookmarks,
                            webViewPoolManager = webViewPoolManager,
                            isRefreshingDashboard = dashboardUiState.isRefreshing || dashboardUiState.isLoading,
                            onLaunchDefault = launchDefault,
                            onLaunchWebView = { service ->
                                launchService(service, ActiveLaunchMode.WEBVIEW)
                            },
                            onLaunchNativePlugin = { service ->
                                launchService(service, ActiveLaunchMode.NATIVE_PLUGIN)
                            },
                            onToggleAutoDockerPinned = { serviceId, pinned ->
                                coroutineScope.launch {
                                    preferencesRepository.setAutoDockerPinned(serviceId, pinned)
                                }
                            },
                            onEditService = { service, newName, newUrl ->
                                coroutineScope.launch {
                                    if (service.source == WebServiceSource.MANUAL) {
                                        val existing = manualBookmarks.firstOrNull { it.id == service.id }
                                        if (existing != null) {
                                            preferencesRepository.updateDockerService(
                                                existing.copy(name = newName, url = newUrl)
                                            )
                                        }
                                    } else {
                                        val currentOverride = autoDockerOverrides[service.id] ?: AutoDockerOverride()
                                        preferencesRepository.saveAutoDockerOverride(
                                            serviceId = service.id,
                                            override = currentOverride.copy(
                                                customName = newName,
                                                customUrl = newUrl
                                            )
                                        )
                                    }
                                }
                            },
                            onTogglePreferNativeUi = { service, preferNative ->
                                coroutineScope.launch {
                                    if (service.source == WebServiceSource.MANUAL) {
                                        preferencesRepository.updateManualServicePreferNativeUi(
                                            serviceId = service.id,
                                            preferNativeUi = preferNative
                                        )
                                    } else {
                                        val currentOverride = autoDockerOverrides[service.id] ?: AutoDockerOverride()
                                        preferencesRepository.saveAutoDockerOverride(
                                            serviceId = service.id,
                                            override = currentOverride.copy(preferNativeUi = preferNative)
                                        )
                                    }
                                }
                            },
                            onAddManualService = { name, url ->
                                coroutineScope.launch {
                                    preferencesRepository.addDockerService(name, url)
                                }
                            },
                            onDeleteManualService = { serviceId ->
                                webViewPoolManager.evict(serviceId)
                                coroutineScope.launch {
                                    preferencesRepository.deleteDockerService(serviceId)
                                }
                            },
                            onRefreshAutoDetected = {
                                dashboardViewModel.refresh(isPullToRefresh = true)
                            }
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
