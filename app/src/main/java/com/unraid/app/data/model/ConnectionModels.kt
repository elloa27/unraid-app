package com.unraid.app.data.model

/**
 * Unraid / 도커 접속 경로 선택 모드
 */
enum class ConnectionMode(
    val storageKey: String,
    val labelKo: String,
    val descriptionKo: String
) {
    AUTO(
        storageKey = "auto",
        labelKo = "자동 전환 (권장)",
        descriptionKo = "지정한 홈 Wi-Fi에서는 직접 연결, 외부(LTE/5G·외부 Wi-Fi)에서는 Tailscale 자동 연결"
    ),
    DIRECT_ONLY(
        storageKey = "direct",
        labelKo = "항상 직접 연결 (Wi-Fi)",
        descriptionKo = "내장 Tailscale을 끄고 현재 네트워크로 직접 통신합니다."
    ),
    TAILSCALE_ONLY(
        storageKey = "tailscale",
        labelKo = "항상 Tailscale 사용",
        descriptionKo = "홈 Wi-Fi 여부와 관계없이 항상 내장 Tailscale(tsnet)을 통해 통신합니다."
    );

    companion object {
        fun fromStorageKey(key: String?): ConnectionMode {
            return entries.firstOrNull { it.storageKey.equals(key, ignoreCase = true) } ?: AUTO
        }
    }
}

/**
 * 현재 네트워크 라우팅 상태 단계
 */
enum class RoutePhase {
    DIRECT_HOME_WIFI,
    DIRECT_MANUAL,
    TAILSCALE_CONNECTING,
    TAILSCALE_CONNECTED,
    TAILSCALE_ERROR
}

/**
 * 대시보드 상단 배지 및 설정 화면에 실시간 표시되는 연결 상태 스냅샷
 */
data class ConnectionStatus(
    val phase: RoutePhase = RoutePhase.DIRECT_MANUAL,
    val mode: ConnectionMode = ConnectionMode.AUTO,
    val isOnWifi: Boolean = false,
    val currentWifiSsid: String? = null,
    val homeWifiSsid: String = "",
    val hasLocationPermission: Boolean = false,
    val proxyPort: Int = 0,
    val tailscaleDetail: String = "대기 중",
    val lastError: String? = null
) {
    val isUsingTailscale: Boolean
        get() = phase == RoutePhase.TAILSCALE_CONNECTED || phase == RoutePhase.TAILSCALE_CONNECTING

    val badgeLabel: String
        get() = when (phase) {
            RoutePhase.DIRECT_HOME_WIFI -> "홈 Wi-Fi 직접 연결"
            RoutePhase.DIRECT_MANUAL -> "직접 연결"
            RoutePhase.TAILSCALE_CONNECTING -> "Tailscale 연결 중..."
            RoutePhase.TAILSCALE_CONNECTED -> "Tailscale 연결됨"
            RoutePhase.TAILSCALE_ERROR -> "Tailscale 오류"
        }
}

/**
 * 현재 연결 경로(직접 vs Tailscale)에 따라 실제 요청할 Unraid 서버 URL을 결정합니다.
 * 외부(Tailscale)용 URL이 비어 있으면 서브넷 라우터 환경으로 간주하고 기본(홈) URL을 그대로 사용합니다.
 */
fun resolveEffectiveUnraidUrl(
    homeUrl: String,
    tailscaleUrl: String,
    useTailscale: Boolean
): String {
    return if (useTailscale && tailscaleUrl.isNotBlank()) {
        tailscaleUrl
    } else {
        homeUrl
    }
}
