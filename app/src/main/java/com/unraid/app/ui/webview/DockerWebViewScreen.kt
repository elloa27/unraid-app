package com.unraid.app.ui.webview

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unraid.app.data.model.WebServiceItem
import com.unraid.app.data.model.WebServiceSource
import com.unraid.app.ui.theme.StatusGreen
import com.unraid.app.ui.theme.StatusRed
import kotlin.math.roundToInt

/**
 * 100% 전체 화면(PWA 모드) 도커 웹뷰 화면.
 * - 앱 상단 바 및 하단 내비게이션 바 없이 화면 전체를 WebView로 채웁니다.
 * - 우측 가장자리에 위아래 드래그 이동 가능한 소형 반투명 플로팅 메뉴 버튼만 띄웁니다.
 * - WebViewPoolManager 메모리 캐싱과 안드로이드 뒤로가기(canGoBack)를 완벽히 유지합니다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenDockerWebView(
    activeService: WebServiceItem,
    allServices: List<WebServiceItem>,
    webViewPoolManager: WebViewPoolManager,
    onSelectService: (WebServiceItem) -> Unit,
    onNavigateToDashboard: () -> Unit,
    onNavigateToLauncher: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val metaMap by webViewPoolManager.metaByServiceId.collectAsStateWithLifecycle()
    val cachedIds by webViewPoolManager.cachedServiceIds.collectAsStateWithLifecycle()
    val activeMeta = metaMap[activeService.id] ?: WebViewUiMeta()

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

        // 우측 가장자리 플로팅 버튼의 Y 위치 (기본값: 화면 세로 약 42% 지점, 드래그로 자유롭게 이동 가능)
        var handleOffsetY by rememberSaveable(maxHeightPx) {
            mutableFloatStateOf((maxHeightPx * 0.42f).coerceIn(minY, maxY))
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
                allServices = allServices,
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
    activeService: WebServiceItem,
    canGoBack: Boolean,
    allServices: List<WebServiceItem>,
    cachedIds: Set<String>,
    onBackClick: () -> Unit,
    onHomeClick: () -> Unit,
    onReloadClick: () -> Unit,
    onNavigateToDashboard: () -> Unit,
    onNavigateToLauncher: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onSelectService: (WebServiceItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 현재 열려 있는 웹뷰 정보 및 페이지 컨트롤
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBackClick,
                    enabled = canGoBack
                ) {
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

        // 앱 주요 화면 빠른 이동 버튼 3개
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
                label = "도커 목록",
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
            text = "도커 웹 서비스 전환 (${allServices.size})",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(allServices, key = { it.id }) { service ->
                val isCurrent = service.id == activeService.id
                val isCached = service.id in cachedIds
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
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (service.isRunning) StatusGreen else StatusRed)
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
                                    ServiceTagBadge(
                                        text = "열림",
                                        color = StatusGreen
                                    )
                                }
                                if (service.source == WebServiceSource.MANUAL) {
                                    ServiceTagBadge(
                                        text = "수동",
                                        color = MaterialTheme.colorScheme.secondary
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
 * '도커 웹뷰' 탭을 눌렀을 때 표시되는 도커 웹 앱 런처 화면.
 * - Unraid GraphQL API에서 자동 감지된 도커 웹 UI와 수동 등록 서비스를 한눈에 보여줍니다.
 * - 항목을 탭하면 즉시 100% 전체 화면(PWA 모드) 웹뷰로 진입합니다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DockerWebLauncherScreen(
    allServices: List<WebServiceItem>,
    webViewPoolManager: WebViewPoolManager,
    isRefreshingDashboard: Boolean,
    onOpenService: (WebServiceItem) -> Unit,
    onRefreshAutoDetected: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cachedIds by webViewPoolManager.cachedServiceIds.collectAsStateWithLifecycle()
    val cachedServices = remember(allServices, cachedIds) {
        allServices.filter { it.id in cachedIds }
    }
    val autoDetectedServices = remember(allServices) {
        allServices.filter { it.source == WebServiceSource.AUTO_DOCKER }
    }
    val manualServices = remember(allServices) {
        allServices.filter { it.source == WebServiceSource.MANUAL }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "도커 웹뷰 런처",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "선택 시 상·하단 바 없는 100% 전체 화면으로 열립니다",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onRefreshAutoDetected,
                        enabled = !isRefreshingDashboard
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Unraid 도커 목록 새로고침"
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "수동 서비스 설정"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        if (allServices.isEmpty()) {
            EmptyLauncherCard(
                onRefreshAutoDetected = onRefreshAutoDetected,
                onNavigateToSettings = onNavigateToSettings,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 메모리에 캐싱되어 바로 이어볼 수 있는 웹뷰 빠른 복귀 섹션
                if (cachedServices.isNotEmpty()) {
                    item(key = "cached_header") {
                        Text(
                            text = "열려 있는 웹뷰 (새로고침 없이 바로 복귀)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    item(key = "cached_row") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(cachedServices, key = { "cached_${it.id}" }) { service ->
                                FilledTonalButton(
                                    onClick = { onOpenService(service) },
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(StatusGreen)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = service.name)
                                }
                            }
                        }
                    }
                }

                // Unraid 자동 감지 도커 웹 UI 목록
                if (autoDetectedServices.isNotEmpty()) {
                    item(key = "auto_header") {
                        LauncherSectionHeader(
                            title = "Unraid 자동 감지 도커",
                            countText = "${autoDetectedServices.size}개"
                        )
                    }
                    items(autoDetectedServices, key = { it.id }) { service ->
                        LauncherServiceCard(
                            service = service,
                            isCached = service.id in cachedIds,
                            onClick = { onOpenService(service) }
                        )
                    }
                }

                // 설정 화면에서 수동 추가/커스텀한 서비스 목록
                if (manualServices.isNotEmpty()) {
                    item(key = "manual_header") {
                        LauncherSectionHeader(
                            title = "수동 등록 / 커스텀 서비스",
                            countText = "${manualServices.size}개"
                        )
                    }
                    items(manualServices, key = { it.id }) { service ->
                        LauncherServiceCard(
                            service = service,
                            isCached = service.id in cachedIds,
                            onClick = { onOpenService(service) }
                        )
                    }
                }

                item(key = "launcher_bottom_spacer") {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun LauncherSectionHeader(title: String, countText: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = countText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LauncherServiceCard(
    service: WebServiceItem,
    isCached: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Public,
                        contentDescription = null,
                        tint = if (service.isRunning) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (service.isRunning) StatusGreen else StatusRed)
                    )
                    Text(
                        text = service.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isCached) {
                        ServiceTagBadge(text = "열림", color = StatusGreen)
                    }
                    if (!service.isRunning) {
                        ServiceTagBadge(text = "중지됨", color = StatusRed)
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = service.url,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.Filled.OpenInNew,
                contentDescription = "전체 화면 열기",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
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
private fun EmptyLauncherCard(
    onRefreshAutoDetected: () -> Unit,
    onNavigateToSettings: () -> Unit,
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
                    text = "표시할 도커 웹 서비스가 없습니다",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Unraid 서버가 연결되어 있으면 웹 UI가 있는 도커를 자동으로 불러옵니다. 자동 감지가 되지 않는 서비스는 설정에서 직접 추가할 수 있습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onRefreshAutoDetected) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("자동 불러오기")
                    }
                    OutlinedButton(onClick = onNavigateToSettings) {
                        Text("수동 추가")
                    }
                }
            }
        }
    }
}
