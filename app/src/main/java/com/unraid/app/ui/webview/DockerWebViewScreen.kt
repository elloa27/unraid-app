package com.unraid.app.ui.webview

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.unraid.app.data.model.DockerContainerItem
import com.unraid.app.data.model.DockerServiceBookmark
import com.unraid.app.data.model.WebServiceSource
import com.unraid.app.plugin.InstalledPlugin
import com.unraid.app.plugin.LauncherService
import com.unraid.app.ui.theme.StatusGreen
import com.unraid.app.ui.theme.StatusRed
import kotlin.math.abs
import kotlin.math.roundToInt

private val StoppedStatusGray = Color(0xFF64748B)

private val FallbackIconPalette = listOf(
    Color(0xFFEA580C),
    Color(0xFF2563EB),
    Color(0xFF059669),
    Color(0xFF7C3AED),
    Color(0xFFDB2777),
    Color(0xFF0891B2),
    Color(0xFFD97706),
    Color(0xFF4F46E5)
)

/**
 * 100% 전체 화면(PWA 모드) 도커 웹뷰 화면.
 * - 앱 상단 바 및 하단 내비게이션 바 없이 화면 전체를 WebView로 채웁니다.
 * - 우측 가장자리에 위아래 드래그 이동 가능한 소형 반투명 플로팅 메뉴 버튼만 띄웁니다.
 * - WebViewPoolManager 메모리 캐싱과 안드로이드 뒤로가기(canGoBack)를 완벽히 유지합니다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenDockerWebView(
    activeService: LauncherService,
    quickSwitchServices: List<LauncherService>,
    installedPlugins: List<InstalledPlugin>,
    webViewPoolManager: WebViewPoolManager,
    onSelectService: (LauncherService) -> Unit,
    onOpenPlugin: ((LauncherService) -> Unit)? = null,
    onNavigateToDashboard: () -> Unit,
    onNavigateToLauncher: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val metaMap by webViewPoolManager.metaByServiceId.collectAsStateWithLifecycle()
    val cachedIds by webViewPoolManager.cachedServiceIds.collectAsStateWithLifecycle()
    val activeMeta = metaMap[activeService.id] ?: WebViewUiMeta()
    val matchingPlugin = remember(activeService, installedPlugins) {
        installedPlugins.firstOrNull { it.matches(activeService) }
    }

    var showQuickMenu by remember { mutableStateOf(false) }

    // 뒤로가기 버튼: 웹뷰 내 이전 페이지가 있으면 우선 뒤로가기, 최상단이면 런처로 안전 복귀 (웹뷰 캐시는 유지)
    BackHandler(enabled = true) {
        val wentBack = webViewPoolManager.goBack(activeService.id)
        if (!wentBack) {
            onNavigateToLauncher()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
    ) {
        val density = LocalDensity.current
        val maxHeightPx = with(density) { maxHeight.toPx() }
        val buttonHeightPx = with(density) { 52.dp.toPx() }
        val verticalMarginPx = with(density) { 24.dp.toPx() }
        val minY = verticalMarginPx
        val maxY = (maxHeightPx - buttonHeightPx - verticalMarginPx).coerceAtLeast(minY)

        var handleOffsetY by rememberSaveable(maxHeightPx) {
            mutableStateOf((maxHeightPx * 0.42f).coerceIn(minY, maxY))
        }

        // 100% 전체 화면 캐싱 WebView
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { container ->
                val webView = webViewPoolManager.getOrCreate(container.context, activeService)
                val currentlyAttached = container.getChildAt(0)
                if (currentlyAttached !== webView) {
                    container.removeAllViews()
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    container.addView(
                        webView,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }
            }
        )

        // 페이지 로딩 중에만 최상단에 얇은 진행률 바 표시
        if (activeMeta.isLoading && activeMeta.progress in 1..99) {
            LinearProgressIndicator(
                progress = { activeMeta.progress / 100f },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(2.dp)
            )
        }

        // 우측 가장자리 소형 반투명 드래그 가능 플로팅 메뉴 버튼
        Surface(
            onClick = { showQuickMenu = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, handleOffsetY.roundToInt()) }
                .alpha(0.58f)
                .pointerInput(minY, maxY) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        handleOffsetY = (handleOffsetY + dragAmount.y).coerceIn(minY, maxY)
                    }
                },
            shape = RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            ),
            shadowElevation = 6.dp
        ) {
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(52.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = "빠른 전환 메뉴 열기",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    // 플로팅 버튼 클릭 시 열리는 빠른 전환 시트
    if (showQuickMenu) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showQuickMenu = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            QuickSwitcherSheetContent(
                activeService = activeService,
                canGoBack = activeMeta.canGoBack,
                matchingPluginName = matchingPlugin?.name,
                allServices = quickSwitchServices,
                installedPlugins = installedPlugins,
                cachedIds = cachedIds,
                onBackClick = {
                    showQuickMenu = false
                    webViewPoolManager.goBack(activeService.id)
                },
                onHomeClick = {
                    showQuickMenu = false
                    webViewPoolManager.goHome(activeService)
                },
                onReloadClick = {
                    showQuickMenu = false
                    webViewPoolManager.reload(activeService.id)
                },
                onOpenPlugin = if (matchingPlugin != null && onOpenPlugin != null) {
                    {
                        showQuickMenu = false
                        onOpenPlugin(activeService)
                    }
                } else {
                    null
                },
                onNavigateToDashboard = {
                    showQuickMenu = false
                    onNavigateToDashboard()
                },
                onNavigateToLauncher = {
                    showQuickMenu = false
                    onNavigateToLauncher()
                },
                onNavigateToSettings = {
                    showQuickMenu = false
                    onNavigateToSettings()
                },
                onSelectService = { target ->
                    showQuickMenu = false
                    onSelectService(target)
                }
            )
        }
    }
}

@Composable
private fun QuickSwitcherSheetContent(
    activeService: LauncherService,
    canGoBack: Boolean,
    matchingPluginName: String?,
    allServices: List<LauncherService>,
    installedPlugins: List<InstalledPlugin>,
    cachedIds: Set<String>,
    onBackClick: () -> Unit,
    onHomeClick: () -> Unit,
    onReloadClick: () -> Unit,
    onOpenPlugin: (() -> Unit)?,
    onNavigateToDashboard: () -> Unit,
    onNavigateToLauncher: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onSelectService: (LauncherService) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppServiceIcon(
                    name = activeService.name,
                    iconUrl = activeService.iconUrl,
                    isRunning = activeService.isRunning,
                    nativeBadgeLabel = null,
                    size = 38.dp,
                    showStatusDot = false
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeService.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = activeService.url,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBackClick, enabled = canGoBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "뒤로가기"
                    )
                }
                IconButton(onClick = onHomeClick) {
                    Icon(
                        imageVector = Icons.Filled.Home,
                        contentDescription = "서비스 홈"
                    )
                }
                IconButton(onClick = onReloadClick) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "새로고침"
                    )
                }
            }
        }

        if (matchingPluginName != null && onOpenPlugin != null) {
            FilledTonalButton(
                onClick = onOpenPlugin,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("플러그인으로 전환 ($matchingPluginName)")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickNavTile(
                icon = Icons.Filled.Dashboard,
                label = "Unraid",
                onClick = onNavigateToDashboard,
                modifier = Modifier.weight(1f)
            )
            QuickNavTile(
                icon = Icons.Filled.Apps,
                label = "앱 런처",
                onClick = onNavigateToLauncher,
                modifier = Modifier.weight(1f)
            )
            QuickNavTile(
                icon = Icons.Filled.Settings,
                label = "설정",
                onClick = onNavigateToSettings,
                modifier = Modifier.weight(1f)
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

        Text(
            text = "앱 빠른 전환 (${allServices.size})",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(allServices, key = { it.id }) { service ->
                val isCurrent = service.id == activeService.id
                val isCached = service.id in cachedIds
                val plugin = remember(service, installedPlugins) {
                    installedPlugins.firstOrNull { it.matches(service) }
                }
                Surface(
                    onClick = { onSelectService(service) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    border = if (isCurrent) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    } else {
                        null
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppServiceIcon(
                            name = service.name,
                            iconUrl = service.iconUrl,
                            isRunning = service.isRunning,
                            nativeBadgeLabel = null,
                            size = 34.dp,
                            showStatusDot = true
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = service.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (isCurrent) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isCached && !isCurrent) {
                                    ServiceTagBadge(text = "열림", color = StatusGreen)
                                }
                                if (plugin != null) {
                                    ServiceTagBadge(
                                        text = "Plugin",
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                text = service.url,
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
    }
}

@Composable
private fun QuickNavTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 스마트폰 홈 화면 스타일의 아이콘 그리드 도커 앱 런처 화면.
 * - 사용자가 선택한 Unraid 도커 앱과 수동 등록 웹 앱만 3~4열 아이콘 그리드로 깔끔하게 표시합니다.
 * - 아이콘 탭: 플러그인(전용 UI 우선) 또는 100% 전체 화면 웹뷰 실행
 * - 아이콘 길게 누르기: 웹뷰/전용 UI 선택, 이름·URL 편집, 런처에서 숨기기
 * - 상단 '앱 편집 / 추가' 버튼: 자동 감지된 도커 전체 켜기/끄기 및 수동 웹 앱 추가/관리
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DockerWebLauncherScreen(
    launcherServices: List<LauncherService>,
    allDetectedContainers: List<DockerContainerItem>,
    pinnedAutoDockerIds: Set<String>,
    manualBookmarks: List<DockerServiceBookmark>,
    installedPlugins: List<InstalledPlugin>,
    webViewPoolManager: WebViewPoolManager,
    isRefreshingDashboard: Boolean,
    onLaunchDefault: (LauncherService) -> Unit,
    onLaunchWebView: (LauncherService) -> Unit,
    onLaunchPlugin: (LauncherService) -> Unit,
    onToggleAutoDockerPinned: (serviceId: String, pinned: Boolean) -> Unit,
    onEditService: (service: LauncherService, newName: String, newUrl: String) -> Unit,
    onTogglePreferNativeUi: (service: LauncherService, preferNative: Boolean) -> Unit,
    onAddManualService: (name: String, url: String) -> Unit,
    onDeleteManualService: (serviceId: String) -> Unit,
    onRefreshAutoDetected: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cachedIds by webViewPoolManager.cachedServiceIds.collectAsStateWithLifecycle()
    val cachedServices = remember(launcherServices, cachedIds) {
        launcherServices.filter { it.id in cachedIds }
    }

    var showManageSheet by remember { mutableStateOf(false) }
    var longPressedService by remember { mutableStateOf<LauncherService?>(null) }
    var editingService by remember { mutableStateOf<LauncherService?>(null) }
    var showAddManualDialog by remember { mutableStateOf(false) }
    var addPrefillName by remember { mutableStateOf("") }
    var addPrefillUrl by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "도커 웹뷰",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "탭하여 실행 · 길게 눌러 편집",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = { showManageSheet = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "앱 편집 / 추가",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    IconButton(
                        onClick = onRefreshAutoDetected,
                        enabled = !isRefreshingDashboard
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Unraid 도커 목록 새로고침"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        if (launcherServices.isEmpty()) {
            val detectedWebUiCount = allDetectedContainers.count { it.hasWebUi }
            EmptyLauncherGridCard(
                detectedWebUiCount = detectedWebUiCount,
                onOpenManageSheet = { showManageSheet = true },
                onRefreshAutoDetected = onRefreshAutoDetected,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // 메모리에 캐싱되어 새로고침 없이 바로 복귀 가능한 앱 칩
                if (cachedServices.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "열려 있는 웹뷰 (바로 복귀)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(cachedServices, key = { "cached_${it.id}" }) { service ->
                                FilledTonalButton(
                                    onClick = { onLaunchWebView(service) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(StatusGreen)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = service.name,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                }

                // 스마트폰 홈 화면 스타일 3~4열 아이콘 그리드
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 86.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    gridItems(launcherServices, key = { it.id }) { service ->
                        val matchingPlugin = remember(service, installedPlugins) {
                            installedPlugins.firstOrNull { it.matches(service) }
                        }
                        LauncherAppIconTile(
                            service = service,
                            nativeBadgeLabel = if (matchingPlugin != null) "Plugin" else null,
                            onClick = { onLaunchDefault(service) },
                            onLongClick = { longPressedService = service }
                        )
                    }
                }
            }
        }
    }

    // 아이콘 길게 누르기 빠른 액션 시트
    longPressedService?.let { target ->
        val matchingPlugin = remember(target, installedPlugins) {
            installedPlugins.firstOrNull { it.matches(target) }
        }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { longPressedService = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    AppServiceIcon(
                        name = target.name,
                        iconUrl = target.iconUrl,
                        isRunning = target.isRunning,
                        nativeBadgeLabel = if (matchingPlugin != null) "Plugin" else null,
                        size = 44.dp,
                        showStatusDot = true
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = target.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = target.url,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

                if (matchingPlugin != null) {
                    ActionSheetRow(
                        icon = Icons.Filled.AutoAwesome,
                        label = "플러그인으로 열기 (${matchingPlugin.name})",
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = {
                            longPressedService = null
                            onLaunchPlugin(target)
                        }
                    )
                }

                ActionSheetRow(
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    label = "일반 웹뷰로 열기",
                    onClick = {
                        longPressedService = null
                        onLaunchWebView(target)
                    }
                )

                if (matchingPlugin != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                val next = !target.preferNativeUi
                                onTogglePreferNativeUi(target, next)
                                longPressedService = target.copy(preferNativeUi = next)
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "탭 시 외부 플러그인 우선 사용",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (target.preferNativeUi) {
                                    "현재: 설치된 플러그인으로 바로 열림"
                                } else {
                                    "현재: 일반 웹뷰로 바로 열림"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = target.preferNativeUi,
                            onCheckedChange = { next ->
                                onTogglePreferNativeUi(target, next)
                                longPressedService = target.copy(preferNativeUi = next)
                            }
                        )
                    }
                }

                ActionSheetRow(
                    icon = Icons.Filled.Edit,
                    label = "이름 · URL 편집",
                    onClick = {
                        longPressedService = null
                        editingService = target
                    }
                )

                ActionSheetRow(
                    icon = if (target.source == WebServiceSource.MANUAL) {
                        Icons.Filled.Delete
                    } else {
                        Icons.Filled.VisibilityOff
                    },
                    label = if (target.source == WebServiceSource.MANUAL) {
                        "런처에서 삭제"
                    } else {
                        "런처에서 숨기기"
                    },
                    tint = StatusRed,
                    onClick = {
                        longPressedService = null
                        if (target.source == WebServiceSource.MANUAL) {
                            onDeleteManualService(target.id)
                        } else {
                            onToggleAutoDockerPinned(target.id, false)
                        }
                    }
                )
            }
        }
    }

    // '앱 편집 / 추가' 관리 시트
    if (showManageSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showManageSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            LauncherManageSheetContent(
                allDetectedContainers = allDetectedContainers,
                pinnedAutoDockerIds = pinnedAutoDockerIds,
                manualBookmarks = manualBookmarks,
                onToggleAutoDockerPinned = onToggleAutoDockerPinned,
                onRequestAddManual = { prefillName, prefillUrl ->
                    addPrefillName = prefillName
                    addPrefillUrl = prefillUrl
                    showAddManualDialog = true
                },
                onEditManual = { bookmark ->
                    editingService = LauncherService(
                        id = bookmark.id,
                        name = bookmark.name,
                        url = bookmark.url,
                        source = WebServiceSource.MANUAL,
                        isRunning = true,
                        iconUrl = bookmark.iconUrl,
                        preferNativeUi = bookmark.preferNativeUi
                    )
                },
                onDeleteManual = onDeleteManualService,
                onRefreshAutoDetected = onRefreshAutoDetected,
                onClose = { showManageSheet = false }
            )
        }
    }

    // 이름·URL 편집 다이얼로그 (자동 감지 커스텀 / 수동 앱 공용)
    editingService?.let { target ->
        LauncherServiceEditDialog(
            title = "앱 이름 · URL 편집",
            initialName = target.name,
            initialUrl = target.url,
            confirmLabel = "저장",
            onDismiss = { editingService = null },
            onConfirm = { newName, newUrl ->
                onEditService(target, newName, newUrl)
                editingService = null
            }
        )
    }

    // 수동 웹 앱 추가 다이얼로그
    if (showAddManualDialog) {
        LauncherServiceEditDialog(
            title = "수동 웹 앱 추가",
            initialName = addPrefillName,
            initialUrl = addPrefillUrl,
            confirmLabel = "추가",
            onDismiss = { showAddManualDialog = false },
            onConfirm = { name, url ->
                onAddManualService(name, url)
                showAddManualDialog = false
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LauncherAppIconTile(
    service: LauncherService,
    nativeBadgeLabel: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AppServiceIcon(
            name = service.name,
            iconUrl = service.iconUrl,
            isRunning = service.isRunning,
            nativeBadgeLabel = nativeBadgeLabel,
            size = 62.dp,
            showStatusDot = true
        )
        Spacer(modifier = Modifier.height(7.dp))
        Text(
            text = service.name,
            style = MaterialTheme.typography.labelMedium,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

/**
 * 둥근 사각형 도커 앱 아이콘 컴포넌트.
 * - UnraidApp의 전역 Coil ImageLoader(Tailscale 프록시 + 사설 SSL 허용 + SVG 지원)로 아이콘을 로드합니다.
 * - 아이콘 URL이 없거나 로드 실패 시 앱 이름 첫 글자 기반 기본 아이콘을 표시합니다.
 * - 우측 하단 실행 상태 점(실행 중: 초록 / 중지: 회색), 우측 상단 전용 UI 플러그인 배지 지원.
 */
