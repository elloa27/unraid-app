package com.unraid.app.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.unraid.app.data.local.AppErrorLogger
import com.unraid.app.data.local.AppPreferencesRepository
import com.unraid.app.data.local.SecureCredentialStore
import com.unraid.app.data.model.ConnectionMode
import com.unraid.app.data.model.ConnectionStatus
import com.unraid.app.data.model.RoutePhase
import com.unraid.app.tsnetbridge.Tsnetbridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * '현재 Wi-Fi 등록' 버튼 실행 결과
 */
sealed class WifiSsidReadResult {
    data class Success(val ssid: String) : WifiSsidReadResult()
    data object PermissionDenied : WifiSsidReadResult()
    data object LocationServiceDisabled : WifiSsidReadResult()
    data object NotOnWifi : WifiSsidReadResult()
    data object UnknownSsid : WifiSsidReadResult()
}

/**
 * 홈 Wi-Fi 감지(ConnectivityManager.NetworkCallback) 및 내장 Tailscale(tsnet) 자동 전환 엔진.
 *
 * - 홈 Wi-Fi 연결 시: tsnet 즉시 중지 + OkHttp/WebView 프록시 해제(직접 연결)
 * - 외부 네트워크(LTE/5G 또는 외부 Wi-Fi) 시: tsnet 시작 + 127.0.0.1:<포트>로 OkHttp/WebView 일괄 라우팅
 */
