package com.unraid.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unraid.app.data.model.DockerAction
import com.unraid.app.data.model.DockerContainerItem
import com.unraid.app.data.model.UnraidDashboardData
import com.unraid.app.data.model.UnraidDisk
import com.unraid.app.ui.theme.StatusAmber
import com.unraid.app.ui.theme.StatusBlue
import com.unraid.app.ui.theme.StatusGreen
import com.unraid.app.ui.theme.StatusRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Unraid 네이티브 대시보드 화면.
 * 어레이 상태, 디스크 및 온도, 도커 컨테이너 제어 및 당겨서 새로고침(Pull-to-Refresh)을 제공합니다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNavigateToSettings: () -> Unit,
    onOpenDockerWebUi: (DockerContainerItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.feedbackMessage) {
        val message = state.feedbackMessage
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearFeedbackMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Unraid 대시보드",
                            style = MaterialTheme.typography.titleLarge
                        )
                        if (state.serverUrl.isNotBlank()) {
                            Text(
                                text = state.serverUrl,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    if (state.isConfigured) {
                        IconButton(onClick = { viewModel.refresh(isPullToRefresh = true) }) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "새로고침"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                !state.isConfigured -> {
                    UnconfiguredDashboardCard(onNavigateToSettings = onNavigateToSettings)
                }

                state.isLoading && state.data == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Unraid 서버 상태를 불러오는 중...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                else -> {
                    PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = { viewModel.refresh(isPullToRefresh = true) },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // 오류 발생 배너 (이전 캐시 데이터가 있어도 상단에 명확히 안내)
                            if (!state.errorMessage.isNullOrBlank()) {
                                item(key = "error_banner") {
                                    ErrorBannerCard(
                                        message = state.errorMessage.orEmpty(),
                                        onRetry = { viewModel.refresh(isPullToRefresh = true) },
                                        onOpenSettings = onNavigateToSettings
                                    )
                                }
                            }

                            val dashboardData = state.data
                            if (dashboardData != null) {
                                item(key = "array_summary") {
                                    ArrayStatusCard(data = dashboardData)
                                }

                                // 디스크 섹션
                                item(key = "disks_header") {
                                    SectionHeader(
                                        title = "디스크 및 온도",
                                        subtitle = "총 ${dashboardData.disks.size}개"
                                    )
                                }

                                if (dashboardData.disks.isEmpty()) {
                                    item(key = "disks_empty") {
                                        EmptySectionCard(text = "표시할 디스크 정보가 없습니다.")
                                    }
                                } else {
                                    items(
                                        items = dashboardData.disks,
                                        key = { "disk_${it.id}_${it.name}" }
                                    ) { disk ->
                                        DiskItemCard(disk = disk)
                                    }
                                }

                                // 도커 컨테이너 섹션
                                item(key = "docker_header") {
                                    val runningCount = dashboardData.containers.count { it.isRunning }
                                    SectionHeader(
                                        title = "도커 컨테이너",
                                        subtitle = "실행 중 $runningCount / 전체 ${dashboardData.containers.size}"
                                    )
                                }

                                if (dashboardData.containers.isEmpty()) {
                                    item(key = "docker_empty") {
                                        EmptySectionCard(text = "조회된 도커 컨테이너가 없습니다.")
                                    }
                                } else {
                                    items(
                                        items = dashboardData.containers,
                                        key = { "container_${it.id}" }
                                    ) { container ->
                                        DockerContainerCard(
                                            container = container,
                                            isActionRunning = state.actionInProgressContainerId == container.id,
                                            isAnyActionRunning = state.actionInProgressContainerId != null,
                                            onAction = { action ->
                                                viewModel.performDockerAction(container, action)
                                            },
                                            onOpenWebUi = {
                                                onOpenDockerWebUi(container)
                                            }
                                        )
                                    }
                                }

                                item(key = "footer_space") {
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UnconfiguredDashboardCard(onNavigateToSettings: () -> Unit) {
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
                    imageVector = Icons.Filled.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Unraid 서버를 연결해 주세요",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "설정 화면에서 Unraid 서버 주소와 API 키를 입력하면 어레이 상태, 디스크 온도, 도커 컨테이너를 바로 관리할 수 있습니다.",
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
                    Text("설정으로 이동")
                }
            }
        }
    }
}

@Composable
private fun ErrorBannerCard(
    message: String,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = StatusRed.copy(alpha = 0.14f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = StatusRed,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "서버 연결 오류",
                    style = MaterialTheme.typography.titleMedium,
                    color = StatusRed
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "상세 원인은 [설정 > 에러 로그 보기]에서 복사해 확인할 수 있습니다.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry) {
                    Text("다시 시도")
                }
                OutlinedButton(onClick = onOpenSettings) {
                    Text("설정 확인")
                }
            }
        }
    }
}

