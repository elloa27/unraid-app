package com.unraid.app.plugin

import kotlinx.serialization.Serializable
import java.io.File
import java.net.URI

/**
 * 외부 플러그인 manifest.json 내 도커 서비스 자동 매칭 규칙
 */
@Serializable
data class PluginMatchRule(
    val keywords: List<String> = emptyList(),
    val ports: List<Int> = emptyList()
)

/**
 * 외부 플러그인 패키지의 manifest.json 규격
 */
@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val entry: String = "index.html",
    val updateUrl: String? = null,
    val match: PluginMatchRule = PluginMatchRule()
)

/**
 * 앱 내부 저장소(filesDir/plugins/<id>/)에 설치 완료된 외부 플러그인 모델
 */
data class InstalledPlugin(
    val manifest: PluginManifest,
    val installDir: File,
    val installedAtMillis: Long = System.currentTimeMillis()
) {
    val id: String get() = manifest.id
    val name: String get() = manifest.name
    val version: String get() = manifest.version
    val description: String get() = manifest.description
    val entryPath: String get() = manifest.entry.trim().trimStart('/').ifBlank { "index.html" }

    /**
     * WebViewAssetLoader 가상 HTTPS 도메인 기반 진입 URL
     * 예: https://plugins.unraidhub.local/emby-ui/index.html
     */
    val virtualEntryUrl: String
        get() = "https://${PLUGIN_VIRTUAL_HOST}/$id/$entryPath"

    /**
     * 런처의 도커 서비스가 이 플러그인의 match.keywords 또는 match.ports 규칙과 일치하는지 판별합니다.
     */
    fun matches(service: LauncherService): Boolean {
        val keywords = manifest.match.keywords
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (keywords.isNotEmpty()) {
            val haystack = listOfNotNull(
                service.imageName,
                service.containerName,
                service.name,
                service.url
            )
            if (keywords.any { kw -> haystack.any { field -> field.contains(kw, ignoreCase = true) } }) {
                return true
            }
        }

        val ports = manifest.match.ports
        if (ports.isNotEmpty()) {
            val servicePort = runCatching { URI(service.url).port }.getOrNull()
            if (servicePort != null && servicePort in ports) {
                return true
            }
        }

        return false
    }

    /**
     * 설정 화면 표시용 매칭 규칙 요약 문자열
     */
    fun matchSummaryText(): String {
        val parts = buildList {
            if (manifest.match.keywords.isNotEmpty()) {
                add("키워드: ${manifest.match.keywords.joinToString(", ")}")
            }
            if (manifest.match.ports.isNotEmpty()) {
                add("포트: ${manifest.match.ports.joinToString(", ")}")
            }
        }
        return if (parts.isEmpty()) "자동 매칭 규칙 없음" else parts.joinToString(" · ")
    }

    companion object {
        const val PLUGIN_VIRTUAL_HOST = "plugins.unraidhub.local"
    }
}

/**
 * 플러그인 실행 시 window.UnraidHub.getContext()로 전달되는 컨텍스트 정보
 */
data class PluginBridgeContext(
    val pluginId: String,
    val pluginVersion: String,
    val serviceName: String,
    val serviceUrl: String,
    val tailscaleUrl: String,
    val activeBaseUrl: String,
    val isTailscaleActive: Boolean
)