class TailscaleConnectionManager(
    context: Context,
    private val preferencesRepository: AppPreferencesRepository,
    private val credentialStore: SecureCredentialStore
) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val routeMutex = Mutex()

    private val stateDir: File = File(appContext.filesDir, TSNET_STATE_DIR_NAME)

    private val _status = MutableStateFlow(ConnectionStatus())
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    @Volatile
    private var currentMode: ConnectionMode = ConnectionMode.AUTO

    @Volatile
    private var homeWifiSsid: String = ""

    @Volatile
    private var authKey: String = ""

    @Volatile
    private var latestCallbackWifiSsid: String? = null

    @Volatile
    private var activeProxyPort: Int = 0

    @Volatile
    private var loggedMissingLocationOnce: Boolean = false

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * 앱 시작 시 1회 호출하여 네트워크 감시 콜백과 설정 구독을 활성화합니다.
     */
    fun startMonitoring() {
        registerNetworkCallback()

        scope.launch {
            combine(
                preferencesRepository.connectionModeFlow,
                preferencesRepository.homeWifiSsidFlow,
                credentialStore.tailscaleAuthKeyFlow
            ) { mode, homeSsid, key ->
                Triple(mode, homeSsid, key)
            }.collect { (mode, homeSsid, key) ->
                val authKeyChanged = key != authKey && key.isNotBlank()
                currentMode = mode
                homeWifiSsid = homeSsid.trim()
                authKey = key.trim()
                evaluateAndApplyRoute(forceRestart = authKeyChanged && _status.value.phase == RoutePhase.TAILSCALE_ERROR)
            }
        }
    }

    /**
     * 위치 권한 허용 직후 호출하여 Android 12+ NetworkCallback을 재등록하고 경로를 즉시 재평가합니다.
     */
    fun onLocationPermissionUpdated() {
        loggedMissingLocationOnce = false
        registerNetworkCallback()
        scope.launch {
            evaluateAndApplyRoute(forceRestart = false)
        }
    }

    /**
     * 수동 재연결 버튼: 내장 Tailscale을 재시작하거나 현재 모드에 맞게 즉시 재평가합니다.
     */
    fun reconnectNow() {
        scope.launch {
            evaluateAndApplyRoute(forceRestart = true)
        }
    }

    /**
     * Tailscale 로컬 상태(tsnet-state)를 완전히 삭제하고 중지합니다.
     * Auth Key 교체나 Tailscale 관리 콘솔에서 기기를 삭제한 뒤 재등록할 때 사용합니다.
     */
    fun resetTailscaleState() {
        scope.launch {
            routeMutex.withLock {
                stopTsnetLocked()
                runCatching {
                    if (stateDir.exists()) {
                        stateDir.deleteRecursively()
                    }
                }.onFailure { e ->
                    AppErrorLogger.logError(
                        tag = "TailscaleTsnet",
                        message = "Tailscale 상태 폴더 초기화 중 오류가 발생했습니다.",
                        throwable = e
                    )
                }
                val (isWifi, ssid) = inspectCurrentNetwork()
                _status.value = ConnectionStatus(
                    phase = if (isHomeWifiMatched(isWifi, ssid)) {
                        RoutePhase.DIRECT_HOME_WIFI
                    } else {
                        RoutePhase.DIRECT_MANUAL
                    },
                    mode = currentMode,
                    isOnWifi = isWifi,
                    currentWifiSsid = ssid,
                    homeWifiSsid = homeWifiSsid,
                    hasLocationPermission = hasFineLocationPermission(),
                    proxyPort = 0,
                    tailscaleDetail = "Tailscale 상태가 초기화되었습니다. 재연결 시 새 키로 등록됩니다.",
                    lastError = null
                )
            }
        }
    }

    /**
     * 설정 화면의 '현재 Wi-Fi 등록' 버튼에서 호출하여 현재 연결된 Wi-Fi SSID를 정확히 읽어옵니다.
     */
    fun readCurrentWifiSsid(): WifiSsidReadResult {
        if (!hasFineLocationPermission()) {
            AppErrorLogger.logError(
                tag = "WifiDetection",
                message = "위치 권한(ACCESS_FINE_LOCATION)이 허용되지 않아 현재 Wi-Fi SSID를 읽을 수 없습니다."
            )
            return WifiSsidReadResult.PermissionDenied
        }

        val (isWifi, ssid) = inspectCurrentNetwork()
        if (!isWifi) {
            return WifiSsidReadResult.NotOnWifi
        }

        if (!isLocationEnabled()) {
            AppErrorLogger.logError(
                tag = "WifiDetection",
                message = "기기의 위치 서비스(GPS)가 꺼져 있어 Android가 Wi-Fi SSID를 반환하지 않습니다."
            )
            return WifiSsidReadResult.LocationServiceDisabled
        }

        return if (!ssid.isNullOrBlank()) {
            WifiSsidReadResult.Success(ssid)
        } else {
            AppErrorLogger.logError(
                tag = "WifiDetection",
                message = "Wi-Fi에 연결되어 있으나 SSID를 확인할 수 없습니다(<unknown ssid>)."
            )
            WifiSsidReadResult.UnknownSsid
        }
    }

    fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isLocationEnabled(): Boolean {
        return runCatching { locationManager.isLocationEnabled }.getOrDefault(true)
    }

    private fun registerNetworkCallback() {
        networkCallback?.let { old ->
            runCatching { connectivityManager.unregisterNetworkCallback(old) }
        }

        val callback = createNetworkCallback()
        networkCallback = callback

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        runCatching {
            connectivityManager.registerNetworkCallback(request, callback)
        }.onFailure { e ->
            AppErrorLogger.logError(
                tag = "NetworkMonitor",
                message = "ConnectivityManager NetworkCallback 등록에 실패했습니다.",
                throwable = e
            )
        }
    }

    private fun createNetworkCallback(): ConnectivityManager.NetworkCallback {
        val onNetworkChanged: (NetworkCapabilities?) -> Unit = { caps ->
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val info = caps.transportInfo as? WifiInfo
                val parsed = sanitizeSsid(info?.ssid)
                if (parsed != null) {
                    latestCallbackWifiSsid = parsed
                }
            } else if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                latestCallbackWifiSsid = null
            }

            scope.launch {
                evaluateAndApplyRoute(forceRestart = false)
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) {
                    val caps = connectivityManager.getNetworkCapabilities(network)
                    onNetworkChanged(caps)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    onNetworkChanged(networkCapabilities)
                }

                override fun onLost(network: Network) {
                    latestCallbackWifiSsid = null
                    scope.launch {
                        evaluateAndApplyRoute(forceRestart = false)
                    }
                }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val caps = connectivityManager.getNetworkCapabilities(network)
                    onNetworkChanged(caps)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    onNetworkChanged(networkCapabilities)
                }

                override fun onLost(network: Network) {
                    latestCallbackWifiSsid = null
                    scope.launch {
                        evaluateAndApplyRoute(forceRestart = false)
                    }
                }
            }
        }
    }

    private suspend fun evaluateAndApplyRoute(forceRestart: Boolean) {
        routeMutex.withLock {
            val hasLocPerm = hasFineLocationPermission()
            val (isWifi, detectedSsid) = inspectCurrentNetwork()
            val matchedHomeWifi = isHomeWifiMatched(isWifi, detectedSsid)

            // 자동 전환 모드에서 홈 Wi-Fi가 설정되어 있고 Wi-Fi에 붙어 있는데 위치 권한이 없으면 로그 기록
            if (currentMode == ConnectionMode.AUTO &&
                homeWifiSsid.isNotBlank() &&
                isWifi &&
                !hasLocPerm &&
                !loggedMissingLocationOnce
            ) {
                loggedMissingLocationOnce = true
                AppErrorLogger.logError(
                    tag = "WifiDetection",
                    message = "위치 권한이 없어 홈 Wi-Fi($homeWifiSsid) 자동 판별을 수행할 수 없습니다. 설정에서 '현재 Wi-Fi 등록'을 눌러 권한을 허용해 주세요."
                )
            }

            when (currentMode) {
                ConnectionMode.DIRECT_ONLY -> {
                    stopTsnetLocked()
                    _status.value = ConnectionStatus(
                        phase = if (matchedHomeWifi) RoutePhase.DIRECT_HOME_WIFI else RoutePhase.DIRECT_MANUAL,
                        mode = currentMode,
                        isOnWifi = isWifi,
                        currentWifiSsid = detectedSsid,
                        homeWifiSsid = homeWifiSsid,
                        hasLocationPermission = hasLocPerm,
                        proxyPort = 0,
                        tailscaleDetail = "직접 연결 모드 (Tailscale 중지됨)",
                        lastError = null
                    )
                }

                ConnectionMode.AUTO -> {
                    if (matchedHomeWifi) {
                        stopTsnetLocked()
                        _status.value = ConnectionStatus(
                            phase = RoutePhase.DIRECT_HOME_WIFI,
                            mode = currentMode,
                            isOnWifi = true,
                            currentWifiSsid = detectedSsid,
                            homeWifiSsid = homeWifiSsid,
                            hasLocationPermission = hasLocPerm,
                            proxyPort = 0,
                            tailscaleDetail = "홈 Wi-Fi($detectedSsid) 감지 — 직접 연결 사용 중",
                            lastError = null
                        )
                    } else if (authKey.isBlank() && !hasSavedTsnetState()) {
                        // 초기 설치 등 Tailscale Auth Key가 아직 등록되지 않은 경우 직접 연결 유지
                        stopTsnetLocked()
                        _status.value = ConnectionStatus(
                            phase = RoutePhase.DIRECT_MANUAL,
                            mode = currentMode,
                            isOnWifi = isWifi,
                            currentWifiSsid = detectedSsid,
                            homeWifiSsid = homeWifiSsid,
                            hasLocationPermission = hasLocPerm,
                            proxyPort = 0,
                            tailscaleDetail = "Tailscale Auth Key 미등록 (직접 연결 유지 중)",
                            lastError = null
                        )
                    } else {
                        ensureTailscaleStartedLocked(
                            isWifi = isWifi,
                            detectedSsid = detectedSsid,
                            hasLocPerm = hasLocPerm,
                            forceRestart = forceRestart
                        )
                    }
                }

                ConnectionMode.TAILSCALE_ONLY -> {
                    if (authKey.isBlank() && !hasSavedTsnetState()) {
                        stopTsnetLocked()
                        val msg = "Tailscale Auth Key가 입력되지 않았습니다. 설정 화면에서 Auth Key를 등록해 주세요."
                        AppErrorLogger.logError(tag = "TailscaleTsnet", message = msg)
                        _status.value = ConnectionStatus(
                            phase = RoutePhase.TAILSCALE_ERROR,
                            mode = currentMode,
                            isOnWifi = isWifi,
                            currentWifiSsid = detectedSsid,
                            homeWifiSsid = homeWifiSsid,
                            hasLocationPermission = hasLocPerm,
                            proxyPort = 0,
                            tailscaleDetail = "Auth Key 필요",
                            lastError = msg
                        )
                    } else {
                        ensureTailscaleStartedLocked(
                            isWifi = isWifi,
                            detectedSsid = detectedSsid,
                            hasLocPerm = hasLocPerm,
                            forceRestart = forceRestart
                        )
                    }
                }
            }
        }
    }

    private fun ensureTailscaleStartedLocked(
        isWifi: Boolean,
        detectedSsid: String?,
        hasLocPerm: Boolean,
        forceRestart: Boolean
    ) {
        val alreadyRunning = runCatching { Tsnetbridge.isRunning() }.getOrDefault(false)
        if (!forceRestart && alreadyRunning && activeProxyPort > 0) {
            NetworkProxyManager.enableLocalProxy(
                port = activeProxyPort,
                scheme = LocalProxyScheme.HTTP
            )
            val statusStr = runCatching { Tsnetbridge.getStatus() }.getOrDefault("연결됨")
            _status.value = ConnectionStatus(
                phase = RoutePhase.TAILSCALE_CONNECTED,
                mode = currentMode,
                isOnWifi = isWifi,
                currentWifiSsid = detectedSsid,
                homeWifiSsid = homeWifiSsid,
                hasLocationPermission = hasLocPerm,
                proxyPort = activeProxyPort,
                tailscaleDetail = statusStr,
                lastError = null
            )
            return
        }

        _status.value = ConnectionStatus(
            phase = RoutePhase.TAILSCALE_CONNECTING,
            mode = currentMode,
            isOnWifi = isWifi,
            currentWifiSsid = detectedSsid,
            homeWifiSsid = homeWifiSsid,
            hasLocationPermission = hasLocPerm,
            proxyPort = 0,
            tailscaleDetail = "내장 Tailscale(tsnet) 연결 중...",
            lastError = null
        )

        try {
            if (!stateDir.exists()) {
                stateDir.mkdirs()
            }
            val portLong = Tsnetbridge.start(
                stateDir.absolutePath,
                DEFAULT_TSNET_HOSTNAME,
                authKey
            )
            val port = portLong.toInt()
            if (port !in 1..65535) {
                throw IllegalStateException("유효하지 않은 로컬 프록시 포트($port)가 반환되었습니다.")
            }

            activeProxyPort = port
            NetworkProxyManager.enableLocalProxy(
                port = port,
                scheme = LocalProxyScheme.HTTP
            )
            val detail = runCatching { Tsnetbridge.getStatus() }
                .getOrDefault("연결됨 (127.0.0.1:$port)")

            _status.value = ConnectionStatus(
                phase = RoutePhase.TAILSCALE_CONNECTED,
                mode = currentMode,
                isOnWifi = isWifi,
                currentWifiSsid = detectedSsid,
                homeWifiSsid = homeWifiSsid,
                hasLocationPermission = hasLocPerm,
                proxyPort = port,
                tailscaleDetail = detail,
                lastError = null
            )
        } catch (t: Throwable) {
            activeProxyPort = 0
            NetworkProxyManager.disableLocalProxy()
            val errorText = t.localizedMessage ?: t.javaClass.simpleName
            AppErrorLogger.logError(
                tag = "TailscaleTsnet",
                message = "내장 Tailscale(tsnet) 시작에 실패했습니다: $errorText",
                throwable = t
            )
            _status.value = ConnectionStatus(
                phase = RoutePhase.TAILSCALE_ERROR,
                mode = currentMode,
                isOnWifi = isWifi,
                currentWifiSsid = detectedSsid,
                homeWifiSsid = homeWifiSsid,
                hasLocationPermission = hasLocPerm,
                proxyPort = 0,
                tailscaleDetail = runCatching { Tsnetbridge.getStatus() }.getOrDefault("연결 실패"),
                lastError = errorText
            )
        }
    }

    private fun stopTsnetLocked() {
        runCatching {
            if (Tsnetbridge.isRunning() || activeProxyPort > 0) {
                Tsnetbridge.stop()
            }
        }.onFailure { e ->
            AppErrorLogger.logError(
                tag = "TailscaleTsnet",
                message = "내장 Tailscale(tsnet) 중지 중 오류가 발생했습니다.",
                throwable = e
            )
        }
        activeProxyPort = 0
        NetworkProxyManager.disableLocalProxy()
    }

    private fun inspectCurrentNetwork(): Pair<Boolean, String?> {
        val activeNetwork = connectivityManager.activeNetwork
        val caps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (!isWifi) {
            return false to null
        }

        // 1) NetworkCapabilities.transportInfo (Android 10+ / 12+ 권장 경로)
        val fromCaps = sanitizeSsid((caps?.transportInfo as? WifiInfo)?.ssid)
        if (fromCaps != null) {
            latestCallbackWifiSsid = fromCaps
            return true to fromCaps
        }

        // 2) 최근 NetworkCallback으로 수신한 SSID 캐시
        val fromCallback = latestCallbackWifiSsid
        if (fromCallback != null) {
            return true to fromCallback
        }

        // 3) WifiManager.connectionInfo 폴백 (Android 10~11 호환)
        val fromWifiManager = runCatching {
            @Suppress("DEPRECATION")
            sanitizeSsid(wifiManager.connectionInfo?.ssid)
        }.getOrNull()

        return true to fromWifiManager
    }

    private fun isHomeWifiMatched(isWifi: Boolean, currentSsid: String?): Boolean {
        if (!isWifi || homeWifiSsid.isBlank() || currentSsid.isNullOrBlank()) return false
        return currentSsid.equals(homeWifiSsid, ignoreCase = true)
    }

    private fun hasSavedTsnetState(): Boolean {
        return stateDir.exists() && (stateDir.listFiles()?.isNotEmpty() == true)
    }

    private fun sanitizeSsid(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().removeSurrounding("\"")
        if (cleaned.isBlank() || cleaned.equals("<unknown ssid>", ignoreCase = true)) {
            return null
        }
        return cleaned
    }

    companion object {
        private const val TSNET_STATE_DIR_NAME = "tsnet-state"
        private const val DEFAULT_TSNET_HOSTNAME = "unraid-hub-android"
    }
}
