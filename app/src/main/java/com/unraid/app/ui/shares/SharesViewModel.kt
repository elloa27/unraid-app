package com.unraid.app.ui.shares

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.unraid.app.data.local.AppErrorLogger
import com.unraid.app.data.local.AppPreferencesRepository
import com.unraid.app.data.local.SecureCredentialStore
import com.unraid.app.data.local.SmbCredentials
import com.unraid.app.data.model.ConnectionStatus
import com.unraid.app.data.model.RoutePhase
import com.unraid.app.data.model.UnraidShareItem
import com.unraid.app.data.model.resolveEffectiveUnraidUrl
import com.unraid.app.data.remote.UnraidGraphQlClient
import com.unraid.app.network.TailscaleConnectionManager
import com.unraid.app.player.MpvPlayRequest
import com.unraid.app.shares.FileTransferProgress
import com.unraid.app.shares.ShareRootEntry
import com.unraid.app.shares.ShareSortMode
import com.unraid.app.shares.ShareViewMode
import com.unraid.app.shares.SmbAuthConfig
import com.unraid.app.shares.SmbFileEntry
import com.unraid.app.shares.SmbLocalStreamServer
import com.unraid.app.shares.SmbShareClient
import com.unraid.app.shares.TransferDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets

data class SmbTestResultState(
    val isTesting: Boolean = false,
    val isSuccess: Boolean? = null,
    val message: String? = null
)

data class ImagePreviewState(
    val entry: SmbFileEntry,
    val bytes: ByteArray
)

data class TextPreviewState(
    val entry: SmbFileEntry,
    val content: String
)

data class ExternalFileOpenRequest(
    val uri: Uri,
    val mimeType: String,
    val fileName: String
)

data class SharesUiState(
    val effectiveHost: String = "",
    val connectionStatus: ConnectionStatus = ConnectionStatus(),
    val smbCredentials: SmbCredentials = SmbCredentials(),
    val isSmbConfigured: Boolean = false,
    val isUnraidApiConfigured: Boolean = false,

    // 탐색 상태 (currentShare == null 이면 루트 공유 목록)
    val currentShare: String? = null,
    val currentPath: String = "",
    val rootShares: List<ShareRootEntry> = emptyList(),
    val entries: List<SmbFileEntry> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val graphQlSharesWarning: String? = null,

    // 검색 / 보기 / 정렬
    val searchQuery: String = "",
    val isSearchVisible: Boolean = false,
    val sortMode: ShareSortMode = ShareSortMode.NAME_ASC,
    val viewMode: ShareViewMode = ShareViewMode.LIST,

    // 전송 / 미리보기 / 외부 앱 열기 / 동영상 재생
    val activeTransfer: FileTransferProgress? = null,
    val isPreviewLoading: Boolean = false,
    val imagePreview: ImagePreviewState? = null,
    val textPreview: TextPreviewState? = null,
    val pendingExternalOpen: ExternalFileOpenRequest? = null,
    val activePlayRequest: MpvPlayRequest? = null,
    val smbTestState: SmbTestResultState = SmbTestResultState(),
    val feedbackMessage: String? = null
) {
    val isAtRoot: Boolean get() = currentShare == null

    val breadcrumbSegments: List<String>
        get() = if (currentPath.isBlank()) {
            emptyList()
        } else {
            currentPath.split('/').filter { it.isNotBlank() }
        }
}

/**
 * Unraid SMB 기반 드라이브 스타일 파일 탐색기 + 내장 libmpv 즉시 스트리밍 ViewModel.
 */
