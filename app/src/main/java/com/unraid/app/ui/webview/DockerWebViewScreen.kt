package com.unraid.app.ui.webview

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unraid.app.data.model.DockerServiceBookmark

/**
 * 도커 웹뷰(WebView) 통합 허브 화면.
 * - 주소창 없는 PWA 스타일의 전체 화면 제공
 * - 상단 컴팩트 드롭다운으로 등록된 도커 서비스(Kavita 등) 즉시 전환
 * - WebViewPoolManager로 인스턴스를 메모리에 캐싱하여 탭 이동 시 새로고침 방지
 * - 뒤로가기 버튼 시 앱 종료 대신 웹뷰 뒤로가기(canGoBack) 우선 처리
 */
@Composable
fun DockerWebViewScreen(
    services: List<DockerServiceBookmark>,
    selectedServiceId: String?,
    webViewPoolManager: WebViewPoolManager,
    isTabActive: Boolean,
    onSelectService: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 설정에서 삭제된 서비스의 WebView 캐시 자동 정리
    LaunchedEffect(services) {
        webViewPoolManager.retainOnly(services.map { it.id }.toSet())
    }

    val activeService = remember(services, selectedServiceId) {
        services.firstOrNull { it.id == selectedServiceId } ?: services.firstOrNull()
    }

    val metaMap by webViewPoolManager.metaByServiceId.collectAsStateWithLifecycle()
    val activeMeta = activeService?.let { metaMap[it.id] } ?: WebViewUiMeta()

    // 현재 웹뷰 탭이 활성화되어 있고 뒤로 갈 히스토리가 있을 때만 뒤로가기 가로채기
    BackHandler(enabled = isTabActive && activeService != null && activeMeta.canGoBack) {
        activeService?.let { service ->
            webViewPoolManager.goBack(service.id)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        if (activeService == null) {
            EmptyWebViewHubCard(onNavigateToSettings = onNavigateToSettings)
        } else {
            // 주소창 없이 서비스 전환 + 새로고침/홈 버튼만 배치한 슬림 헤더 바
            DockerServiceSwitcherBar(
                services = services,
                activeService = activeService,
                onSelectService = onSelectService,
                onHomeClick = { webViewPoolManager.goHome(activeService) },
                onReloadClick = { webViewPoolManager.reload(activeService.id) }
            )

            if (activeMeta.isLoading && activeMeta.progress in 1..99) {
                LinearProgressIndicator(
                    progress = { activeMeta.progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                )
            } else {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                )
            }

            // 캐싱된 WebView 인스턴스를 컨테이너 FrameLayout에 부착하여 상태 유지
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
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
        }
    }
}

@Composable
private fun DockerServiceSwitcherBar(
    services: List<DockerServiceBookmark>,
    activeService: DockerServiceBookmark,
    onSelectService: (String) -> Unit,
    onHomeClick: () -> Unit,
    onReloadClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .clickable { if (services.size > 1) expanded = true }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Public,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = activeService.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (services.size > 1) {
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = "서비스 전환",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    services.forEach { service ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = service.name,
                                    fontWeight = if (service.id == activeService.id) {
                                        FontWeight.Bold
                                    } else {
                                        FontWeight.Normal
                                    },
                                    color = if (service.id == activeService.id) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            },
                            onClick = {
                                expanded = false
                                onSelectService(service.id)
                            }
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onHomeClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Home,
                        contentDescription = "서비스 홈으로 이동",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onReloadClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "새로고침",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyWebViewHubCard(onNavigateToSettings: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
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
                    text = "등록된 도커 웹 서비스가 없습니다",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "설정 화면에서 Kavita, Portainer 등 자주 쓰는 도커 웹 URL을 등록하면 주소창 없는 전체 화면으로 바로 사용할 수 있습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = onNavigateToSettings) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("설정에서 서비스 추가")
                }
            }
        }
    }
}
