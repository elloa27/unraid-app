package com.unraid.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 하단 내비게이션 바 탭 정의: Unraid | 공유 | 도커 웹뷰 | 설정
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
    SHARES(
        route = "shares",
        label = "공유",
        icon = Icons.Filled.Folder
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
