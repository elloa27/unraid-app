package com.unraid.app.data.remote

import com.unraid.app.data.local.AppErrorLogger
import com.unraid.app.data.local.AppPreferencesRepository
import com.unraid.app.data.model.CreatedApiKeyResult
import com.unraid.app.data.model.DiskRole
import com.unraid.app.data.model.DockerAction
import com.unraid.app.data.model.DockerContainerItem
import com.unraid.app.data.model.NotificationUnreadCounts
import com.unraid.app.data.model.ParityCheckAction
import com.unraid.app.data.model.ParityHistoryItem
import com.unraid.app.data.model.SystemHardwareInfo
import com.unraid.app.data.model.SystemMetricsData
import com.unraid.app.data.model.SystemOverviewData
import com.unraid.app.data.model.UnraidApiKeyItem
import com.unraid.app.data.model.UnraidDashboardData
import com.unraid.app.data.model.UnraidDisk
import com.unraid.app.data.model.UnraidNotificationItem
import com.unraid.app.data.model.UnraidPluginItem
import com.unraid.app.data.model.UnraidSettingsSummary
import com.unraid.app.data.model.UnraidShareItem
import com.unraid.app.data.model.UnraidUserProfile
import com.unraid.app.data.model.UsersAndApiKeysData
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
import java.net.URI

/**
 * Unraid 공식 GraphQL API (<서버주소>/graphql, 헤더: x-api-key) 통신 클라이언트.
 * - Unraid OS(7.0~7.2 / Unraid Connect) 버전별 스키마 차이에 안전하도록
 *   방어적 JsonObject 파싱(allowPartialDataOnErrors, BigInt 문자열→Long 안전 변환)과
 *   자동 폴백 쿼리를 제공합니다.
 */
