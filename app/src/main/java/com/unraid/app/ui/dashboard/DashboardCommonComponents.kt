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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unraid.app.data.model.UnraidDashboardData
import com.unraid.app.data.model.UnraidDisk
import com.unraid.app.ui.theme.StatusAmber
import com.unraid.app.ui.theme.StatusBlue
import com.unraid.app.ui.theme.StatusGreen
import com.unraid.app.ui.theme.StatusRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun UnconfiguredDashboardCard(onNavigateToSettings: () -> Unit) {
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
                    text = "설정 화면에서 Unraid 서버 주소와 API 키를 입력하면 실시간 CPU·메모리 관제, 어레이 제어, 도커·공유·플러그인을 바로 관리할 수 있습니다.",
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
internal fun ErrorBannerCard(
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

/**
 * 특정 서브탭 API가 현재 Unraid 버전에서 미지원/실패일 때 표시하는 친절한 안내 + 웹 UI 바로가기 카드
 */
@Composable
internal fun UnsupportedFeatureCard(
    title: String,
    description: String,
    webUiButtonLabel: String,
    onOpenWebUi: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = StatusAmber,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onOpenWebUi,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = webUiButtonLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (onRetry != null) {
                    OutlinedButton(onClick = onRetry) {
                        Text("재시도")
                    }
                }
            }
        }
    }
}

@Composable
internal fun WebUiShortcutButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            modifier = Modifier.size(17.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
internal fun TabLoadingCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.2.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun SectionHeader(title: String, subtitle: String = "") {
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
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun EmptySectionCard(text: String) {
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
internal fun ArrayStatusCard(data: UnraidDashboardData) {
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
        formatClockTime(data.fetchedAtMillis)
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
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                        color = statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
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
internal fun DiskItemCard(disk: UnraidDisk) {
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
internal fun KeyValueSummaryRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

internal fun formatClockTime(millis: Long): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))
}

internal fun formatKilobytes(kb: Long): String {
    val tb = kb / (1024.0 * 1024.0 * 1024.0)
    if (tb >= 1.0) return String.format(Locale.getDefault(), "%.2f TB", tb)
    val gb = kb / (1024.0 * 1024.0)
    if (gb >= 1.0) return String.format(Locale.getDefault(), "%.1f GB", gb)
    val mb = kb / 1024.0
    return String.format(Locale.getDefault(), "%.0f MB", mb)
}

internal fun formatBytes(bytes: Long): String {
    val tb = bytes / (1024.0 * 1024.0 * 1024.0 * 1024.0)
    if (tb >= 1.0) return String.format(Locale.getDefault(), "%.2f TB", tb)
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 1.0) return String.format(Locale.getDefault(), "%.1f GB", gb)
    val mb = bytes / (1024.0 * 1024.0)
    if (mb >= 1.0) return String.format(Locale.getDefault(), "%.0f MB", mb)
    val kb = bytes / 1024.0
    return String.format(Locale.getDefault(), "%.0f KB", kb)
}

/**
 * Unraid GraphQL API의 size/free/used 필드는 버전/엔티티에 따라 KB 또는 Byte 단위로 내려올 수 있어
 * 크기 범위를 기준으로 안전하게 변환합니다.
 */
internal fun formatDiskSize(rawValue: Long): String {
    val kb = if (rawValue > 10_000_000_000L) rawValue / 1024L else rawValue
    return formatKilobytes(kb)
}

internal fun formatDurationSeconds(seconds: Long): String {
    if (seconds <= 0L) return "-"
    val hours = seconds / 3600L
    val mins = (seconds % 3600L) / 60L
    val secs = seconds % 60L
    return when {
        hours > 0L -> "${hours}시간 ${mins}분"
        mins > 0L -> "${mins}분 ${secs}초"
        else -> "${secs}초"
    }
}
