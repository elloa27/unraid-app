package com.unraid.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unraid.app.BuildConfig
import com.unraid.app.data.model.DockerServiceBookmark
import com.unraid.app.data.model.ErrorLogEntry
import com.unraid.app.ui.theme.StatusGreen
import com.unraid.app.ui.theme.StatusRed

/**
 * 설정 및 자체 에러 로그 화면.
 * - Unraid 서버 URL, API 키 입력/저장 및 연결 테스트
 * - 도커 웹뷰 목록(이름, URL) 추가/수정/삭제
 * - 자체 에러 로그 확인 및 클립보드 원클릭 복사
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val dockerServices by viewModel.dockerServices.collectAsStateWithLifecycle()
    val errorLogs by viewModel.errorLogs.collectAsStateWithLifecycle()
    val proxyConfig by viewModel.proxyConfig.collectAsStateWithLifecycle()
    val allowSelfSignedSsl by viewModel.allowSelfSignedSsl.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showApiKey by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingService by remember { mutableStateOf<DockerServiceBookmark?>(null) }
    var deletingService by remember { mutableStateOf<DockerServiceBookmark?>(null) }
    var showErrorLogsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.feedbackMessage) {
        val message = uiState.feedbackMessage
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
                    Text(
                        text = "설정",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Unraid 서버 연결 설정 카드
            item(key = "unraid_connection_card") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Unraid 서버 연결 (GraphQL API)",
                            style = MaterialTheme.typography.titleMedium
                        )

                        OutlinedTextField(
                            value = uiState.serverUrlInput,
                            onValueChange = viewModel::onServerUrlChanged,
                            label = { Text("Unraid 서버 URL") },
                            placeholder = { Text("예: http://192.168.0.10") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = uiState.apiKeyInput,
                            onValueChange = viewModel::onApiKeyChanged,
                            label = { Text("Unraid API 키 (암호화 보관)") },
                            placeholder = { Text("x-api-key 값 입력") },
                            singleLine = true,
                            visualTransformation = if (showApiKey) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { showApiKey = !showApiKey }) {
                                    Icon(
                                        imageVector = if (showApiKey) {
                                            Icons.Filled.VisibilityOff
                                        } else {
                                            Icons.Filled.Visibility
                                        },
                                        contentDescription = "API 키 표시 전환"
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        // 개인 NAS 자체 서명 SSL 인증서 허용 스위치 (기본값 ON)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 12.dp)
                            ) {
                                Text(
                                    text = "자체 서명 SSL 인증서 허용 (개인 NAS용)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "켜짐 상태에서는 Unraid API와 도커 웹뷰의 사설 HTTPS 인증서 오류를 무시하고 접속합니다.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = allowSelfSignedSsl,
                                onCheckedChange = viewModel::onAllowSelfSignedSslChanged
                            )
                        }

                        // 연결 테스트 결과 표시 영역
                        val testState = uiState.connectionTest
                        if (testState.isTesting || !testState.message.isNullOrBlank()) {
                            val tint = when (testState.isSuccess) {
                                true -> StatusGreen
                                false -> StatusRed
                                null -> MaterialTheme.colorScheme.primary
                            }
                            Surface(
                                color = tint.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (testState.isTesting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = tint
                                        )
                                    } else {
                                        Icon(
                                            imageVector = if (testState.isSuccess == true) {
                                                Icons.Filled.CheckCircle
                                            } else {
                                                Icons.Filled.Error
                                            },
                                            contentDescription = null,
                                            tint = tint,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = testState.message.orEmpty(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = tint
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = viewModel::testConnection,
                                enabled = !uiState.connectionTest.isTesting,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.WifiFind,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("연결 테스트")
                            }

                            Button(
                                onClick = viewModel::saveServerSettings,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Save,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("저장")
                            }
                        }
                    }
                }
            }

            // 2. 도커 웹뷰 목록 관리 카드
            item(key = "docker_services_card") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "도커 웹뷰 서비스 목록",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Button(
                                onClick = { showAddDialog = true },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("추가")
                            }
                        }

                        Text(
                            text = "Unraid 도커 웹 UI는 자동으로 감지됩니다. 자동 감지가 안 되거나 주소를 직접 지정(커스텀)하고 싶은 서비스만 아래에서 추가하세요. (같은 이름으로 등록하면 자동 감지 주소를 덮어씁니다.)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (dockerServices.isEmpty()) {
                            Text(
                                text = "수동 등록된 커스텀 서비스가 없습니다.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            dockerServices.forEachIndexed { index, service ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = service.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = service.url,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(onClick = { editingService = service }) {
                                        Icon(
                                            imageVector = Icons.Filled.Edit,
                                            contentDescription = "수정",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { deletingService = service }) {
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
                }
            }

            // 3. 자체 에러 로그 및 디버깅 카드
            item(key = "error_log_card") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.BugReport,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "자체 에러 로그",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            Surface(
                                color = if (errorLogs.isEmpty()) {
                                    MaterialTheme.colorScheme.surfaceVariant
                                } else {
                                    StatusRed.copy(alpha = 0.16f)
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "최근 ${errorLogs.size}건",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (errorLogs.isEmpty()) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        StatusRed
                                    }
                                )
                            }
                        }

                        Text(
                            text = "API 오류, GraphQL 스키마 오류, 웹뷰 오류 및 앱 내부 예외가 자동 기록됩니다. 문제 발생 시 로그를 복사해 바로 원인을 확인할 수 있습니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showErrorLogsDialog = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("에러 로그 보기")
                            }
                            Button(
                                onClick = {
                                    copyToClipboard(
                                        context = context,
                                        label = "Unraid Hub Error Logs",
                                        text = viewModel.getFormattedLogsForClipboard()
                                    )
                                    viewModel.showMessage("에러 로그가 클립보드에 복사되었습니다.")
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("로그 복사")
                            }
                        }
                    }
                }
            }

            // 4. 2단계(Tailscale tsnet) 대비 네트워크 레이어 상태 및 앱 정보
            item(key = "network_info_card") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.VpnKey,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "네트워크 프록시 상태 (2단계 tsnet 대비)",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Text(
                            text = if (proxyConfig.isActive) {
                                "활성 프록시: ${proxyConfig.toWebViewProxyRule()} (OkHttp + WebView 일괄 적용)"
                            } else {
                                "현재 모드: 직접 연결 (2단계 Tailscale tsnet 로컬 프록시 주입 준비 완료)"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "앱 버전: v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item(key = "settings_bottom_spacer") {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    // 도커 서비스 추가 다이얼로그
    if (showAddDialog) {
        DockerServiceEditorDialog(
            title = "도커 웹 서비스 추가",
            initialName = "",
            initialUrl = "",
            confirmLabel = "추가",
            onDismiss = { showAddDialog = false },
            onConfirm = { name, url ->
                viewModel.addDockerService(name, url)
                showAddDialog = false
            }
        )
    }

    // 도커 서비스 수정 다이얼로그
    editingService?.let { target ->
        DockerServiceEditorDialog(
            title = "도커 웹 서비스 수정",
            initialName = target.name,
            initialUrl = target.url,
            confirmLabel = "저장",
            onDismiss = { editingService = null },
            onConfirm = { name, url ->
                viewModel.updateDockerService(target.copy(name = name, url = url))
                editingService = null
            }
        )
    }

    // 도커 서비스 삭제 확인 다이얼로그
    deletingService?.let { target ->
        AlertDialog(
            onDismissRequest = { deletingService = null },
            title = { Text("서비스 삭제") },
            text = { Text("'${target.name}' 서비스를 도커 웹뷰 목록에서 삭제할까요?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteDockerService(target)
                        deletingService = null
                    }
                ) {
                    Text("삭제", color = StatusRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingService = null }) {
                    Text("취소")
                }
            }
        )
    }

    // 에러 로그 상세 보기 모달
    if (showErrorLogsDialog) {
        ErrorLogsDialog(
            logs = errorLogs,
            onCopyAll = {
                copyToClipboard(
                    context = context,
                    label = "Unraid Hub Error Logs",
                    text = viewModel.getFormattedLogsForClipboard()
                )
                viewModel.showMessage("에러 로그가 클립보드에 복사되었습니다.")
            },
            onClearAll = { viewModel.clearErrorLogs() },
            onDismiss = { showErrorLogsDialog = false }
        )
    }
}

@Composable
private fun DockerServiceEditorDialog(
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
                    label = { Text("서비스 이름") },
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
private fun ErrorLogsDialog(
    logs: List<ErrorLogEntry>,
    onCopyAll: () -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.84f),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "앱 에러 로그 (${logs.size}건)",
                        style = MaterialTheme.typography.titleLarge
                    )
                    TextButton(onClick = onDismiss) {
                        Text("닫기")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onCopyAll,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("전체 복사")
                    }
                    OutlinedButton(
                        onClick = onClearAll,
                        enabled = logs.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("비우기")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(8.dp))

                if (logs.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "기록된 에러 로그가 없습니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(logs) { entry ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "${entry.timestamp}  [${entry.tag}]",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = entry.message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (!entry.details.isNullOrBlank()) {
                                        Text(
                                            text = entry.details,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            lineHeight = 15.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
}
