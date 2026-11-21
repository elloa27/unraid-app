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
import com.unraid.app.network.NetworkProxyManager
import com.unraid.app.player.PlayerSettingsStore
import com.unraid.app.plugin.ExternalPluginManager
import com.unraid.app.plugin.InstalledPlugin
import com.unraid.app.plugin.LauncherService
import com.unraid.app.plugin.PluginBridgeContext
import com.unraid.app.plugin.PluginRuntimeScreen
import com.unraid.app.plugin.PluginStorageVault
import com.unraid.app.ui.dashboard.DashboardScreen
import com.unraid.app.ui.dashboard.DashboardViewModel
import com.unraid.app.ui.navigation.AppDestination
import com.unraid.app.ui.settings.SettingsScreen
import com.unraid.app.ui.settings.SettingsViewModel
import com.unraid.app.ui.webview.DockerWebLauncherScreen
import com.unraid.app.ui.webview.FullScreenDockerWebView
import com.unraid.app.ui.webview.WebViewPoolManager
import kotlinx.coroutines.launch
import java.net.URI

private enum class ActiveLaunchMode {
    WEBVIEW,
    EXTERNAL_PLUGIN
}

/**
 * 하단 내비게이션 바, 아이콘 그리드 런처, 100% 전체 화면(PWA) 웹뷰,
 * 그리고 동적 외부 플러그인 런타임(WebView + window.UnraidHub + 내장 libmpv)을 총괄하는 최상위 화면.
 */
@Composable
fun MainScreen(
    dashboardViewModel: DashboardViewModel,
    settingsViewModel: SettingsViewModel,
    preferencesRepository: AppPreferencesRepository,
    webViewPoolManager: WebViewPoolManager,
    externalPluginManager: ExternalPluginManager,
    pluginStorageVault: PluginStorageVault,
    playerSettingsStore: PlayerSettingsStore
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
    val unraidServerUrl by preferencesRepository.unraidServerUrlFlow
        .collectAsStateWithLifecycle(initialValue = "")
    val tailscaleServerUrl by preferencesRepository.tailscaleServerUrlFlow
        .collectAsStateWithLifecycle(initialValue = "")
    val installedPlugins by externalPluginManager.installedPlugins.collectAsStateWithLifecycle()
    val proxyConfig by NetworkProxyManager.proxyConfig.collectAsStateWithLifecycle()
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
        val matchingPlugin = installedPlugins.firstOrNull { it.matches(service) }
        val targetMode = if (matchingPlugin != null && service.preferNativeUi) {
            ActiveLaunchMode.EXTERNAL_PLUGIN
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
        val matchingPlugin: InstalledPlugin? = remember(activeSessionService, installedPlugins) {
            installedPlugins.firstOrNull { it.matches(activeSessionService) }
        }

        if (activeLaunchMode == ActiveLaunchMode.EXTERNAL_PLUGIN && matchingPlugin != null) {
            val bridgeContext = remember(
                matchingPlugin.id,
                matchingPlugin.version,
                activeSessionService,
                unraidServerUrl,
                tailscaleServerUrl,
                proxyConfig.isActive
            ) {
                buildPluginBridgeContext(
                    plugin = matchingPlugin,
                    service = activeSessionService,
                    unraidServerUrl = unraidServerUrl,
                    tailscaleServerUrl = tailscaleServerUrl,
                    isTailscaleActive = proxyConfig.isActive
                )
            }

            // 설치된 외부 플러그인 런타임 실행 (가상 HTTPS 도메인 + window.UnraidHub + 내장 libmpv)
            PluginRuntimeScreen(
                plugin = matchingPlugin,
                bridgeContext = bridgeContext,
                pluginsRootDir = externalPluginManager.getPluginsRootDir(),
                storageVault = pluginStorageVault,
                playerSettingsStore = playerSettingsStore,
                onOpenWebViewFallback = {
                    activeLaunchMode = ActiveLaunchMode.WEBVIEW
                },
                onNavigateToDashboard = {
                    activeSessionServiceId = null
                    currentTab = AppDestination.DASHBOARD
                },
                onExitToLauncher = {
                    activeSessionServiceId = null
                    currentTab = AppDestination.DOCKER_WEBVIEW
                },
                onNavigateToSettings = {
                    activeSessionServiceId = null
                    currentTab = AppDestination.SETTINGS
                }
            )
        } else {
            // 100% 전체 화면(PWA 모드) 웹뷰: 상·하단 바 완전 숨김 + 우측 드래그 플로팅 메뉴
            FullScreenDockerWebView(
                activeService = activeSessionService,
                quickSwitchServices = quickSwitchServices,
                installedPlugins = installedPlugins,
                webViewPoolManager = webViewPoolManager,
                onSelectService = { target -> launchDefault(target) },
                onOpenPlugin = if (matchingPlugin != null) {
                    { activeLaunchMode = ActiveLaunchMode.EXTERNAL_PLUGIN }
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
                            installedPlugins = installedPlugins,
                            webViewPoolManager = webViewPoolManager,
                            isRefreshingDashboard = dashboardUiState.isRefreshing || dashboardUiState.isLoading,
                            onLaunchDefault = launchDefault,
                            onLaunchWebView = { service ->
                                launchService(service, ActiveLaunchMode.WEBVIEW)
                            },
                            onLaunchPlugin = { service ->
                                launchService(service, ActiveLaunchMode.EXTERNAL_PLUGIN)
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
                            viewModel = settingsViewModel,
                            externalPluginManager = externalPluginManager,
                            playerSettingsStore = playerSettingsStore
                        )
                    }
                }
            }
        }
    }
}

