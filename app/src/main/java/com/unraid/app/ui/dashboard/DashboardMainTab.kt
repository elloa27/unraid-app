package com.unraid.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unraid.app.data.model.ParityCheckAction
import com.unraid.app.data.model.ParityHistoryItem
import com.unraid.app.ui.theme.StatusAmber
import com.unraid.app.ui.theme.StatusGreen
import com.unraid.app.ui.theme.StatusRed

/**
 * 서브탭 2: 메인 (어레이 시작/중지 제어 + 패리티 체크 제어/이력 + 패리티/어레이/캐시 디스크 목록)
 */
internal fun LazyListScope.dashboardMainTabContent(
    state: DashboardUiState,
    onRequestArrayStateChange: (start: Boolean) -> Unit,
    onRequestParityAction: (ParityCheckAction) -> Unit,
    onRetryParityHistory: () -> Unit,
    onOpenUnraidWebUi: (String) -> Unit
) {
    val data = state.data

    // 1. 어레이 상태 + 시작/중지 제어 카드
    if (data != null) {
        item(key = "main_array_status") {
            ArrayStatusCard(data = data)
        }

        item(key = "main_array_control") {
            val isStarted = data.arrayState.equals("STARTED", ignoreCase = true)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PowerSettingsNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "어레이 제어",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = if (isStarted) {
                            "어레이를 중지하면 모든 도커 컨테이너, VM 및 SMB 공유 접근이 중단됩니다."
                        } else {
                            "어레이를 시작하면 스토리지 볼륨과 도커·공유 서비스가 마운트됩니다."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { onRequestArrayStateChange(true) },
                            enabled = !state.isMainActionInProgress && !isStarted,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StatusGreen,
                                contentColor = MaterialTheme.colorScheme.background
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("어레이 시작")
                        }
                        Button(
                            onClick = { onRequestArrayStateChange(false) },
                            enabled = !state.isMainActionInProgress && isStarted,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StatusRed,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("어레이 중지")
                        }
                    }
                    if (state.isMainActionInProgress) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                text = "어레이/패리티 명령을 처리하는 중...",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }

    // 2. 패리티 체크 제어 카드
    item(key = "main_parity_control") {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.VerifiedUser,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "패리티 체크 제어",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "패리티 검사는 읽기 전용(비교정) 모드로 안전하게 시작됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { onRequestParityAction(ParityCheckAction.START) },
                        enabled = !state.isMainActionInProgress,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("시작")
                    }
                    FilledTonalButton(
                        onClick = { onRequestParityAction(ParityCheckAction.PAUSE) },
                        enabled = !state.isMainActionInProgress,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Pause, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("일시정지")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onRequestParityAction(ParityCheckAction.RESUME) },
                        enabled = !state.isMainActionInProgress,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("재개")
                    }
                    OutlinedButton(
                        onClick = { onRequestParityAction(ParityCheckAction.CANCEL) },
                        enabled = !state.isMainActionInProgress,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Cancel,
                            contentDescription = null,
                            tint = StatusRed,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("취소", color = StatusRed)
                    }
                }
            }
        }
    }

    // 3. 최근 패리티 체크 이력
    item(key = "main_parity_history_header") {
        SectionHeader(
            title = "최근 패리티 체크 이력",
            subtitle = if (state.parityHistory.isNotEmpty()) "${state.parityHistory.size}건" else ""
        )
    }

    when {
        state.isParityHistoryLoading && state.parityHistory.isEmpty() -> {
            item(key = "main_parity_history_loading") {
                TabLoadingCard("패리티 체크 이력을 불러오는 중...")
            }
        }

        !state.parityHistoryError.isNullOrBlank() && state.parityHistory.isEmpty() -> {
            item(key = "main_parity_history_error") {
                UnsupportedFeatureCard(
                    title = "패리티 이력 조회 불가",
                    description = state.parityHistoryError.orEmpty(),
                    webUiButtonLabel = "Unraid 메인 웹 UI 열기",
                    onOpenWebUi = { onOpenUnraidWebUi("/Main") },
                    onRetry = onRetryParityHistory
                )
            }
        }

        state.parityHistory.isEmpty() -> {
            item(key = "main_parity_history_empty") {
                EmptySectionCard("기록된 패리티 체크 이력이 없습니다.")
            }
        }

        else -> {
            items(
                items = state.parityHistory.take(5),
                key = { "parity_hist_${it.date}_${it.durationSec}_${it.errors}" }
            ) { history ->
                ParityHistoryCard(item = history)
            }
        }
    }

    // 4. 디스크 목록 (패리티 / 어레이 / 캐시)
    if (data != null) {
        item(key = "main_disks_header") {
            SectionHeader(
                title = "패리티 · 어레이 · 캐시 디스크",
                subtitle = "총 ${data.disks.size}개"
            )
        }

        if (data.disks.isEmpty()) {
            item(key = "main_disks_empty") {
                EmptySectionCard("표시할 디스크 정보가 없습니다.")
            }
        } else {
            items(
                items = data.disks,
                key = { "main_disk_${it.id}_${it.name}" }
            ) { disk ->
                DiskItemCard(disk = disk)
            }
        }
    }

    // 5. 하단 웹 UI 비상 바로가기
    item(key = "main_webui_shortcut") {
        WebUiShortcutButton(
            label = "Unraid 메인 웹 UI 열기 (/Main)",
            onClick = { onOpenUnraidWebUi("/Main") }
        )
    }
}

