package com.unraid.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unraid.app.data.model.ConnectionStatus
import com.unraid.app.data.model.DockerContainerItem
import com.unraid.app.data.model.ParityCheckAction
import com.unraid.app.data.model.RoutePhase
import com.unraid.app.data.model.UnraidApiKeyItem
import com.unraid.app.data.model.UnraidSubTab
import com.unraid.app.ui.theme.StatusAmber
import com.unraid.app.ui.theme.StatusBlue
import com.unraid.app.ui.theme.StatusGreen
import com.unraid.app.ui.theme.StatusRed
import kotlinx.coroutines.launch

/**
 * Unraid 네이티브 7개 서브탭 관리 화면
 * (대시보드 · 메인 · 도커 · 공유 · 사용자 · 설정 · 앱 + 5초 실시간 관제 + 웹 UI 비상 바로가기)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNavigateToSettings: () -> Unit,
    onOpenDockerWebUi: (DockerContainerItem) -> Unit,
    onOpenUnraidWebUi: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // 도커 검색·필터 로컬 UI 상태
    var dockerSearchQuery by rememberSaveable { mutableStateOf("") }
    var dockerFilterMode by rememberSaveable { mutableStateOf(DockerFilterMode.ALL) }

    // 각종 중요/파괴적 제어 확인 다이얼로그 상태
    var pendingArrayStart by remember { mutableStateOf<Boolean?>(null) }
    var arrayConfirmStep by remember { mutableIntStateOf(1) }
    var pendingParityAction by remember { mutableStateOf<ParityCheckAction?>(null) }
    var pendingUpdateContainer by remember { mutableStateOf<DockerContainerItem?>(null) }
    var showCreateApiKeyDialog by remember { mutableStateOf(false) }
    var pendingDeleteApiKey by remember { mutableStateOf<UnraidApiKeyItem?>(null) }
    var showInstallPluginDialog by remember { mutableStateOf(false) }

    // Activity Lifecycle(ON_RESUME/ON_STOP) + Composable 이탈 시 5초 실시간 폴링 즉시 중지
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.setScreenResumed(true)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> viewModel.setScreenResumed(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.setScreenResumed(true)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setScreenResumed(false)
        }
    }

    LaunchedEffect(state.feedbackMessage) {
        val message = state.feedbackMessage
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearFeedbackMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Unraid",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                ConnectionRouteBadge(
                                    status = state.connectionStatus,
                                    onClick = onNavigateToSettings
                                )
                            }
                            if (state.serverUrl.isNotBlank()) {
                                Text(
                                    text = state.serverUrl,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    actions = {
                        if (state.isConfigured) {
                            IconButton(
                                onClick = { onOpenUnraidWebUi(state.selectedSubTab.webUiPath) }
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = "현재 탭 Unraid 웹 UI 열기"
                                )
                            }
                            IconButton(
                                onClick = { viewModel.refreshCurrentTab(isPullToRefresh = true) }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = "새로고침"
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )

                // 가로 스크롤 가능한 7개 서브탭 칩 바
                if (state.isConfigured) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = UnraidSubTab.entries,
                            key = { it.name }
                        ) { tab ->
                            val selected = state.selectedSubTab == tab
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.selectSubTab(tab) },
                                label = {
                                    Text(
                                        text = tab.labelKo,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    selectedLabelColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                !state.isConfigured -> {
                    UnconfiguredDashboardCard(onNavigateToSettings = onNavigateToSettings)
                }

                state.isLoading && state.data == null &&
                    (state.selectedSubTab == UnraidSubTab.DASHBOARD ||
                        state.selectedSubTab == UnraidSubTab.MAIN ||
                        state.selectedSubTab == UnraidSubTab.DOCKER) -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Unraid 서버 상태를 불러오는 중...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                else -> {
                    PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = { viewModel.refreshCurrentTab(isPullToRefresh = true) },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // 공통 기본 대시보드 연결 오류 배너
                            if (!state.errorMessage.isNullOrBlank() &&
                                (state.selectedSubTab == UnraidSubTab.DASHBOARD ||
                                    state.selectedSubTab == UnraidSubTab.MAIN ||
                                    state.selectedSubTab == UnraidSubTab.DOCKER)
                            ) {
                                item(key = "error_banner") {
                                    ErrorBannerCard(
                                        message = state.errorMessage.orEmpty(),
                                        onRetry = { viewModel.refreshCurrentTab(isPullToRefresh = true) },
                                        onOpenSettings = onNavigateToSettings
                                    )
                                }
                            }

                            when (state.selectedSubTab) {
                                UnraidSubTab.DASHBOARD -> {
                                    dashboardOverviewTabContent(
                                        state = state,
                                        onRetryMetricsAndOverview = {
                                            viewModel.refreshCurrentTab(isPullToRefresh = true)
                                        },
                                        onOpenUnraidWebUi = onOpenUnraidWebUi
                                    )
                                }

                                UnraidSubTab.MAIN -> {
                                    dashboardMainTabContent(
                                        state = state,
                                        onRequestArrayStateChange = { start ->
                                            arrayConfirmStep = 1
                                            pendingArrayStart = start
                                        },
                                        onRequestParityAction = { action ->
                                            pendingParityAction = action
                                        },
                                        onRetryParityHistory = {
                                            viewModel.refreshCurrentTab(isPullToRefresh = true)
                                        },
                                        onOpenUnraidWebUi = onOpenUnraidWebUi
                                    )
                                }

                                UnraidSubTab.DOCKER -> {
                                    dashboardDockerTabContent(
                                        state = state,
                                        searchQuery = dockerSearchQuery,
                                        filterMode = dockerFilterMode,
                                        onSearchQueryChange = { dockerSearchQuery = it },
                                        onFilterModeChange = { dockerFilterMode = it },
                                        onDockerAction = { container, action ->
                                            viewModel.performDockerAction(container, action)
                                        },
                                        onRequestUpdateContainer = { container ->
                                            pendingUpdateContainer = container
                                        },
                                        onOpenDockerWebUi = onOpenDockerWebUi,
                                        onOpenUnraidWebUi = onOpenUnraidWebUi
                                    )
                                }

                                UnraidSubTab.SHARES -> {
                                    dashboardSharesTabContent(
                                        state = state,
                                        onRetry = { viewModel.refreshCurrentTab(isPullToRefresh = true) },
                                        onOpenUnraidWebUi = onOpenUnraidWebUi
                                    )
                                }

                                UnraidSubTab.USERS -> {
                                    dashboardUsersTabContent(
                                        state = state,
                                        onOpenCreateApiKeyDialog = { showCreateApiKeyDialog = true },
                                        onRequestDeleteApiKey = { keyItem -> pendingDeleteApiKey = keyItem },
                                        onRetry = { viewModel.refreshCurrentTab(isPullToRefresh = true) },
                                        onOpenUnraidWebUi = onOpenUnraidWebUi
                                    )
                                }

                                UnraidSubTab.SETTINGS -> {
                                    dashboardSettingsTabContent(
                                        state = state,
                                        onRetry = { viewModel.refreshCurrentTab(isPullToRefresh = true) },
                                        onOpenUnraidWebUi = onOpenUnraidWebUi
                                    )
                                }

                                UnraidSubTab.APPS -> {
                                    dashboardAppsTabContent(
                                        state = state,
                                        onOpenInstallPluginDialog = { showInstallPluginDialog = true },
                                        onRetry = { viewModel.refreshCurrentTab(isPullToRefresh = true) },
                                        onOpenUnraidWebUi = onOpenUnraidWebUi
                                    )
                                }
                            }

                            item(key = "footer_space") {
                                Spacer(modifier = Modifier.height(14.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // 1. 어레이 시작/중지 2단계 확인 다이얼로그
    pendingArrayStart?.let { startArray ->
        ArrayControlTwoStepDialog(
            startArray = startArray,
            step = arrayConfirmStep,
            onNextStep = { arrayConfirmStep = 2 },
            onConfirmExecute = {
                pendingArrayStart = null
                arrayConfirmStep = 1
                viewModel.setArrayState(start = startArray)
            },
            onDismiss = {
                pendingArrayStart = null
                arrayConfirmStep = 1
            }
        )
    }

    // 2. 패리티 체크 제어 확인 다이얼로그
    pendingParityAction?.let { action ->
        ParityControlConfirmDialog(
            action = action,
            onConfirm = {
                pendingParityAction = null
                viewModel.controlParityCheck(action)
            },
            onDismiss = { pendingParityAction = null }
        )
    }

    // 3. 도커 컨테이너 업데이트 확인 다이얼로그
    pendingUpdateContainer?.let { container ->
        DockerUpdateConfirmDialog(
            container = container,
            onConfirm = {
                pendingUpdateContainer = null
                viewModel.updateDockerContainer(container)
            },
            onDismiss = { pendingUpdateContainer = null }
        )
    }

    // 4. 새 API 키 생성 다이얼로그
    if (showCreateApiKeyDialog) {
        CreateApiKeyDialog(
            onConfirm = { name, desc, roles ->
                showCreateApiKeyDialog = false
                viewModel.createApiKey(name, desc, roles)
            },
            onDismiss = { showCreateApiKeyDialog = false }
        )
    }

    // 5. 생성된 API 키 결과 다이얼로그
    state.newlyCreatedApiKey?.let { created ->
        CreatedApiKeyResultDialog(
            result = created,
            onCopied = {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("API 키를 클립보드에 복사했습니다.")
                }
            },
            onDismiss = { viewModel.clearNewlyCreatedApiKey() }
        )
    }

    // 6. API 키 삭제 확인 다이얼로그
    pendingDeleteApiKey?.let { keyItem ->
        DeleteApiKeyConfirmDialog(
            keyItem = keyItem,
            onConfirm = {
                pendingDeleteApiKey = null
                viewModel.deleteApiKey(keyItem.id, keyItem.name)
            },
            onDismiss = { pendingDeleteApiKey = null }
        )
    }

    // 7. .plg URL 플러그인 설치 다이얼로그
    if (showInstallPluginDialog) {
        InstallPluginByUrlDialog(
            onConfirm = { url ->
                showInstallPluginDialog = false
                viewModel.installPluginByUrl(url)
            },
            onDismiss = { showInstallPluginDialog = false }
        )
    }
}

@Composable
private fun ConnectionRouteBadge(
    status: ConnectionStatus,
    onClick: () -> Unit
) {
    val badgeColor = when (status.phase) {
        RoutePhase.DIRECT_HOME_WIFI -> StatusGreen
        RoutePhase.DIRECT_MANUAL -> StatusBlue
        RoutePhase.TAILSCALE_CONNECTING -> StatusAmber
        RoutePhase.TAILSCALE_CONNECTED -> MaterialTheme.colorScheme.primary
        RoutePhase.TAILSCALE_ERROR -> StatusRed
    }

    Surface(
        onClick = onClick,
        color = badgeColor.copy(alpha = 0.16f),
        shape = RoundedCornerShape(999.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (status.phase == RoutePhase.TAILSCALE_CONNECTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(11.dp),
                    strokeWidth = 1.6.dp,
                    color = badgeColor
                )
            } else {
                val icon = when (status.phase) {
                    RoutePhase.DIRECT_HOME_WIFI, RoutePhase.DIRECT_MANUAL -> Icons.Filled.Wifi
                    RoutePhase.TAILSCALE_CONNECTED -> Icons.Filled.VpnLock
                    RoutePhase.TAILSCALE_ERROR -> Icons.Filled.Warning
                    RoutePhase.TAILSCALE_CONNECTING -> Icons.Filled.VpnLock
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(12.dp)
                )
            }
            Text(
                text = status.badgeLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = badgeColor,
                maxLines = 1
            )
        }
    }
}
