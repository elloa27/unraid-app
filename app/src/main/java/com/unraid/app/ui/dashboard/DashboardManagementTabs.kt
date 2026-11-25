package com.unraid.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unraid.app.data.model.CreatedApiKeyResult
import com.unraid.app.data.model.UnraidApiKeyItem
import com.unraid.app.data.model.UnraidPluginItem
import com.unraid.app.data.model.UnraidSettingsSummary
import com.unraid.app.data.model.UnraidShareItem
import com.unraid.app.ui.theme.StatusAmber
import com.unraid.app.ui.theme.StatusBlue
import com.unraid.app.ui.theme.StatusGreen
import com.unraid.app.ui.theme.StatusRed

// =============================================================================
// 서브탭 4: 공유 (Shares)
// =============================================================================

internal fun LazyListScope.dashboardSharesTabContent(
    state: DashboardUiState,
    onRetry: () -> Unit,
    onOpenUnraidWebUi: (String) -> Unit
) {
    item(key = "shares_header") {
        SectionHeader(
            title = "공유 폴더",
            subtitle = if (state.shares.isNotEmpty()) "총 ${state.shares.size}개" else ""
        )
    }

    when {
        state.isSharesLoading && state.shares.isEmpty() -> {
            item(key = "shares_loading") {
                TabLoadingCard("공유 폴더 목록을 불러오는 중...")
            }
        }

        !state.sharesError.isNullOrBlank() && state.shares.isEmpty() -> {
            item(key = "shares_error") {
                UnsupportedFeatureCard(
                    title = "공유 폴더 조회 불가",
                    description = state.sharesError.orEmpty(),
                    webUiButtonLabel = "Unraid 공유 웹 UI 열기",
                    onOpenWebUi = { onOpenUnraidWebUi("/Shares") },
                    onRetry = onRetry
                )
            }
        }

        state.shares.isEmpty() -> {
            item(key = "shares_empty") {
                EmptySectionCard("조회된 공유 폴더가 없습니다.")
            }
        }

        else -> {
            items(
                items = state.shares,
                key = { "share_${it.id}_${it.name}" }
            ) { share ->
                ShareItemCard(share = share)
            }
        }
    }

    item(key = "shares_webui_shortcut") {
        WebUiShortcutButton(
            label = "공유 생성·권한 편집은 웹 UI에서 열기 (/Shares)",
            onClick = { onOpenUnraidWebUi("/Shares") }
        )
    }
}

