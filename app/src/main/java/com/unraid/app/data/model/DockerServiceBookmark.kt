package com.unraid.app.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 도커 웹뷰 허브에 등록되는 개별 웹 서비스 북마크 모델
 * (예: Kavita, Portainer, Sonarr 등)
 */
@Serializable
data class DockerServiceBookmark(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String
)