class SharesViewModel(
    private val preferencesRepository: AppPreferencesRepository,
    private val credentialStore: SecureCredentialStore,
    private val graphQlClient: UnraidGraphQlClient,
    private val connectionManager: TailscaleConnectionManager,
    private val smbClient: SmbShareClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(SharesUiState())
    val uiState: StateFlow<SharesUiState> = _uiState.asStateFlow()

    private val streamServer = SmbLocalStreamServer(smbClient)

    private var currentEffectiveUrl: String = ""
    private var currentApiKey: String = ""
    private var currentEffectiveHost: String = ""
    private var currentSmbCreds: SmbCredentials = SmbCredentials()
    private var manualShareNames: Set<String> = emptySet()
    private var cachedGraphQlShares: List<UnraidShareItem> = emptyList()
    private var lastAutoLoadedSignature: String = ""

    private var activeTransferJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                preferencesRepository.unraidServerUrlFlow,
                preferencesRepository.tailscaleServerUrlFlow,
                credentialStore.apiKeyFlow,
                connectionManager.status,
                credentialStore.smbCredentialsFlow
            ) { homeUrl, tsUrl, apiKey, connStatus, smbCreds ->
                val effectiveUrl = resolveEffectiveUnraidUrl(
                    homeUrl = homeUrl,
                    tailscaleUrl = tsUrl,
                    useTailscale = connStatus.isUsingTailscale
                )
                NetworkSnapshot(effectiveUrl, apiKey, connStatus, smbCreds)
            }.combine(preferencesRepository.manualShareNamesFlow) { snap, manuals ->
                snap to manuals
            }.collect { (snap, manuals) ->
                currentEffectiveUrl = snap.effectiveUrl
                currentApiKey = snap.apiKey
                currentSmbCreds = snap.smbCreds
                manualShareNames = manuals

                val resolvedHost = snap.smbCreds.customHost.trim().ifBlank {
                    extractHost(snap.effectiveUrl)
                }
                currentEffectiveHost = resolvedHost

                val apiConfigured = snap.effectiveUrl.isNotBlank() && snap.apiKey.isNotBlank()
                _uiState.update {
                    it.copy(
                        effectiveHost = resolvedHost,
                        connectionStatus = snap.connStatus,
                        smbCredentials = snap.smbCreds,
                        isSmbConfigured = snap.smbCreds.isConfigured,
                        isUnraidApiConfigured = apiConfigured,
                        rootShares = mergeRootShares(cachedGraphQlShares, manuals)
                    )
                }

                val routeReady = snap.connStatus.phase != RoutePhase.TAILSCALE_CONNECTING
                val signature = "${snap.effectiveUrl}|${snap.apiKey}|$resolvedHost|${snap.connStatus.phase}|${snap.connStatus.proxyPort}"
                if (routeReady && lastAutoLoadedSignature != signature && (apiConfigured || manuals.isNotEmpty())) {
                    lastAutoLoadedSignature = signature
                    loadRootShares(isPullToRefresh = false)
                }
            }
        }
    }

    // =========================================================================
    // 루트 공유 목록 및 폴더 탐색
    // =========================================================================

    fun refreshCurrentLocation(isPullToRefresh: Boolean = true) {
        val state = _uiState.value
        if (state.currentShare == null) {
            loadRootShares(isPullToRefresh = isPullToRefresh)
        } else {
            loadDirectory(
                shareName = state.currentShare,
                relativePath = state.currentPath,
                isPullToRefresh = isPullToRefresh
            )
        }
    }

    fun loadRootShares(isPullToRefresh: Boolean = false) {
        viewModelScope.launch {
            if (currentEffectiveUrl.isBlank() || currentApiKey.isBlank()) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        rootShares = mergeRootShares(emptyList(), manualShareNames),
                        graphQlSharesWarning = if (manualShareNames.isEmpty()) {
                            "Unraid API 설정이 없어 자동 공유 목록을 조회할 수 없습니다. 설정을 입력하거나 아래에서 공유 폴더 이름을 직접 추가하세요."
                        } else {
                            null
                        }
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isLoading = !isPullToRefresh && it.rootShares.isEmpty(),
                    isRefreshing = isPullToRefresh,
                    errorMessage = null,
                    graphQlSharesWarning = null
                )
            }

            graphQlClient.fetchShares(currentEffectiveUrl, currentApiKey)
                .onSuccess { shares ->
                    cachedGraphQlShares = shares
                    _uiState.update {
                        it.copy(
                            rootShares = mergeRootShares(shares, manualShareNames),
                            isLoading = false,
                            isRefreshing = false,
                            graphQlSharesWarning = null
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            rootShares = mergeRootShares(cachedGraphQlShares, manualShareNames),
                            isLoading = false,
                            isRefreshing = false,
                            graphQlSharesWarning = "Unraid GraphQL 공유 목록 조회 실패 (${err.localizedMessage ?: "오류"}). 수동 공유 추가를 이용할 수 있습니다."
                        )
                    }
                }
        }
    }

    fun openShare(shareName: String) {
        if (currentEffectiveHost.isBlank()) {
            _uiState.update {
                it.copy(feedbackMessage = "접속할 Unraid 서버 주소 또는 SMB 호스트를 먼저 설정해 주세요.")
            }
            return
        }
        _uiState.update {
            it.copy(
                currentShare = shareName,
                currentPath = "",
                entries = emptyList(),
                errorMessage = null,
                searchQuery = "",
                isSearchVisible = false
            )
        }
        loadDirectory(shareName = shareName, relativePath = "", isPullToRefresh = false)
    }

    fun openDirectory(entry: SmbFileEntry) {
        val shareName = _uiState.value.currentShare ?: return
        if (!entry.isDirectory) return
        _uiState.update {
            it.copy(
                currentPath = entry.relativePath,
                errorMessage = null,
                searchQuery = ""
            )
        }
        loadDirectory(shareName = shareName, relativePath = entry.relativePath, isPullToRefresh = false)
    }

    fun navigateToRoot() {
        _uiState.update {
            it.copy(
                currentShare = null,
                currentPath = "",
                entries = emptyList(),
                errorMessage = null,
                searchQuery = "",
                isSearchVisible = false
            )
        }
    }

    fun navigateToBreadcrumb(depthIndex: Int) {
        val shareName = _uiState.value.currentShare ?: return
        if (depthIndex < 0) {
            navigateToRoot()
            return
        }
        val segments = _uiState.value.breadcrumbSegments
        val targetPath = if (depthIndex == 0) {
            ""
        } else {
            segments.take(depthIndex).joinToString("/")
        }
        _uiState.update { it.copy(currentPath = targetPath, errorMessage = null, searchQuery = "") }
        loadDirectory(shareName = shareName, relativePath = targetPath, isPullToRefresh = false)
    }

    fun navigateUp(): Boolean {
        val state = _uiState.value
        val shareName = state.currentShare ?: return false
        if (state.currentPath.isBlank()) {
            navigateToRoot()
            return true
        }
        val parentPath = state.currentPath.substringBeforeLast('/', "")
        _uiState.update { it.copy(currentPath = parentPath, errorMessage = null, searchQuery = "") }
        loadDirectory(shareName = shareName, relativePath = parentPath, isPullToRefresh = false)
        return true
    }

    private fun loadDirectory(shareName: String, relativePath: String, isPullToRefresh: Boolean) {
        val host = currentEffectiveHost
        val auth = SmbAuthConfig.fromCredentials(currentSmbCreds)
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = !isPullToRefresh,
                    isRefreshing = isPullToRefresh,
                    errorMessage = null
                )
            }

            smbClient.listDirectory(host, auth, shareName, relativePath)
                .onSuccess { list ->
                    _uiState.update {
                        it.copy(
                            entries = list,
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = null
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = err.localizedMessage ?: "SMB 폴더를 열 수 없습니다. 계정 정보와 네트워크 연결을 확인해 주세요."
                        )
                    }
                }
        }
    }

    // =========================================================================
    // 검색 / 정렬 / 보기 전환 / 수동 공유 관리
    // =========================================================================

    fun toggleSearchVisibility() {
        _uiState.update {
            val next = !it.isSearchVisible
            it.copy(
                isSearchVisible = next,
                searchQuery = if (next) it.searchQuery else ""
            )
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setSortMode(mode: ShareSortMode) {
        _uiState.update { it.copy(sortMode = mode) }
    }

    fun toggleViewMode() {
        _uiState.update {
            it.copy(
                viewMode = if (it.viewMode == ShareViewMode.LIST) ShareViewMode.GRID else ShareViewMode.LIST
            )
        }
    }

    fun addManualShare(shareName: String) {
        val clean = shareName.trim().trim('/', '\\')
        if (clean.isBlank()) return
        viewModelScope.launch {
            preferencesRepository.addManualShareName(clean)
            _uiState.update { it.copy(feedbackMessage = "'$clean' 공유 폴더가 추가되었습니다.") }
        }
    }

    fun removeManualShare(shareName: String) {
        viewModelScope.launch {
            preferencesRepository.removeManualShareName(shareName)
            _uiState.update { it.copy(feedbackMessage = "'$shareName' 수동 공유가 제거되었습니다.") }
        }
    }

    // =========================================================================
    // SMB 계정 저장 및 연결 테스트
    // =========================================================================

    fun saveSmbCredentials(
        username: String,
        password: String,
        useGuest: Boolean,
        customHost: String
    ) {
        credentialStore.saveSmbCredentials(
            username = username,
            password = password,
            useGuest = useGuest,
            customHost = customHost
        )
        _uiState.update {
            it.copy(
                smbTestState = SmbTestResultState(),
                feedbackMessage = "SMB 계정 정보가 암호화되어 저장되었습니다."
            )
        }
    }

    fun testSmbConnection(
        username: String,
        password: String,
        useGuest: Boolean,
        customHost: String
    ) {
        val targetHost = customHost.trim().ifBlank { extractHost(currentEffectiveUrl) }
        if (targetHost.isBlank()) {
            _uiState.update {
                it.copy(
                    smbTestState = SmbTestResultState(
                        isTesting = false,
                        isSuccess = false,
                        message = "Unraid 서버 URL 또는 SMB 호스트를 입력해 주세요."
                    )
                )
            }
            return
        }

        val sampleShare = _uiState.value.currentShare
            ?: _uiState.value.rootShares.firstOrNull()?.name

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    smbTestState = SmbTestResultState(
                        isTesting = true,
                        isSuccess = null,
                        message = "SMB 연결 확인 중... ($targetHost:445)"
                    )
                )
            }
            val result = smbClient.testConnection(
                host = targetHost,
                auth = SmbAuthConfig(username = username.trim(), password = password, useGuest = useGuest),
                sampleShareName = sampleShare
            )
            result.onSuccess { msg ->
                _uiState.update {
                    it.copy(
                        smbTestState = SmbTestResultState(
                            isTesting = false,
                            isSuccess = true,
                            message = msg
                        )
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        smbTestState = SmbTestResultState(
                            isTesting = false,
                            isSuccess = false,
                            message = err.localizedMessage ?: "SMB 연결에 실패했습니다."
                        )
                    )
                }
            }
        }
    }

    fun clearSmbTestState() {
        _uiState.update { it.copy(smbTestState = SmbTestResultState()) }
    }

    // =========================================================================
    // 폴더 생성 / 이름 변경 / 삭제
    // =========================================================================

    fun createFolder(folderName: String) {
        val state = _uiState.value
        val shareName = state.currentShare ?: return
        val auth = SmbAuthConfig.fromCredentials(currentSmbCreds)
        viewModelScope.launch {
            smbClient.createDirectory(
                host = currentEffectiveHost,
                auth = auth,
                shareName = shareName,
                parentRelativePath = state.currentPath,
                newFolderName = folderName
            ).onSuccess {
                _uiState.update { it.copy(feedbackMessage = "'$folderName' 폴더를 생성했습니다.") }
                loadDirectory(shareName, state.currentPath, isPullToRefresh = true)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(feedbackMessage = "폴더 생성 실패: ${err.localizedMessage ?: "오류 발생"}")
                }
            }
        }
    }

    fun renameEntry(entry: SmbFileEntry, newName: String) {
        val state = _uiState.value
        val shareName = state.currentShare ?: return
        val auth = SmbAuthConfig.fromCredentials(currentSmbCreds)
        viewModelScope.launch {
            smbClient.renameEntry(
                host = currentEffectiveHost,
                auth = auth,
                shareName = shareName,
                entry = entry,
                newName = newName
            ).onSuccess {
                _uiState.update { it.copy(feedbackMessage = "'${entry.name}'의 이름을 '$newName'(으)로 변경했습니다.") }
                loadDirectory(shareName, state.currentPath, isPullToRefresh = true)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(feedbackMessage = "이름 변경 실패: ${err.localizedMessage ?: "오류 발생"}")
                }
            }
        }
    }

    fun deleteEntry(entry: SmbFileEntry) {
        val state = _uiState.value
        val shareName = state.currentShare ?: return
        val auth = SmbAuthConfig.fromCredentials(currentSmbCreds)
        viewModelScope.launch {
            smbClient.deleteEntry(
                host = currentEffectiveHost,
                auth = auth,
                shareName = shareName,
                entry = entry,
                recursive = true
            ).onSuccess {
                _uiState.update { it.copy(feedbackMessage = "'${entry.name}'을(를) 삭제했습니다.") }
                loadDirectory(shareName, state.currentPath, isPullToRefresh = true)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(feedbackMessage = "삭제 실패: ${err.localizedMessage ?: "오류 발생"}")
                }
            }
        }
    }

    // =========================================================================
    // 다중 파일 업로드 및 MediaStore.Downloads 다운로드
    // =========================================================================

    fun uploadFiles(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val state = _uiState.value
        val shareName = state.currentShare ?: return
        val targetPath = state.currentPath
        val auth = SmbAuthConfig.fromCredentials(currentSmbCreds)
        val host = currentEffectiveHost
        val appContext = context.applicationContext

        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            var successCount = 0
            var lastError: String? = null
            try {
                uris.forEachIndexed { idx, uri ->
                    val (displayName, sizeBytes) = resolveUriMeta(appContext, uri, idx)
                    _uiState.update {
                        it.copy(
                            activeTransfer = FileTransferProgress(
                                direction = TransferDirection.UPLOAD,
                                fileName = displayName,
                                transferredBytes = 0L,
                                totalBytes = sizeBytes,
                                currentFileIndex = idx + 1,
                                totalFileCount = uris.size
                            )
                        )
                    }

                    val uploadResult = withContext(Dispatchers.IO) {
                        val stream = appContext.contentResolver.openInputStream(uri)
                            ?: return@withContext Result.failure<Long>(
                                IllegalStateException("선택한 파일을 열 수 없습니다: $displayName")
                            )
                        stream.use { input ->
                            smbClient.uploadFromStream(
                                host = host,
                                auth = auth,
                                shareName = shareName,
                                parentRelativePath = targetPath,
                                fileName = displayName,
                                inputStream = input,
                                totalBytes = sizeBytes,
                                onProgress = { transferred, total ->
                                    _uiState.update { prev ->
                                        prev.copy(
                                            activeTransfer = prev.activeTransfer?.copy(
                                                transferredBytes = transferred,
                                                totalBytes = total
                                            )
                                        )
                                    }
                                }
                            )
                        }
                    }

                    uploadResult.onSuccess {
                        successCount += 1
                    }.onFailure { err ->
                        lastError = err.localizedMessage
                    }
                }

                _uiState.update {
                    it.copy(
                        activeTransfer = null,
                        feedbackMessage = when {
                            successCount == uris.size -> "파일 ${successCount}개 업로드를 완료했습니다."
                            successCount > 0 -> "파일 ${successCount}/${uris.size}개 업로드 완료 (일부 오류: $lastError)"
                            else -> "업로드 실패: ${lastError ?: "오류 발생"}"
                        }
                    )
                }
                if (successCount > 0) {
                    loadDirectory(shareName, targetPath, isPullToRefresh = true)
                }
            } catch (_: CancellationException) {
                _uiState.update {
                    it.copy(
                        activeTransfer = null,
                        feedbackMessage = "파일 업로드를 취소했습니다."
                    )
                }
            } finally {
                activeTransferJob = null
            }
        }
    }

    fun downloadFile(context: Context, entry: SmbFileEntry) {
        if (entry.isDirectory) return
        val shareName = _uiState.value.currentShare ?: return
        val auth = SmbAuthConfig.fromCredentials(currentSmbCreds)
        val host = currentEffectiveHost
        val appContext = context.applicationContext

        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    activeTransfer = FileTransferProgress(
                        direction = TransferDirection.DOWNLOAD,
                        fileName = entry.name,
                        transferredBytes = 0L,
                        totalBytes = entry.sizeBytes
                    )
                )
            }

            var insertedUri: Uri? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    val mimeType = SmbFileEntry.guessMimeType(entry.name)
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, entry.name)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/UnraidHub")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = appContext.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("Downloads/UnraidHub에 파일을 생성할 수 없습니다.")
                    insertedUri = uri

                    val outStream = resolver.openOutputStream(uri)
                        ?: throw IllegalStateException("다운로드 출력 스트림을 열 수 없습니다.")

                    val downloadRes = outStream.use { out ->
                        smbClient.downloadToStream(
                            host = host,
                            auth = auth,
                            shareName = shareName,
                            relativePath = entry.relativePath,
                            outputStream = out,
                            onProgress = { transferred, total ->
                                _uiState.update { prev ->
                                    prev.copy(
                                        activeTransfer = prev.activeTransfer?.copy(
                                            transferredBytes = transferred,
                                            totalBytes = if (total > 0L) total else entry.sizeBytes
                                        )
                                    )
                                }
                            }
                        )
                    }

                    if (downloadRes.isSuccess) {
                        val doneValues = ContentValues().apply {
                            put(MediaStore.Downloads.IS_PENDING, 0)
                        }
                        resolver.update(uri, doneValues, null, null)
                    } else {
                        runCatching { resolver.delete(uri, null, null) }
                    }
                    downloadRes
                }

                result.onSuccess {
                    _uiState.update {
                        it.copy(
                            activeTransfer = null,
                            feedbackMessage = "Downloads/UnraidHub/${entry.name}에 저장했습니다."
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            activeTransfer = null,
                            feedbackMessage = "다운로드 실패: ${err.localizedMessage ?: "오류 발생"}"
                        )
                    }
                }
            } catch (_: CancellationException) {
                insertedUri?.let { uri ->
                    runCatching { appContext.contentResolver.delete(uri, null, null) }
                }
                _uiState.update {
                    it.copy(
                        activeTransfer = null,
                        feedbackMessage = "파일 다운로드를 취소했습니다."
                    )
                }
            } catch (e: Exception) {
                insertedUri?.let { uri ->
                    runCatching { appContext.contentResolver.delete(uri, null, null) }
                }
                AppErrorLogger.logError(
                    tag = "ShareDownload",
                    message = "Downloads 저장 오류 (${entry.name}): ${e.localizedMessage ?: e.javaClass.simpleName}",
                    throwable = e
                )
                _uiState.update {
                    it.copy(
                        activeTransfer = null,
                        feedbackMessage = "다운로드 실패: ${e.localizedMessage ?: "저장소 오류"}"
                    )
                }
            } finally {
                activeTransferJob = null
            }
        }
    }

    fun cancelActiveTransfer() {
        activeTransferJob?.cancel()
        activeTransferJob = null
    }

    // =========================================================================
    // 파일 열기: 동영상 libmpv 즉시 스트리밍 / 이미지·텍스트 인앱 미리보기 / 외부 앱 열기
    // =========================================================================

    fun openFile(context: Context, entry: SmbFileEntry) {
        if (entry.isDirectory) {
            openDirectory(entry)
            return
        }
        when {
            entry.isVideo -> startVideoStream(entry)
            entry.isImage -> openImagePreview(entry)
            entry.isTextPreviewable -> openTextPreview(entry)
            else -> prepareExternalOpen(context, entry)
        }
    }

    fun startVideoStream(entry: SmbFileEntry) {
        val state = _uiState.value
        val shareName = state.currentShare ?: return
        val host = currentEffectiveHost
        val auth = SmbAuthConfig.fromCredentials(currentSmbCreds)

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                // 동일 폴더에서 확장자만 다르거나 언어 접미사가 붙은 동명 자막 파일 자동 매칭
                val baseLower = entry.baseNameWithoutExt.lowercase()
                val siblingSubtitles = state.entries.filter { candidate ->
                    if (!candidate.isSubtitle) return@filter false
                    val candBase = candidate.baseNameWithoutExt.lowercase()
                    candBase == baseLower || candBase.startsWith("$baseLower.")
                }

                val sessionInfo = streamServer.startSession(
                    host = host,
                    auth = auth,
                    shareName = shareName,
                    videoEntry = entry,
                    siblingSubtitles = siblingSubtitles
                )

                val parentFolder = entry.relativePath.substringBeforeLast('/', "")
                val subtitleLabel = if (parentFolder.isBlank()) "/$shareName" else "/$shareName/$parentFolder"

                MpvPlayRequest(
                    url = sessionInfo.videoUrl,
                    title = entry.name,
                    subtitle = subtitleLabel,
                    startPositionSec = 0.0,
                    subtitles = sessionInfo.subtitles
                )
            }.onSuccess { playRequest ->
                _uiState.update { it.copy(activePlayRequest = playRequest) }
            }.onFailure { err ->
                AppErrorLogger.logError(
                    tag = "SmbStreamServer",
                    message = "동영상 스트림 세션 시작 실패 (${entry.name}): ${err.localizedMessage ?: err.javaClass.simpleName}",
                    throwable = err
                )
                _uiState.update {
                    it.copy(feedbackMessage = "동영상 스트리밍을 시작하지 못했습니다: ${err.localizedMessage ?: "오류"}")
                }
            }
        }
    }

    fun closeVideoPlayer() {
        _uiState.update { it.copy(activePlayRequest = null) }
        viewModelScope.launch(Dispatchers.IO) {
            streamServer.stop()
        }
    }

    private fun openImagePreview(entry: SmbFileEntry) {
        val shareName = _uiState.value.currentShare ?: return
        val auth = SmbAuthConfig.fromCredentials(currentSmbCreds)
        viewModelScope.launch {
            _uiState.update { it.copy(isPreviewLoading = true) }
            smbClient.readSmallFileBytes(
                host = currentEffectiveHost,
                auth = auth,
                shareName = shareName,
                relativePath = entry.relativePath,
                maxBytes = MAX_IMAGE_PREVIEW_BYTES
            ).onSuccess { bytes ->
                _uiState.update {
                    it.copy(
                        isPreviewLoading = false,
                        imagePreview = ImagePreviewState(entry = entry, bytes = bytes)
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isPreviewLoading = false,
                        feedbackMessage = "이미지 미리보기 실패: ${err.localizedMessage ?: "다운로드 후 확인해 주세요."}"
                    )
                }
            }
        }
    }

    fun dismissImagePreview() {
        _uiState.update { it.copy(imagePreview = null) }
    }

    private fun openTextPreview(entry: SmbFileEntry) {
        if (entry.sizeBytes > MAX_TEXT_PREVIEW_BYTES.toLong()) {
            _uiState.update {
                it.copy(feedbackMessage = "텍스트 파일이 1MB를 초과합니다. 우측 메뉴에서 다운로드해 확인해 주세요.")
            }
            return
        }
        val shareName = _uiState.value.currentShare ?: return
        val auth = SmbAuthConfig.fromCredentials(currentSmbCreds)
        viewModelScope.launch {
            _uiState.update { it.copy(isPreviewLoading = true) }
            smbClient.readSmallFileBytes(
                host = currentEffectiveHost,
                auth = auth,
                shareName = shareName,
                relativePath = entry.relativePath,
                maxBytes = MAX_TEXT_PREVIEW_BYTES
            ).onSuccess { bytes ->
                val text = String(bytes, StandardCharsets.UTF_8)
                _uiState.update {
                    it.copy(
                        isPreviewLoading = false,
                        textPreview = TextPreviewState(entry = entry, content = text)
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isPreviewLoading = false,
                        feedbackMessage = "텍스트 미리보기 실패: ${err.localizedMessage ?: "오류 발생"}"
                    )
                }
            }
        }
    }

    fun dismissTextPreview() {
        _uiState.update { it.copy(textPreview = null) }
    }

    private fun prepareExternalOpen(context: Context, entry: SmbFileEntry) {
        val shareName = _uiState.value.currentShare ?: return
        val auth = SmbAuthConfig.fromCredentials(currentSmbCreds)
        val host = currentEffectiveHost
        val appContext = context.applicationContext

        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    activeTransfer = FileTransferProgress(
                        direction = TransferDirection.PREPARE_OPEN,
                        fileName = entry.name,
                        transferredBytes = 0L,
                        totalBytes = entry.sizeBytes
                    )
                )
            }

            try {
                val openResult = withContext(Dispatchers.IO) {
                    val previewDir = File(appContext.cacheDir, "shared_preview").apply {
                        if (!exists()) mkdirs()
                    }
                    // 오래된 임시 미리보기 파일 정리 (최근 8개만 유지)
                    previewDir.listFiles()
                        ?.sortedByDescending { it.lastModified() }
                        ?.drop(8)
                        ?.forEach { runCatching { it.delete() } }

                    val safeName = entry.name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
                    val cacheFile = File(previewDir, safeName)

                    val downloadRes = FileOutputStream(cacheFile).use { fos ->
                        smbClient.downloadToStream(
                            host = host,
                            auth = auth,
                            shareName = shareName,
                            relativePath = entry.relativePath,
                            outputStream = fos,
                            onProgress = { transferred, total ->
                                _uiState.update { prev ->
                                    prev.copy(
                                        activeTransfer = prev.activeTransfer?.copy(
                                            transferredBytes = transferred,
                                            totalBytes = if (total > 0L) total else entry.sizeBytes
                                        )
                                    )
                                }
                            }
                        )
                    }

                    if (downloadRes.isFailure) {
                        runCatching { cacheFile.delete() }
                        return@withContext Result.failure<ExternalFileOpenRequest>(
                            downloadRes.exceptionOrNull() ?: IllegalStateException("파일 준비 실패")
                        )
                    }

                    val uri = FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        cacheFile
                    )
                    Result.success(
                        ExternalFileOpenRequest(
                            uri = uri,
                            mimeType = SmbFileEntry.guessMimeType(entry.name),
                            fileName = entry.name
                        )
                    )
                }

                openResult.onSuccess { req ->
                    _uiState.update {
                        it.copy(
                            activeTransfer = null,
                            pendingExternalOpen = req
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            activeTransfer = null,
                            feedbackMessage = "외부 앱 열기 준비 실패: ${err.localizedMessage ?: "오류 발생"}"
                        )
                    }
                }
            } catch (_: CancellationException) {
                _uiState.update {
                    it.copy(
                        activeTransfer = null,
                        feedbackMessage = "파일 열기를 취소했습니다."
                    )
                }
            } finally {
                activeTransferJob = null
            }
        }
    }

    fun consumePendingExternalOpen() {
        _uiState.update { it.copy(pendingExternalOpen = null) }
    }

    fun showMessage(msg: String) {
        _uiState.update { it.copy(feedbackMessage = msg) }
    }

    fun clearFeedbackMessage() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }

    override fun onCleared() {
        activeTransferJob?.cancel()
        streamServer.stop()
        super.onCleared()
    }

    private fun mergeRootShares(
        graphQlShares: List<UnraidShareItem>,
        manualNames: Set<String>
    ): List<ShareRootEntry> {
        val byLowerName = LinkedHashMap<String, ShareRootEntry>()
        graphQlShares.forEach { item ->
            byLowerName[item.name.lowercase()] = ShareRootEntry(
                name = item.name,
                comment = item.comment,
                usedKb = item.usedKb,
                freeKb = item.freeKb,
                sizeKb = item.sizeKb,
                cachePolicy = item.cache,
                isManual = false
            )
        }
        manualNames.sortedBy { it.lowercase() }.forEach { manual ->
            val key = manual.lowercase()
            if (key !in byLowerName) {
                byLowerName[key] = ShareRootEntry(
                    name = manual,
                    comment = "수동 등록 공유",
                    isManual = true
                )
            }
        }
        return byLowerName.values.sortedBy { it.name.lowercase() }
    }

    private fun resolveUriMeta(context: Context, uri: Uri, index: Int): Pair<String, Long> {
        var displayName = "upload_${System.currentTimeMillis()}_$index"
        var size = -1L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) {
                        cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        size = cursor.getLong(sizeIdx)
                    }
                }
            }
        }
        return displayName to size
    }

    private fun extractHost(rawUrl: String): String {
        val normalized = AppPreferencesRepository.normalizeUrl(rawUrl)
        if (normalized.isBlank()) return ""
        return runCatching { URI(normalized).host.orEmpty() }.getOrDefault("")
    }

    private data class NetworkSnapshot(
        val effectiveUrl: String,
        val apiKey: String,
        val connStatus: ConnectionStatus,
        val smbCreds: SmbCredentials
    )

    class Factory(
        private val preferencesRepository: AppPreferencesRepository,
        private val credentialStore: SecureCredentialStore,
        private val graphQlClient: UnraidGraphQlClient,
        private val connectionManager: TailscaleConnectionManager,
        private val smbClient: SmbShareClient
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SharesViewModel(
                preferencesRepository = preferencesRepository,
                credentialStore = credentialStore,
                graphQlClient = graphQlClient,
                connectionManager = connectionManager,
                smbClient = smbClient
            ) as T
        }
    }

    companion object {
        private const val MAX_IMAGE_PREVIEW_BYTES = 20 * 1024 * 1024
        private const val MAX_TEXT_PREVIEW_BYTES = 1 * 1024 * 1024
    }
}