@Composable
private fun ArrayStatusCard(data: UnraidDashboardData) {
    val isStarted = data.arrayState.equals("STARTED", ignoreCase = true)
    val statusColor = when {
        isStarted -> StatusGreen
        data.arrayState.equals("STOPPED", ignoreCase = true) -> StatusRed
        else -> StatusAmber
    }
    val statusLabel = when {
        isStarted -> "어레이 정상 가동 중 (STARTED)"
        data.arrayState.equals("STOPPED", ignoreCase = true) -> "어레이 중지됨 (STOPPED)"
        else -> "어레이 상태: ${data.arrayState}"
    }

    val updatedTimeText = remember(data.fetchedAtMillis) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(data.fetchedAtMillis))
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = statusColor
                    )
                }
                Text(
                    text = "갱신 $updatedTimeText",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (data.capacityTotalKb != null && data.capacityTotalKb > 0L) {
                val usedKb = data.capacityUsedKb ?: 0L
                val ratio = (usedKb.toFloat() / data.capacityTotalKb.toFloat()).coerceIn(0f, 1f)
                val percent = (ratio * 100f).toInt()

                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "어레이 용량 (${formatKilobytes(usedKb)} / ${formatKilobytes(data.capacityTotalKb)})",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "$percent%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (percent >= 85) StatusAmber else MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = if (percent >= 85) StatusAmber else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptySectionCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DiskItemCard(disk: UnraidDisk) {
    val tempColor = when {
        disk.tempCelsius == null -> MaterialTheme.colorScheme.onSurfaceVariant
        disk.tempCelsius >= 50 -> StatusRed
        disk.tempCelsius >= 41 -> StatusAmber
        else -> StatusBlue
    }
    val diskStatusOk = disk.status.contains("OK", ignoreCase = true)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = disk.role.labelKo,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = disk.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                val metaParts = buildList {
                    if (disk.device.isNotBlank()) add(disk.device)
                    if (disk.sizeKb != null && disk.sizeKb > 0L) add(formatDiskSize(disk.sizeKb))
                    add(if (diskStatusOk) "정상" else disk.status)
                }
                Text(
                    text = metaParts.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (diskStatusOk) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        StatusAmber
                    }
                )
            }

            // 디스크 온도 배지
            Surface(
                color = tempColor.copy(alpha = 0.14f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Thermostat,
                        contentDescription = "디스크 온도",
                        tint = tempColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = disk.tempCelsius?.let { "${it}°C" } ?: "절전",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = tempColor
                    )
                }
            }
        }
    }
}

@Composable
private fun DockerContainerCard(
    container: DockerContainerItem,
    isActionRunning: Boolean,
    isAnyActionRunning: Boolean,
    onAction: (DockerAction) -> Unit,
    onOpenWebUi: () -> Unit
) {
    val stateColor = if (container.isRunning) StatusGreen else StatusRed

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(stateColor)
                )
                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = container.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = container.status.ifBlank { container.state },
                        style = MaterialTheme.typography.labelMedium,
                        color = stateColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (container.image.isNotBlank()) {
                        Text(
                            text = container.image,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                if (isActionRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (container.isRunning) {
                            FilledTonalIconButton(
                                onClick = { onAction(DockerAction.RESTART) },
                                enabled = !isAnyActionRunning,
                                modifier = Modifier.size(38.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = StatusAmber.copy(alpha = 0.16f),
                                    contentColor = StatusAmber
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.RestartAlt,
                                    contentDescription = "재시작",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            FilledTonalIconButton(
                                onClick = { onAction(DockerAction.STOP) },
                                enabled = !isAnyActionRunning,
                                modifier = Modifier.size(38.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = StatusRed.copy(alpha = 0.16f),
                                    contentColor = StatusRed
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Stop,
                                    contentDescription = "중지",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        } else {
                            FilledTonalIconButton(
                                onClick = { onAction(DockerAction.START) },
                                enabled = !isAnyActionRunning,
                                modifier = Modifier.size(38.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = StatusGreen.copy(alpha = 0.16f),
                                    contentColor = StatusGreen
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = "시작",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            FilledTonalIconButton(
                                onClick = { onAction(DockerAction.RESTART) },
                                enabled = !isAnyActionRunning,
                                modifier = Modifier.size(38.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.RestartAlt,
                                    contentDescription = "재시작",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 실행 중이면서 웹 UI 주소가 자동 감지된 컨테이너에는 즉시 전체 화면 웹뷰 열기 버튼 제공
            if (container.isRunning && container.hasWebUi) {
                Spacer(modifier = Modifier.height(10.dp))
                FilledTonalButton(
                    onClick = onOpenWebUi,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "웹 UI 열기",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

private fun formatKilobytes(kb: Long): String {
    val tb = kb / (1024.0 * 1024.0 * 1024.0)
    if (tb >= 1.0) return String.format(Locale.getDefault(), "%.2f TB", tb)
    val gb = kb / (1024.0 * 1024.0)
    if (gb >= 1.0) return String.format(Locale.getDefault(), "%.1f GB", gb)
    val mb = kb / 1024.0
    return String.format(Locale.getDefault(), "%.0f MB", mb)
}

/**
 * Unraid GraphQL API의 disk.size는 환경에 따라 KB 또는 Byte 단위로 내려올 수 있어
 * 크기 범위를 기준으로 사람이 읽기 쉬운 용량 단위로 변환합니다.
 */
private fun formatDiskSize(rawValue: Long): String {
    // 100억(10GB in bytes) 이상이면 Byte 단위로 간주, 그 미만이면 KB 단위로 간주
    val kb = if (rawValue > 10_000_000_000L) rawValue / 1024L else rawValue
    return formatKilobytes(kb)
}
