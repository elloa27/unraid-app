package com.unraid.app.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 사용자가 수동으로 추가한 외부/커스텀 도커 웹 앱 북마크 모델
 */
@Serializable
data class DockerServiceBookmark(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val iconUrl: String? = null,
    val preferNativeUi: Boolean = true
)

/**
 * Unraid 자동 감지 도커 항목에 대해 사용자가 커스텀한 이름, URL, 전용 UI 우선 사용 여부 저장 모델
 */
@Serializable
data class AutoDockerOverride(
    val customName: String? = null,
    val customUrl: String? = null,
    val preferNativeUi: Boolean = true
)
