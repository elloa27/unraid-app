package com.unraid.app.plugin.emby

import com.unraid.app.data.local.AppErrorLogger
import com.unraid.app.network.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.UUID

data class EmbyLoginResult(
    val accessToken: String,
    val userId: String,
    val username: String
)

/**
 * 기존 NetworkModule.okHttpClient(동적 Tailscale 프록시 + 자체 서명 SSL 허용)를 재사용하는
 * Emby REST API 클라이언트.
 */
class EmbyApiClient(
    private val httpClientProvider: () -> OkHttpClient = { NetworkModule.okHttpClient }
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * 사용자 아이디 / 비밀번호로 Emby 서버 인증 (POST /Users/AuthenticateByName)
     */
    suspend fun authenticateByName(
        rawBaseUrl: String,
        username: String,
        password: String
    ): Result<EmbyLoginResult> = withContext(Dispatchers.IO) {
        val baseUrl = normalizeEmbyServerUrl(rawBaseUrl)
        if (baseUrl.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Emby 서버 URL을 입력해 주세요."))
        }
        if (username.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Emby 아이디를 입력해 주세요."))
        }

        val payload = buildJsonObject {
            put("Username", username.trim())
            put("Pw", password)
        }.toString()

        executeRequest(
            url = "$baseUrl/Users/AuthenticateByName",
            method = "POST",
            accessToken = null,
            jsonBody = payload,
            operationTag = "Emby 로그인"
        ).mapCatching { bodyText ->
            val root = json.parseToJsonElement(bodyText) as? JsonObject
                ?: throw IllegalStateException("Emby 로그인 응답 형식이 올바르지 않습니다.")
            val token = root.stringOrNull("AccessToken")
                ?: throw IllegalStateException("응답에 AccessToken이 없습니다.")
            val userObj = root["User"] as? JsonObject
            val userId = userObj?.stringOrNull("Id")
                ?: throw IllegalStateException("응답에 User.Id가 없습니다.")
            val userName = userObj.stringOrNull("Name") ?: username.trim()
            EmbyLoginResult(accessToken = token, userId = userId, username = userName)
        }
    }

    /**
     * 홈 화면 - 이어보기(Resume) 목록 조회
     */
    suspend fun fetchResumeItems(
        baseUrl: String,
        userId: String,
        accessToken: String
    ): Result<List<EmbyItem>> {
        val url = "$baseUrl/Users/$userId/Items/Resume" +
            "?Limit=15&Recursive=true&MediaTypes=Video" +
            "&Fields=PrimaryImageAspectRatio,Overview,MediaSources,UserData,SeriesName,ParentIndexNumber,IndexNumber,RunTimeTicks,ProductionYear"
        return fetchItemListFromItemsWrapper(url, baseUrl, accessToken, "Emby 이어보기 조회")
    }

    /**
     * 홈 화면 - 최근 추가된 항목(Latest) 조회
     */
    suspend fun fetchLatestItems(
        baseUrl: String,
        userId: String,
        accessToken: String
    ): Result<List<EmbyItem>> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/Users/$userId/Items/Latest" +
            "?Limit=16&Fields=PrimaryImageAspectRatio,Overview,MediaSources,UserData,ProductionYear,RunTimeTicks"
        executeRequest(
            url = url,
            method = "GET",
            accessToken = accessToken,
            operationTag = "Emby 최근 추가 항목 조회"
        ).mapCatching { body ->
            val elem = json.parseToJsonElement(body)
            val arr = when (elem) {
                is JsonArray -> elem
                is JsonObject -> elem["Items"] as? JsonArray
                else -> null
            } ?: JsonArray(emptyList())
            arr.mapNotNull { (it as? JsonObject)?.let { obj -> parseItem(obj, baseUrl, accessToken) } }
        }
    }

    /**
     * 홈 화면 - 라이브러리 목록(영화, TV 시리즈, 애니 등) 조회
     */
    suspend fun fetchLibraries(
        baseUrl: String,
        userId: String,
        accessToken: String
    ): Result<List<EmbyLibrary>> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/Users/$userId/Views"
        executeRequest(
            url = url,
            method = "GET",
            accessToken = accessToken,
            operationTag = "Emby 라이브러리 목록 조회"
        ).mapCatching { body ->
            val root = json.parseToJsonElement(body) as? JsonObject
            val arr = root?.get("Items") as? JsonArray ?: JsonArray(emptyList())
            arr.mapNotNull { elem ->
                val obj = elem as? JsonObject ?: return@mapNotNull null
                val id = obj.stringOrNull("Id") ?: return@mapNotNull null
                val name = obj.stringOrNull("Name") ?: return@mapNotNull null
                val collectionType = obj.stringOrNull("CollectionType").orEmpty()
                val hasPrimary = (obj["ImageTags"] as? JsonObject)?.containsKey("Primary") == true
                EmbyLibrary(
                    id = id,
                    name = name,
                    collectionType = collectionType,
                    imageUrl = if (hasPrimary) buildPrimaryImageUrl(baseUrl, id, accessToken, 540) else null
                )
            }
        }
    }

    /**
     * 특정 라이브러리 내부 아이템 그리드 조회
     */
    suspend fun fetchLibraryItems(
        baseUrl: String,
        userId: String,
        accessToken: String,
        libraryId: String
    ): Result<List<EmbyItem>> {
        val url = "$baseUrl/Users/$userId/Items" +
            "?ParentId=$libraryId&Recursive=true&IncludeItemTypes=Movie,Series,Video" +
            "&SortBy=DateCreated,SortName&SortOrder=Descending&Limit=100" +
            "&Fields=PrimaryImageAspectRatio,Overview,ProductionYear,UserData,RunTimeTicks"
        return fetchItemListFromItemsWrapper(url, baseUrl, accessToken, "Emby 라이브러리 항목 조회")
    }

    /**
     * 전체 라이브러리 키워드 검색
     */
    suspend fun searchItems(
        baseUrl: String,
        userId: String,
        accessToken: String,
        query: String
    ): Result<List<EmbyItem>> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$baseUrl/Users/$userId/Items" +
            "?SearchTerm=$encoded&Recursive=true&IncludeItemTypes=Movie,Series,Episode,Video&Limit=50" +
            "&Fields=PrimaryImageAspectRatio,Overview,ProductionYear,UserData,SeriesName,ParentIndexNumber,IndexNumber,RunTimeTicks"
        return fetchItemListFromItemsWrapper(url, baseUrl, accessToken, "Emby 검색")
    }

    /**
     * 시리즈의 시즌 목록 조회
     */
    suspend fun fetchSeasons(
        baseUrl: String,
        userId: String,
        accessToken: String,
        seriesId: String
    ): Result<List<EmbyItem>> {
        val url = "$baseUrl/Shows/$seriesId/Seasons" +
            "?UserId=$userId&Fields=Overview,PrimaryImageAspectRatio,UserData"
        return fetchItemListFromItemsWrapper(url, baseUrl, accessToken, "Emby 시즌 목록 조회")
    }

    /**
     * 특정 시즌의 에피소드 목록 조회
     */
    suspend fun fetchEpisodes(
        baseUrl: String,
        userId: String,
        accessToken: String,
        seriesId: String,
        seasonId: String
    ): Result<List<EmbyItem>> {
        val url = "$baseUrl/Shows/$seriesId/Episodes" +
            "?SeasonId=$seasonId&UserId=$userId" +
            "&Fields=Overview,PrimaryImageAspectRatio,MediaSources,UserData,RunTimeTicks,SeriesName,ParentIndexNumber,IndexNumber"
        return fetchItemListFromItemsWrapper(url, baseUrl, accessToken, "Emby 에피소드 목록 조회")
    }

    /**
     * 단일 콘텐츠 상세 정보 조회 (오디오/자막 트랙, 줄거리, 재생 위치 포함)
     */
    suspend fun fetchItemDetail(
        baseUrl: String,
        userId: String,
        accessToken: String,
        itemId: String
    ): Result<EmbyItem> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/Users/$userId/Items/$itemId" +
            "?Fields=Overview,Genres,MediaSources,MediaStreams,UserData,RunTimeTicks,ProductionYear,OfficialRating,CommunityRating,SeriesName,ParentIndexNumber,IndexNumber"
        executeRequest(
            url = url,
            method = "GET",
            accessToken = accessToken,
            operationTag = "Emby 상세 정보 조회"
        ).mapCatching { body ->
            val obj = json.parseToJsonElement(body) as? JsonObject
                ?: throw IllegalStateException("Emby 상세 응답이 올바르지 않습니다.")
            parseItem(obj, baseUrl, accessToken)
        }
    }

    /**
     * 트랜스코딩 없는 Direct Play 재생 요청 객체 생성
     */
    fun buildPlaybackRequest(
        item: EmbyItem,
        baseUrl: String,
        accessToken: String,
        userId: String,
        resume: Boolean
    ): EmbyPlaybackRequest {
        val source = item.mediaSources.firstOrNull()
        val mediaSourceId = source?.id ?: item.id
        val encodedToken = URLEncoder.encode(accessToken, "UTF-8")

        // Unraid CPU 부하가 없는 원본 Direct Stream URL
        val streamUrl = "$baseUrl/Videos/${item.id}/stream" +
            "?static=true&MediaSourceId=$mediaSourceId&api_key=$encodedToken"

        // 외부 SRT/ASS 자막 URL 목록 생성
        val externalSubs = source?.subtitleStreams
            ?.filter { it.isExternal }
            ?.map { stream ->
                val subUrl = if (!stream.deliveryUrl.isNullOrBlank()) {
                    val rawPath = if (stream.deliveryUrl.startsWith("http", ignoreCase = true)) {
                        stream.deliveryUrl
                    } else {
                        "$baseUrl${stream.deliveryUrl}"
                    }
                    if (rawPath.contains("api_key=", ignoreCase = true)) {
                        rawPath
                    } else {
                        val sep = if (rawPath.contains("?")) "&" else "?"
                        "$rawPath${sep}api_key=$encodedToken"
                    }
                } else {
                    val ext = mapSubtitleExtension(stream.codec)
                    "$baseUrl/Videos/${item.id}/$mediaSourceId/Subtitles/${stream.index}/Stream.$ext?api_key=$encodedToken"
                }
                EmbyExternalSubtitle(
                    title = stream.label,
                    language = stream.language.ifBlank { "und" },
                    url = subUrl
                )
            }
            .orEmpty()

        return EmbyPlaybackRequest(
            item = item,
            baseUrl = baseUrl,
            accessToken = accessToken,
            userId = userId,
            mediaSourceId = mediaSourceId,
            streamUrl = streamUrl,
            startPositionTicks = if (resume) item.playbackPositionTicks else 0L,
            externalSubtitles = externalSubs,
            playSessionId = UUID.randomUUID().toString()
        )
    }

    /**
     * Emby 재생 시작 보고 (POST /Sessions/Playing)
     */
    suspend fun reportPlaybackStarted(request: EmbyPlaybackRequest) {
        val payload = buildJsonObject {
            put("ItemId", request.item.id)
            put("MediaSourceId", request.mediaSourceId)
            put("PositionTicks", request.startPositionTicks)
            put("PlayMethod", "DirectPlay")
            put("IsPaused", false)
            put("CanSeek", true)
            put("PlaySessionId", request.playSessionId)
        }.toString()

        executeRequest(
            url = "${request.baseUrl}/Sessions/Playing",
            method = "POST",
            accessToken = request.accessToken,
            jsonBody = payload,
            operationTag = "Emby 재생 시작 보고"
        )
    }

    /**
     * Emby 10초 주기 재생 진행 보고 (POST /Sessions/Playing/Progress)
     */
    suspend fun reportPlaybackProgress(
        request: EmbyPlaybackRequest,
        positionTicks: Long,
        isPaused: Boolean
    ) {
        val payload = buildJsonObject {
            put("ItemId", request.item.id)
            put("MediaSourceId", request.mediaSourceId)
            put("PositionTicks", positionTicks.coerceAtLeast(0L))
            put("PlayMethod", "DirectPlay")
            put("IsPaused", isPaused)
            put("CanSeek", true)
            put("PlaySessionId", request.playSessionId)
        }.toString()

        executeRequest(
            url = "${request.baseUrl}/Sessions/Playing/Progress",
            method = "POST",
            accessToken = request.accessToken,
            jsonBody = payload,
            operationTag = "Emby 진행률 보고"
        )
    }

    /**
     * Emby 재생 종료 및 최종 이어보기 위치 저장 (POST /Sessions/Playing/Stopped)
     */
    suspend fun reportPlaybackStopped(
        request: EmbyPlaybackRequest,
        positionTicks: Long
    ) {
        val payload = buildJsonObject {
            put("ItemId", request.item.id)
            put("MediaSourceId", request.mediaSourceId)
            put("PositionTicks", positionTicks.coerceAtLeast(0L))
            put("PlaySessionId", request.playSessionId)
        }.toString()

        executeRequest(
            url = "${request.baseUrl}/Sessions/Playing/Stopped",
            method = "POST",
            accessToken = request.accessToken,
            jsonBody = payload,
            operationTag = "Emby 재생 종료 보고"
        )
    }

    private suspend fun fetchItemListFromItemsWrapper(
        url: String,
        baseUrl: String,
        accessToken: String,
        operationTag: String
    ): Result<List<EmbyItem>> = withContext(Dispatchers.IO) {
        executeRequest(
            url = url,
            method = "GET",
            accessToken = accessToken,
            operationTag = operationTag
        ).mapCatching { body ->
            val root = json.parseToJsonElement(body) as? JsonObject
            val arr = root?.get("Items") as? JsonArray ?: JsonArray(emptyList())
            arr.mapNotNull { (it as? JsonObject)?.let { obj -> parseItem(obj, baseUrl, accessToken) } }
        }
    }

    private suspend fun executeRequest(
        url: String,
        method: String,
        accessToken: String?,
        jsonBody: String? = null,
        operationTag: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Emby-Authorization", EMBY_AUTH_HEADER)

        if (!accessToken.isNullOrBlank()) {
            builder.header("X-Emby-Token", accessToken)
        }

        if (method == "POST") {
            val body = (jsonBody ?: "{}").toRequestBody(jsonMediaType)
            builder.post(body)
        } else {
            builder.get()
        }

        try {
            httpClientProvider().newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val msg = "HTTP ${response.code} ${response.message} ($operationTag)"
                    AppErrorLogger.logError(
                        tag = "EmbyAPI",
                        message = msg,
                        extraDetails = "URL: $url\n응답: ${text.take(1000)}"
                    )
                    return@withContext Result.failure(IllegalStateException(msg))
                }
                Result.success(text)
            }
        } catch (e: Exception) {
            val msg = "$operationTag 통신 실패: ${e.localizedMessage ?: e.javaClass.simpleName}"
            AppErrorLogger.logError(
                tag = "EmbyAPI",
                message = msg,
                throwable = e,
                extraDetails = "URL: $url"
            )
            Result.failure(e)
        }
    }

    private fun parseItem(
        obj: JsonObject,
        baseUrl: String,
        accessToken: String
    ): EmbyItem {
        val id = obj.stringOrNull("Id").orEmpty()
        val seriesId = obj.stringOrNull("SeriesId")
        val imageTags = obj["ImageTags"] as? JsonObject
        val hasPrimary = imageTags?.containsKey("Primary") == true
        val backdropTags = obj["BackdropImageTags"] as? JsonArray
        val parentBackdropItemId = obj.stringOrNull("ParentBackdropItemId")

        val primaryUrl = when {
            hasPrimary && id.isNotBlank() -> buildPrimaryImageUrl(baseUrl, id, accessToken, 480)
            !seriesId.isNullOrBlank() -> buildPrimaryImageUrl(baseUrl, seriesId, accessToken, 480)
            else -> null
        }

        val backdropUrl = when {
            backdropTags != null && backdropTags.isNotEmpty() && id.isNotBlank() ->
                buildBackdropImageUrl(baseUrl, id, accessToken)
            !parentBackdropItemId.isNullOrBlank() ->
                buildBackdropImageUrl(baseUrl, parentBackdropItemId, accessToken)
            !seriesId.isNullOrBlank() ->
                buildBackdropImageUrl(baseUrl, seriesId, accessToken)
            else -> primaryUrl
        }

        val userData = obj["UserData"] as? JsonObject
        val genres = (obj["Genres"] as? JsonArray)
            ?.mapNotNull { it.primitiveContentOrNull() }
            .orEmpty()

        val mediaSources = parseMediaSources(obj["MediaSources"] as? JsonArray)

        return EmbyItem(
            id = id,
            name = obj.stringOrNull("Name") ?: "제목 없음",
            type = obj.stringOrNull("Type") ?: "Video",
            overview = obj.stringOrNull("Overview").orEmpty(),
            productionYear = obj.intOrNull("ProductionYear"),
            officialRating = obj.stringOrNull("OfficialRating"),
            communityRating = obj.floatOrNull("CommunityRating"),
            seriesId = seriesId,
            seriesName = obj.stringOrNull("SeriesName"),
            seasonNumber = obj.intOrNull("ParentIndexNumber"),
            episodeNumber = obj.intOrNull("IndexNumber"),
            runTimeTicks = obj.longOrNull("RunTimeTicks") ?: 0L,
            playbackPositionTicks = userData?.longOrNull("PlaybackPositionTicks") ?: 0L,
            played = userData?.booleanOrNull("Played") ?: false,
            genres = genres,
            primaryImageUrl = primaryUrl,
            backdropImageUrl = backdropUrl,
            mediaSources = mediaSources
        )
    }

    private fun parseMediaSources(array: JsonArray?): List<EmbyMediaSource> {
        if (array == null) return emptyList()
        return array.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val id = obj.stringOrNull("Id") ?: return@mapNotNull null
            val streamsArr = obj["MediaStreams"] as? JsonArray
            val streams = streamsArr?.mapNotNull { sElem ->
                val sObj = sElem as? JsonObject ?: return@mapNotNull null
                EmbyMediaStream(
                    index = sObj.intOrNull("Index") ?: return@mapNotNull null,
                    type = sObj.stringOrNull("Type").orEmpty(),
                    codec = sObj.stringOrNull("Codec").orEmpty(),
                    language = sObj.stringOrNull("Language").orEmpty(),
                    displayTitle = sObj.stringOrNull("DisplayTitle").orEmpty(),
                    isExternal = sObj.booleanOrNull("IsExternal") ?: false,
                    isDefault = sObj.booleanOrNull("IsDefault") ?: false,
                    deliveryUrl = sObj.stringOrNull("DeliveryUrl")
                )
            }.orEmpty()

            EmbyMediaSource(
                id = id,
                container = obj.stringOrNull("Container").orEmpty(),
                bitrate = obj.longOrNull("Bitrate"),
                mediaStreams = streams
            )
        }
    }

    private fun buildPrimaryImageUrl(
        baseUrl: String,
        itemId: String,
        accessToken: String,
        maxWidth: Int
    ): String {
        val token = URLEncoder.encode(accessToken, "UTF-8")
        return "$baseUrl/Items/$itemId/Images/Primary?maxWidth=$maxWidth&quality=90&api_key=$token"
    }

    private fun buildBackdropImageUrl(
        baseUrl: String,
        itemId: String,
        accessToken: String
    ): String {
        val token = URLEncoder.encode(accessToken, "UTF-8")
        return "$baseUrl/Items/$itemId/Images/Backdrop/0?maxWidth=1280&quality=85&api_key=$token"
    }

    private fun mapSubtitleExtension(codec: String): String {
        return when (codec.lowercase()) {
            "ass", "ssa" -> "ass"
            "vtt", "webvtt" -> "vtt"
            "subrip", "srt" -> "srt"
            else -> "srt"
        }
    }

    private fun JsonElement.primitiveContentOrNull(): String? {
        if (this is JsonNull) return null
        return (this as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject.stringOrNull(key: String): String? = this[key]?.primitiveContentOrNull()
    private fun JsonObject.intOrNull(key: String): Int? = stringOrNull(key)?.toIntOrNull()
    private fun JsonObject.longOrNull(key: String): Long? = stringOrNull(key)?.toLongOrNull()
    private fun JsonObject.floatOrNull(key: String): Float? = stringOrNull(key)?.toFloatOrNull()
    private fun JsonObject.booleanOrNull(key: String): Boolean? = stringOrNull(key)?.toBooleanStrictOrNull()

    companion object {
        private const val EMBY_AUTH_HEADER =
            """MediaBrowser Client="UnraidHub", Device="Android", DeviceId="unraid-hub-android", Version="1.0.0""""
    }
}
