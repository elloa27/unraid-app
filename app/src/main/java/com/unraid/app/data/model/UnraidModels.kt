package com.unraid.app.data.model

/**
 * Unraid 대시보드 화면에서 사용하는 통합 도메인 모델
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
 * Unraid 도커 컨테이너 정보 (자동 감지된 웹 UI 주소 및 아이콘 포함)
 */
data class DockerContainerItem(
    val id: String,
    val name: String,
    val image: String,
    val state: String,
    val status: String,
    val webUiUrl: String? = null,
    val iconUrl: String? = null
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
