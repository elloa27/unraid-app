package com.unraid.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.unraid.app.data.local.AppErrorLogger
import com.unraid.app.data.local.AppPreferencesRepository
import com.unraid.app.data.local.SecureCredentialStore
import com.unraid.app.data.model.DockerServiceBookmark
import com.unraid.app.data.model.ErrorLogEntry
import com.unraid.app.data.remote.UnraidGraphQlClient
import com.unraid.app.network.LocalProxyConfig
import com.unraid.app.network.NetworkModule
import com.unraid.app.network.NetworkProxyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConnectionTestState(
    val isTesting: Boolean = false,
    val isSuccess: Boolean? = null,
    val message: String? = null
)

data class SettingsUiState(
    val serverUrlInput: String = "",
    val apiKeyInput: String = "",
    val connectionTest: ConnectionTestState = ConnectionTestState(),
    val feedbackMessage: String? = null
)

/**
 * 설정 및 자체 에러 로그 화면 ViewModel.
 * Unraid 서버 URL/API 키 저장, 연결 테스트, 도커 웹뷰 CRUD, 에러 로그 관리 기능을 제공합니다.
 */
class SettingsViewModel(
    private val preferencesRepository: AppPreferencesRepository,
    private val credentialStore: SecureCredentialStore,
    private val graphQlClient: UnraidGraphQlClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val dockerServices: StateFlow<List<DockerServiceBookmark>> =
        preferencesRepository.dockerServicesFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val errorLogs: StateFlow<List<ErrorLogEntry>> = AppErrorLogger.logs

    val proxyConfig: StateFlow<LocalProxyConfig> = NetworkProxyManager.proxyConfig

    val allowSelfSignedSsl: StateFlow<Boolean> =
        preferencesRepository.allowSelfSignedSslFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = true
        )

    init {
        viewModelScope.launch {
            val savedUrl = preferencesRepository.unraidServerUrlFlow.first()
            val savedKey = credentialStore.getUnraidApiKey()
            _uiState.update {
                it.copy(
                    serverUrlInput = savedUrl,
                    apiKeyInput = savedKey
                )
            }
        }
    }

    fun onServerUrlChanged(value: String) {
        _uiState.update {
            it.copy(
                serverUrlInput = value,
                connectionTest = ConnectionTestState()
            )
        }
    }

    fun onApiKeyChanged(value: String) {
        _uiState.update {
            it.copy(
                apiKeyInput = value,
                connectionTest = ConnectionTestState()
            )
        }
    }

    fun onAllowSelfSignedSslChanged(enabled: Boolean) {
        // 연결 테스트 및 웹뷰에 즉시 반영되도록 네트워크 모듈과 저장소를 함께 갱신
        NetworkModule.setAllowSelfSignedSsl(enabled)
        viewModelScope.launch {
            preferencesRepository.setAllowSelfSignedSsl(enabled)
        }
        _uiState.update {
            it.copy(
                connectionTest = ConnectionTestState(),
                feedbackMessage = if (enabled) {
                    "자체 서명 SSL 인증서 허용이 켜졌습니다."
                } else {
                    "자체 서명 SSL 인증서 허용이 꺼졌습니다."
                }
            )
        }
    }

    fun saveServerSettings() {
        val rawUrl = _uiState.value.serverUrlInput.trim()
        val rawKey = _uiState.value.apiKeyInput.trim()

        viewModelScope.launch {
            val normalizedUrl = AppPreferencesRepository.normalizeUrl(rawUrl)
            preferencesRepository.saveUnraidServerUrl(normalizedUrl)
            credentialStore.saveUnraidApiKey(rawKey)
            _uiState.update {
                it.copy(
                    serverUrlInput = normalizedUrl,
                    apiKeyInput = rawKey,
                    feedbackMessage = "Unraid 서버 설정이 안전하게 저장되었습니다."
                )
            }
        }
    }

    fun testConnection() {
        val rawUrl = _uiState.value.serverUrlInput.trim()
        val rawKey = _uiState.value.apiKeyInput.trim()

        if (rawUrl.isBlank() || rawKey.isBlank()) {
            _uiState.update {
                it.copy(
                    connectionTest = ConnectionTestState(
                        isTesting = false,
                        isSuccess = false,
                        message = "서버 URL과 API 키를 모두 입력해 주세요."
                    )
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    connectionTest = ConnectionTestState(
                        isTesting = true,
                        isSuccess = null,
                        message = "서버 연결 확인 중..."
                    )
                )
            }

            val result = graphQlClient.testConnection(rawUrl, rawKey)
            result.onSuccess { successText ->
                _uiState.update {
                    it.copy(
                        connectionTest = ConnectionTestState(
                            isTesting = false,
                            isSuccess = true,
                            message = successText
                        )
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        connectionTest = ConnectionTestState(
                            isTesting = false,
                            isSuccess = false,
                            message = error.localizedMessage ?: "서버 연결에 실패했습니다."
                        )
                    )
                }
            }
        }
    }

    fun addDockerService(name: String, url: String) {
        if (name.isBlank() || url.isBlank()) return
        viewModelScope.launch {
            preferencesRepository.addDockerService(name, url)
            _uiState.update { it.copy(feedbackMessage = "'${name.trim()}' 서비스가 추가되었습니다.") }
        }
    }

    fun updateDockerService(service: DockerServiceBookmark) {
        if (service.name.isBlank() || service.url.isBlank()) return
        viewModelScope.launch {
            preferencesRepository.updateDockerService(service)
            _uiState.update { it.copy(feedbackMessage = "'${service.name.trim()}' 서비스가 수정되었습니다.") }
        }
    }

    fun deleteDockerService(service: DockerServiceBookmark) {
        viewModelScope.launch {
            preferencesRepository.deleteDockerService(service.id)
            _uiState.update { it.copy(feedbackMessage = "'${service.name}' 서비스가 삭제되었습니다.") }
        }
    }

    fun clearErrorLogs() {
        AppErrorLogger.clearLogs()
        _uiState.update { it.copy(feedbackMessage = "에러 로그를 모두 비웠습니다.") }
    }

    fun getFormattedLogsForClipboard(): String = AppErrorLogger.formatLogsForClipboard()

    fun showMessage(message: String) {
        _uiState.update { it.copy(feedbackMessage = message) }
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
            return SettingsViewModel(preferencesRepository, credentialStore, graphQlClient) as T
        }
    }
}
