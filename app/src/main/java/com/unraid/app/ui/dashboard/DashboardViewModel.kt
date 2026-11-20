package com.unraid.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.unraid.app.data.local.AppPreferencesRepository
import com.unraid.app.data.local.SecureCredentialStore
import com.unraid.app.data.model.ConnectionStatus
import com.unraid.app.data.model.DockerAction
import com.unraid.app.data.model.DockerContainerItem
import com.unraid.app.data.model.RoutePhase
import com.unraid.app.data.model.UnraidDashboardData
import com.unraid.app.data.model.resolveEffectiveUnraidUrl
import com.unraid.app.data.remote.UnraidGraphQlClient
import com.unraid.app.network.TailscaleConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardUiState(
    val isConfigured: Boolean = false,
    val serverUrl: String = "",
    val connectionStatus: ConnectionStatus = ConnectionStatus(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val data: UnraidDashboardData? = null,
    val errorMessage: String? = null,
    val actionInProgressContainerId: String? = null,
    val feedbackMessage: String? = null
)

/**
 * Unraid 네이티브 대시보드 상태, 도커 컨테이너 제어 및
 * 홈 Wi-Fi / Tailscale 경로 전환에 따른 URL 자동 반영을 관리하는 ViewModel.
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
    private var lastAutoLoadedSignature: String = ""

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
                _uiState.update {
                    it.copy(
                        isConfigured = configured,
                        serverUrl = effectiveUrl,
                        connectionStatus = connStatus,
                        errorMessage = if (!configured) null else it.errorMessage
                    )
                }

                // Tailscale 연결 진행 중이 아닐 때 경로/URL/포트가 확정되면 자동 조회
                val routeReady = connStatus.phase != RoutePhase.TAILSCALE_CONNECTING
                val signature = "$effectiveUrl|$key|${connStatus.phase}|${connStatus.proxyPort}"
                if (configured && routeReady && lastAutoLoadedSignature != signature) {
                    lastAutoLoadedSignature = signature
                    refresh(isPullToRefresh = false)
                }
            }
        }
    }

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

    fun clearFeedbackMessage() {
        _uiState.update { it.copy(feedbackMessage = null) }
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
}
