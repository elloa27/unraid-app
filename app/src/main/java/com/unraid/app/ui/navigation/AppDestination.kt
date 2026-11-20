package com.unraid.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 하단 내비게이션 바 탭 정의.
 *
 * [3단계 확장 가이드]
 * 추후 'Emby 플레이어' 탭을 추가할 때는 아래 enum에 항목을 하나 추가하고
 * MainScreen의 화면 전환 분기(when)에 플레이어 Composable만 연결하면
 * 기존 탭(특히 WebView 메모리 캐시)에 영향을 주지 않고 바로 확장됩니다.
 *
 * 예:
 *   EMBY_PLAYER(
 *       route = "emby_player",
 *       label = "Emby",
 *       icon = Icons.Filled.PlayCircle
 *   )
 */
enum class AppDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    DASHBOARD(
        route = "dashboard",
        label = "Unraid",
        icon = Icons.Filled.Dashboard
    ),
    DOCKER_WEBVIEW(
        route = "docker_webview",
        label = "도커 웹뷰",
        icon = Icons.Filled.Public
    ),
    SETTINGS(
        route = "settings",
        label = "설정",
        icon = Icons.Filled.Settings
    )
}
