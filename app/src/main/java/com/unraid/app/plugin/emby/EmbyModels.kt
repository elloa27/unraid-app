package com.unraid.app.plugin.emby

/**
 * 암호화 저장소에 보관되는 Emby 인증 및 서버 연결 정보
 */
data class EmbyAuthConfig(
    val serverUrl: String = "",
    val tailscaleServerUrl: String = "",
    val accessToken: String = "",
    val userId: String = "",
    val username: String = "",
    val preferHardwareDecoding: Boolean = true
) {
    val isAuthenticated: Boolean
        get() = accessToken.isNotBlank() && userId.isNotBlank()

    /**
     * 현재 네트워크 경로(홈 Wi-Fi vs Tailscale)와 런처 도커 기본 주소를 조합해
     * 실제 요청에 사용할 Emby API 베이스 URL을 반환합니다.
     */
    fun resolveEffectiveBaseUrl(
        useTailscale: Boolean,
        fallbackDockerUrl: String? = null
    ): String {
        val raw = when {
            useTailscale && tailscaleServerUrl.isNotBlank() -> tailscaleServerUrl
            serverUrl.isNotBlank() -> serverUrl
            !fallbackDockerUrl.isNullOrBlank() -> fallbackDockerUrl
            else -> ""
        }
        return normalizeEmbyServerUrl(raw)
    }
}

/**
 * Emby 라이브러리(영화, TV 시리즈, 애니메이션 등) 모델
 */
data class EmbyLibrary(
    val id: String,
    val name: String,
    val collectionType: String = "",
    val imageUrl: String? = null
)

/**
 * Emby 콘텐츠 아이템(영화, 시리즈, 시즌, 에피소드, 일반 비디오) 모델
 */
data class EmbyItem(
    val id: String,
    val name: String,
    val type: String, // Movie, Series, Season, Episode, Video 등
    val overview: String = "",
    val productionYear: Int? = null,
    val officialRating: String? = null,
    val communityRating: Float? = null,
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val runTimeTicks: Long = 0L,
    val playbackPositionTicks: Long = 0L,
    val played: Boolean = false,
    val genres: List<String> = emptyList(),
    val primaryImageUrl: String? = null,
    val backdropImageUrl: String? = null,
    val mediaSources: List<EmbyMediaSource> = emptyList()
) {
    val isPlayable: Boolean
        get() = type.equals("Movie", ignoreCase = true) ||
            type.equals("Episode", ignoreCase = true) ||
            type.equals("Video", ignoreCase = true)

    val isSeries: Boolean
        get() = type.equals("Series", ignoreCase = true)

    val isSeason: Boolean
        get() = type.equals("Season", ignoreCase = true)

    val playbackProgressFraction: Float
        get() = if (runTimeTicks > 0L && playbackPositionTicks > 0L) {
            (playbackPositionTicks.toFloat() / runTimeTicks.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val displaySubtitle: String
        get() = buildList {
            if (!seriesName.isNullOrBlank()) add(seriesName)
            if (seasonNumber != null && episodeNumber != null) {
                add("S${seasonNumber}E${episodeNumber}")
            } else if (productionYear != null) {
                add(productionYear.toString())
            }
            if (runTimeTicks > 0L) {
                val mins = (runTimeTicks / TICKS_PER_MINUTE).toInt()
                if (mins > 0) add("${mins}분")
            }
        }.joinToString(" · ")
}

/**
 * Emby 미디어 소스 (Direct Play 원본 스트림 정보 및 내부/외부 트랙 목록)
 */
data class EmbyMediaSource(
    val id: String,
    val container: String = "",
    val bitrate: Long? = null,
    val mediaStreams: List<EmbyMediaStream> = emptyList()
) {
    val videoStreams: List<EmbyMediaStream>
        get() = mediaStreams.filter { it.type.equals("Video", ignoreCase = true) }

    val audioStreams: List<EmbyMediaStream>
        get() = mediaStreams.filter { it.type.equals("Audio", ignoreCase = true) }

    val subtitleStreams: List<EmbyMediaStream>
        get() = mediaStreams.filter { it.type.equals("Subtitle", ignoreCase = true) }
}

/**
 * 오디오/자막/비디오 개별 스트림 메타데이터
 */
data class EmbyMediaStream(
    val index: Int,
    val type: String, // Video, Audio, Subtitle
    val codec: String = "",
    val language: String = "",
    val displayTitle: String = "",
    val isExternal: Boolean = false,
    val isDefault: Boolean = false,
    val deliveryUrl: String? = null
) {
    val label: String
        get() = displayTitle.ifBlank {
            listOfNotNull(
                language.takeIf { it.isNotBlank() }?.uppercase(),
                codec.takeIf { it.isNotBlank() }?.uppercase(),
                if (isExternal) "외부" else null
            ).joinToString(" · ").ifBlank { "트랙 #$index" }
        }
}

/**
 * 외부 SRT/ASS 자막 로드 정보
 */
data class EmbyExternalSubtitle(
    val title: String,
    val language: String,
    val url: String
)

/**
 * libmpv 전체 화면 플레이어에 전달되는 Direct Play 재생 요청 모델
 */
data class EmbyPlaybackRequest(
    val item: EmbyItem,
    val baseUrl: String,
    val accessToken: String,
    val userId: String,
    val mediaSourceId: String,
    val streamUrl: String,
    val startPositionTicks: Long,
    val externalSubtitles: List<EmbyExternalSubtitle>,
    val playSessionId: String
)

const val TICKS_PER_SECOND = 10_000_000L
const val TICKS_PER_MINUTE = TICKS_PER_SECOND * 60L

/**
 * Unraid 도커 웹 UI 주소(예: http://192.168.0.10:8096/web/index.html)에서
 * 불필요한 /web 경로 및 해시를 제거하고 순수 Emby API 베이스 URL로 정규화합니다.
 */
fun normalizeEmbyServerUrl(rawUrl: String): String {
    var cleaned = rawUrl.trim()
    if (cleaned.isBlank()) return ""

    if (!cleaned.startsWith("http://", ignoreCase = true) &&
        !cleaned.startsWith("https://", ignoreCase = true)
    ) {
        cleaned = "http://$cleaned"
    }

    // URL 해시(#!...)와 쿼리 제거
    cleaned = cleaned.substringBefore("#").substringBefore("?").trimEnd('/')

    // Emby 웹 클라이언트 진입 경로(/web/index.html, /web) 제거
    val lower = cleaned.lowercase()
    cleaned = when {
        lower.endsWith("/web/index.html") -> cleaned.dropLast("/web/index.html".length)
        lower.endsWith("/web") -> cleaned.dropLast("/web".length)
        else -> cleaned
    }

    return cleaned.trimEnd('/')
}
