package com.unraid.app.data.model

/**
 * Unraid 네이티브 관리 화면 7개 서브탭 정의
 */
enum class UnraidSubTab(
    val labelKo: String,
    val webUiPath: String
) {
    DASHBOARD("대시보드", "/Dashboard"),
    MAIN("메인", "/Main"),
    DOCKER("도커", "/Docker"),
    SHARES("공유", "/Shares"),
    USERS("사용자", "/Users"),
    SETTINGS("설정", "/Settings"),
    APPS("앱", "/Apps")
}

/**
 * Unraid 대시보드 화면에서 사용하는 통합 도메인 모델 (어레이/디스크/도커)
 */
data class UnraidDashboardData(
    val arrayState: String,
    val capacityTotalKb: Long?,
    val capacityUsedKb: Long?,
    val capacityFreeKb: Long?,
    val disks: List<UnraidDisk>,
    val containers: List<DockerContainerItem>,
    val fetchedAtMillis: Long = System.currentTimeMillis()
)

/**
 * Unraid 어레이 / 패리티 / 캐시 디스크 정보
 */
data class UnraidDisk(
    val id: String,
    val name: String,
    val device: String,
    val role: DiskRole,
    val status: String,
    val sizeKb: Long?,
    val tempCelsius: Int?,
    val isSpinning: Boolean? = null
)

enum class DiskRole(val labelKo: String) {
    PARITY("패리티"),
    ARRAY("어레이"),
    CACHE("캐시")
}

/**
 * Unraid 도커 컨테이너 정보 (자동 감지된 웹 UI 주소, 아이콘 및 자동 시작 여부 포함)
 */
data class DockerContainerItem(
    val id: String,
    val name: String,
    val image: String,
    val state: String,
    val status: String,
    val webUiUrl: String? = null,
    val iconUrl: String? = null,
    val autoStart: Boolean? = null
) {
    val isRunning: Boolean
        get() = state.equals("RUNNING", ignoreCase = true) ||
            status.startsWith("Up", ignoreCase = true)

    val hasWebUi: Boolean
        get() = !webUiUrl.isNullOrBlank()

    /**
     * 컨테이너 재생성/업데이트로 내부 해시 ID가 바뀌어도 캐시 키가 안정적으로 유지되도록
     * 컨테이너 이름 기반의 자동 감지 서비스 ID를 제공합니다.
     */
    val autoServiceId: String
        get() = "auto:${name.lowercase()}"
}

/**
 * 도커 웹뷰 런처 서비스 출처 구분 (Unraid 자동 감지 / 수동 등록)
 */
enum class WebServiceSource(val labelKo: String) {
    AUTO_DOCKER("자동 감지"),
    MANUAL("수동 등록")
}

/**
 * 도커 컨테이너 제어 명령 종류
 */
enum class DockerAction(val labelKo: String) {
    START("시작"),
    STOP("중지"),
    RESTART("재시작")
}

/**
 * 5초 실시간 관제용 CPU/메모리/Swap 시스템 메트릭
 */
data class SystemMetricsData(
    val cpuPercent: Float? = null,
    val memoryTotalBytes: Long? = null,
    val memoryUsedBytes: Long? = null,
    val memoryFreeBytes: Long? = null,
    val memoryAvailableBytes: Long? = null,
    val memoryActiveBytes: Long? = null,
    val memoryBuffCacheBytes: Long? = null,
    val memoryPercent: Float? = null,
    val swapTotalBytes: Long? = null,
    val swapUsedBytes: Long? = null,
    val swapPercent: Float? = null,
    val fetchedAtMillis: Long = System.currentTimeMillis()
)

/**
 * 미니 스파크라인(추이 그래프)용 CPU/메모리 시계열 샘플
 */
data class MetricSample(
    val cpuPercent: Float,
    val memoryPercent: Float,
    val timestampMillis: Long = System.currentTimeMillis()
)