@Composable
private fun ShareItemCard(share: UnraidShareItem) {
    val used = share.usedKb ?: 0L
    val free = share.freeKb ?: 0L
    val total = share.sizeKb?.takeIf { it > 0L } ?: (used + free).takeIf { it > 0L }
    val ratio = if (total != null && total > 0L) (used.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = share.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                share.cache?.takeIf { it.isNotBlank() }?.let { cachePolicy ->
                    Surface(
                        color = StatusBlue.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "캐시: $cachePolicy",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = StatusBlue
                        )
                    }
                }
            }

            if (share.comment.isNotBlank()) {
                Text(
                    text = share.comment,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (share.usedKb != null || share.freeKb != null) {
                val spaceText = buildList {
                    share.usedKb?.let { add("사용 ${formatDiskSize(it)}") }
                    share.freeKb?.let { add("여유 ${formatDiskSize(it)}") }
                }.joinToString(" · ")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = spaceText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    ratio?.let {
                        Text(
                            text = "${(it * 100f).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (it >= 0.85f) StatusAmber else MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (ratio != null) {
                    LinearProgressIndicator(
                        progress = { ratio },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = if (ratio >= 0.85f) StatusAmber else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }

            val diskPolicyLines = buildList {
                if (share.include.isNotEmpty()) add("포함 디스크: ${share.include.joinToString(", ")}")
                if (share.exclude.isNotEmpty()) add("제외 디스크: ${share.exclude.joinToString(", ")}")
                share.floor?.takeIf { it.isNotBlank() && it != "0" }?.let { add("최소 여유 공간: $it") }
            }
            if (diskPolicyLines.isNotEmpty()) {
                Text(
                    text = diskPolicyLines.joinToString(" | "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// =============================================================================
// 서브탭 5: 사용자 및 API 키 (Users)
// =============================================================================

internal fun LazyListScope.dashboardUsersTabContent(
    state: DashboardUiState,
    onOpenCreateApiKeyDialog: () -> Unit,
    onRequestDeleteApiKey: (UnraidApiKeyItem) -> Unit,
    onRetry: () -> Unit,
    onOpenUnraidWebUi: (String) -> Unit
) {
    // 상단 안내 배너 + 웹 UI 바로가기
    item(key = "users_smb_notice") {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = StatusBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "SMB/NAS 일반 사용자 추가 및 비밀번호 변경은 Unraid GraphQL API 미지원으로 웹 UI 바로가기를 이용해 주세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                WebUiShortcutButton(
                    label = "Unraid 사용자 웹 UI 열기 (/Users)",
                    onClick = { onOpenUnraidWebUi("/Users") }
                )
            }
        }
    }

    val usersData = state.usersData
    when {
        state.isUsersLoading && usersData == null -> {
            item(key = "users_loading") {
                TabLoadingCard("사용자 및 API 키 정보를 불러오는 중...")
            }
        }

        !state.usersError.isNullOrBlank() && usersData == null -> {
            item(key = "users_error") {
                UnsupportedFeatureCard(
                    title = "사용자/API 키 조회 불가",
                    description = state.usersError.orEmpty(),
                    webUiButtonLabel = "Unraid 사용자 웹 UI 열기",
                    onOpenWebUi = { onOpenUnraidWebUi("/Users") },
                    onRetry = onRetry
                )
            }
        }

        usersData != null -> {
            // 현재 연결된 계정(me) 카드
            usersData.me?.let { me ->
                item(key = "users_me_card") {
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
                                    imageVector = Icons.Filled.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "현재 연결된 계정",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            KeyValueSummaryRow(label = "이름", value = me.name)
                            if (me.description.isNotBlank()) {
                                KeyValueSummaryRow(label = "설명", value = me.description)
                            }
                            if (me.roles.isNotEmpty()) {
                                KeyValueSummaryRow(
                                    label = "권한 역할",
                                    value = me.roles.joinToString(", "),
                                    valueColor = StatusGreen
                                )
                            }
                        }
                    }
                }
            }

            // API 키 관리 헤더 + 생성 버튼
            item(key = "users_apikeys_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "API 키 관리 (${usersData.apiKeys.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (usersData.apiKeysSupported) {
                        FilledTonalButton(
                            onClick = onOpenCreateApiKeyDialog,
                            enabled = !state.isUserActionInProgress,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("새 API 키", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            if (!usersData.apiKeysSupported) {
                item(key = "users_apikeys_unsupported") {
                    UnsupportedFeatureCard(
                        title = "API 키 목록 조회 권한/스키마 미지원",
                        description = "현재 API 키 권한이 부족하거나 Unraid 버전에서 apiKeys 목록 조회를 지원하지 않습니다.",
                        webUiButtonLabel = "Unraid 설정 > 관리 접근 열기",
                        onOpenWebUi = { onOpenUnraidWebUi("/Settings/ManagementAccess") }
                    )
                }
            } else if (usersData.apiKeys.isEmpty()) {
                item(key = "users_apikeys_empty") {
                    EmptySectionCard("조회된 API 키가 없습니다.")
                }
            } else {
                items(
                    items = usersData.apiKeys,
                    key = { "apikey_${it.id}" }
                ) { keyItem ->
                    ApiKeyItemCard(
                        item = keyItem,
                        enabled = !state.isUserActionInProgress,
                        onDeleteClick = { onRequestDeleteApiKey(keyItem) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ApiKeyItemCard(
    item: UnraidApiKeyItem,
    enabled: Boolean,
    onDeleteClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.VpnKey,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.description.isNotBlank()) {
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val meta = buildList {
                    if (item.roles.isNotEmpty()) add("역할: ${item.roles.joinToString(", ")}")
                    item.createdAt?.takeIf { it.isNotBlank() }?.let { add("생성: ${it.take(10)}") }
                }
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onDeleteClick, enabled = enabled) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "API 키 삭제",
                    tint = StatusRed
                )
            }
        }
    }
}

@Composable
internal fun CreateApiKeyDialog(
    onConfirm: (name: String, description: String, roles: List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selectedRole by remember { mutableStateOf("VIEWER") }
    val availableRoles = listOf("ADMIN" to "관리자 (ADMIN)", "VIEWER" to "조회 전용 (VIEWER)")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("새 API 키 생성") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("키 이름 *") },
                    placeholder = { Text("예: MobileMonitor") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("설명 (선택)") },
                    placeholder = { Text("용도 메모") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "권한 역할",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    availableRoles.forEach { (roleCode, label) ->
                        FilterChip(
                            selected = selectedRole == roleCode,
                            onClick = { selectedRole = roleCode },
                            label = { Text(label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim(), description.trim(), listOf(selectedRole)) },
                enabled = name.isNotBlank()
            ) {
                Text("생성")
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
internal fun CreatedApiKeyResultDialog(
    result: CreatedApiKeyResult,
    onCopied: () -> Unit,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("API 키가 생성되었습니다") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "'${result.name}' 키가 발급되었습니다. 아래 키 값은 지금만 확인할 수 있으므로 안전한 곳에 보관하세요.",
                    style = MaterialTheme.typography.bodySmall
                )
                result.key?.takeIf { it.isNotBlank() }?.let { rawKey ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = rawKey,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(rawKey))
                            onCopied()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("API 키 복사")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("확인")
            }
        }
    )
}

@Composable
internal fun DeleteApiKeyConfirmDialog(
    keyItem: UnraidApiKeyItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("API 키 삭제 확인") },
        text = {
            Text("'${keyItem.name}' API 키를 삭제하시겠습니까? 이 키를 사용 중인 외부 앱이나 스크립트의 접속이 즉시 차단됩니다.")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = StatusRed)
            ) {
                Text("삭제")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}

// =============================================================================
// 서브탭 6: 설정 (Settings Summary + Quick Web UI Shortcuts Grid)
// =============================================================================

private data class SettingsQuickShortcut(
    val label: String,
    val path: String,
    val icon: ImageVector
)

private val UnraidSettingsShortcuts = listOf(
    SettingsQuickShortcut("전체 설정", "/Settings", Icons.Filled.Settings),
    SettingsQuickShortcut("네트워크", "/Settings/NetworkSettings", Icons.Filled.Public),
    SettingsQuickShortcut("디스크 설정", "/Settings/DiskSettings", Icons.Filled.Storage),
    SettingsQuickShortcut("SMB 공유", "/Settings/SMB", Icons.Filled.Folder),
    SettingsQuickShortcut("도커 설정", "/Settings/DockerSettings", Icons.Filled.Dns),
    SettingsQuickShortcut("VM 관리", "/VMs", Icons.Filled.Computer),
    SettingsQuickShortcut("도구", "/Tools", Icons.Filled.Build)
)

internal fun LazyListScope.dashboardSettingsTabContent(
    state: DashboardUiState,
    onRetry: () -> Unit,
    onOpenUnraidWebUi: (String) -> Unit
) {
    item(key = "settings_summary_header") {
        SectionHeader(title = "Unraid 시스템 설정 요약")
    }

    val summary = state.settingsSummary
    when {
        state.isSettingsLoading && summary == null -> {
            item(key = "settings_loading") {
                TabLoadingCard("시스템 설정 요약을 불러오는 중...")
            }
        }

        !state.settingsError.isNullOrBlank() && summary == null -> {
            item(key = "settings_error") {
                UnsupportedFeatureCard(
                    title = "시스템 설정 요약 조회 불가",
                    description = state.settingsError.orEmpty(),
                    webUiButtonLabel = "Unraid 설정 웹 UI 열기",
                    onOpenWebUi = { onOpenUnraidWebUi("/Settings") },
                    onRetry = onRetry
                )
            }
        }

        summary != null -> {
            item(key = "settings_summary_card") {
                SettingsSummaryCard(summary = summary)
            }
        }
    }

    item(key = "settings_shortcuts_header") {
        SectionHeader(
            title = "자주 쓰는 설정 웹 UI 바로가기",
            subtitle = "모바일 축소·핀치 줌 지원"
        )
    }

    // 2열 빠른 바로가기 그리드 (LazyColumn 중첩 스크롤 없이 2개씩 Row로 구성)
    val rows = UnraidSettingsShortcuts.chunked(2)
    items(
        items = rows,
        key = { chunk -> "shortcut_row_${chunk.first().path}" }
    ) { pair ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            pair.forEach { shortcut ->
                SettingsShortcutTile(
                    shortcut = shortcut,
                    onClick = { onOpenUnraidWebUi(shortcut.path) },
                    modifier = Modifier.weight(1f)
                )
            }
            if (pair.size == 1) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SettingsSummaryCard(summary: UnraidSettingsSummary) {
    val rows = buildList {
        summary.serverName?.takeIf { it.isNotBlank() }?.let { add("서버 이름" to it) }
        summary.version?.takeIf { it.isNotBlank() }?.let { add("Unraid OS 버전" to it) }
        summary.comment?.takeIf { it.isNotBlank() }?.let { add("설명" to it) }
        summary.timeZone?.takeIf { it.isNotBlank() }?.let { add("타임존" to it) }
        summary.systemTime?.takeIf { it.isNotBlank() }?.let { add("시스템 시각" to it) }
        summary.useSsl?.takeIf { it.isNotBlank() }?.let { add("SSL 사용" to it) }
        val portInfo = listOfNotNull(
            summary.port?.let { "HTTP $it" },
            summary.portSsl?.let { "HTTPS $it" }
        ).joinToString(" / ")
        if (portInfo.isNotBlank()) add("웹 UI 포트" to portInfo)
        summary.upsModel?.takeIf { it.isNotBlank() }?.let { add("UPS 모델" to it) }
        summary.upsStatus?.takeIf { it.isNotBlank() }?.let { add("UPS 상태" to it) }
        summary.upsBatteryCharge?.let { add("UPS 배터리" to "${it}%") }
        summary.upsRuntimeLeft?.takeIf { it.isNotBlank() }?.let { add("UPS 예상 유지" to it) }
    }

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
            if (rows.isEmpty()) {
                Text(
                    text = "조회된 설정 요약 항목이 없습니다. 아래 바로가기를 통해 웹 UI에서 상세 설정을 확인해 주세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                rows.forEachIndexed { index, (k, v) ->
                    KeyValueSummaryRow(label = k, value = v)
                    if (index < rows.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsShortcutTile(
    shortcut: SettingsQuickShortcut,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = shortcut.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = shortcut.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = shortcut.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

// =============================================================================
// 서브탭 7: 앱 / 플러그인 (Apps & Plugins)
// =============================================================================

internal fun LazyListScope.dashboardAppsTabContent(
    state: DashboardUiState,
    onOpenInstallPluginDialog: () -> Unit,
    onRetry: () -> Unit,
    onOpenUnraidWebUi: (String) -> Unit
) {
    // Community Applications 웹 UI 바로가기 카드
    item(key = "apps_ca_card") {
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
                Text(
                    text = "Community Applications (앱스토어)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Community Applications 앱스토어 검색·설치는 공식 GraphQL API 미지원으로 웹 UI 바로가기에서 이용하거나, 아래에서 .plg URL로 직접 플러그인을 설치할 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { onOpenUnraidWebUi("/Apps") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("앱스토어 열기", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(
                        onClick = onOpenInstallPluginDialog,
                        enabled = !state.isPluginInstallInProgress,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Download, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(".plg 설치", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    item(key = "apps_plugins_header") {
        SectionHeader(
            title = "설치된 Unraid 플러그인",
            subtitle = if (state.plugins.isNotEmpty()) "${state.plugins.size}개" else ""
        )
    }

    when {
        state.isPluginsLoading && state.plugins.isEmpty() -> {
            item(key = "apps_plugins_loading") {
                TabLoadingCard("설치된 플러그인 목록을 불러오는 중...")
            }
        }

        !state.pluginsError.isNullOrBlank() && state.plugins.isEmpty() -> {
            item(key = "apps_plugins_error") {
                UnsupportedFeatureCard(
                    title = "플러그인 목록 조회 불가",
                    description = state.pluginsError.orEmpty(),
                    webUiButtonLabel = "Unraid 플러그인 웹 UI 열기",
                    onOpenWebUi = { onOpenUnraidWebUi("/Plugins") },
                    onRetry = onRetry
                )
            }
        }

        state.plugins.isEmpty() -> {
            item(key = "apps_plugins_empty") {
                EmptySectionCard("조회된 설치 플러그인이 없습니다.")
            }
        }

        else -> {
            items(
                items = state.plugins,
                key = { "plugin_${it.name}_${it.version}" }
            ) { plugin ->
                PluginItemCard(plugin = plugin)
            }
        }
    }

    item(key = "apps_webui_bottom") {
        WebUiShortcutButton(
            label = "Community Applications(앱) 웹 UI 열기 (/Apps)",
            onClick = { onOpenUnraidWebUi("/Apps") }
        )
    }
}

@Composable
private fun PluginItemCard(plugin: UnraidPluginItem) {
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
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plugin.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val tags = buildList {
                        plugin.version?.takeIf { it.isNotBlank() }?.let { add("v$it") }
                        if (plugin.hasApiModule == true) add("API 모듈")
                        if (plugin.hasCliModule == true) add("CLI 모듈")
                    }
                    if (tags.isNotEmpty()) {
                        Text(
                            text = tags.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun InstallPluginByUrlDialog(
    onConfirm: (url: String) -> Unit,
    onDismiss: () -> Unit
) {
    var plgUrl by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(".plg URL로 플러그인 설치") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "신뢰할 수 있는 Unraid 플러그인(.plg) 주소를 입력하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = plgUrl,
                    onValueChange = { plgUrl = it },
                    label = { Text("플러그인 URL (.plg)") },
                    placeholder = { Text("https://raw.githubusercontent.com/.../plugin.plg") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(plgUrl.trim()) },
                enabled = plgUrl.trim().startsWith("http://", ignoreCase = true) ||
                    plgUrl.trim().startsWith("https://", ignoreCase = true)
            ) {
                Text("설치 실행")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}