@Composable
private fun ParityHistoryCard(item: ParityHistoryItem) {
    val hasErrors = (item.errors ?: 0L) > 0L
    val statusColor = if (hasErrors) StatusRed else StatusGreen

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
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
                Text(
                    text = item.date?.takeIf { it.isNotBlank() } ?: "날짜 정보 없음",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Surface(
                    color = statusColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = if (hasErrors) "오류 ${item.errors}건" else (item.status?.ifBlank { "정상" } ?: "오류 0건"),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
            }
            val meta = buildList {
                item.durationSec?.let { add("소요 ${formatDurationSeconds(it)}") }
                item.speed?.takeIf { it.isNotBlank() }?.let { add("속도 $it") }
                item.errors?.let { add("오류 ${it}건") }
            }
            if (meta.isNotEmpty()) {
                Text(
                    text = meta.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 어레이 시작 / 중지용 2단계 확인 다이얼로그 (실수 방지)
 */
@Composable
internal fun ArrayControlTwoStepDialog(
    startArray: Boolean,
    step: Int,
    onNextStep: () -> Unit,
    onConfirmExecute: () -> Unit,
    onDismiss: () -> Unit
) {
    val actionTitle = if (startArray) "어레이 시작" else "어레이 중지"
    val isStep1 = step <= 1

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isStep1) "$actionTitle 확인 (1/2 단계)" else "$actionTitle 최종 확인 (2/2 단계)",
                color = if (!startArray && !isStep1) StatusRed else MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (isStep1) {
                        if (startArray) {
                            "Unraid 스토리지 어레이를 시작하시겠습니까? 마운트 완료까지 수십 초가 소요될 수 있습니다."
                        } else {
                            "정말로 Unraid 어레이를 중지하시겠습니까? 실행 중인 도커 컨테이너, VM 및 모든 SMB 네트워크 공유가 즉시 중단됩니다."
                        }
                    } else {
                        if (startArray) {
                            "최종 확인: 지금 Unraid 어레이 시작 명령을 서버에 전송합니다."
                        } else {
                            "최종 경고: 진행 중인 파일 전송이나 미디어 스트리밍이 모두 끊어집니다. 지금 어레이를 중지할까요?"
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            if (isStep1) {
                Button(
                    onClick = onNextStep,
                    colors = if (!startArray) {
                        ButtonDefaults.buttonColors(containerColor = StatusAmber, contentColor = MaterialTheme.colorScheme.background)
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    Text("다음 단계")
                }
            } else {
                Button(
                    onClick = onConfirmExecute,
                    colors = if (!startArray) {
                        ButtonDefaults.buttonColors(containerColor = StatusRed)
                    } else {
                        ButtonDefaults.buttonColors(containerColor = StatusGreen, contentColor = MaterialTheme.colorScheme.background)
                    }
                ) {
                    Text("$actionTitle 실행")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}

/**
 * 패리티 체크 제어 확인 다이얼로그
 */
@Composable
internal fun ParityControlConfirmDialog(
    action: ParityCheckAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val description = when (action) {
        ParityCheckAction.START -> "패리티 체크를 읽기 전용(비교정) 모드로 시작하시겠습니까? 디스크 I/O 부하가 증가할 수 있습니다."
        ParityCheckAction.PAUSE -> "현재 진행 중인 패리티 체크를 일시정지하시겠습니까?"
        ParityCheckAction.RESUME -> "일시정지된 패리티 체크를 이어서 재개하시겠습니까?"
        ParityCheckAction.CANCEL -> "현재 진행 중인 패리티 체크를 취소하시겠습니까?"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("패리티 체크 ${action.labelKo}") },
        text = { Text(description) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (action == ParityCheckAction.CANCEL) {
                    ButtonDefaults.buttonColors(containerColor = StatusRed)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(action.labelKo)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}
