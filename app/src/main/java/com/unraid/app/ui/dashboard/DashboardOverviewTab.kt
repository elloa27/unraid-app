package com.unraid.app.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unraid.app.data.model.MetricSample
import com.unraid.app.data.model.NotificationUnreadCounts
import com.unraid.app.data.model.SystemHardwareInfo
import com.unraid.app.data.model.SystemMetricsData
import com.unraid.app.data.model.UnraidNotificationItem
import com.unraid.app.ui.theme.StatusAmber
import com.unraid.app.ui.theme.StatusBlue
import com.unraid.app.ui.theme.StatusGreen
import com.unraid.app.ui.theme.StatusRed
import java.util.Locale

/**
 * 서브탭 1: 대시보드 (5초 실시간 CPU·메모리 관제 + 스파크라인 + 어레이 요약 + 시스템 알림)
 */
internal fun LazyListScope.dashboardOverviewTabContent(
    state: DashboardUiState,
    onRetryMetricsAndOverview: () -> Unit,
    onOpenUnraidWebUi: (String) -> Unit
) {
    // 1. 실시간 CPU/메모리 관제 카드
    item(key = "realtime_metrics_card") {
        RealtimeSystemMetricsCard(
            metrics = state.metrics,
            history = state.metricsHistory,
            hardware = state.overview?.hardware,
            isPollingActive = state.isMetricsPollingActive,
            metricsError = state.metricsError,
            onOpenWebUi = { onOpenUnraidWebUi("/Dashboard") },
            onRetry = onRetryMetricsAndOverview
        )
    }

    // 2. 어레이 상태·용량 요약 카드
    val dashboardData = state.data
    if (dashboardData != null) {
        item(key = "overview_array_summary") {
            ArrayStatusCard(data = dashboardData)
        }

        // 핵심 지표 빠른 요약 (디스크/도커 실행 수)
        item(key = "overview_quick_stats") {
            val runningDockers = dashboardData.containers.count { it.isRunning }
            val maxTemp = dashboardData.disks.mapNotNull { it.tempCelsius }.maxOrNull()
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    QuickStatColumn(
                        label = "활성 디스크",
                        value = "${dashboardData.disks.size}개",
                        accentColor = StatusBlue
                    )
                    QuickStatColumn(
                        label = "최고 디스크 온도",
                        value = maxTemp?.let { "${it}°C" } ?: "절전",
                        accentColor = when {
                            maxTemp == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            maxTemp >= 50 -> StatusRed
                            maxTemp >= 41 -> StatusAmber
                            else -> StatusGreen
                        }
                    )
                    QuickStatColumn(
                        label = "도커 실행 중",
                        value = "$runningDockers / ${dashboardData.containers.size}",
                        accentColor = StatusGreen
                    )
                }
            }
        }
    }

    // 3. 최근 읽지 않은 시스템 알림 카드
    item(key = "overview_notifications_header") {
        val total = state.overview?.unreadCounts?.total
        SectionHeader(
            title = "최근 읽지 않은 시스템 알림",
            subtitle = total?.let { "미확인 ${it}건" } ?: ""
        )
    }

    val overview = state.overview
    when {
        state.isOverviewLoading && overview == null -> {
            item(key = "overview_notifications_loading") {
                TabLoadingCard("시스템 알림을 불러오는 중...")
            }
        }

        overview != null && !overview.notificationsSupported -> {
            item(key = "overview_notifications_unsupported") {
                UnsupportedFeatureCard(
                    title = "알림 API 미지원",
                    description = "현재 Unraid 버전에서는 GraphQL 시스템 알림 조회를 지원하지 않습니다. 웹 UI에서 알림을 확인해 주세요.",
                    webUiButtonLabel = "Unraid 대시보드 웹 UI 열기",
                    onOpenWebUi = { onOpenUnraidWebUi("/Dashboard") }
                )
            }
        }

        overview != null -> {
            overview.unreadCounts?.let { counts ->
                item(key = "overview_notifications_counts") {
                    NotificationSummaryBadgesRow(counts = counts)
                }
            }
            if (overview.unreadNotifications.isEmpty()) {
                item(key = "overview_notifications_empty") {
                    EmptySectionCard("읽지 않은 시스템 알림이 없습니다.")
                }
            } else {
                items(
                    items = overview.unreadNotifications,
                    key = { "notif_${it.id}" }
                ) { notif ->
                    NotificationItemCard(item = notif)
                }
            }
        }

        !state.overviewError.isNullOrBlank() -> {
            item(key = "overview_error") {
                UnsupportedFeatureCard(
                    title = "시스템 개요 정보를 불러오지 못했습니다",
                    description = state.overviewError.orEmpty(),
                    webUiButtonLabel = "Unraid 대시보드 웹 UI 열기",
                    onOpenWebUi = { onOpenUnraidWebUi("/Dashboard") },
                    onRetry = onRetryMetricsAndOverview
                )
            }
        }
    }

    item(key = "overview_webui_btn") {
        WebUiShortcutButton(
            label = "Unraid 공식 대시보드 웹 UI 열기 (/Dashboard)",
            onClick = { onOpenUnraidWebUi("/Dashboard") }
        )
    }
}