class UnraidGraphQlClient(
    private val httpClientProvider: () -> OkHttpClient = { NetworkModule.okHttpClient }
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private data class ContainerPortMapping(
        val ip: String?,
        val privatePort: Int?,
        val publicPort: Int?,
        val type: String?
    )

    // =========================================================================
    // 1. 연결 테스트 및 기본 대시보드 (어레이 / 디스크 / 도커)
    // =========================================================================

    /**
     * 설정 화면의 '연결 테스트' 용도로 Unraid 어레이 상태를 가볍게 조회합니다.
     */
    suspend fun testConnection(serverUrl: String, apiKey: String): Result<String> {
        val query = """
            query TestUnraidConnection {
              array {
                state
              }
            }
        """.trimIndent()

        return executeGraphQl(
            serverUrl = serverUrl,
            apiKey = apiKey,
            query = query,
            operationTag = "연결 테스트"
        ).mapCatching { root ->
            val data = root["data"] as? JsonObject
                ?: throw IllegalStateException("서버 응답에 data 필드가 없습니다.")
            val arrayObj = data["array"] as? JsonObject
            val state = arrayObj?.stringOrNull("state") ?: "UNKNOWN"
            "연결 성공 (어레이 상태: $state)"
        }
    }

    /**
     * 어레이 상태, 용량, 디스크(패리티/어레이/캐시), 도커 컨테이너 목록(웹 UI 라벨/포트/autoStart)을 조회합니다.
     * - autoStart 등 최신 필드가 미지원인 구버전에서는 단계별 폴백으로 기존 안정성을 100% 보존합니다.
     */
    suspend fun fetchDashboard(serverUrl: String, apiKey: String): Result<UnraidDashboardData> {
        val fullQueryWithAutoStart = """
            query GetUnraidDashboard {
              array {
                state
                capacity {
                  kilobytes {
                    total
                    used
                    free
                  }
                }
                parities {
                  id
                  name
                  device
                  status
                  size
                  temp
                }
                disks {
                  id
                  name
                  device
                  status
                  size
                  temp
                }
                caches {
                  id
                  name
                  device
                  status
                  size
                  temp
                }
              }
              docker {
                containers {
                  id
                  names
                  image
                  state
                  status
                  autoStart
                  labels
                  ports {
                    ip
                    privatePort
                    publicPort
                    type
                  }
                }
              }
            }
        """.trimIndent()

        val standardQueryWithoutAutoStart = """
            query GetUnraidDashboardCompat {
              array {
                state
                capacity {
                  kilobytes {
                    total
                    used
                    free
                  }
                }
                parities {
                  id
                  name
                  device
                  status
                  size
                  temp
                }
                disks {
                  id
                  name
                  device
                  status
                  size
                  temp
                }
                caches {
                  id
                  name
                  device
                  status
                  size
                  temp
                }
              }
              docker {
                containers {
                  id
                  names
                  image
                  state
                  status
                  labels
                  ports {
                    ip
                    privatePort
                    publicPort
                    type
                  }
                }
              }
            }
        """.trimIndent()

        val minimalFallbackQuery = """
            query GetUnraidDashboardMinimal {
              array {
                state
                disks {
                  id
                  name
                  device
                  status
                  size
                  temp
                }
              }
              docker {
                containers {
                  id
                  names
                  image
                  state
                  status
                }
              }
            }
        """.trimIndent()

        val primaryResult = executeGraphQl(
            serverUrl = serverUrl,
            apiKey = apiKey,
            query = fullQueryWithAutoStart,
            operationTag = "대시보드 조회",
            allowPartialDataOnErrors = true
        )

        val root = primaryResult.takeIf { it.isSuccess && hasUsableDashboardData(it.getOrNull()) }
            ?.getOrNull()
            ?: run {
                val primaryErr = primaryResult.exceptionOrNull()
                val compatRes = executeGraphQl(
                    serverUrl = serverUrl,
                    apiKey = apiKey,
                    query = standardQueryWithoutAutoStart,
                    operationTag = "대시보드 호환 조회(autoStart 제외)",
                    allowPartialDataOnErrors = true
                )
                if (compatRes.isSuccess && hasUsableDashboardData(compatRes.getOrNull())) {
                    compatRes.getOrThrow()
                } else {
                    executeGraphQl(
                        serverUrl = serverUrl,
                        apiKey = apiKey,
                        query = minimalFallbackQuery,
                        operationTag = "대시보드 최소 폴백 조회",
                        allowPartialDataOnErrors = true
                    ).getOrElse {
                        return Result.failure(primaryErr ?: it)
                    }
                }
            }

        val serverHost = extractHostFromUrl(serverUrl)
        return runCatching {
            parseDashboardData(root, serverHost)
        }.onFailure { e ->
            AppErrorLogger.logError(
                tag = "UnraidGraphQL",
                message = "대시보드 응답 데이터 파싱에 실패했습니다.",
                throwable = e
            )
        }
    }

    private fun hasUsableDashboardData(root: JsonObject?): Boolean {
        val data = root?.get("data") as? JsonObject ?: return false
        return data["array"] is JsonObject || data["docker"] is JsonObject
    }

    // =========================================================================
    // 2. 실시간 시스템 메트릭 조회 (5초 폴링 전용 경량 쿼리)
    // =========================================================================

    suspend fun fetchSystemMetrics(serverUrl: String, apiKey: String): Result<SystemMetricsData> {
        val primaryQuery = """
            query GetSystemMetrics {
              metrics {
                cpu {
                  percentTotal
                }
                memory {
                  total
                  used
                  free
                  available
                  active
                  buffcache
                  percentTotal
                  swapTotal
                  swapUsed
                  percentSwapTotal
                }
              }
            }
        """.trimIndent()

        val fallbackQuery = """
            query GetSystemMetricsMinimal {
              metrics {
                cpu {
                  percentTotal
                }
                memory {
                  total
                  used
                  free
                  percentTotal
                }
              }
            }
        """.trimIndent()

        val primaryRes = executeGraphQl(
            serverUrl = serverUrl,
            apiKey = apiKey,
            query = primaryQuery,
            operationTag = "실시간 메트릭 조회",
            allowPartialDataOnErrors = true
        )

        val root = primaryRes.getOrNull()?.takeIf { hasNonNullDataField(it, "metrics") }
            ?: run {
                val primaryErr = primaryRes.exceptionOrNull()
                AppErrorLogger.logError(
                    tag = "UnraidMetrics",
                    message = "확장 메트릭 필드 조회 실패 — 최소 메트릭 필드로 폴백 조회합니다.",
                    extraDetails = primaryErr?.message
                )
                executeGraphQl(
                    serverUrl = serverUrl,
                    apiKey = apiKey,
                    query = fallbackQuery,
                    operationTag = "실시간 메트릭 최소 폴백 조회",
                    allowPartialDataOnErrors = true
                ).getOrElse { fallbackErr ->
                    return Result.failure(primaryErr ?: fallbackErr)
                }
            }

        return runCatching {
            parseSystemMetrics(root)
        }.onFailure { e ->
            AppErrorLogger.logError(
                tag = "UnraidMetrics",
                message = "실시간 CPU/메모리 메트릭 파싱에 실패했습니다.",
                throwable = e
            )
        }
    }

    // =========================================================================
    // 3. 시스템 하드웨어 정보 및 읽지 않은 알림 조회
    // =========================================================================

    suspend fun fetchSystemOverview(serverUrl: String, apiKey: String): Result<SystemOverviewData> {
        val hardwareResult = fetchHardwareInfo(serverUrl, apiKey)
        val notificationsResult = fetchUnreadNotifications(serverUrl, apiKey)

        if (hardwareResult.isFailure && notificationsResult.isFailure) {
            return Result.failure(
                hardwareResult.exceptionOrNull()
                    ?: notificationsResult.exceptionOrNull()
                    ?: IllegalStateException("시스템 개요 정보를 불러오지 못했습니다.")
            )
        }

        val notifPair = notificationsResult.getOrNull()
        return Result.success(
            SystemOverviewData(
                hardware = hardwareResult.getOrNull(),
                unreadCounts = notifPair?.first,
                unreadNotifications = notifPair?.second.orEmpty(),
                notificationsSupported = notificationsResult.isSuccess
            )
        )
    }

    private suspend fun fetchHardwareInfo(serverUrl: String, apiKey: String): Result<SystemHardwareInfo> {
        val primaryQuery = """
            query GetSystemHardwareInfo {
              info {
                os {
                  hostname
                  uptime
                  distro
                  release
                }
                cpu {
                  brand
                  cores
                  threads
                }
                baseboard {
                  manufacturer
                  model
                }
              }
            }
        """.trimIndent()

        val fallbackQuery = """
            query GetSystemHardwareInfoCompat {
              info {
                os {
                  hostname
                  uptime
                  distro
                  release
                }
                cpu {
                  name
                  cores
                  threads
                }
              }
            }
        """.trimIndent()

        val primaryRes = executeGraphQl(
            serverUrl = serverUrl,
            apiKey = apiKey,
            query = primaryQuery,
            operationTag = "시스템 하드웨어 정보 조회",
            allowPartialDataOnErrors = true
        )

        val root = primaryRes.getOrNull()?.takeIf { hasNonNullDataField(it, "info") }
            ?: run {
                val primaryErr = primaryRes.exceptionOrNull()
                AppErrorLogger.logError(
                    tag = "UnraidOverview",
                    message = "하드웨어 기본 쿼리 실패 — 구버전 호환 쿼리(cpu.name)로 폴백합니다.",
                    extraDetails = primaryErr?.message
                )
                executeGraphQl(
                    serverUrl = serverUrl,
                    apiKey = apiKey,
                    query = fallbackQuery,
                    operationTag = "시스템 하드웨어 정보 호환 조회",
                    allowPartialDataOnErrors = true
                ).getOrElse { fallbackErr ->
                    return Result.failure(primaryErr ?: fallbackErr)
                }
            }

        return runCatching {
            val dataObj = root["data"] as? JsonObject
                ?: throw IllegalStateException("하드웨어 정보 응답에 data가 없습니다.")
            val infoObj = dataObj["info"] as? JsonObject
                ?: throw IllegalStateException("하드웨어 info 필드가 비어 있습니다.")
            val osObj = infoObj["os"] as? JsonObject
            val cpuObj = infoObj["cpu"] as? JsonObject
            val boardObj = infoObj["baseboard"] as? JsonObject

            SystemHardwareInfo(
                hostname = osObj?.stringOrNull("hostname"),
                uptime = osObj?.stringOrNull("uptime"),
                distro = osObj?.stringOrNull("distro"),
                release = osObj?.stringOrNull("release"),
                cpuBrand = cpuObj?.stringOrNull("brand") ?: cpuObj?.stringOrNull("name"),
                cpuCores = cpuObj?.intOrNull("cores"),
                cpuThreads = cpuObj?.intOrNull("threads"),
                boardManufacturer = boardObj?.stringOrNull("manufacturer"),
                boardModel = boardObj?.stringOrNull("model")
            )
        }.onFailure { e ->
            AppErrorLogger.logError(
                tag = "UnraidOverview",
                message = "시스템 하드웨어 정보 파싱에 실패했습니다.",
                throwable = e
            )
        }
    }

    private suspend fun fetchUnreadNotifications(
        serverUrl: String,
        apiKey: String
    ): Result<Pair<NotificationUnreadCounts, List<UnraidNotificationItem>>> {
        val query = """
            query GetUnreadNotifications {
              notifications {
                overview {
                  unread {
                    info
                    warning
                    alert
                    total
                  }
                }
                list(filter: { type: UNREAD, offset: 0, limit: 15 }) {
                  id
                  title
                  subject
                  description
                  importance
                  timestamp
                }
              }
            }
        """.trimIndent()

        return executeGraphQl(
            serverUrl = serverUrl,
            apiKey = apiKey,
            query = query,
            operationTag = "시스템 알림 조회",
            allowPartialDataOnErrors = true
        ).mapCatching { root ->
            val dataObj = root["data"] as? JsonObject
                ?: throw IllegalStateException("알림 응답에 data가 없습니다.")
            val notifObj = dataObj["notifications"] as? JsonObject
                ?: throw IllegalStateException("현재 Unraid 버전에서 notifications 필드를 지원하지 않습니다.")

            val unreadObj = (notifObj["overview"] as? JsonObject)?.get("unread") as? JsonObject
            val counts = NotificationUnreadCounts(
                info = unreadObj?.intOrNull("info") ?: 0,
                warning = unreadObj?.intOrNull("warning") ?: 0,
                alert = unreadObj?.intOrNull("alert") ?: 0,
                total = unreadObj?.intOrNull("total") ?: 0
            )

            val listArray = notifObj["list"] as? JsonArray
            val items = listArray?.mapIndexedNotNull { idx, elem ->
                val obj = elem as? JsonObject ?: return@mapIndexedNotNull null
                UnraidNotificationItem(
                    id = obj.stringOrNull("id") ?: "notif_$idx",
                    title = obj.stringOrNull("title").orEmpty(),
                    subject = obj.stringOrNull("subject").orEmpty(),
                    description = obj.stringOrNull("description").orEmpty(),
                    importance = obj.stringOrNull("importance") ?: "INFO",
                    timestamp = obj.stringOrNull("timestamp")
                )
            }.orEmpty()

            counts to items
        }
    }

    // =========================================================================
    // 4. 메인 탭 제어 (어레이 시작/중지, 패리티 체크 제어, 패리티 이력)
    // =========================================================================

    suspend fun setArrayState(
        serverUrl: String,
        apiKey: String,
        start: Boolean
    ): Result<String> {
        val desired = if (start) "START" else "STOP"
        val mutation = """
            mutation SetUnraidArrayState {
              array {
                setState(input: { desiredState: $desired }) {
                  state
                }
              }
            }
        """.trimIndent()

        return executeGraphQl(
            serverUrl = serverUrl,
            apiKey = apiKey,
            query = mutation,
            operationTag = if (start) "어레이 시작" else "어레이 중지"
        ).mapCatching { root ->
            val dataObj = root["data"] as? JsonObject
            val arrayObj = dataObj?.get("array") as? JsonObject
            val setStateObj = arrayObj?.get("setState") as? JsonObject
            setStateObj?.stringOrNull("state") ?: desired
        }
    }

    suspend fun controlParityCheck(
        serverUrl: String,
        apiKey: String,
        action: ParityCheckAction
    ): Result<Unit> {
        val fieldCall = when (action) {
            ParityCheckAction.START -> "start(correct: false)"
            ParityCheckAction.PAUSE -> "pause"
            ParityCheckAction.RESUME -> "resume"
            ParityCheckAction.CANCEL -> "cancel"
        }

        val scalarMutation = """
            mutation ControlParityCheck {
              parityCheck {
                $fieldCall
              }
            }
        """.trimIndent()

        val firstAttempt = executeGraphQl(
            serverUrl = serverUrl,
            apiKey = apiKey,
            query = scalarMutation,
            operationTag = "패리티 체크 ${action.labelKo}"
        )

        if (firstAttempt.isSuccess) return Result.success(Unit)

        // 일부 스키마에서 객체 반환을 요구할 경우 서브필드 선택 폴백
        val firstErrMsg = firstAttempt.exceptionOrNull()?.message.orEmpty()
        if (firstErrMsg.contains("selection", ignoreCase = true) ||
            firstErrMsg.contains("subfields", ignoreCase = true)
        ) {
            val objectMutation = """
                mutation ControlParityCheckObject {
                  parityCheck {
                    $fieldCall {
                      status
                    }
                  }
                }
            """.trimIndent()
            return executeGraphQl(
                serverUrl = serverUrl,
                apiKey = apiKey,
                query = objectMutation,
                operationTag = "패리티 체크 ${action.labelKo}(폴백)"
            ).map { Unit }
        }

        return firstAttempt.map { Unit }
    }

    suspend fun fetchParityHistory(
        serverUrl: String,
        apiKey: String
    ): Result<List<ParityHistoryItem>> {
        val query = """
            query GetParityHistory {
              parityHistory {
                date
                duration
                speed
                status
                errors
              }
            }
        """.trimIndent()

        return executeGraphQl(
            serverUrl = serverUrl,
            apiKey = apiKey,
            query = query,
            operationTag = "패리티 이력 조회",
            allowPartialDataOnErrors = true
        ).mapCatching { root ->
            val dataObj = root["data"] as? JsonObject
                ?: throw IllegalStateException("패리티 이력 응답에 data가 없습니다.")
            val historyArray = dataObj["parityHistory"] as? JsonArray
                ?: throw IllegalStateException("현재 Unraid 버전에서 parityHistory 조회를 지원하지 않습니다.")

            historyArray.mapNotNull { elem ->
                val obj = elem as? JsonObject ?: return@mapNotNull null
                ParityHistoryItem(
                    date = obj.stringOrNull("date"),
                    durationSec = obj.longOrNull("duration"),
                    speed = obj.stringOrNull("speed"),
                    status = obj.stringOrNull("status"),
                    errors = obj.longOrNull("errors")
                )
            }
        }
    }

    // =========================================================================
    // 5. 도커 컨테이너 제어 및 업데이트
    // =========================================================================

    /**
     * 특정 도커 컨테이너를 시작 / 중지 / 재시작합니다.
     */
    suspend fun controlDockerContainer(
        serverUrl: String,
        apiKey: String,
        containerId: String,
        action: DockerAction
    ): Result<Unit> {
        return when (action) {
            DockerAction.START -> runDockerMutation(serverUrl, apiKey, containerId, "start")
            DockerAction.STOP -> runDockerMutation(serverUrl, apiKey, containerId, "stop")
            DockerAction.RESTART -> {
                val restartAttempt = runDockerMutation(serverUrl, apiKey, containerId, "restart")
                if (restartAttempt.isSuccess) {
                    restartAttempt
                } else {
                    AppErrorLogger.logError(
                        tag = "UnraidGraphQL",
                        message = "docker.restart 뮤테이션 실패 — stop 후 start 방식으로 재시도합니다.",
                        extraDetails = restartAttempt.exceptionOrNull()?.message
                    )
                    val stopResult = runDockerMutation(serverUrl, apiKey, containerId, "stop")
                    if (stopResult.isFailure) return stopResult
                    runDockerMutation(serverUrl, apiKey, containerId, "start")
                }
            }
        }
    }

    /**
     * 특정 도커 컨테이너 이미지를 최신 버전으로 업데이트합니다.
     * - updateContainers(ids: [...])를 우선 호출하고 미지원 시 단일 updateContainer / update로 폴백합니다.
     */
    suspend fun updateDockerContainer(
        serverUrl: String,
        apiKey: String,
        containerId: String
    ): Result<Unit> {
        val escapedId = JsonPrimitive(containerId).toString()

        val batchMutation = """
            mutation UpdateDockerContainers {
              docker {
                updateContainers(ids: [$escapedId]) {
                  id
                }
              }
            }
        """.trimIndent()

        val firstRes = executeGraphQl(
            serverUrl = serverUrl,
            apiKey = apiKey,
            query = batchMutation,
            operationTag = "도커 컨테이너 업데이트"
        )
        if (firstRes.isSuccess) return Result.success(Unit)

        AppErrorLogger.logError(
            tag = "UnraidDocker",
            message = "docker.updateContainers 실패 — 단일 updateContainer 뮤테이션으로 폴백합니다.",
            extraDetails = firstRes.exceptionOrNull()?.message
        )

        val singleMutation = """
            mutation UpdateDockerContainerSingle {
              docker {
                updateContainer(id: $escapedId) {
                  id
                }
              }
            }
        """.trimIndent()

        val secondRes = executeGraphQl(
            serverUrl = serverUrl,
            apiKey = apiKey,
            query = singleMutation,
            operationTag = "도커 컨테이너 단일 업데이트"
        )
        if (secondRes.isSuccess) return Result.success(Unit)

        return firstRes.map { Unit }
    }

    private suspend fun runDockerMutation(
        serverUrl: String,
        apiKey: String,
        containerId: String,
        mutationField: String
    ): Result<Unit> {
        val escapedIdLiteral = JsonPrimitive(containerId).toString()
        val mutation = """
            mutation ControlDockerContainer {
              docker {
                $mutationField(id: $escapedIdLiteral) {
                  id
                  state
                }
              }
            }
        """.trimIndent()

        return executeGraphQl(
            serverUrl = serverUrl,
            apiKey = apiKey,
            query = mutation,
            operationTag = "도커 $mutationField"
        ).map { Unit }
    }

    // =========================================================================
    // 6. 공유 탭 조회
    // =========================================================================

    suspend fun fetchShares(serverUrl: String, apiKey: String): Result<List<UnraidShareItem>> {
        val primaryQuery = """
            query GetUnraidShares {
              shares {
                id
                name
                comment
                free
                used
                size
                cache
                floor
                include
                exclude
              }
            }
        """.trimIndent()

        val fallbackQuery = """
            query GetUnraidSharesMinimal {
              shares {
                id
                name
                comment
                free
                used
              }
            }
        """.trimIndent()

        val primaryRes = executeGraphQl(
            serverUrl = serverUrl,
            apiKey = apiKey,
            query = primaryQuery,
            operationTag = "공유 폴더 조회",
            allowPartialDataOnErrors = true
        )

        val root = primaryRes.getOrNull()?.takeIf { hasNonNullDataField(it, "shares") }
            ?: run {
                val primaryErr = primaryRes.exceptionOrNull()
                AppErrorLogger.logError(
                    tag = "UnraidShares",
                    message = "공유 폴더 확장 조회 실패 — 최소 필드로 폴백 조회합니다.",
                    extraDetails = primaryErr?.message
                )
                executeGraphQl(
                    serverUrl = serverUrl,
                    apiKey = apiKey,
                    query = fallbackQuery,
                    operationTag = "공유 폴더 최소 폴백 조회",
                    allowPartialDataOnErrors = true
                ).getOrElse { fallbackErr ->
                    return Result.failure(primaryErr ?: fallbackErr)
                }
            }

        return runCatching {
            val dataObj = root["data"] as? JsonObject
                ?: throw IllegalStateException("공유 폴더 응답에 data가 없습니다.")
            val sharesArray = dataObj["shares"] as? JsonArray
                ?: throw IllegalStateException("현재 Unraid 버전에서 shares 조회를 지원하지 않습니다.")

            sharesArray.mapIndexedNotNull { idx, elem ->
                val obj = elem as? JsonObject ?: return@mapIndexedNotNull null
                val name = obj.stringOrNull("name").orEmpty().ifBlank { return@mapIndexedNotNull null }
                UnraidShareItem(
                    id = obj.stringOrNull("id") ?: "share_${name}_$idx",
                    name = name,
                    comment = obj.stringOrNull("comment").orEmpty(),
                    freeKb = obj.longOrNull("free"),
                    usedKb = obj.longOrNull("used"),
                    sizeKb = obj.longOrNull("size"),
                    cache = obj.stringOrNull("cache"),
                    floor = obj.stringOrNull("floor"),
                    include = obj.stringList("include"),
                    exclude = obj.stringList("exclude")
                )
            }.sortedBy { it.name.lowercase() }
        }.onFailure { e ->
            AppErrorLogger.logError(
                tag = "UnraidShares",
                message = "공유 폴더 데이터 파싱에 실패했습니다.",
                throwable = e
            )
        }
    }

    // =========================================================================
    // 7. 사용자 및 API 키 관리
    // =========================================================================

    suspend fun fetchUsersAndApiKeys(serverUrl: String, apiKey: String): Result<UsersAndApiKeysData> {
        val fullQuery = """
            query GetUsersAndApiKeys {
              me {
                id
                name
                description
                roles
              }
              apiKeys {
                id
                name
                description
                roles
                createdAt
              }
            }
        """.trimIndent()

        val meOnlyQuery = """
            query GetCurrentUserOnly {
              me {
                id
                name
                description
                roles
              }
            }
        """.trimIndent()

        val primaryRes = executeGraphQl(
            serverUrl = serverUrl,
            apiKey = apiKey,
            query = fullQuery,
            operationTag = "사용자 및 API 키 조회",
            allowPartialDataOnErrors = true
        )

        var apiKeysSupported = true
        val root = primaryRes.getOrNull()?.takeIf {
            hasNonNullDataField(it, "me") || hasNonNullDataField(it, "apiKeys")
        } ?: run {
            apiKeysSupported = false
            val primaryErr = primaryRes.exceptionOrNull()
            AppErrorLogger.logError(
                tag = "UnraidUsers",
                message = "사용자/API 키 통합 조회 실패 — 현재 계정(me) 단독 조회로 폴백합니다.",
                extraDetails = primaryErr?.message
            )
            executeGraphQl(
                serverUrl = serverUrl,
                apiKey = apiKey,
                query = meOnlyQuery,
                operationTag = "현재 사용자 조회(폴백)",
                allowPartialDataOnErrors = true
            ).getOrElse { fallbackErr ->
                return Result.failure(primaryErr ?: fallbackErr)
            }
        }

        return runCatching {
            val dataObj = root["data"] as? JsonObject
                ?: throw IllegalStateException("사용자 응답에 data가 없습니다.")
            val meObj = dataObj["me"] as? JsonObject
            val me = meObj?.let {
                UnraidUserProfile(
                    id = it.stringOrNull("id").orEmpty(),
                    name = it.stringOrNull("name").orEmpty().ifBlank { "Unraid 사용자" },
                    description = it.stringOrNull("description").orEmpty(),
                    roles = it.stringList("roles")
                )
            }

            val keysElem = dataObj["apiKeys"]
            val keysArray = keysElem as? JsonArray
            if (keysArray == null) {
                apiKeysSupported = false
            }
            val apiKeys = keysArray?.mapIndexedNotNull { idx, elem ->
                val obj = elem as? JsonObject ?: return@mapIndexedNotNull null
                UnraidApiKeyItem(
                    id = obj.stringOrNull("id") ?: "key_$idx",
                    name = obj.stringOrNull("name").orEmpty().ifBlank { "이름 없는 API 키" },
                    description = obj.stringOrNull("description").orEmpty(),
                    roles = obj.stringList("roles"),
                    createdAt = obj.stringOrNull("createdAt")
                )
            }.orEmpty()

            UsersAndApiKeysData(
                me = me,
                apiKeys = apiKeys,
                apiKeysSupported = apiKeysSupported
            )
        }.onFailure { e ->
            AppErrorLogger.logError(
                tag = "UnraidUsers",
                message = "사용자 및 API 키 응답 파싱에 실패했습니다.",
                throwable = e
            )
        }
    }

    suspend fun createApiKey(
        serverUrl: String,
        apiKey: String,
        name: String,
        description: String,
        roles: List<String>
    ): Result<CreatedApiKeyResult> {
        val escapedName = JsonPrimitive(name.trim()).toString()
        val escapedDesc = JsonPrimitive(description.trim()).toString()
        val roleTokens = roles
            .map { it.trim().uppercase().replace(Regex("[^A-Z0-9_]"), "") }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("VIEWER") }
            .joinToString(", ")

        val primaryMutation = """
            mutation CreateUnraidApiKey {
              apiKey {
                create(input: { name: $escapedName, description: $escapedDesc, roles: [$roleTokens] }) {
                  id
                  name
                  key
                }
              }
            }
        """.trimIndent()

        val firstRes = executeGraphQl(
            serverUrl = serverUrl,
            apiKey = apiKey,
            query = primaryMutation,
            operationTag = "API 키 생성"
        )

        val root = firstRes.getOrElse { firstErr ->
            AppErrorLogger.logError(
                tag = "UnraidUsers",
                message = "apiKey.create 뮤테이션 실패 — createApiKey 폴백을 시도합니다.",
                extraDetails = firstErr.message
            )
            val fallbackMutation = """
                mutation CreateUnraidApiKeyFallback {
                  createApiKey(input: { name: $escapedName, description: $escapedDesc, roles: [$roleTokens] }) {
                    id
                    name
                    key
                  }
                }
            """.trimIndent()
            executeGraphQl(
                serverUrl = serverUrl,
                apiKey = apiKey,
                query = fallbackMutation,
                operationTag = "API 키 생성(폴백)"
            ).getOrElse {
                return Result.failure(firstErr)
            }
        }

        return runCatching {
            val dataObj = root["data"] as? JsonObject
            val apiKeyObj = (dataObj?.get("apiKey") as? JsonObject)?.get("create") as? JsonObject
                ?: dataObj?.get("createApiKey") as? JsonObject
                ?: throw IllegalStateException("API 키 생성 응답을 파싱할 수 없습니다.")

            CreatedApiKeyResult(
                id = apiKeyObj.stringOrNull("id").orEmpty(),
                name = apiKeyObj.stringOrNull("name").orEmpty().ifBlank { name },
                key = apiKeyObj.stringOrNull("key")
            )
        }
    }

    suspend fun deleteApiKey(
        serverUrl: String,
        apiKey: String,
        keyId: String
    ): Result<Unit> {
        val escapedId = JsonPrimitive(keyId).toString()
        val primaryMutation = """
            mutation DeleteUnraidApiKey {
              apiKey {
                delete(input: { ids: [$escapedId] })
              }
            }
        """.trimIndent()

        val firstRes = executeGraphQl(
            serverUrl = serverUrl,
            apiKey = apiKey,
            query = primaryMutation,
            operationTag = "API 키 삭제"
        )
        if (firstRes.isSuccess) return Result.success(Unit)

        AppErrorLogger.logError(
            tag = "UnraidUsers",
            message = "apiKey.delete(input.ids) 실패 — 단일 id 삭제 뮤테이션으로 폴백합니다.",
            extraDetails = firstRes.exceptionOrNull()?.message
        )

        val fallbackMutation = """
            mutation DeleteUnraidApiKeySingle {
              apiKey {
                delete(id: $escapedId)
              }
            }
        """.trimIndent()

        return executeGraphQl(
            serverUrl = serverUrl,
            apiKey = apiKey,
            query = fallbackMutation,
            operationTag = "API 키 삭제(폴백)"
        ).map { Unit }.recoverCatching {
            throw firstRes.exceptionOrNull() ?: it
        }
    }

    // =========================================================================
    // 8. 설정 탭 시스템 요약 조회
    // =========================================================================

    suspend fun fetchSettingsSummary(
        serverUrl: String,
        apiKey: String
    ): Result<UnraidSettingsSummary> {
        val varsQuery = """
            query GetUnraidVars {
              vars {
                version
                name
                comment
                timeZone
                useSsl
                port
                portssl
              }
            }
        """.trimIndent()

        val varsFallbackQuery = """
            query GetUnraidVarsMinimal {
              vars {
                version
                name
                comment
              }
            }
        """.trimIndent()

        val varsRes = executeGraphQl(
            serverUrl = serverUrl,
            apiKey = apiKey,
            query = varsQuery,
            operationTag = "시스템 설정(vars) 조회",
            allowPartialDataOnErrors = true
        )

        val varsRoot = varsRes.getOrNull()?.takeIf { hasNonNullDataField(it, "vars") }
            ?: run {
                val err = varsRes.exceptionOrNull()
                AppErrorLogger.logError(
                    tag = "UnraidSettings",
                    message = "vars 전체 필드 조회 실패 — 최소 필드로 폴백합니다.",
                    extraDetails = err?.message
                )
                executeGraphQl(
                    serverUrl = serverUrl,
                    apiKey = apiKey,
                    query = varsFallbackQuery,
                    operationTag = "시스템 설정 최소 폴백 조회",
                    allowPartialDataOnErrors = true
                ).getOrNull()
            }

        // 선택적 보조 정보(시스템 시각 / UPS)는 실패해도 vars 요약을 막지 않음
        val extraRoot = executeGraphQl(
            serverUrl = serverUrl,
            apiKey = apiKey,
            query = """
                query GetSystemExtraSettings {
                  systemTime {
                    currentTime
                    timeZone
                  }
                  upsDevices {
                    name
                    model
                    status
                    battery {
                      chargeLevel
                      estimatedRuntime
                    }
                  }
                }
            """.trimIndent(),
            operationTag = "시스템 시각/UPS 보조 조회",
            allowPartialDataOnErrors = true
        ).getOrNull()

        if (varsRoot == null && extraRoot == null) {
            return Result.failure(
                varsRes.exceptionOrNull()
                    ?: IllegalStateException("현재 Unraid 버전에서 시스템 설정 요약 조회를 지원하지 않습니다.")
            )
        }

        return runCatching {
            val varsObj = (varsRoot?.get("data") as? JsonObject)?.get("vars") as? JsonObject
            val extraData = extraRoot?.get("data") as? JsonObject
            val timeObj = extraData?.get("systemTime") as? JsonObject
            val upsArray = extraData?.get("upsDevices") as? JsonArray
            val firstUps = upsArray?.firstOrNull() as? JsonObject
            val upsBattery = firstUps?.get("battery") as? JsonObject

            UnraidSettingsSummary(
                version = varsObj?.stringOrNull("version"),
                serverName = varsObj?.stringOrNull("name"),
                comment = varsObj?.stringOrNull("comment"),
                timeZone = varsObj?.stringOrNull("timeZone") ?: timeObj?.stringOrNull("timeZone"),
                useSsl = varsObj?.stringOrNull("useSsl"),
                port = varsObj?.intOrNull("port"),
                portSsl = varsObj?.intOrNull("portssl"),
                systemTime = timeObj?.stringOrNull("currentTime"),
                upsStatus = firstUps?.stringOrNull("status"),
                upsModel = firstUps?.stringOrNull("model") ?: firstUps?.stringOrNull("name"),
                upsBatteryCharge = upsBattery?.intOrNull("chargeLevel"),
                upsRuntimeLeft = upsBattery?.stringOrNull("estimatedRuntime")
            )
        }
    }

    // =========================================================================
    // 9. 앱(플러그인) 탭 조회 및 .plg URL 설치
    // =========================================================================

    suspend fun fetchPlugins(serverUrl: String, apiKey: String): Result<List<UnraidPluginItem>> {
        val primaryQuery = """
            query GetUnraidPlugins {
              plugins {
                name
                version
                hasApiModule
                hasCliModule
              }
            }
        """.trimIndent()

        val fallbackQuery = """
            query GetInstalledUnraidPlugins {
              installedUnraidPlugins
            }
        """.trimIndent()

        val primaryRes = executeGraphQl(
            serverUrl = serverUrl,
            apiKey = apiKey,
            query = primaryQuery,
            operationTag = "플러그인 목록 조회",
            allowPartialDataOnErrors = true
        )

        val primaryRoot = primaryRes.getOrNull()
        if (primaryRoot != null && hasNonNullDataField(primaryRoot, "plugins")) {
            return runCatching {
                val pluginsArray = (primaryRoot["data"] as? JsonObject)?.get("plugins") as? JsonArray
                    ?: emptyList<JsonElement>()
                pluginsArray.mapNotNull { elem ->
                    when (elem) {
                        is JsonObject -> {
                            val name = elem.stringOrNull("name").orEmpty().ifBlank { return@mapNotNull null }
                            UnraidPluginItem(
                                name = name,
                                version = elem.stringOrNull("version"),
                                hasApiModule = elem.booleanOrNull("hasApiModule"),
                                hasCliModule = elem.booleanOrNull("hasCliModule")
                            )
                        }
                        is JsonPrimitive -> {
                            val name = elem.contentOrNull.orEmpty().ifBlank { return@mapNotNull null }
                            UnraidPluginItem(name = name)
                        }
                        else -> null
                    }
                }.sortedBy { it.name.lowercase() }
            }
        }

        AppErrorLogger.logError(
            tag = "UnraidPlugins",
            message = "plugins 조회 실패 — installedUnraidPlugins 폴백을 시도합니다.",
            extraDetails = primaryRes.exceptionOrNull()?.message
        )

        val fallbackRes = executeGraphQl(
            serverUrl = serverUrl,
            apiKey = apiKey,
            query = fallbackQuery,
            operationTag = "플러그인 목록 폴백 조회",
            allowPartialDataOnErrors = true
        )

        val fallbackRoot = fallbackRes.getOrElse {
            return Result.failure(primaryRes.exceptionOrNull() ?: it)
        }

        return runCatching {
            val arr = (fallbackRoot["data"] as? JsonObject)?.get("installedUnraidPlugins") as? JsonArray
                ?: throw IllegalStateException("현재 Unraid 버전에서 플러그인 목록 조회를 지원하지 않습니다.")
            arr.mapNotNull { elem ->
                when (elem) {
                    is JsonPrimitive -> elem.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                        UnraidPluginItem(name = it)
                    }
                    is JsonObject -> elem.stringOrNull("name")?.takeIf { it.isNotBlank() }?.let {
                        UnraidPluginItem(name = it, version = elem.stringOrNull("version"))
                    }
                    else -> null
                }
            }.sortedBy { it.name.lowercase() }
        }
    }

    suspend fun installPluginByUrl(
        serverUrl: String,
        apiKey: String,
        plgUrl: String
    ): Result<Unit> {
        val cleanUrl = plgUrl.trim()
        if (!cleanUrl.startsWith("http://", ignoreCase = true) &&
            !cleanUrl.startsWith("https://", ignoreCase = true)
        ) {
            return Result.failure(IllegalArgumentException("http:// 또는 https://로 시작하는 .plg URL을 입력해 주세요."))
        }

        val escapedUrl = JsonPrimitive(cleanUrl).toString()

        val primaryMutation = """
            mutation InstallUnraidPlugin {
              installPlugin(url: $escapedUrl)
            }
        """.trimIndent()

        val firstRes = executeGraphQl(
            serverUrl = serverUrl,
            apiKey = apiKey,
            query = primaryMutation,
            operationTag = "플러그인 설치(installPlugin)"
        )
        if (firstRes.isSuccess) return Result.success(Unit)

        AppErrorLogger.logError(
            tag = "UnraidPlugins",
            message = "installPlugin(url) 실패 — addPlugin 폴백 뮤테이션을 시도합니다.",
            extraDetails = firstRes.exceptionOrNull()?.message
        )

        val fallbackMutation = """
            mutation AddUnraidPlugin {
              addPlugin(input: { names: [$escapedUrl], bundled: false })
            }
        """.trimIndent()

        return executeGraphQl(
            serverUrl = serverUrl,
            apiKey = apiKey,
            query = fallbackMutation,
            operationTag = "플러그인 설치(addPlugin 폴백)"
        ).map { Unit }.recoverCatching {
            throw firstRes.exceptionOrNull() ?: it
        }
    }

    // =========================================================================
    // 공통 GraphQL HTTP 실행 및 파서
    // =========================================================================

    private suspend fun executeGraphQl(
        serverUrl: String,
        apiKey: String,
        query: String,
        variables: JsonObject? = null,
        operationTag: String,
        allowPartialDataOnErrors: Boolean = false
    ): Result<JsonObject> = withContext(Dispatchers.IO) {
        val normalizedBase = AppPreferencesRepository.normalizeUrl(serverUrl)
        if (normalizedBase.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Unraid 서버 URL이 설정되지 않았습니다."))
        }
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Unraid API 키가 설정되지 않았습니다."))
        }

        val endpoint = if (normalizedBase.endsWith("/graphql", ignoreCase = true)) {
            normalizedBase
        } else {
            "$normalizedBase/graphql"
        }

        val payload = buildJsonObject {
            put("query", query)
            if (variables != null) {
                put("variables", variables)
            }
        }.toString()

        val request = Request.Builder()
            .url(endpoint)
            .post(payload.toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("x-api-key", apiKey.trim())
            .build()

        try {
            httpClientProvider().newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    val errorMsg = "HTTP ${response.code} ${response.message} ($operationTag)"
                    AppErrorLogger.logError(
                        tag = "UnraidAPI",
                        message = errorMsg,
                        extraDetails = "URL: $endpoint\n응답 본문: ${bodyText.take(1500)}"
                    )
                    return@withContext Result.failure(IllegalStateException(errorMsg))
                }

                val parsedElement = json.parseToJsonElement(bodyText)
                val rootObj = parsedElement as? JsonObject
                    ?: throw IllegalStateException("GraphQL 응답이 JSON 객체 형식이 아닙니다.")

                val errorsArray = rootObj["errors"] as? JsonArray
                val hasData = rootObj["data"] is JsonObject

                if (errorsArray != null && errorsArray.isNotEmpty()) {
                    val errorMessages = errorsArray.mapNotNull { elem ->
                        (elem as? JsonObject)?.stringOrNull("message")
                    }.joinToString(" / ").ifBlank { errorsArray.toString() }

                    AppErrorLogger.logError(
                        tag = "UnraidGraphQL",
                        message = "$operationTag 중 GraphQL 오류 반환: $errorMessages",
                        extraDetails = "URL: $endpoint\n원본 오류: ${errorsArray.toString().take(1500)}"
                    )

                    if (!allowPartialDataOnErrors || !hasData) {
                        return@withContext Result.failure(IllegalStateException("GraphQL 오류: $errorMessages"))
                    }
                }

                Result.success(rootObj)
            }
        } catch (e: Exception) {
            val message = "$operationTag 네트워크 통신 실패: ${e.localizedMessage ?: e.javaClass.simpleName}"
            AppErrorLogger.logError(
                tag = "UnraidAPI",
                message = message,
                throwable = e,
                extraDetails = "URL: $endpoint"
            )
            Result.failure(e)
        }
    }

    private fun hasNonNullDataField(root: JsonObject?, fieldName: String): Boolean {
        val data = root?.get("data") as? JsonObject ?: return false
        val value = data[fieldName] ?: return false
        return value !is JsonNull
    }

    private fun parseSystemMetrics(root: JsonObject): SystemMetricsData {
        val dataObj = root["data"] as? JsonObject
            ?: throw IllegalStateException("메트릭 응답에 data 객체가 없습니다.")
        val metricsObj = dataObj["metrics"] as? JsonObject
            ?: throw IllegalStateException("현재 Unraid 버전에서 metrics 필드를 지원하지 않습니다.")

        val cpuObj = metricsObj["cpu"] as? JsonObject
        val memObj = metricsObj["memory"] as? JsonObject

        val cpuPercent = cpuObj?.floatOrNull("percentTotal")?.coerceIn(0f, 100f)
        val memTotal = memObj?.longOrNull("total")
        val memUsed = memObj?.longOrNull("used")
        val memFree = memObj?.longOrNull("free")
        val memAvailable = memObj?.longOrNull("available")
        val memActive = memObj?.longOrNull("active")
        val memBuffCache = memObj?.longOrNull("buffcache")
        val memPercentRaw = memObj?.floatOrNull("percentTotal")
        val computedMemPercent = memPercentRaw
            ?: if (memTotal != null && memTotal > 0L && memUsed != null) {
                ((memUsed.toDouble() / memTotal.toDouble()) * 100.0).toFloat()
            } else {
                null
            }

        val swapTotal = memObj?.longOrNull("swapTotal")
        val swapUsed = memObj?.longOrNull("swapUsed")
        val swapPercentRaw = memObj?.floatOrNull("percentSwapTotal")
        val computedSwapPercent = swapPercentRaw
            ?: if (swapTotal != null && swapTotal > 0L && swapUsed != null) {
                ((swapUsed.toDouble() / swapTotal.toDouble()) * 100.0).toFloat()
            } else {
                null
            }

        return SystemMetricsData(
            cpuPercent = cpuPercent,
            memoryTotalBytes = memTotal,
            memoryUsedBytes = memUsed,
            memoryFreeBytes = memFree,
            memoryAvailableBytes = memAvailable,
            memoryActiveBytes = memActive,
            memoryBuffCacheBytes = memBuffCache,
            memoryPercent = computedMemPercent?.coerceIn(0f, 100f),
            swapTotalBytes = swapTotal,
            swapUsedBytes = swapUsed,
            swapPercent = computedSwapPercent?.coerceIn(0f, 100f)
        )
    }

    private fun parseDashboardData(root: JsonObject, serverHost: String?): UnraidDashboardData {
        val dataObj = root["data"] as? JsonObject
            ?: throw IllegalStateException("대시보드 응답에 data 객체가 없습니다.")

        val arrayObj = dataObj["array"] as? JsonObject
        val arrayState = arrayObj?.stringOrNull("state") ?: "UNKNOWN"

        val kbObj = (arrayObj?.get("capacity") as? JsonObject)?.get("kilobytes") as? JsonObject
        val totalKb = kbObj?.longOrNull("total")
        val usedKb = kbObj?.longOrNull("used")
        val freeKb = kbObj?.longOrNull("free")

        val allDisks = buildList {
            addAll(parseDiskList(arrayObj?.get("parities") as? JsonArray, DiskRole.PARITY))
            addAll(parseDiskList(arrayObj?.get("disks") as? JsonArray, DiskRole.ARRAY))
            addAll(parseDiskList(arrayObj?.get("caches") as? JsonArray, DiskRole.CACHE))
        }

        val dockerObj = dataObj["docker"] as? JsonObject
        val containersArray = dockerObj?.get("containers") as? JsonArray
        val containers = parseContainerList(containersArray, serverHost)

        return UnraidDashboardData(
            arrayState = arrayState,
            capacityTotalKb = totalKb,
            capacityUsedKb = usedKb,
            capacityFreeKb = freeKb,
            disks = allDisks,
            containers = containers
        )
    }

    private fun parseDiskList(array: JsonArray?, role: DiskRole): List<UnraidDisk> {
        if (array == null) return emptyList()
        return array.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val name = obj.stringOrNull("name").orEmpty().ifBlank { "${role.labelKo} ${index + 1}" }
            val device = obj.stringOrNull("device").orEmpty()
            val status = obj.stringOrNull("status") ?: "UNKNOWN"

            if (status.equals("DISK_NP", ignoreCase = true) && device.isBlank()) {
                return@mapIndexedNotNull null
            }

            UnraidDisk(
                id = obj.stringOrNull("id") ?: "${role.name}-$name-$index",
                name = name,
                device = device,
                role = role,
                status = status,
                sizeKb = obj.longOrNull("size"),
                tempCelsius = obj.intOrNull("temp"),
                isSpinning = obj.booleanOrNull("isSpinning")
            )
        }
    }

    private fun parseContainerList(
        array: JsonArray?,
        serverHost: String?
    ): List<DockerContainerItem> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.stringOrNull("id") ?: return@mapNotNull null

            val namesArray = obj["names"] as? JsonArray
            val rawName = namesArray?.firstOrNull()?.primitiveContentOrNull()
                ?: obj.stringOrNull("name")
                ?: id
            val cleanName = rawName.removePrefix("/")

            val labelsObj = extractLabelsObject(obj["labels"])
            val portMappings = parsePortMappings(obj["ports"] as? JsonArray)

            val rawWebUi = labelsObj?.stringOrNull(LABEL_UNRAID_WEBUI)
            val resolvedWebUiUrl = resolveUnraidWebUiUrl(
                rawTemplate = rawWebUi,
                serverHost = serverHost,
                ports = portMappings
            )

            val iconUrl = labelsObj?.stringOrNull(LABEL_UNRAID_ICON)
                ?.trim()
                ?.takeIf {
                    it.startsWith("http://", ignoreCase = true) ||
                        it.startsWith("https://", ignoreCase = true)
                }

            DockerContainerItem(
                id = id,
                name = cleanName,
                image = obj.stringOrNull("image").orEmpty(),
                state = obj.stringOrNull("state") ?: "UNKNOWN",
                status = obj.stringOrNull("status").orEmpty(),
                webUiUrl = resolvedWebUiUrl,
                iconUrl = iconUrl,
                autoStart = obj.booleanOrNull("autoStart")
            )
        }.sortedWith(
            compareByDescending<DockerContainerItem> { it.isRunning }
                .thenBy { it.name.lowercase() }
        )
    }

    private fun extractLabelsObject(element: JsonElement?): JsonObject? {
        if (element == null || element is JsonNull) return null
        if (element is JsonObject) return element
        if (element is JsonPrimitive) {
            val raw = element.contentOrNull ?: return null
            return runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        }
        return null
    }

    private fun parsePortMappings(array: JsonArray?): List<ContainerPortMapping> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            ContainerPortMapping(
                ip = obj.stringOrNull("ip"),
                privatePort = obj.intOrNull("privatePort"),
                publicPort = obj.intOrNull("publicPort"),
                type = obj.stringOrNull("type")
            )
        }
    }

    private fun resolveUnraidWebUiUrl(
        rawTemplate: String?,
        serverHost: String?,
        ports: List<ContainerPortMapping>
    ): String? {
        val template = rawTemplate?.trim().orEmpty()
        if (template.isBlank()) return null

        var resolved = template

        if (IP_PLACEHOLDER_REGEX.containsMatchIn(resolved)) {
            val host = serverHost?.takeIf { it.isNotBlank() } ?: return null
            resolved = IP_PLACEHOLDER_REGEX.replace(resolved, host)
        }

        resolved = PORT_PLACEHOLDER_REGEX.replace(resolved) { match ->
            val requestedPort = match.groupValues[1].toIntOrNull()
            val mappedPort = if (requestedPort != null) {
                val matching = ports.filter { it.privatePort == requestedPort && (it.publicPort ?: 0) > 0 }
                val tcpPreferred = matching.firstOrNull { it.type.equals("TCP", ignoreCase = true) }
                    ?: matching.firstOrNull()
                tcpPreferred?.publicPort
            } else {
                null
            }
            (mappedPort ?: requestedPort)?.toString() ?: match.groupValues[1]
        }

        if (resolved.contains("[IP]", ignoreCase = true) || resolved.contains("[PORT:", ignoreCase = true)) {
            return null
        }

        val withScheme = if (
            resolved.startsWith("http://", ignoreCase = true) ||
            resolved.startsWith("https://", ignoreCase = true)
        ) {
            resolved
        } else {
            "http://$resolved"
        }

        return withScheme.trim()
    }

    private fun extractHostFromUrl(serverUrl: String): String? {
        val normalized = AppPreferencesRepository.normalizeUrl(serverUrl)
        if (normalized.isBlank()) return null
        return runCatching { URI(normalized).host }.getOrNull()
    }

    private fun JsonElement.primitiveContentOrNull(): String? {
        if (this is JsonNull) return null
        return (this as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val value = this[key] ?: return null
        return value.primitiveContentOrNull()
    }

    private fun JsonObject.longOrNull(key: String): Long? {
        val raw = stringOrNull(key)?.trim() ?: return null
        if (raw.isBlank() || raw.equals("NaN", ignoreCase = true)) return null
        return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        val raw = stringOrNull(key)?.trim() ?: return null
        if (raw.isBlank() || raw.equals("NaN", ignoreCase = true) || raw == "*") return null
        return raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt()
    }

    private fun JsonObject.floatOrNull(key: String): Float? {
        val raw = stringOrNull(key)?.trim() ?: return null
        if (raw.isBlank() || raw.equals("NaN", ignoreCase = true)) return null
        return raw.toFloatOrNull()
    }

    private fun JsonObject.booleanOrNull(key: String): Boolean? {
        val raw = stringOrNull(key)?.trim() ?: return null
        return raw.toBooleanStrictOrNull() ?: when {
            raw.equals("yes", ignoreCase = true) || raw == "1" -> true
            raw.equals("no", ignoreCase = true) || raw == "0" -> false
            else -> null
        }
    }

    private fun JsonObject.stringList(key: String): List<String> {
        val elem = this[key] ?: return emptyList()
        return when (elem) {
            is JsonArray -> elem.mapNotNull { it.primitiveContentOrNull()?.trim()?.takeIf { s -> s.isNotBlank() } }
            is JsonPrimitive -> elem.contentOrNull
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            else -> emptyList()
        }
    }

    companion object {
        private const val LABEL_UNRAID_WEBUI = "net.unraid.docker.webui"
        private const val LABEL_UNRAID_ICON = "net.unraid.docker.icon"
        private val IP_PLACEHOLDER_REGEX = Regex("\\[IP]", RegexOption.IGNORE_CASE)
        private val PORT_PLACEHOLDER_REGEX = Regex("\\[PORT:(\\d+)]", RegexOption.IGNORE_CASE)
    }
}
