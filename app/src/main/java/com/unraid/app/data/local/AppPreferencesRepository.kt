package com.unraid.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.unraid.app.data.model.ConnectionMode
import com.unraid.app.data.model.DockerServiceBookmark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "unraid_hub_preferences"
)

/**
 * Unraid 서버 URL 및 도커 웹뷰 북마크 목록을 Jetpack DataStore에 저장/조회하는 리포지토리.
 */
class AppPreferencesRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val unraidServerUrlFlow: Flow<String> = context.appDataStore.data.map { prefs ->
        prefs[KEY_UNRAID_SERVER_URL].orEmpty()
    }

    val dockerServicesFlow: Flow<List<DockerServiceBookmark>> = context.appDataStore.data.map { prefs ->
        val raw = prefs[KEY_DOCKER_SERVICES_JSON].orEmpty()
        if (raw.isBlank()) {
            emptyList()
        } else {
            runCatching {
                json.decodeFromString<List<DockerServiceBookmark>>(raw)
            }.onFailure { e ->
                AppErrorLogger.logError(
                    tag = "Preferences",
                    message = "도커 서비스 목록 JSON 파싱에 실패했습니다.",
                    throwable = e
                )
            }.getOrDefault(emptyList())
        }
    }

    val selectedServiceIdFlow: Flow<String?> = context.appDataStore.data.map { prefs ->
        prefs[KEY_SELECTED_SERVICE_ID]
    }

    /**
     * 개인 NAS 환경의 자체 서명(Self-signed) SSL 인증서 허용 여부 (기본값: ON)
     */
    val allowSelfSignedSslFlow: Flow<Boolean> = context.appDataStore.data.map { prefs ->
        prefs[KEY_ALLOW_SELF_SIGNED_SSL] ?: true
    }

    /**
     * 네트워크 연결 모드 (자동 전환 / 항상 직접 연결 / 항상 Tailscale)
     */
    val connectionModeFlow: Flow<ConnectionMode> = context.appDataStore.data.map { prefs ->
        ConnectionMode.fromStorageKey(prefs[KEY_CONNECTION_MODE])
    }

    /**
     * 직접 연결에 사용할 홈 Wi-Fi SSID
     */
    val homeWifiSsidFlow: Flow<String> = context.appDataStore.data.map { prefs ->
        prefs[KEY_HOME_WIFI_SSID].orEmpty()
    }

    /**
     * 외부(Tailscale) 접속용 Unraid URL (비워두면 기본 Unraid URL 그대로 사용)
     */
    val tailscaleServerUrlFlow: Flow<String> = context.appDataStore.data.map { prefs ->
        prefs[KEY_TAILSCALE_SERVER_URL].orEmpty()
    }

    suspend fun getUnraidServerUrl(): String = unraidServerUrlFlow.first()

    suspend fun setAllowSelfSignedSsl(enabled: Boolean) {
        context.appDataStore.edit { prefs ->
            prefs[KEY_ALLOW_SELF_SIGNED_SSL] = enabled
        }
    }

    suspend fun setConnectionMode(mode: ConnectionMode) {
        context.appDataStore.edit { prefs ->
            prefs[KEY_CONNECTION_MODE] = mode.storageKey
        }
    }

    suspend fun saveHomeWifiSsid(ssid: String) {
        context.appDataStore.edit { prefs ->
            prefs[KEY_HOME_WIFI_SSID] = ssid.trim()
        }
    }

    suspend fun saveTailscaleServerUrl(url: String) {
        val normalized = normalizeUrl(url)
        context.appDataStore.edit { prefs ->
            prefs[KEY_TAILSCALE_SERVER_URL] = normalized
        }
    }

    suspend fun saveUnraidServerUrl(url: String) {
        val normalized = normalizeUrl(url)
        context.appDataStore.edit { prefs ->
            prefs[KEY_UNRAID_SERVER_URL] = normalized
        }
    }

    suspend fun addDockerService(name: String, url: String) {
        val current = dockerServicesFlow.first()
        val item = DockerServiceBookmark(
            name = name.trim(),
            url = normalizeUrl(url)
        )
        saveDockerServices(current + item)
        // 최초 등록된 서비스라면 자동으로 선택 상태 지정
        if (current.isEmpty()) {
            setSelectedServiceId(item.id)
        }
    }

    suspend fun updateDockerService(updated: DockerServiceBookmark) {
        val current = dockerServicesFlow.first()
        val normalized = updated.copy(
            name = updated.name.trim(),
            url = normalizeUrl(updated.url)
        )
        saveDockerServices(current.map { if (it.id == normalized.id) normalized else it })
    }

    suspend fun deleteDockerService(serviceId: String) {
        val current = dockerServicesFlow.first()
        val remaining = current.filterNot { it.id == serviceId }
        saveDockerServices(remaining)

        val currentSelected = selectedServiceIdFlow.first()
        if (currentSelected == serviceId) {
            setSelectedServiceId(remaining.firstOrNull()?.id)
        }
    }

    suspend fun setSelectedServiceId(serviceId: String?) {
        context.appDataStore.edit { prefs ->
            if (serviceId.isNullOrBlank()) {
                prefs.remove(KEY_SELECTED_SERVICE_ID)
            } else {
                prefs[KEY_SELECTED_SERVICE_ID] = serviceId
            }
        }
    }

    private suspend fun saveDockerServices(services: List<DockerServiceBookmark>) {
        context.appDataStore.edit { prefs ->
            prefs[KEY_DOCKER_SERVICES_JSON] = json.encodeToString(services)
        }
    }

    companion object {
        private val KEY_UNRAID_SERVER_URL = stringPreferencesKey("unraid_server_url")
        private val KEY_DOCKER_SERVICES_JSON = stringPreferencesKey("docker_services_json")
        private val KEY_SELECTED_SERVICE_ID = stringPreferencesKey("selected_service_id")
        private val KEY_ALLOW_SELF_SIGNED_SSL = booleanPreferencesKey("allow_self_signed_ssl")
        private val KEY_CONNECTION_MODE = stringPreferencesKey("connection_mode")
        private val KEY_HOME_WIFI_SSID = stringPreferencesKey("home_wifi_ssid")
        private val KEY_TAILSCALE_SERVER_URL = stringPreferencesKey("tailscale_server_url")

        /**
         * 입력된 서버 URL에 스킴이 없으면 http:// 를 보정하고 끝의 불필요한 슬래시를 정리합니다.
         */
        fun normalizeUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            if (trimmed.isBlank()) return ""
            val withScheme = if (
                trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                trimmed
            } else {
                "http://$trimmed"
            }
            return withScheme.trimEnd('/')
        }
    }
}
