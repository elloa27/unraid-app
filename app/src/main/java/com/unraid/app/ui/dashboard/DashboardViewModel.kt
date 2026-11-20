package com.unraid.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.unraid.app.data.local.AppPreferencesRepository
import com.unraid.app.data.local.SecureCredentialStore
import com.unraid.app.data.model.DockerAction
import com.unraid.app.data.model.DockerContainerItem
import com.unraid.app.data.model.UnraidDashboardData
import com.unraid.app.data.remote.UnraidGraphQlClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardUiState(
    val isConfigured: Boolean = false,
    val serverUrl: String = "",
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val data: UnraidDashboardData? = null,
    val errorMessage: String? = null,
    val actionInProgressContainerId: String? = null,
    val feedbackMessage: String? = null
)

/**
 * Unraid 네이티브 대시보드 상태 및 도커 컨테이너 제어 ViewModel.
 */
class DashboardViewModel(
    private val preferencesRepository: AppPreferencesRepository,
    private val credentialStore: SecureCredentialStore,
    private val graphQlClient: UnraidGraphQlClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var currentServerUrl: String = ""
    private var currentApiKey: String = ""
    private var hasAutoLoadedForConfig: String = ""

    init {
        viewModelScope.launch {
            combine(
                preferencesRepository.unraidServerUrlFlow,
                credentialStore.apiKeyFlow
            ) { url, key -> url to key }
                .collect { (url, key) ->
                    currentServerUrl = url
                    currentApiKey = key
                    val configured = url.isNotBlank() && key.isNotBlank()
                    _uiState.update {
                        it.copy(
                            isConfigured = configured,
                            serverUrl = url,
                            errorMessage = if (!configured) null else it.errorMessage
                        )
                    }

                    val configKey = "$url|$key"
                    if (configured && hasAutoLoadedForConfig != configKey) {
                        hasAutoLoadedForConfig = configKey
                        refresh(isPullToRefresh = false)
                    }
                }
        }
    }

    fun refresh(isPullToRefresh: Boolean = true) {
        if (currentServerUrl.isBlank() || currentApiKey.isBlank()) {
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

            val result = graphQlClient.fetchDashboard(currentServerUrl, currentApiKey)
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
                serverUrl = currentServerUrl,
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

    class Factory(
        private val preferencesRepository: AppPreferencesRepository,
        private val credentialStore: SecureCredentialStore,
        private val graphQlClient: UnraidGraphQlClient
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DashboardViewModel(preferencesRepository, credentialStore, graphQlClient) as T
        }
    }
}