/**
 * 서버 OS / CPU / 메인보드 하드웨어 정보
 */
data class SystemHardwareInfo(
    val hostname: String? = null,
    val uptime: String? = null,
    val distro: String? = null,
    val release: String? = null,
    val cpuBrand: String? = null,
    val cpuCores: Int? = null,
    val cpuThreads: Int? = null,
    val boardManufacturer: String? = null,
    val boardModel: String? = null
)

/**
 * 읽지 않은 시스템 알림 개수 요약
 */
data class NotificationUnreadCounts(
    val info: Int = 0,
    val warning: Int = 0,
    val alert: Int = 0,
    val total: Int = 0
)

/**
 * Unraid 시스템 알림 항목
 */
data class UnraidNotificationItem(
    val id: String,
    val title: String,
    val subject: String,
    val description: String,
    val importance: String,
    val timestamp: String? = null
)

/**
 * 대시보드 탭 상단/하단에 표시되는 시스템 개요(하드웨어 + 알림) 통합 모델
 */
data class SystemOverviewData(
    val hardware: SystemHardwareInfo? = null,
    val unreadCounts: NotificationUnreadCounts? = null,
    val unreadNotifications: List<UnraidNotificationItem> = emptyList(),
    val notificationsSupported: Boolean = true,
    val fetchedAtMillis: Long = System.currentTimeMillis()
)

/**
 * 패리티 체크 제어 명령
 */
enum class ParityCheckAction(val labelKo: String) {
    START("시작"),
    PAUSE("일시정지"),
    RESUME("재개"),
    CANCEL("취소")
}

/**
 * 최근 패리티 체크 이력 항목
 */
data class ParityHistoryItem(
    val date: String? = null,
    val durationSec: Long? = null,
    val speed: String? = null,
    val status: String? = null,
    val errors: Long? = null
)

/**
 * Unraid 공유 폴더 정보
 */
data class UnraidShareItem(
    val id: String,
    val name: String,
    val comment: String = "",
    val freeKb: Long? = null,
    val usedKb: Long? = null,
    val sizeKb: Long? = null,
    val cache: String? = null,
    val floor: String? = null,
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList()
)

/**
 * 현재 연결된 Unraid 계정(me) 정보
 */
data class UnraidUserProfile(
    val id: String,
    val name: String,
    val description: String = "",
    val roles: List<String> = emptyList()
)

/**
 * Unraid API 키 항목
 */
data class UnraidApiKeyItem(
    val id: String,
    val name: String,
    val description: String = "",
    val roles: List<String> = emptyList(),
    val createdAt: String? = null
)

/**
 * 새로 생성된 API 키 결과 (키 원문은 생성 직후 1회만 반환)
 */
data class CreatedApiKeyResult(
    val id: String,
    val name: String,
    val key: String? = null
)

/**
 * 사용자 탭 통합 모델
 */
data class UsersAndApiKeysData(
    val me: UnraidUserProfile? = null,
    val apiKeys: List<UnraidApiKeyItem> = emptyList(),
    val apiKeysSupported: Boolean = true,
    val fetchedAtMillis: Long = System.currentTimeMillis()
)

/**
 * Unraid 설정 탭 시스템 요약 모델
 */
data class UnraidSettingsSummary(
    val version: String? = null,
    val serverName: String? = null,
    val comment: String? = null,
    val timeZone: String? = null,
    val useSsl: String? = null,
    val port: Int? = null,
    val portSsl: Int? = null,
    val systemTime: String? = null,
    val upsStatus: String? = null,
    val upsModel: String? = null,
    val upsBatteryCharge: Int? = null,
    val upsRuntimeLeft: String? = null,
    val fetchedAtMillis: Long = System.currentTimeMillis()
)

/**
 * 설치된 Unraid 플러그인 정보
 */
data class UnraidPluginItem(
    val name: String,
    val version: String? = null,
    val hasApiModule: Boolean? = null,
    val hasCliModule: Boolean? = null
)
