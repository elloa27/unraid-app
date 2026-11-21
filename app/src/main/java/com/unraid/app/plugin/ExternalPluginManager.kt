package com.unraid.app.plugin

import android.content.Context
import android.net.Uri
import com.unraid.app.data.local.AppErrorLogger
import com.unraid.app.network.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * 외부 .zip 파일 또는 원격 .zip URL을 통해 서비스 전용 UI 플러그인을
 * 동적으로 설치·업데이트·삭제·매칭하는 외부 플러그인 매니저.
 *
 * - 설치 경로: filesDir/plugins/<pluginId>/
 * - Zip Slip(경로 이탈) 방지 canonicalPath 검증 필수 적용
 * - 동일 id 재설치 시 자동 덮어쓰기(업데이트)
 * - 삭제 시 압축 해제 폴더와 플러그인 전용 암호화 저장소 동시 정리
 */
class ExternalPluginManager(
    context: Context,
    private val storageVault: PluginStorageVault,
    private val httpClientProvider: () -> OkHttpClient = { NetworkModule.okHttpClient }
) {
    private val appContext = context.applicationContext
    private val pluginsRootDir: File = File(appContext.filesDir, PLUGINS_ROOT_DIR_NAME).apply {
        if (!exists()) mkdirs()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val _installedPlugins = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val installedPlugins: StateFlow<List<InstalledPlugin>> = _installedPlugins.asStateFlow()

    init {
        refreshInstalledPlugins()
    }

    /**
     * WebViewAssetLoader의 커스텀 PathHandler가 참조하는 플러그인 루트 디렉터리를 반환합니다.
     */
    fun getPluginsRootDir(): File = pluginsRootDir

    /**
     * 디스크(filesDir/plugins/)에 설치된 모든 플러그인의 manifest.json을 다시 읽어 상태를 갱신합니다.
     */
    fun refreshInstalledPlugins() {
        val loaded = pluginsRootDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.mapNotNull { dir ->
                val manifestFile = File(dir, MANIFEST_FILE_NAME)
                if (!manifestFile.exists() || !manifestFile.isFile) return@mapNotNull null
                runCatching {
                    val manifest = json.decodeFromString<PluginManifest>(manifestFile.readText())
                    if (!isValidPluginId(manifest.id) || manifest.id != dir.name) {
                        return@mapNotNull null
                    }
                    InstalledPlugin(
                        manifest = manifest,
                        installDir = dir,
                        installedAtMillis = manifestFile.lastModified()
                    )
                }.onFailure { e ->
                    AppErrorLogger.logError(
                        tag = "ExternalPlugin",
                        message = "플러그인(${dir.name}) manifest.json 로드 실패",
                        throwable = e
                    )
                }.getOrNull()
            }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

        _installedPlugins.value = loaded
    }

    /**
     * 런처 서비스와 매칭되는 첫 번째 설치된 외부 플러그인을 반환합니다.
     */
    fun findMatchingPlugin(service: LauncherService): InstalledPlugin? {
        return _installedPlugins.value.firstOrNull { it.matches(service) }
    }

    fun findPluginById(pluginId: String): InstalledPlugin? {
        return _installedPlugins.value.firstOrNull { it.id == pluginId }
    }

    /**
     * 안드로이드 파일 선택기(OpenDocument)로 선택한 로컬 .zip URI에서 플러그인을 설치합니다.
     */
    suspend fun installFromUri(uri: Uri): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        runCatching {
            val inputStream = appContext.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("선택한 ZIP 파일을 열 수 없습니다.")
            inputStream.use { stream ->
                installFromZipStream(stream)
            }
        }.onFailure { e ->
            AppErrorLogger.logError(
                tag = "ExternalPlugin",
                message = "로컬 ZIP 플러그인 설치 실패: ${e.localizedMessage ?: e.javaClass.simpleName}",
                throwable = e
            )
        }
    }

    /**
     * 원격 .zip URL(예: GitHub Releases 주소)에서 기존 OkHttpClient(tsnet 프록시 + 사설 SSL 지원)로
     * 다운로드한 뒤 플러그인을 설치하거나 업데이트합니다.
     */
    suspend fun installFromUrl(rawUrl: String): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        val trimmedUrl = rawUrl.trim()
        if (!trimmedUrl.startsWith("http://", ignoreCase = true) &&
            !trimmedUrl.startsWith("https://", ignoreCase = true)
        ) {
            return@withContext Result.failure(
                IllegalArgumentException("http:// 또는 https:// 로 시작하는 ZIP URL을 입력해 주세요.")
            )
        }

        val tempZip = File(appContext.cacheDir, "plugin_dl_${UUID.randomUUID()}.zip")
        runCatching {
            val request = Request.Builder()
                .url(trimmedUrl)
                .get()
                .header("Accept", "application/zip, application/octet-stream, */*")
                .build()

            httpClientProvider().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("ZIP 다운로드 실패: HTTP ${response.code} ${response.message}")
                }
                val body = response.body ?: throw IllegalStateException("다운로드 응답 본문이 비어 있습니다.")
                body.byteStream().use { input ->
                    tempZip.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            FileInputStream(tempZip).use { fis ->
                installFromZipStream(fis)
            }
        }.onFailure { e ->
            AppErrorLogger.logError(
                tag = "ExternalPlugin",
                message = "원격 URL 플러그인 설치 실패: ${e.localizedMessage ?: e.javaClass.simpleName}",
                throwable = e,
                extraDetails = "URL: $trimmedUrl"
            )
        }.also {
            runCatching { tempZip.delete() }
        }
    }

    /**
     * 설치된 플러그인의 파일과 격리된 암호화 저장소 데이터를 함께 삭제합니다.
     */
    suspend fun uninstallPlugin(pluginId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isValidPluginId(pluginId)) {
                throw IllegalArgumentException("잘못된 플러그인 ID입니다: $pluginId")
            }
            val targetDir = File(pluginsRootDir, pluginId)
            val canonicalRoot = pluginsRootDir.canonicalPath + File.separator
            if (!targetDir.canonicalPath.startsWith(canonicalRoot)) {
                throw SecurityException("허용되지 않은 플러그인 삭제 경로입니다.")
            }
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            storageVault.clearPluginStorage(pluginId)
            refreshInstalledPlugins()
        }.onFailure { e ->
            AppErrorLogger.logError(
                tag = "ExternalPlugin",
                message = "플러그인($pluginId) 삭제 중 오류가 발생했습니다.",
                throwable = e
            )
        }
    }

    /**
     * ZIP 스트림을 임시 스테이징 폴더에 안전하게 해제(Zip Slip 차단)하고
     * manifest.json 유효성을 검증한 뒤 최종 경로(filesDir/plugins/<id>/)로 설치합니다.
     */
    private fun installFromZipStream(inputStream: InputStream): InstalledPlugin {
        val stagingDir = File(pluginsRootDir, ".staging-${UUID.randomUUID()}")
        if (!stagingDir.mkdirs()) {
            throw IllegalStateException("임시 압축 해제 폴더를 생성할 수 없습니다.")
        }

        try {
            val canonicalStagingPrefix = stagingDir.canonicalPath + File.separator
            var extractedFileCount = 0

            ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val entryName = entry.name.replace('\\', '/')
                    if (entryName.isBlank() || entryName.startsWith("__MACOSX/")) {
                        zis.closeEntry()
                        continue
                    }

                    val targetFile = File(stagingDir, entryName)
                    val canonicalTarget = targetFile.canonicalPath
                    // 필수 보안 검증: Zip Slip 경로 이탈 차단
                    if (!canonicalTarget.startsWith(canonicalStagingPrefix)) {
                        throw SecurityException("Zip Slip 경로 이탈이 감지되어 설치가 차단되었습니다: $entryName")
                    }

                    if (entry.isDirectory) {
                        targetFile.mkdirs()
                    } else {
                        targetFile.parentFile?.mkdirs()
                        targetFile.outputStream().use { out ->
                            zis.copyTo(out)
                        }
                        extractedFileCount += 1
                    }
                    zis.closeEntry()
                }
            }

            if (extractedFileCount == 0) {
                throw IllegalArgumentException("ZIP 파일 안에 추출할 파일이 없습니다.")
            }

            // 최상위 manifest.json 또는 단일 루트 폴더 내부의 manifest.json 탐색
            val pluginContentRoot = resolvePluginContentRoot(stagingDir)
            val manifestFile = File(pluginContentRoot, MANIFEST_FILE_NAME)
            val manifest = json.decodeFromString<PluginManifest>(manifestFile.readText())

            validateManifest(manifest, pluginContentRoot)

            val finalDir = File(pluginsRootDir, manifest.id)
            val canonicalRootPrefix = pluginsRootDir.canonicalPath + File.separator
            if (!finalDir.canonicalPath.startsWith(canonicalRootPrefix)) {
                throw SecurityException("잘못된 플러그인 설치 경로입니다: ${manifest.id}")
            }

            // 동일 id 재설치(업데이트) 시 기존 파일 깔끔하게 교체 (저장소 데이터는 유지)
            if (finalDir.exists()) {
                finalDir.deleteRecursively()
            }
            if (!pluginContentRoot.renameTo(finalDir)) {
                pluginContentRoot.copyRecursively(finalDir, overwrite = true)
            }

            refreshInstalledPlugins()
            return findPluginById(manifest.id) ?: InstalledPlugin(manifest = manifest, installDir = finalDir)
        } finally {
            runCatching {
                if (stagingDir.exists()) {
                    stagingDir.deleteRecursively()
                }
            }
        }
    }

    private fun resolvePluginContentRoot(stagingDir: File): File {
        val rootManifest = File(stagingDir, MANIFEST_FILE_NAME)
        if (rootManifest.exists() && rootManifest.isFile) {
            return stagingDir
        }

        val visibleChildren = stagingDir.listFiles()
            ?.filter { !it.name.startsWith(".") && it.name != "__MACOSX" }
            .orEmpty()

        if (visibleChildren.size == 1 && visibleChildren[0].isDirectory) {
            val nestedManifest = File(visibleChildren[0], MANIFEST_FILE_NAME)
            if (nestedManifest.exists() && nestedManifest.isFile) {
                return visibleChildren[0]
            }
        }

        throw IllegalArgumentException(
            "ZIP 패키지 최상위(또는 단일 루트 폴더 안)에 manifest.json 파일이 없습니다."
        )
    }

    private fun validateManifest(manifest: PluginManifest, contentRoot: File) {
        if (!isValidPluginId(manifest.id)) {
            throw IllegalArgumentException(
                "manifest.json의 id('${manifest.id}')는 영문 소문자, 숫자, 하이픈(-)만 사용할 수 있습니다."
            )
        }
        if (manifest.name.isBlank()) {
            throw IllegalArgumentException("manifest.json의 name 필드가 비어 있습니다.")
        }

        val entryRelative = manifest.entry.trim().trimStart('/').ifBlank { "index.html" }
        val entryFile = File(contentRoot, entryRelative)
        val canonicalContentPrefix = contentRoot.canonicalPath + File.separator
        if (!entryFile.canonicalPath.startsWith(canonicalContentPrefix)) {
            throw SecurityException("manifest.json의 entry 경로가 플러그인 폴더를 벗어납니다: $entryRelative")
        }
        if (!entryFile.exists() || !entryFile.isFile) {
            throw IllegalArgumentException("manifest.json에 지정된 진입 파일($entryRelative)을 찾을 수 없습니다.")
        }
    }

    private fun isValidPluginId(id: String): Boolean {
        return PLUGIN_ID_REGEX.matches(id)
    }

    companion object {
        private const val PLUGINS_ROOT_DIR_NAME = "plugins"
        private const val MANIFEST_FILE_NAME = "manifest.json"
        private val PLUGIN_ID_REGEX = Regex("^[a-z0-9][a-z0-9\\-]{0,62}$")
    }
}
