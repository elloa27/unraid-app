package com.unraid.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.unraid.app.data.local.AppErrorLogger
import com.unraid.app.data.local.AppPreferencesRepository
import com.unraid.app.data.local.SecureCredentialStore
import com.unraid.app.data.model.ConnectionMode
import com.unraid.app.data.model.ConnectionStatus
import com.unraid.app.data.model.DockerServiceBookmark
import com.unraid.app.data.model.ErrorLogEntry
import com.unraid.app.data.model.resolveEffectiveUnraidUrl
import com.unraid.app.data.remote.UnraidGraphQlClient
import com.unraid.app.network.NetworkModule
import com.unraid.app.network.TailscaleConnectionManager
import com.unraid.app.network.WifiSsidReadResult
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
    val homeWifiSsidInput: String = "",
    val tailscaleAuthKeyInput: String = "",
    val tailscaleServerUrlInput: String = "",
    val connectionTest: ConnectionTestState = ConnectionTestState(),
    val feedbackMessage: String? = null
)

/**
 * 설정 및 자체 에러 로그 화면 ViewModel.
 * - Unraid 서버 URL/API 키 저장, 자체 서명 SSL 허용, 연결 테스트
 * - Tailscale(tsnet) Auth Key, 홈 Wi-Fi SSID 자동 감지 및 연결 모드 설정
 * - 도커 웹뷰 목록 수동 CRUD, 자체 에러 로그 관리
 */