@Composable
private fun RealtimeSystemMetricsCard(
    metrics: SystemMetricsData?,
    history: List<MetricSample>,
    hardware: SystemHardwareInfo?,
    isPollingActive: Boolean,
    metricsError: String?,
    onOpenWebUi: () -> Unit,
    onRetry: () -> Unit
) {
    val updatedText = remember(metrics?.fetchedAtMillis) {
        metrics?.fetchedAtMillis?.let { formatClockTime(it) } ?: "수집 대기"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 상단 헤더: 제목 + 5초 실시간 배지 + 갱신 시각
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "실시간 시스템 관제",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        color = if (isPollingActive) StatusGreen.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (isPollingActive) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                            Text(
                                text = if (isPollingActive) "5초 실시간" else "대기 중",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isPollingActive) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Text(
                    text = updatedText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (metrics == null && !metricsError.isNullOrBlank()) {
                Text(
                    text = "실시간 메트릭 API를 불러오지 못했습니다. ($metricsError)",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusAmber
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        onClick = onRetry,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "재시도",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Surface(
                        onClick = onOpenWebUi,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "웹 UI에서 보기",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            } else {
                // CPU 사용률
                val cpuPct = metrics?.cpuPercent ?: 0f
                val cpuColor = usageColor(cpuPct)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Speed,
                                contentDescription = null,
                                tint = cpuColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "CPU 사용률",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = metrics?.cpuPercent?.let {
                                String.format(Locale.getDefault(), "%.1f%%", it)
                            } ?: "-",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = cpuColor
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (cpuPct / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = cpuColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                // RAM 사용량
                val memPct = metrics?.memoryPercent ?: 0f
                val memColor = usageColor(memPct)
                val memUsageDetail = if (metrics?.memoryTotalBytes != null && metrics.memoryTotalBytes > 0L) {
                    val used = metrics.memoryUsedBytes ?: 0L
                    "${formatBytes(used)} / ${formatBytes(metrics.memoryTotalBytes)}"
                } else {
                    ""
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Memory,
                                contentDescription = null,
                                tint = memColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = if (memUsageDetail.isNotBlank()) "RAM ($memUsageDetail)" else "RAM 사용률",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = metrics?.memoryPercent?.let {
                                String.format(Locale.getDefault(), "%.1f%%", it)
                            } ?: "-",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = memColor
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (memPct / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = memColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                // Swap 사용량 (존재할 때만 표시)
                val swapTotal = metrics?.swapTotalBytes
                if (swapTotal != null && swapTotal > 0L) {
                    val swapUsed = metrics.swapUsedBytes ?: 0L
                    val swapPct = metrics.swapPercent ?: 0f
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Swap (${formatBytes(swapUsed)} / ${formatBytes(swapTotal)})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = String.format(Locale.getDefault(), "%.1f%%", swapPct),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = StatusBlue
                        )
                    }
                }

                // 미니 스파크라인 추이 그래프 (최근 20개 샘플)
                if (history.size >= 2) {
                    MetricsSparklineSection(history = history)
                }
            }

            // 하드웨어 요약 (호스트명 / 업타임 / CPU 모델·코어·스레드 / 메인보드)
            if (hardware != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    hardware.hostname?.takeIf { it.isNotBlank() }?.let { host ->
                        val osText = listOfNotNull(hardware.distro, hardware.release)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                        KeyValueSummaryRow(
                            label = "호스트",
                            value = if (osText.isNotBlank()) "$host ($osText)" else host
                        )
                    }
                    hardware.cpuBrand?.takeIf { it.isNotBlank() }?.let { brand ->
                        val coreThread = when {
                            hardware.cpuCores != null && hardware.cpuThreads != null ->
                                "${hardware.cpuCores}코어 / ${hardware.cpuThreads}스레드"
                            hardware.cpuCores != null -> "${hardware.cpuCores}코어"
                            else -> ""
                        }
                        KeyValueSummaryRow(
                            label = "CPU",
                            value = if (coreThread.isNotBlank()) "$brand ($coreThread)" else brand
                        )
                    }
                    val board = listOfNotNull(hardware.boardManufacturer, hardware.boardModel)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                    if (board.isNotBlank()) {
                        KeyValueSummaryRow(label = "메인보드", value = board)
                    }
                    hardware.uptime?.takeIf { it.isNotBlank() }?.let { uptime ->
                        KeyValueSummaryRow(label = "가동 시작/업타임", value = uptime)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricsSparklineSection(history: List<MetricSample>) {
    val cpuLineColor = MaterialTheme.colorScheme.primary
    val ramLineColor = StatusBlue
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "최근 추이 (${history.size}회 샘플)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SparklineLegendDot(label = "CPU", color = cpuLineColor)
                SparklineLegendDot(label = "RAM", color = ramLineColor)
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            val w = size.width
            val h = size.height
            // 50% 기준선
            drawLine(
                color = gridColor,
                start = Offset(0f, h * 0.5f),
                end = Offset(w, h * 0.5f),
                strokeWidth = 1.dp.toPx()
            )

            val stepX = if (history.size > 1) w / (history.size - 1).toFloat() else w
            val stroke = Stroke(
                width = 2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )

            val ramPath = Path()
            val cpuPath = Path()

            history.forEachIndexed { index, sample ->
                val x = index * stepX
                val cpuY = h - (sample.cpuPercent.coerceIn(0f, 100f) / 100f) * h
                val ramY = h - (sample.memoryPercent.coerceIn(0f, 100f) / 100f) * h
                if (index == 0) {
                    cpuPath.moveTo(x, cpuY)
                    ramPath.moveTo(x, ramY)
                } else {
                    cpuPath.lineTo(x, cpuY)
                    ramPath.lineTo(x, ramY)
                }
            }

            drawPath(path = ramPath, color = ramLineColor, style = stroke)
            drawPath(path = cpuPath, color = cpuLineColor, style = stroke)
        }
    }
}

@Composable
private fun SparklineLegendDot(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun QuickStatColumn(label: String, value: String, accentColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = accentColor
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NotificationSummaryBadgesRow(counts: NotificationUnreadCounts) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        NotificationCountBadge(label = "정보", count = counts.info, color = StatusBlue, modifier = Modifier.weight(1f))
        NotificationCountBadge(label = "경고", count = counts.warning, color = StatusAmber, modifier = Modifier.weight(1f))
        NotificationCountBadge(label = "위험", count = counts.alert, color = StatusRed, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun NotificationCountBadge(
    label: String,
    count: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = color
            )
            Text(
                text = "${count}건",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
private fun NotificationItemCard(item: UnraidNotificationItem) {
    val badgeColor = when (item.importance.uppercase(Locale.US)) {
        "ALERT" -> StatusRed
        "WARNING" -> StatusAmber
        else -> StatusBlue
    }
    val importanceKo = when (item.importance.uppercase(Locale.US)) {
        "ALERT" -> "위험"
        "WARNING" -> "경고"
        else -> "알림"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = item.subject.ifBlank { item.title.ifBlank { "시스템 알림" } },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = badgeColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = importanceKo,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor
                    )
                }
            }
            if (item.description.isNotBlank()) {
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            item.timestamp?.takeIf { it.isNotBlank() }?.let { ts ->
                Text(
                    text = ts,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }
        }
    }
}

private fun usageColor(percent: Float): Color = when {
    percent >= 85f -> StatusRed
    percent >= 65f -> StatusAmber
    else -> StatusGreen
}
