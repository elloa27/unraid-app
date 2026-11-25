package com.unraid.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.unraid.app.data.local.AppPreferencesRepository
import com.unraid.app.data.local.SecureCredentialStore
import com.unraid.app.data.model.ConnectionStatus
import com.unraid.app.data.model.CreatedApiKeyResult
import com.unraid.app.data.model.DockerAction
import com.unraid.app.data.model.DockerContainerItem
import com.unraid.app.data.model.MetricSample
import com.unraid.app.data.model.ParityCheckAction
import com.unraid.app.data.model.ParityHistoryItem
import com.unraid.app.data.model.RoutePhase
import com.unraid.app.data.model.SystemMetricsData
import com.unraid.app.data.model.SystemOverviewData
import com.unraid.app.data.model.UnraidDashboardData
import com.unraid.app.data.model.UnraidPluginItem
import com.unraid.app.data.model.UnraidSettingsSummary
import com.unraid.app.data.model.UnraidShareItem
import com.unraid.app.data.model.UnraidSubTab
import com.unraid.app.data.model.UsersAndApiKeysData
import com.unraid.app.data.model.resolveEffectiveUnraidUrl
import com.unraid.app.data.remote.UnraidGraphQlClient
import com.unraid.app.network.TailscaleConnectionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DashboardUiState(
    val isConfigured: Boolean = false,
    val serverUrl: String = "",
    val connectionStatus: ConnectionStatus = ConnectionStatus(),
    val selectedSubTab: UnraidSubTab = UnraidSubTab.DASHBOARD,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val data: UnraidDashboardData? = null,
    val errorMessage: String? = null,
    val actionInProgressContainerId: String? = null,
    val feedbackMessage: String? = null,

    // 대시보드 탭: 5초 실시간 CPU/RAM 관제 및 시스템 개요/알림
    val isMetricsPollingActive: Boolean = false,
    val metrics: SystemMetricsData? = null,
    val metricsHistory: List<MetricSample> = emptyList(),
    val metricsError: String? = null,
    val overview: SystemOverviewData? = null,
    val isOverviewLoading: Boolean = false,
    val overviewLoaded: Boolean = false,
    val overviewError: String? = null,

    // 메인 탭: 어레이·패리티 제어 및 이력
    val isMainActionInProgress: Boolean = false,
    val parityHistory: List<ParityHistoryItem> = emptyList(),
    val isParityHistoryLoading: Boolean = false,
    val parityHistoryLoaded: Boolean = false,
    val parityHistoryError: String? = null,

    // 공유 탭
    val shares: List<UnraidShareItem> = emptyList(),
    val isSharesLoading: Boolean = false,
    val sharesLoaded: Boolean = false,
    val sharesError: String? = null,

    // 사용자 및 API 키 탭
    val usersData: UsersAndApiKeysData? = null,
    val isUsersLoading: Boolean = false,
    val usersLoaded: Boolean = false,
    val usersError: String? = null,
    val isUserActionInProgress: Boolean = false,
    val newlyCreatedApiKey: CreatedApiKeyResult? = null,

    // 설정 탭
    val settingsSummary: UnraidSettingsSummary? = null,
    val isSettingsLoading: Boolean = false,
    val settingsLoaded: Boolean = false,
    val settingsError: String? = null,

    // 앱(플러그인) 탭
    val plugins: List<UnraidPluginItem> = emptyList(),
    val isPluginsLoading: Boolean = false,
    val pluginsLoaded: Boolean = false,
    val pluginsError: String? = null,
    val isPluginInstallInProgress: Boolean = false
)

/**
 * Unraid 네이티브 7개 서브탭 상태, 5초 실시간 CPU/메모리 폴링,
 * 서브탭별 지연 로드(Lazy Load)·캐싱 및 홈 Wi-Fi/Tailscale URL 자동 반영을 관리하는 ViewModel.
 */