/**
 * 서비스 주소와 Unraid Tailscale URL 설정을 바탕으로 플러그인에 전달할 컨텍스트를 구성합니다.
 * - Tailscale용 Unraid URL이 지정돼 있고 서비스 호스트가 기본 Unraid 호스트와 같으면 Tailscale 호스트로 치환한 주소를 생성합니다.
 * - 서브넷 라우터 환경(Tailscale용 URL 미입력)에서는 기존 서비스 URL을 그대로 사용합니다.
 */
private fun buildPluginBridgeContext(
    plugin: InstalledPlugin,
    service: LauncherService,
    unraidServerUrl: String,
    tailscaleServerUrl: String,
    isTailscaleActive: Boolean
): PluginBridgeContext {
    val cleanServiceUrl = service.url.trim().trimEnd('/')
    val computedTailscaleUrl = computeTailscaleServiceUrl(
        serviceUrl = cleanServiceUrl,
        unraidServerUrl = unraidServerUrl,
        tailscaleServerUrl = tailscaleServerUrl
    )
    val activeBaseUrl = if (isTailscaleActive && computedTailscaleUrl.isNotBlank()) {
        computedTailscaleUrl
    } else {
        cleanServiceUrl
    }

    return PluginBridgeContext(
        pluginId = plugin.id,
        pluginVersion = plugin.version,
        serviceName = service.name,
        serviceUrl = cleanServiceUrl,
        tailscaleUrl = computedTailscaleUrl,
        activeBaseUrl = activeBaseUrl,
        isTailscaleActive = isTailscaleActive
    )
}

private fun computeTailscaleServiceUrl(
    serviceUrl: String,
    unraidServerUrl: String,
    tailscaleServerUrl: String
): String {
    if (tailscaleServerUrl.isBlank()) return serviceUrl
    return runCatching {
        val svcUri = URI(serviceUrl)
        val tsUri = URI(tailscaleServerUrl.trim())
        val baseUri = unraidServerUrl.trim().takeIf { it.isNotBlank() }?.let { URI(it) }

        val tsHost = tsUri.host?.takeIf { it.isNotBlank() } ?: return serviceUrl
        val svcHost = svcUri.host?.takeIf { it.isNotBlank() } ?: return serviceUrl
        val baseHost = baseUri?.host

        if (baseHost != null && svcHost.equals(baseHost, ignoreCase = true)) {
            val scheme = svcUri.scheme ?: tsUri.scheme ?: "http"
            val portPart = if (svcUri.port > 0) ":${svcUri.port}" else ""
            val pathPart = svcUri.rawPath.orEmpty().trimEnd('/')
            "$scheme://$tsHost$portPart$pathPart"
        } else {
            serviceUrl
        }
    }.getOrDefault(serviceUrl)
}