class SettingsViewModel(
    private val preferencesRepository: AppPreferencesRepository,
    private val credentialStore: SecureCredentialStore,
    private val graphQlClient: UnraidGraphQlClient,
    private val connectionManager: TailscaleConnectionManager
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

    val allowSelfSignedSsl: StateFlow<Boolean> =
        preferencesRepository.allowSelfSignedSslFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = true
        )

    val connectionMode: StateFlow<ConnectionMode> =
        preferencesRepository.connectionModeFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ConnectionMode.AUTO
        )

    val connectionStatus: StateFlow<ConnectionStatus> = connectionManager.status

    init {
        viewModelScope.launch {
            val savedUrl = preferencesRepository.unraidServerUrlFlow.first()
            val savedKey = credentialStore.getUnraidApiKey()
            val savedHomeSsid = preferencesRepository.homeWifiSsidFlow.first()
            val savedTsKey = credentialStore.getTailscaleAuthKey()
            val savedTsUrl = preferencesRepository.tailscaleServerUrlFlow.first()
            _uiState.update {
                it.copy(
                    serverUrlInput = savedUrl,
                    apiKeyInput = savedKey,
                    homeWifiSsidInput = savedHomeSsid,
                    tailscaleAuthKeyInput = savedTsKey,
                    tailscaleServerUrlInput = savedTsUrl
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

    fun onHomeWifiSsidChanged(value: String) {
        _uiState.update { it.copy(homeWifiSsidInput = value) }
    }

    fun onTailscaleAuthKeyChanged(value: String) {
        _uiState.update { it.copy(tailscaleAuthKeyInput = value) }
    }

    fun onTailscaleServerUrlChanged(value: String) {
        _uiState.update { it.copy(tailscaleServerUrlInput = value) }
    }

    fun onAllowSelfSignedSslChanged(enabled: Boolean) {
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

    fun onConnectionModeSelected(mode: ConnectionMode) {
        viewModelScope.launch {
            preferencesRepository.setConnectionMode(mode)
        }
        _uiState.update {
            it.copy(feedbackMessage = "연결 모드를 '${mode.labelKo}'(으)로 변경했습니다.")
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

    fun saveTailscaleSettings() {
        val ssid = _uiState.value.homeWifiSsidInput.trim()
        val tsAuthKey = _uiState.value.tailscaleAuthKeyInput.trim()
        val tsUrl = _uiState.value.tailscaleServerUrlInput.trim()

        viewModelScope.launch {
            val normalizedTsUrl = AppPreferencesRepository.normalizeUrl(tsUrl)
            preferencesRepository.saveHomeWifiSsid(ssid)
            preferencesRepository.saveTailscaleServerUrl(normalizedTsUrl)
            credentialStore.saveTailscaleAuthKey(tsAuthKey)
            _uiState.update {
                it.copy(
                    homeWifiSsidInput = ssid,
                    tailscaleAuthKeyInput = tsAuthKey,
                    tailscaleServerUrlInput = normalizedTsUrl,
                    feedbackMessage = "Tailscale 및 자동 전환 설정이 저장되었습니다."
                )
            }
        }
    }

    fun hasLocationPermission(): Boolean = connectionManager.hasFineLocationPermission()

    fun onLocationPermissionResult(granted: Boolean) {
        connectionManager.onLocationPermissionUpdated()
        if (granted) {
            registerCurrentWifiSsid()
        } else {
            val msg = "위치 권한이 거부되어 현재 Wi-Fi 이름을 자동으로 읽을 수 없습니다. 직접 입력하거나 권한을 허용해 주세요."
            AppErrorLogger.logError(tag = "WifiDetection", message = msg)
            _uiState.update { it.copy(feedbackMessage = msg) }
        }
    }

    fun registerCurrentWifiSsid() {
        when (val result = connectionManager.readCurrentWifiSsid()) {
            is WifiSsidReadResult.Success -> {
                viewModelScope.launch {
                    preferencesRepository.saveHomeWifiSsid(result.ssid)
                }
                _uiState.update {
                    it.copy(
                        homeWifiSsidInput = result.ssid,
                        feedbackMessage = "현재 Wi-Fi '${result.ssid}'를 홈 Wi-Fi로 등록했습니다."
                    )
                }
            }

            WifiSsidReadResult.PermissionDenied -> {
                _uiState.update {
                    it.copy(feedbackMessage = "현재 Wi-Fi 이름을 읽으려면 위치 권한이 필요합니다.")
                }
            }

            WifiSsidReadResult.LocationServiceDisabled -> {
                _uiState.update {
                    it.copy(feedbackMessage = "안드로이드 기기의 위치(GPS) 스위치를 켠 뒤 다시 시도해 주세요.")
                }
            }

            WifiSsidReadResult.NotOnWifi -> {
                _uiState.update {
                    it.copy(feedbackMessage = "현재 Wi-Fi에 연결되어 있지 않습니다. 홈 Wi-Fi에 연결 후 눌러 주세요.")
                }
            }

            WifiSsidReadResult.UnknownSsid -> {
                _uiState.update {
                    it.copy(feedbackMessage = "Wi-Fi 이름을 가져오지 못했습니다. 위치(GPS)가 켜져 있는지 확인하거나 직접 입력해 주세요.")
                }
            }
        }
    }

    fun reconnectTailscale() {
        connectionManager.reconnectNow()
        _uiState.update { it.copy(feedbackMessage = "네트워크 경로 재연결을 시작합니다.") }
    }

    fun resetTailscaleState() {
        connectionManager.resetTailscaleState()
        _uiState.update { it.copy(feedbackMessage = "내장 Tailscale 상태를 초기화했습니다.") }
    }

    fun testConnection() {
        val rawHomeUrl = _uiState.value.serverUrlInput.trim()
        val rawTsUrl = _uiState.value.tailscaleServerUrlInput.trim()
        val rawKey = _uiState.value.apiKeyInput.trim()
        val useTailscale = connectionStatus.value.isUsingTailscale

        val targetUrl = resolveEffectiveUnraidUrl(
            homeUrl = rawHomeUrl,
            tailscaleUrl = rawTsUrl,
            useTailscale = useTailscale
        )

        if (targetUrl.isBlank() || rawKey.isBlank()) {
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
                        message = "서버 연결 확인 중... ($targetUrl)"
                    )
                )
            }

            val result = graphQlClient.testConnection(targetUrl, rawKey)
            result.onSuccess { successText ->
                _uiState.update {
                    it.copy(
                        connectionTest = ConnectionTestState(
                            isTesting = false,
                            isSuccess = true,
                            message = "$successText · 경로: ${connectionStatus.value.badgeLabel}"
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
        private val graphQlClient: UnraidGraphQlClient,
        private val connectionManager: TailscaleConnectionManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(
                preferencesRepository,
                credentialStore,
                graphQlClient,
                connectionManager
            ) as T
        }
    }
}