class DashboardViewModel(
    private val preferencesRepository: AppPreferencesRepository,
    private val credentialStore: SecureCredentialStore,
    private val graphQlClient: UnraidGraphQlClient,
    private val connectionManager: TailscaleConnectionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var currentEffectiveUrl: String = ""
    private var currentApiKey: String = ""
    private var currentRouteReady: Boolean = false
    private var lastAutoLoadedSignature: String = ""

    private var isScreenVisibleAndResumed: Boolean = false
    private var metricsPollingJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                preferencesRepository.unraidServerUrlFlow,
                preferencesRepository.tailscaleServerUrlFlow,
                credentialStore.apiKeyFlow,
                connectionManager.status
            ) { homeUrl, tailscaleUrl, key, connStatus ->
                val effectiveUrl = resolveEffectiveUnraidUrl(
                    homeUrl = homeUrl,
                    tailscaleUrl = tailscaleUrl,
                    useTailscale = connStatus.isUsingTailscale
                )
                Quadruple(effectiveUrl, key, connStatus, homeUrl)
            }.collect { (effectiveUrl, key, connStatus, _) ->
                currentEffectiveUrl = effectiveUrl
                currentApiKey = key
                val configured = effectiveUrl.isNotBlank() && key.isNotBlank()
                currentRouteReady = connStatus.phase != RoutePhase.TAILSCALE_CONNECTING

                _uiState.update {
                    it.copy(
                        isConfigured = configured,
                        serverUrl = effectiveUrl,
                        connectionStatus = connStatus,
                        errorMessage = if (!configured) null else it.errorMessage
                    )
                }

                val signature = "$effectiveUrl|$key|${connStatus.phase}|${connStatus.proxyPort}"
                if (configured && currentRouteReady && lastAutoLoadedSignature != signature) {
                    lastAutoLoadedSignature = signature
                    resetCachedTabFlags()
                    refresh(isPullToRefresh = false)
                    loadDataForSubTab(_uiState.value.selectedSubTab, forceReload = true)
                }

                syncMetricsPolling()
            }
        }
    }

    /**
     * DashboardScreen의 화면 표시 및 Activity Lifecycle(ON_RESUME / ON_STOP) 상태 반영.
     * - 다른 하단 탭 이동, 전체 화면 웹뷰 진입, 백그라운드 전환 시 즉시 5초 폴링을 중지합니다.
     */
    fun setScreenResumed(isResumed: Boolean) {
        if (isScreenVisibleAndResumed == isResumed) return
        isScreenVisibleAndResumed = isResumed
        syncMetricsPolling()
    }

    /**
     * 7개 서브탭 전환 + 해당 탭 지연 로드 + 대시보드 탭 이탈 시 5초 폴링 즉시 중지.
     */
    fun selectSubTab(tab: UnraidSubTab) {
        if (_uiState.value.selectedSubTab == tab) return
        _uiState.update { it.copy(selectedSubTab = tab) }
        syncMetricsPolling()
        if (_uiState.value.isConfigured && currentRouteReady) {
            loadDataForSubTab(tab, forceReload = false)
        }
    }

    private fun syncMetricsPolling() {
        val state = _uiState.value
        val shouldPoll = isScreenVisibleAndResumed &&
            state.selectedSubTab == UnraidSubTab.DASHBOARD &&
            state.isConfigured &&
            currentRouteReady

        if (!shouldPoll) {
            metricsPollingJob?.cancel()
            metricsPollingJob = null
            if (state.isMetricsPollingActive) {
                _uiState.update { it.copy(isMetricsPollingActive = false) }
            }
            return
        }

        if (metricsPollingJob?.isActive == true) return

        _uiState.update { it.copy(isMetricsPollingActive = true) }
        metricsPollingJob = viewModelScope.launch {
            while (isActive) {
                fetchMetricsOnce()
                delay(METRICS_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun fetchMetricsOnce() {
        val url = currentEffectiveUrl
        val key = currentApiKey
        if (url.isBlank() || key.isBlank()) return

        val result = graphQlClient.fetchSystemMetrics(url, key)
        result.onSuccess { metrics ->
            _uiState.update { prev ->
                val nextHistory = if (metrics.cpuPercent != null || metrics.memoryPercent != null) {
                    val sample = MetricSample(
                        cpuPercent = metrics.cpuPercent ?: prev.metricsHistory.lastOrNull()?.cpuPercent ?: 0f,
                        memoryPercent = metrics.memoryPercent ?: prev.metricsHistory.lastOrNull()?.memoryPercent ?: 0f,
                        timestampMillis = metrics.fetchedAtMillis
                    )
                    (prev.metricsHistory + sample).takeLast(MAX_METRIC_HISTORY_SAMPLES)
                } else {
                    prev.metricsHistory
                }
                prev.copy(
                    metrics = metrics,
                    metricsHistory = nextHistory,
                    metricsError = null
                )
            }
        }.onFailure { error ->
            _uiState.update {
                it.copy(
                    metricsError = error.localizedMessage ?: "실시간 CPU/메모리 메트릭을 불러오지 못했습니다."
                )
            }
        }
    }

    private fun resetCachedTabFlags() {
        _uiState.update {
            it.copy(
                metricsHistory = emptyList(),
                metricsError = null,
                overviewLoaded = false,
                parityHistoryLoaded = false,
                sharesLoaded = false,
                usersLoaded = false,
                settingsLoaded = false,
                pluginsLoaded = false
            )
        }
    }

    /**
     * 기본 어레이/디스크/도커 데이터를 새로고침합니다. (외부 도커 런처 새로고침 호환 유지)
     */
    fun refresh(isPullToRefresh: Boolean = true) {
        if (currentEffectiveUrl.isBlank() || currentApiKey.isBlank()) {
            _uiState.update {
                it.copy(
                    isConfigured = false,
                    isLoading = false,
                    isRefreshing = false
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = !isPullToRefresh && it.data == null,
                    isRefreshing = isPullToRefresh,
                    errorMessage = null
                )
            }

            val result = graphQlClient.fetchDashboard(currentEffectiveUrl, currentApiKey)
            result.onSuccess { dashboardData ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        data = dashboardData,
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = error.localizedMessage ?: "Unraid 서버 데이터를 불러오지 못했습니다."
                    )
                }
            }
        }
    }

    /**
     * 현재 선택된 서브탭과 필요한 기본 데이터를 함께 새로고침합니다.
     */
    fun refreshCurrentTab(isPullToRefresh: Boolean = true) {
        if (!hasValidCredentials()) return
        val tab = _uiState.value.selectedSubTab
        when (tab) {
            UnraidSubTab.DASHBOARD -> {
                refresh(isPullToRefresh = isPullToRefresh)
                viewModelScope.launch { fetchMetricsOnce() }
                loadOverview(force = true)
            }
            UnraidSubTab.MAIN -> {
                refresh(isPullToRefresh = isPullToRefresh)
                loadParityHistory(force = true)
            }
            UnraidSubTab.DOCKER -> {
                refresh(isPullToRefresh = isPullToRefresh)
            }
            UnraidSubTab.SHARES -> {
                loadShares(force = true, isPullToRefresh = isPullToRefresh)
            }
            UnraidSubTab.USERS -> {
                loadUsers(force = true, isPullToRefresh = isPullToRefresh)
            }
            UnraidSubTab.SETTINGS -> {
                loadSettingsSummary(force = true, isPullToRefresh = isPullToRefresh)
            }
            UnraidSubTab.APPS -> {
                loadPlugins(force = true, isPullToRefresh = isPullToRefresh)
            }
        }
    }

    private fun loadDataForSubTab(tab: UnraidSubTab, forceReload: Boolean) {
        if (!hasValidCredentials()) return
        when (tab) {
            UnraidSubTab.DASHBOARD -> loadOverview(force = forceReload)
            UnraidSubTab.MAIN -> loadParityHistory(force = forceReload)
            UnraidSubTab.DOCKER -> {
                if (_uiState.value.data == null || forceReload) {
                    refresh(isPullToRefresh = false)
                }
            }
            UnraidSubTab.SHARES -> loadShares(force = forceReload, isPullToRefresh = false)
            UnraidSubTab.USERS -> loadUsers(force = forceReload, isPullToRefresh = false)
            UnraidSubTab.SETTINGS -> loadSettingsSummary(force = forceReload, isPullToRefresh = false)
            UnraidSubTab.APPS -> loadPlugins(force = forceReload, isPullToRefresh = false)
        }
    }

    private fun loadOverview(force: Boolean) {
        val current = _uiState.value
        if (!force && (current.overviewLoaded || current.isOverviewLoading)) return
        viewModelScope.launch {
            _uiState.update { it.copy(isOverviewLoading = true, overviewError = null) }
            graphQlClient.fetchSystemOverview(currentEffectiveUrl, currentApiKey)
                .onSuccess { overview ->
                    _uiState.update {
                        it.copy(
                            overview = overview,
                            isOverviewLoading = false,
                            overviewLoaded = true,
                            overviewError = null
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isOverviewLoading = false,
                            overviewLoaded = true,
                            overviewError = error.localizedMessage ?: "시스템 개요 정보를 불러오지 못했습니다."
                        )
                    }
                }
        }
    }

    private fun loadParityHistory(force: Boolean) {
        val current = _uiState.value
        if (!force && (current.parityHistoryLoaded || current.isParityHistoryLoading)) return
        viewModelScope.launch {
            _uiState.update { it.copy(isParityHistoryLoading = true, parityHistoryError = null) }
            graphQlClient.fetchParityHistory(currentEffectiveUrl, currentApiKey)
                .onSuccess { history ->
                    _uiState.update {
                        it.copy(
                            parityHistory = history,
                            isParityHistoryLoading = false,
                            parityHistoryLoaded = true,
                            parityHistoryError = null
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isParityHistoryLoading = false,
                            parityHistoryLoaded = true,
                            parityHistoryError = error.localizedMessage ?: "패리티 체크 이력을 불러오지 못했습니다."
                        )
                    }
                }
        }
    }

    private fun loadShares(force: Boolean, isPullToRefresh: Boolean) {
        val current = _uiState.value
        if (!force && (current.sharesLoaded || current.isSharesLoading)) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSharesLoading = !isPullToRefresh,
                    isRefreshing = isPullToRefresh,
                    sharesError = null
                )
            }
            graphQlClient.fetchShares(currentEffectiveUrl, currentApiKey)
                .onSuccess { shares ->
                    _uiState.update {
                        it.copy(
                            shares = shares,
                            isSharesLoading = false,
                            isRefreshing = false,
                            sharesLoaded = true,
                            sharesError = null
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isSharesLoading = false,
                            isRefreshing = false,
                            sharesLoaded = true,
                            sharesError = error.localizedMessage ?: "공유 폴더 목록을 불러오지 못했습니다."
                        )
                    }
                }
        }
    }

    private fun loadUsers(force: Boolean, isPullToRefresh: Boolean) {
        val current = _uiState.value
        if (!force && (current.usersLoaded || current.isUsersLoading)) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isUsersLoading = !isPullToRefresh,
                    isRefreshing = isPullToRefresh,
                    usersError = null
                )
            }
            graphQlClient.fetchUsersAndApiKeys(currentEffectiveUrl, currentApiKey)
                .onSuccess { usersData ->
                    _uiState.update {
                        it.copy(
                            usersData = usersData,
                            isUsersLoading = false,
                            isRefreshing = false,
                            usersLoaded = true,
                            usersError = null
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isUsersLoading = false,
                            isRefreshing = false,
                            usersLoaded = true,
                            usersError = error.localizedMessage ?: "사용자 및 API 키 정보를 불러오지 못했습니다."
                        )
                    }
                }
        }
    }

    private fun loadSettingsSummary(force: Boolean, isPullToRefresh: Boolean) {
        val current = _uiState.value
        if (!force && (current.settingsLoaded || current.isSettingsLoading)) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSettingsLoading = !isPullToRefresh,
                    isRefreshing = isPullToRefresh,
                    settingsError = null
                )
            }
            graphQlClient.fetchSettingsSummary(currentEffectiveUrl, currentApiKey)
                .onSuccess { summary ->
                    _uiState.update {
                        it.copy(
                            settingsSummary = summary,
                            isSettingsLoading = false,
                            isRefreshing = false,
                            settingsLoaded = true,
                            settingsError = null
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isSettingsLoading = false,
                            isRefreshing = false,
                            settingsLoaded = true,
                            settingsError = error.localizedMessage ?: "시스템 설정 요약 정보를 불러오지 못했습니다."
                        )
                    }
                }
        }
    }

    private fun loadPlugins(force: Boolean, isPullToRefresh: Boolean) {
        val current = _uiState.value
        if (!force && (current.pluginsLoaded || current.isPluginsLoading)) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isPluginsLoading = !isPullToRefresh,
                    isRefreshing = isPullToRefresh,
                    pluginsError = null
                )
            }
            graphQlClient.fetchPlugins(currentEffectiveUrl, currentApiKey)
                .onSuccess { plugins ->
                    _uiState.update {
                        it.copy(
                            plugins = plugins,
                            isPluginsLoading = false,
                            isRefreshing = false,
                            pluginsLoaded = true,
                            pluginsError = null
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isPluginsLoading = false,
                            isRefreshing = false,
                            pluginsLoaded = true,
                            pluginsError = error.localizedMessage ?: "설치된 플러그인 목록을 불러오지 못했습니다."
                        )
                    }
                }
        }
    }

    // =========================================================================
    // 제어 뮤테이션 (어레이 / 패리티 / 도커 / API 키 / 플러그인 설치)
    // =========================================================================

    fun setArrayState(start: Boolean) {
        if (!hasValidCredentials() || _uiState.value.isMainActionInProgress) return
        val label = if (start) "어레이 시작" else "어레이 중지"
        viewModelScope.launch {
            _uiState.update { it.copy(isMainActionInProgress = true, feedbackMessage = null) }
            graphQlClient.setArrayState(currentEffectiveUrl, currentApiKey, start)
                .onSuccess { newState ->
                    _uiState.update {
                        it.copy(
                            isMainActionInProgress = false,
                            feedbackMessage = "$label 요청을 완료했습니다. (상태: $newState)"
                        )
                    }
                    refresh(isPullToRefresh = true)
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isMainActionInProgress = false,
                            feedbackMessage = "$label 실패: ${error.localizedMessage ?: "오류 발생"}"
                        )
                    }
                }
        }
    }

    fun controlParityCheck(action: ParityCheckAction) {
        if (!hasValidCredentials() || _uiState.value.isMainActionInProgress) return
        viewModelScope.launch {
            _uiState.update { it.copy(isMainActionInProgress = true, feedbackMessage = null) }
            graphQlClient.controlParityCheck(currentEffectiveUrl, currentApiKey, action)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isMainActionInProgress = false,
                            feedbackMessage = "패리티 체크 '${action.labelKo}' 명령을 전송했습니다."
                        )
                    }
                    loadParityHistory(force = true)
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isMainActionInProgress = false,
                            feedbackMessage = "패리티 체크 '${action.labelKo}' 실패: ${error.localizedMessage ?: "오류 발생"}"
                        )
                    }
                }
        }
    }

    fun performDockerAction(container: DockerContainerItem, action: DockerAction) {
        if (_uiState.value.actionInProgressContainerId != null) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    actionInProgressContainerId = container.id,
                    feedbackMessage = null
                )
            }

            val result = graphQlClient.controlDockerContainer(
                serverUrl = currentEffectiveUrl,
                apiKey = currentApiKey,
                containerId = container.id,
                action = action
            )

            result.onSuccess {
                _uiState.update {
                    it.copy(
                        actionInProgressContainerId = null,
                        feedbackMessage = "'${container.name}' ${action.labelKo} 명령을 완료했습니다."
                    )
                }
                refresh(isPullToRefresh = true)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        actionInProgressContainerId = null,
                        feedbackMessage = "'${container.name}' ${action.labelKo} 실패: ${error.localizedMessage ?: "오류 발생"}"
                    )
                }
            }
        }
    }

    fun updateDockerContainer(container: DockerContainerItem) {
        if (_uiState.value.actionInProgressContainerId != null) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    actionInProgressContainerId = container.id,
                    feedbackMessage = null
                )
            }

            graphQlClient.updateDockerContainer(
                serverUrl = currentEffectiveUrl,
                apiKey = currentApiKey,
                containerId = container.id
            ).onSuccess {
                _uiState.update {
                    it.copy(
                        actionInProgressContainerId = null,
                        feedbackMessage = "'${container.name}' 업데이트를 요청했습니다."
                    )
                }
                refresh(isPullToRefresh = true)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        actionInProgressContainerId = null,
                        feedbackMessage = "'${container.name}' 업데이트 실패: ${error.localizedMessage ?: "오류 발생"}"
                    )
                }
            }
        }
    }

    fun createApiKey(name: String, description: String, roles: List<String>) {
        if (!hasValidCredentials() || _uiState.value.isUserActionInProgress) return
        viewModelScope.launch {
            _uiState.update { it.copy(isUserActionInProgress = true, feedbackMessage = null) }
            graphQlClient.createApiKey(
                serverUrl = currentEffectiveUrl,
                apiKey = currentApiKey,
                name = name,
                description = description,
                roles = roles
            ).onSuccess { created ->
                _uiState.update {
                    it.copy(
                        isUserActionInProgress = false,
                        newlyCreatedApiKey = created,
                        feedbackMessage = "새 API 키 '${created.name}'을(를) 생성했습니다."
                    )
                }
                loadUsers(force = true, isPullToRefresh = false)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isUserActionInProgress = false,
                        feedbackMessage = "API 키 생성 실패: ${error.localizedMessage ?: "권한 또는 서버 버전을 확인해 주세요."}"
                    )
                }
            }
        }
    }

    fun deleteApiKey(keyId: String, keyName: String) {
        if (!hasValidCredentials() || _uiState.value.isUserActionInProgress) return
        viewModelScope.launch {
            _uiState.update { it.copy(isUserActionInProgress = true, feedbackMessage = null) }
            graphQlClient.deleteApiKey(
                serverUrl = currentEffectiveUrl,
                apiKey = currentApiKey,
                keyId = keyId
            ).onSuccess {
                _uiState.update {
                    it.copy(
                        isUserActionInProgress = false,
                        feedbackMessage = "API 키 '$keyName'을(를) 삭제했습니다."
                    )
                }
                loadUsers(force = true, isPullToRefresh = false)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isUserActionInProgress = false,
                        feedbackMessage = "API 키 삭제 실패: ${error.localizedMessage ?: "오류 발생"}"
                    )
                }
            }
        }
    }

    fun clearNewlyCreatedApiKey() {
        _uiState.update { it.copy(newlyCreatedApiKey = null) }
    }

    fun installPluginByUrl(plgUrl: String) {
        if (!hasValidCredentials() || _uiState.value.isPluginInstallInProgress) return
        viewModelScope.launch {
            _uiState.update { it.copy(isPluginInstallInProgress = true, feedbackMessage = null) }
            graphQlClient.installPluginByUrl(
                serverUrl = currentEffectiveUrl,
                apiKey = currentApiKey,
                plgUrl = plgUrl
            ).onSuccess {
                _uiState.update {
                    it.copy(
                        isPluginInstallInProgress = false,
                        feedbackMessage = "플러그인 설치를 요청했습니다. 잠시 후 목록을 갱신합니다."
                    )
                }
                loadPlugins(force = true, isPullToRefresh = false)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isPluginInstallInProgress = false,
                        feedbackMessage = "플러그인 설치 실패: ${error.localizedMessage ?: "URL 또는 서버 권한을 확인해 주세요."}"
                    )
                }
            }
        }
    }

    fun clearFeedbackMessage() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }

    private fun hasValidCredentials(): Boolean {
        return currentEffectiveUrl.isNotBlank() && currentApiKey.isNotBlank()
    }

    override fun onCleared() {
        metricsPollingJob?.cancel()
        metricsPollingJob = null
        super.onCleared()
    }

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )

    class Factory(
        private val preferencesRepository: AppPreferencesRepository,
        private val credentialStore: SecureCredentialStore,
        private val graphQlClient: UnraidGraphQlClient,
        private val connectionManager: TailscaleConnectionManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DashboardViewModel(
                preferencesRepository,
                credentialStore,
                graphQlClient,
                connectionManager
            ) as T
        }
    }

    companion object {
        private const val METRICS_POLL_INTERVAL_MS = 5_000L
        private const val MAX_METRIC_HISTORY_SAMPLES = 20
    }
}
