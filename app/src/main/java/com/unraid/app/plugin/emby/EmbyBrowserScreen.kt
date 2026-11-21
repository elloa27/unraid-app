package com.unraid.app.plugin.emby

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.unraid.app.network.NetworkProxyManager
import com.unraid.app.plugin.LauncherService
import com.unraid.app.ui.theme.StatusGreen
import com.unraid.app.ui.theme.StatusRed
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.Locale

private sealed class EmbyNavRoute {
    data object Home : EmbyNavRoute()
    data object Search : EmbyNavRoute()
    data class Library(val library: EmbyLibrary) : EmbyNavRoute()
    data class Series(val seriesItem: EmbyItem) : EmbyNavRoute()
    data class Detail(val item: EmbyItem) : EmbyNavRoute()
}

/**
 * Emby 전용 네이티브 UI 최상위 컨테이너.
 * - 미로그인 시 내장 로그인 화면으로 자연스럽게 안내
 * - 로그인 시 홈(이어보기/최근 추가/라이브러리) → 라이브러리 그리드/검색 → 시리즈/시즌/에피소드 → 상세 → libmpv 플레이어
 */
@Composable
fun EmbyNativeRootScreen(
    service: LauncherService,
    apiClient: EmbyApiClient,
    credentialStore: EmbyCredentialStore,
    onOpenWebViewFallback: () -> Unit,
    onExit: () -> Unit
) {
    val authConfig by credentialStore.configFlow.collectAsStateWithLifecycle()
    val proxyConfig by NetworkProxyManager.proxyConfig.collectAsStateWithLifecycle()
    val effectiveBaseUrl = remember(authConfig, proxyConfig.isActive, service.url) {
        authConfig.resolveEffectiveBaseUrl(
            useTailscale = proxyConfig.isActive,
            fallbackDockerUrl = service.url
        )
    }

    var activePlaybackRequest by remember { mutableStateOf<EmbyPlaybackRequest?>(null) }
    var seriesPlaylist by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
    var isSwitchingEpisode by remember { mutableStateOf(false) }
    var homeRefreshTrigger by remember { mutableStateOf(0) }
    val navStack = remember { mutableStateListOf<EmbyNavRoute>(EmbyNavRoute.Home) }
    val rootScope = rememberCoroutineScope()

    // 에피소드 재생 시(홈 이어보기 포함) 해당 시리즈의 전체 에피소드 목록을 자동 조회해 이전화/다음화 버튼 활성화
    val activeSeriesId = activePlaybackRequest?.item?.seriesId
    LaunchedEffect(activeSeriesId, effectiveBaseUrl, authConfig.accessToken) {
        if (activeSeriesId.isNullOrBlank() || !authConfig.isAuthenticated) {
            seriesPlaylist = emptyList()
            return@LaunchedEffect
        }
        val res = apiClient.fetchAllSeriesEpisodes(
            baseUrl = effectiveBaseUrl,
            userId = authConfig.userId,
            accessToken = authConfig.accessToken,
            seriesId = activeSeriesId
        )
        seriesPlaylist = res.getOrDefault(emptyList())
    }

    if (activePlaybackRequest != null) {
        val currentReq = activePlaybackRequest!!
        val currentIdx = seriesPlaylist.indexOfFirst { it.id == currentReq.item.id }
        val prevEp = if (currentIdx > 0) seriesPlaylist[currentIdx - 1] else null
        val nextEp = if (currentIdx >= 0 && currentIdx < seriesPlaylist.lastIndex) {
            seriesPlaylist[currentIdx + 1]
        } else {
            null
        }

        MpvPlayerSessionHost(
            request = currentReq,
            apiClient = apiClient,
            credentialStore = credentialStore,
            previousEpisode = prevEp,
            nextEpisode = nextEp,
            allEpisodes = seriesPlaylist,
            isSwitchingEpisode = isSwitchingEpisode,
            onSelectEpisode = { targetEp ->
                if (isSwitchingEpisode) return@MpvPlayerSessionHost
                rootScope.launch {
                    isSwitchingEpisode = true
                    val fullEp = apiClient.fetchItemDetail(
                        baseUrl = effectiveBaseUrl,
                        userId = authConfig.userId,
                        accessToken = authConfig.accessToken,
                        itemId = targetEp.id
                    ).getOrDefault(targetEp)

                    activePlaybackRequest = apiClient.buildPlaybackRequest(
                        item = fullEp,
                        baseUrl = effectiveBaseUrl,
                        accessToken = authConfig.accessToken,
                        userId = authConfig.userId,
                        resume = false
                    )
                    // 상세 화면에서 진입한 경우 뒤로 나갔을 때도 마지막 시청 회차가 보이도록 갱신
                    val topRoute = navStack.lastOrNull()
                    if (topRoute is EmbyNavRoute.Detail) {
                        navStack[navStack.lastIndex] = EmbyNavRoute.Detail(fullEp)
                    }
                    isSwitchingEpisode = false
                }
            },
            onExit = {
                activePlaybackRequest = null
                isSwitchingEpisode = false
                // 재생 종료 후 이어보기 위치가 반영되도록 홈/상세 갱신 트리거
                homeRefreshTrigger += 1
            }
        )
        return
    }

    if (!authConfig.isAuthenticated || effectiveBaseUrl.isBlank()) {
        BackHandler(enabled = true) { onExit() }
        EmbyLoginScreen(
            defaultServerUrl = effectiveBaseUrl.ifBlank { normalizeEmbyServerUrl(service.url) },
            savedTailscaleUrl = authConfig.tailscaleServerUrl,
            apiClient = apiClient,
            credentialStore = credentialStore,
            onOpenWebViewFallback = onOpenWebViewFallback,
            onExit = onExit
        )
        return
    }

    val currentRoute = navStack.lastOrNull() ?: EmbyNavRoute.Home

    fun popOrExit() {
        if (navStack.size > 1) {
            navStack.removeAt(navStack.lastIndex)
        } else {
            onExit()
        }
    }

    fun openItem(item: EmbyItem) {
        if (item.isSeries) {
            navStack.add(EmbyNavRoute.Series(item))
        } else {
            navStack.add(EmbyNavRoute.Detail(item))
        }
    }

    fun startPlayback(detailItem: EmbyItem, resume: Boolean) {
        activePlaybackRequest = apiClient.buildPlaybackRequest(
            item = detailItem,
            baseUrl = effectiveBaseUrl,
            accessToken = authConfig.accessToken,
            userId = authConfig.userId,
            resume = resume
        )
    }

    BackHandler(enabled = true) { popOrExit() }

    when (currentRoute) {
        EmbyNavRoute.Home -> {
            EmbyHomeScreen(
                baseUrl = effectiveBaseUrl,
                authConfig = authConfig,
                apiClient = apiClient,
                refreshTrigger = homeRefreshTrigger,
                onBackToLauncher = onExit,
                onOpenWebViewFallback = onOpenWebViewFallback,
                onOpenSearch = { navStack.add(EmbyNavRoute.Search) },
                onOpenLibrary = { lib -> navStack.add(EmbyNavRoute.Library(lib)) },
                onOpenItem = ::openItem
            )
        }

        EmbyNavRoute.Search -> {
            EmbySearchScreen(
                baseUrl = effectiveBaseUrl,
                authConfig = authConfig,
                apiClient = apiClient,
                onBack = ::popOrExit,
                onOpenItem = ::openItem
            )
        }

        is EmbyNavRoute.Library -> {
            EmbyLibraryGridScreen(
                library = currentRoute.library,
                baseUrl = effectiveBaseUrl,
                authConfig = authConfig,
                apiClient = apiClient,
                onSelectSortOption = { option -> credentialStore.setLibrarySortOption(option) },
                onBack = ::popOrExit,
                onOpenSearch = { navStack.add(EmbyNavRoute.Search) },
                onOpenItem = ::openItem
            )
        }

        is EmbyNavRoute.Series -> {
            EmbySeriesScreen(
                seriesItem = currentRoute.seriesItem,
                baseUrl = effectiveBaseUrl,
                authConfig = authConfig,
                apiClient = apiClient,
                refreshTrigger = homeRefreshTrigger,
                onBack = ::popOrExit,
                onOpenEpisode = { ep -> navStack.add(EmbyNavRoute.Detail(ep)) }
            )
        }

        is EmbyNavRoute.Detail -> {
            EmbyDetailScreen(
                previewItem = currentRoute.item,
                baseUrl = effectiveBaseUrl,
                authConfig = authConfig,
                apiClient = apiClient,
                refreshTrigger = homeRefreshTrigger,
                onBack = ::popOrExit,
                onPlay = ::startPlayback
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmbyLoginScreen(
    defaultServerUrl: String,
    savedTailscaleUrl: String,
    apiClient: EmbyApiClient,
    credentialStore: EmbyCredentialStore,
    onOpenWebViewFallback: () -> Unit,
    onExit: () -> Unit
) {
    var serverUrl by remember(defaultServerUrl) { mutableStateOf(defaultServerUrl) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Emby 로그인") },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "런처로 돌아가기"
                        )
                    }
                },
                actions = {
                    OutlinedButton(
                        onClick = onOpenWebViewFallback,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Public,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("웹뷰", style = MaterialTheme.typography.labelMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Emby 전용 네이티브 플레이어",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "로그인하면 Unraid 트랜스코딩 없이 libmpv로 원본 Direct Play 재생과 이어보기 동기화를 사용할 수 있습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("Emby 서버 URL") },
                        placeholder = { Text("예: http://192.168.0.10:8096") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("사용자 아이디") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("비밀번호") },
                        singleLine = true,
                        visualTransformation = if (showPassword) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) {
                                        Icons.Filled.VisibilityOff
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                    contentDescription = "비밀번호 표시 전환"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (!errorMessage.isNullOrBlank()) {
                        Text(
                            text = errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = StatusRed
                        )
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                isLoading = true
                                errorMessage = null
                                val result = apiClient.authenticateByName(serverUrl, username, password)
                                result.onSuccess { login ->
                                    credentialStore.saveLoginSession(
                                        serverUrl = serverUrl,
                                        tailscaleServerUrl = savedTailscaleUrl,
                                        accessToken = login.accessToken,
                                        userId = login.userId,
                                        username = login.username
                                    )
                                }.onFailure { err ->
                                    errorMessage = err.localizedMessage ?: "Emby 로그인에 실패했습니다."
                                }
                                isLoading = false
                            }
                        },
                        enabled = !isLoading && serverUrl.isNotBlank() && username.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Login,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isLoading) "로그인 중..." else "Emby 로그인")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmbyHomeScreen(
    baseUrl: String,
    authConfig: EmbyAuthConfig,
    apiClient: EmbyApiClient,
    refreshTrigger: Int,
    onBackToLauncher: () -> Unit,
    onOpenWebViewFallback: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenLibrary: (EmbyLibrary) -> Unit,
    onOpenItem: (EmbyItem) -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var resumeItems by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
    var latestItems by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
    var libraries by remember { mutableStateOf<List<EmbyLibrary>>(emptyList()) }
    var manualReloadCount by remember { mutableStateOf(0) }

    LaunchedEffect(baseUrl, authConfig.accessToken, refreshTrigger, manualReloadCount) {
        isLoading = true
        errorMessage = null
        val resumeDeferred = async { apiClient.fetchResumeItems(baseUrl, authConfig.userId, authConfig.accessToken) }
        val latestDeferred = async { apiClient.fetchLatestItems(baseUrl, authConfig.userId, authConfig.accessToken) }
        val libsDeferred = async { apiClient.fetchLibraries(baseUrl, authConfig.userId, authConfig.accessToken) }

        val rRes = resumeDeferred.await()
        val lRes = latestDeferred.await()
        val vRes = libsDeferred.await()

        resumeItems = rRes.getOrDefault(emptyList())
        latestItems = lRes.getOrDefault(emptyList())
        libraries = vRes.getOrDefault(emptyList())

        val firstError = rRes.exceptionOrNull() ?: lRes.exceptionOrNull() ?: vRes.exceptionOrNull()
        if (firstError != null && resumeItems.isEmpty() && latestItems.isEmpty() && libraries.isEmpty()) {
            errorMessage = firstError.localizedMessage ?: "Emby 서버 데이터를 불러오지 못했습니다."
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Emby",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = authConfig.username.ifBlank { baseUrl },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackToLauncher) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "런처로 복귀"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(imageVector = Icons.Filled.Search, contentDescription = "검색")
                    }
                    IconButton(onClick = { manualReloadCount += 1 }) {
                        Icon(imageVector = Icons.Filled.Refresh, contentDescription = "새로고침")
                    }
                    FilledTonalButton(
                        onClick = onOpenWebViewFallback,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .height(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Public,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("웹뷰", style = MaterialTheme.typography.labelMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        when {
            isLoading && resumeItems.isEmpty() && latestItems.isEmpty() && libraries.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            errorMessage != null && resumeItems.isEmpty() && latestItems.isEmpty() && libraries.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Emby 연결 오류", style = MaterialTheme.typography.titleMedium, color = StatusRed)
                            Text(errorMessage.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { manualReloadCount += 1 }) { Text("다시 시도") }
                                OutlinedButton(onClick = onOpenWebViewFallback) { Text("웹뷰로 열기") }
                            }
                        }
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // 1) 이어보기(Resume) 가로 목록
                    if (resumeItems.isNotEmpty()) {
                        item(key = "emby_resume") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                EmbySectionTitle("이어보기")
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(resumeItems, key = { "res_${it.id}" }) { item ->
                                        EmbyResumeCard(item = item, onClick = { onOpenItem(item) })
                                    }
                                }
                            }
                        }
                    }

                    // 2) 라이브러리 목록
                    if (libraries.isNotEmpty()) {
                        item(key = "emby_libraries") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                EmbySectionTitle("내 라이브러리")
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(libraries, key = { "lib_${it.id}" }) { lib ->
                                        EmbyLibraryCard(library = lib, onClick = { onOpenLibrary(lib) })
                                    }
                                }
                            }
                        }
                    }

                    // 3) 최근 추가된 항목(Latest)
                    if (latestItems.isNotEmpty()) {
                        item(key = "emby_latest") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                EmbySectionTitle("최근 추가된 항목")
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(latestItems, key = { "lat_${it.id}" }) { item ->
                                        EmbyPosterCard(
                                            item = item,
                                            onClick = { onOpenItem(item) },
                                            modifier = Modifier.width(122.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item(key = "emby_home_bottom") {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmbyLibraryGridScreen(
    library: EmbyLibrary,
    baseUrl: String,
    authConfig: EmbyAuthConfig,
    apiClient: EmbyApiClient,
    onSelectSortOption: (EmbySortOption) -> Unit,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenItem: (EmbyItem) -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var items by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }

    val sortOption = authConfig.librarySortOption

    LaunchedEffect(library.id, baseUrl, sortOption) {
        isLoading = true
        errorMessage = null
        val res = apiClient.fetchLibraryItems(
            baseUrl = baseUrl,
            userId = authConfig.userId,
            accessToken = authConfig.accessToken,
            libraryId = library.id,
            sortOption = sortOption
        )
        res.onSuccess { items = it }
            .onFailure { errorMessage = it.localizedMessage ?: "라이브러리를 불러오지 못했습니다." }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = library.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "정렬: ${sortOption.labelKo}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Sort,
                                contentDescription = "정렬 변경"
                            )
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            EmbySortOption.entries.forEach { option ->
                                val selected = option == sortOption
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = option.labelKo,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            }
                                        )
                                    },
                                    trailingIcon = if (selected) {
                                        {
                                            Icon(
                                                imageVector = Icons.Filled.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        showSortMenu = false
                                        onSelectSortOption(option)
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "검색")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        when {
            isLoading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            errorMessage != null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) { Text(errorMessage.orEmpty(), color = StatusRed) }

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 112.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                gridItems(items, key = { it.id }) { item ->
                    EmbyPosterCard(item = item, onClick = { onOpenItem(item) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmbySearchScreen(
    baseUrl: String,
    authConfig: EmbyAuthConfig,
    apiClient: EmbyApiClient,
    onBack: () -> Unit,
    onOpenItem: (EmbyItem) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
    var searchedOnce by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun executeSearch() {
        if (query.isBlank()) return
        scope.launch {
            isSearching = true
            searchedOnce = true
            val res = apiClient.searchItems(baseUrl, authConfig.userId, authConfig.accessToken, query)
            results = res.getOrDefault(emptyList())
            isSearching = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Emby 검색") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("영화, 시리즈, 에피소드 검색") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { executeSearch() }),
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { executeSearch() }, enabled = query.isNotBlank() && !isSearching) {
                    Text("검색")
                }
            }

            when {
                isSearching -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                searchedOnce && results.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("검색 결과가 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 112.dp),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    gridItems(results, key = { it.id }) { item ->
                        EmbyPosterCard(item = item, onClick = { onOpenItem(item) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmbySeriesScreen(
    seriesItem: EmbyItem,
    baseUrl: String,
    authConfig: EmbyAuthConfig,
    apiClient: EmbyApiClient,
    refreshTrigger: Int,
    onBack: () -> Unit,
    onOpenEpisode: (EmbyItem) -> Unit
) {
    var seasons by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
    var selectedSeasonId by remember { mutableStateOf<String?>(null) }
    var episodes by remember { mutableStateOf<List<EmbyItem>>(emptyList()) }
    var reverseEpisodeOrder by remember { mutableStateOf(false) }
    var isLoadingSeasons by remember { mutableStateOf(true) }
    var isLoadingEpisodes by remember { mutableStateOf(false) }

    val displayedEpisodes = remember(episodes, reverseEpisodeOrder) {
        if (reverseEpisodeOrder) episodes.reversed() else episodes
    }

    LaunchedEffect(seriesItem.id, baseUrl) {
        isLoadingSeasons = true
        val res = apiClient.fetchSeasons(baseUrl, authConfig.userId, authConfig.accessToken, seriesItem.id)
        seasons = res.getOrDefault(emptyList())
        if (selectedSeasonId == null) {
            selectedSeasonId = seasons.firstOrNull()?.id
        }
        isLoadingSeasons = false
    }

    LaunchedEffect(selectedSeasonId, refreshTrigger) {
        val seasonId = selectedSeasonId ?: return@LaunchedEffect
        isLoadingEpisodes = true
        val res = apiClient.fetchEpisodes(
            baseUrl = baseUrl,
            userId = authConfig.userId,
            accessToken = authConfig.accessToken,
            seriesId = seriesItem.id,
            seasonId = seasonId
        )
        episodes = res.getOrDefault(emptyList())
        isLoadingEpisodes = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = seriesItem.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = { reverseEpisodeOrder = !reverseEpisodeOrder },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .height(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SwapVert,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (reverseEpisodeOrder) "최신화부터" else "1화부터",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 시리즈 상단 배경 + 줄거리
            item(key = "series_hero") {
                Column {
                    if (!seriesItem.backdropImageUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(seriesItem.backdropImageUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = seriesItem.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(190.dp)
                        )
                    }
                    if (seriesItem.overview.isNotBlank()) {
                        Text(
                            text = seriesItem.overview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            }

            // 시즌 선택 칩
            if (isLoadingSeasons) {
                item(key = "seasons_loading") {
                    Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (seasons.isNotEmpty()) {
                item(key = "seasons_row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(seasons, key = { it.id }) { season ->
                            FilterChip(
                                selected = season.id == selectedSeasonId,
                                onClick = { selectedSeasonId = season.id },
                                label = { Text(season.name) }
                            )
                        }
                    }
                }
            }

            // 에피소드 목록
            if (isLoadingEpisodes) {
                item(key = "eps_loading") {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                items(displayedEpisodes, key = { it.id }) { ep ->
                    EmbyEpisodeRowCard(
                        episode = ep,
                        onClick = { onOpenEpisode(ep) },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmbyDetailScreen(
    previewItem: EmbyItem,
    baseUrl: String,
    authConfig: EmbyAuthConfig,
    apiClient: EmbyApiClient,
    refreshTrigger: Int,
    onBack: () -> Unit,
    onPlay: (item: EmbyItem, resume: Boolean) -> Unit
) {
    var detailItem by remember(previewItem.id) { mutableStateOf(previewItem) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(previewItem.id, baseUrl, refreshTrigger) {
        isLoading = true
        val res = apiClient.fetchItemDetail(baseUrl, authConfig.userId, authConfig.accessToken, previewItem.id)
        res.onSuccess { detailItem = it }
        isLoading = false
    }

    val primarySource = detailItem.mediaSources.firstOrNull()
    val hasResumePoint = detailItem.playbackPositionTicks > TICKS_PER_SECOND * 5L

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = detailItem.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 배경 + 포스터 헤더
            item(key = "detail_hero") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                ) {
                    if (!detailItem.backdropImageUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(detailItem.backdropImageUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = detailItem.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0x33000000), MaterialTheme.colorScheme.background)
                                )
                            )
                    )
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (!detailItem.primaryImageUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(detailItem.primaryImageUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = detailItem.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(96.dp)
                                    .aspectRatio(2f / 3f)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = detailItem.name,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (detailItem.displaySubtitle.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = detailItem.displaySubtitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (detailItem.genres.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = detailItem.genres.take(4).joinToString(" · "),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            // 재생 / 이어보기 버튼
            if (detailItem.isPlayable) {
                item(key = "detail_play_buttons") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (hasResumePoint) {
                            val resumeMin = detailItem.playbackPositionTicks / TICKS_PER_MINUTE
                            val resumeSec = (detailItem.playbackPositionTicks / TICKS_PER_SECOND) % 60
                            val resumeText = String.format(Locale.getDefault(), "%d:%02d부터 이어보기", resumeMin, resumeSec)
                            Button(
                                onClick = { onPlay(detailItem, true) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(resumeText)
                            }
                            OutlinedButton(
                                onClick = { onPlay(detailItem, false) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("처음부터 재생 (Direct Play)")
                            }
                        } else {
                            Button(
                                onClick = { onPlay(detailItem, false) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("재생 (libmpv Direct Play)")
                            }
                        }
                    }
                }
            }

            // 줄거리
            if (detailItem.overview.isNotBlank()) {
                item(key = "detail_overview") {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("줄거리", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = detailItem.overview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 오디오 / 자막 / 원본 스트림 트랙 정보
            if (primarySource != null) {
                item(key = "detail_tracks") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val containerInfo = buildList {
                                if (primarySource.container.isNotBlank()) add(primarySource.container.uppercase())
                                primarySource.videoStreams.firstOrNull()?.let { add(it.label) }
                            }.joinToString(" · ")
                            Text(
                                text = "미디어 정보 (원본 Direct Play)",
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (containerInfo.isNotBlank()) {
                                Text(
                                    text = containerInfo,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            if (primarySource.audioStreams.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.Audiotrack,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("오디오 트랙", style = MaterialTheme.typography.labelMedium)
                                }
                                primarySource.audioStreams.forEach { stream ->
                                    Text(
                                        text = "• ${stream.label}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (primarySource.subtitleStreams.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.Subtitles,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("자막 트랙", style = MaterialTheme.typography.labelMedium)
                                }
                                primarySource.subtitleStreams.forEach { stream ->
                                    Text(
                                        text = "• ${stream.label}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (isLoading) {
                item(key = "detail_loading_tracks") {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmbySectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@Composable
private fun EmbyResumeCard(
    item: EmbyItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(210.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(118.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                val img = item.backdropImageUrl ?: item.primaryImageUrl
                if (!img.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(img)
                            .crossfade(true)
                            .build(),
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (item.playbackProgressFraction > 0f) {
                    LinearProgressIndicator(
                        progress = { item.playbackProgressFraction },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color(0x88000000)
                    )
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.displaySubtitle.isNotBlank()) {
                    Text(
                        text = item.displaySubtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun EmbyLibraryCard(
    library: EmbyLibrary,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(150.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (!library.imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(library.imageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = library.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.VideoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Text(
                text = library.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun EmbyPosterCard(
    item: EmbyItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (!item.primaryImageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(item.primaryImageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
            }
            if (item.played) {
                Surface(
                    color = StatusGreen.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "시청 완료",
                        tint = Color.Black,
                        modifier = Modifier
                            .padding(2.dp)
                            .size(14.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = item.name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (item.productionYear != null) {
            Text(
                text = item.productionYear.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmbyEpisodeRowCard(
    episode: EmbyItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(112.dp)
                    .height(66.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                val img = episode.primaryImageUrl ?: episode.backdropImageUrl
                if (!img.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(img)
                            .crossfade(true)
                            .build(),
                        contentDescription = episode.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (episode.playbackProgressFraction > 0f) {
                    LinearProgressIndicator(
                        progress = { episode.playbackProgressFraction },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(3.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                val epPrefix = episode.episodeNumber?.let { "${it}화 · " }.orEmpty()
                Text(
                    text = "$epPrefix${episode.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (episode.overview.isNotBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = episode.overview,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
