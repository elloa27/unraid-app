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
 * Unraid 도커 컨테이너 정보
 */
data class DockerContainerItem(
    val id: String,
    val name: String,
    val image: String,
    val state: String,
    val status: String
) {
    val isRunning: Boolean
        get() = state.equals("RUNNING", ignoreCase = true) ||
            status.startsWith("Up", ignoreCase = true)
}

/**
 * 도커 컨테이너 제어 명령 종류
 */
enum class DockerAction(val labelKo: String) {
    START("시작"),
    STOP("중지"),
    RESTART("재시작")
}
