package com.unraid.app.data.remote

import com.unraid.app.data.local.AppErrorLogger
import com.unraid.app.data.local.AppPreferencesRepository
import com.unraid.app.data.model.DiskRole
import com.unraid.app.data.model.DockerAction
import com.unraid.app.data.model.DockerContainerItem
import com.unraid.app.data.model.UnraidDashboardData
import com.unraid.app.data.model.UnraidDisk
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

/**
 * Unraid 공식 GraphQL API (<서버주소>/graphql, 헤더: x-api-key) 통신 클라이언트.
 * Unraid 버전에 따른 미세한 스키마 차이에도 안전하게 동작하도록 방어적 JSON 파싱을 수행합니다.
 */
class UnraidGraphQlClient(
    private val httpClient: OkHttpClient = NetworkModule.okHttpClient
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

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
     * 대시보드에 필요한 어레이 상태, 용량, 디스크(패리티/어레이/캐시), 도커 컨테이너 목록을 조회합니다.
     */
    suspend fun fetchDashboard(serverUrl: String, apiKey: String): Result<UnraidDashboardData> {
        val primaryQuery = """
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
                }
              }
            }
        """.trimIndent()

        val primaryResult = executeGraphQl(
            serverUrl = serverUrl,
            apiKey = apiKey,
            query = primaryQuery,
            operationTag = "대시보드 조회",
            allowPartialDataOnErrors = true
        )

        val root = primaryResult.getOrElse { primaryError ->
            // 일부 구버전 Unraid API 스키마 차이를 대비해 최소 필드로 1회 폴백 조회
            val fallbackQuery = """
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

            executeGraphQl(
                serverUrl = serverUrl,
                apiKey = apiKey,
                query = fallbackQuery,
                operationTag = "대시보드 폴백 조회",
                allowPartialDataOnErrors = true
            ).getOrElse {
                return Result.failure(primaryError)
            }
        }

        return runCatching {
            parseDashboardData(root)
        }.onFailure { e ->
            AppErrorLogger.logError(
                tag = "UnraidGraphQL",
                message = "대시보드 응답 데이터 파싱에 실패했습니다.",
                throwable = e
            )
        }
    }

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
                // 최신 Unraid API는 docker.restart를 지원하며, 미지원 버전에서는 stop -> start로 폴백
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

    private suspend fun runDockerMutation(
        serverUrl: String,
        apiKey: String,
        containerId: String,
        mutationField: String
    ): Result<Unit> {
        // Unraid API 버전에 따라 ID 스칼라 타입명(PrefixedID / ID)이 다를 수 있으므로
        // 안전하게 JSON 이스케이프된 문자열 리터럴로 전달하여 모든 버전과 호환되도록 처리합니다.
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
            httpClient.newCall(request).execute().use { response ->
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

    private fun parseDashboardData(root: JsonObject): UnraidDashboardData {
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
        val containers = parseContainerList(containersArray)

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

            // 비어 있는 슬롯(DISK_NP 등)은 목록에서 제외해 모바일 가독성 향상
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

    private fun parseContainerList(array: JsonArray?): List<DockerContainerItem> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.stringOrNull("id") ?: return@mapNotNull null

            val namesArray = obj["names"] as? JsonArray
            val rawName = namesArray?.firstOrNull()?.primitiveContentOrNull()
                ?: obj.stringOrNull("name")
                ?: id
            val cleanName = rawName.removePrefix("/")

            DockerContainerItem(
                id = id,
                name = cleanName,
                image = obj.stringOrNull("image").orEmpty(),
                state = obj.stringOrNull("state") ?: "UNKNOWN",
                status = obj.stringOrNull("status").orEmpty()
            )
        }.sortedWith(
            compareByDescending<DockerContainerItem> { it.isRunning }
                .thenBy { it.name.lowercase() }
        )
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
        val raw = stringOrNull(key) ?: return null
        return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        val raw = stringOrNull(key) ?: return null
        if (raw.equals("NaN", ignoreCase = true) || raw == "*") return null
        return raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt()
    }

    private fun JsonObject.booleanOrNull(key: String): Boolean? {
        val raw = stringOrNull(key) ?: return null
        return raw.toBooleanStrictOrNull()
    }
}