@Composable
fun AppServiceIcon(
    name: String,
    iconUrl: String?,
    isRunning: Boolean,
    nativeBadgeLabel: String?,
    size: Dp = 60.dp,
    showStatusDot: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cornerRadius = if (size >= 52.dp) 16.dp else 10.dp
    val fallbackColor = remember(name) {
        FallbackIconPalette[abs(name.lowercase().hashCode()) % FallbackIconPalette.size]
    }
    val initialChar = remember(name) {
        name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    }

    Box(modifier = modifier.size(size)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(cornerRadius),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
            )
        ) {
            if (!iconUrl.isNullOrBlank()) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(iconUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = {
                        MonogramFallbackBox(
                            initial = initialChar,
                            backgroundColor = fallbackColor,
                            size = size
                        )
                    },
                    error = {
                        MonogramFallbackBox(
                            initial = initialChar,
                            backgroundColor = fallbackColor,
                            size = size
                        )
                    }
                )
            } else {
                MonogramFallbackBox(
                    initial = initialChar,
                    backgroundColor = fallbackColor,
                    size = size
                )
            }
        }

        // 전용 네이티브 UI 플러그인이 연결된 앱의 우측 상단 배지
        if (!nativeBadgeLabel.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
            ) {
                Text(
                    text = nativeBadgeLabel,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        // 우측 하단 작은 실행 상태 점 (실행 중: 초록, 중지: 회색)
        if (showStatusDot) {
            val dotSize = if (size >= 52.dp) 13.dp else 10.dp
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp, y = 2.dp)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(if (isRunning) StatusGreen else StoppedStatusGray)
                    .border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.background,
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
private fun MonogramFallbackBox(
    initial: String,
    backgroundColor: Color,
    size: Dp
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor.copy(alpha = 0.88f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            fontSize = if (size >= 52.dp) 24.sp else 15.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@Composable
private fun LauncherManageSheetContent(
    allDetectedContainers: List<DockerContainerItem>,
    pinnedAutoDockerIds: Set<String>,
    manualBookmarks: List<DockerServiceBookmark>,
    onToggleAutoDockerPinned: (serviceId: String, pinned: Boolean) -> Unit,
    onRequestAddManual: (prefillName: String, prefillUrl: String) -> Unit,
    onEditManual: (DockerServiceBookmark) -> Unit,
    onDeleteManual: (String) -> Unit,
    onRefreshAutoDetected: () -> Unit,
    onClose: () -> Unit
) {
    val webUiContainers = remember(allDetectedContainers) {
        allDetectedContainers.filter { it.hasWebUi }
    }
    val nonWebUiContainers = remember(allDetectedContainers) {
        allDetectedContainers.filterNot { it.hasWebUi }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "런처 앱 편집 / 추가",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "홈 화면에 띄울 도커 앱만 스위치로 선택하세요",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onClose) {
                Text("완료")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onRequestAddManual("", "") },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("수동 웹 앱 추가")
            }
            OutlinedButton(onClick = onRefreshAutoDetected) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("새로고침")
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 440.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1) Unraid 자동 감지 웹 UI 도커 목록
            item(key = "manage_auto_header") {
                Text(
                    text = "Unraid 자동 감지 도커 (${webUiContainers.size}개)",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (webUiContainers.isEmpty()) {
                item(key = "manage_auto_empty") {
                    Text(
                        text = "자동 감지된 웹 UI 도커가 없습니다. Unraid 서버 연결 상태를 확인하거나 새로고침을 눌러 주세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(webUiContainers, key = { "auto_opt_${it.autoServiceId}" }) { container ->
                    val isPinned = container.autoServiceId in pinnedAutoDockerIds
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onToggleAutoDockerPinned(container.autoServiceId, !isPinned)
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppServiceIcon(
                                name = container.name,
                                iconUrl = container.iconUrl,
                                isRunning = container.isRunning,
                                nativeBadgeLabel = null,
                                size = 38.dp,
                                showStatusDot = true
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = container.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = container.webUiUrl.orEmpty(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = isPinned,
                                onCheckedChange = { checked ->
                                    onToggleAutoDockerPinned(container.autoServiceId, checked)
                                }
                            )
                        }
                    }
                }
            }

            // 2) 수동 추가 웹 앱 목록
            item(key = "manage_manual_header") {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "수동 등록 / 커스텀 웹 앱 (${manualBookmarks.size}개)",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (manualBookmarks.isEmpty()) {
                item(key = "manage_manual_empty") {
                    Text(
                        text = "직접 추가한 외부/커스텀 웹 앱이 없습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(manualBookmarks, key = { "manual_opt_${it.id}" }) { bookmark ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppServiceIcon(
                                name = bookmark.name,
                                iconUrl = bookmark.iconUrl,
                                isRunning = true,
                                nativeBadgeLabel = null,
                                size = 36.dp,
                                showStatusDot = false
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = bookmark.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = bookmark.url,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { onEditManual(bookmark) }) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = "수정",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { onDeleteManual(bookmark.id) }) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "삭제",
                                    tint = StatusRed
                                )
                            }
                        }
                    }
                }
            }

            // 3) 웹 UI 라벨이 없는 컨테이너(DB, 백그라운드 데몬 등)는 수동 URL 지정 옵션으로만 분리 표시
            if (nonWebUiContainers.isNotEmpty()) {
                item(key = "manage_non_webui_header") {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "웹 UI 미감지 도커 (${nonWebUiContainers.size}개 · DB/데몬 등)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(nonWebUiContainers, key = { "noweb_${it.id}" }) { container ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = container.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = container.image.ifBlank { "웹 UI 라벨 없음" },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(
                                onClick = { onRequestAddManual(container.name, "") }
                            ) {
                                Text("URL 지정")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionSheetRow(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = tint
        )
    }
}

@Composable
private fun LauncherServiceEditDialog(
    title: String,
    initialName: String,
    initialUrl: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("앱 이름") },
                    placeholder = { Text("예: Kavita") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("웹 URL") },
                    placeholder = { Text("예: http://192.168.0.10:5000") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, url) },
                enabled = name.isNotBlank() && url.isNotBlank()
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}

@Composable
private fun ServiceTagBadge(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

@Composable
private fun EmptyLauncherGridCard(
    detectedWebUiCount: Int,
    onOpenManageSheet: () -> Unit,
    onRefreshAutoDetected: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "런처에 표시할 앱을 선택해 주세요",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                val description = if (detectedWebUiCount > 0) {
                    "Unraid에서 웹 UI 도커 ${detectedWebUiCount}개가 자동 감지되었습니다. [앱 편집 / 추가]를 눌러 홈 화면에 띄울 앱만 골라 배치하세요."
                } else {
                    "[앱 편집 / 추가]를 눌러 Unraid 자동 감지 도커를 선택하거나, 자주 쓰는 외부/커스텀 웹 앱을 직접 등록할 수 있습니다."
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onOpenManageSheet) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("앱 편집 / 추가")
                    }
                    OutlinedButton(onClick = onRefreshAutoDetected) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("새로고침")
                    }
                }
            }
        }
    }
}
