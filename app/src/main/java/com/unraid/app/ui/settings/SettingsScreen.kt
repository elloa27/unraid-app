package com.unraid.app.ui.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.unraid.app.data.model.ConnectionMode
import com.unraid.app.data.model.DockerServiceBookmark
import com.unraid.app.data.model.ErrorLogEntry
import com.unraid.app.data.model.RoutePhase
import com.unraid.app.player.MpvFontStorage
import com.unraid.app.player.PlayerSettingsStore
import com.unraid.app.plugin.ExternalPluginManager
import com.unraid.app.plugin.InstalledPlugin
import com.unraid.app.ui.theme.StatusAmber
import com.unraid.app.ui.theme.StatusBlue
import com.unraid.app.ui.theme.StatusGreen
import com.unraid.app.ui.theme.StatusRed
import kotlinx.coroutines.launch

/**
 * 설정 및 자체 에러 로그 화면.
 * - Unraid 서버 URL, API 키 입력/저장, 자체 서명 SSL 허용, 연결 테스트
 * - 내장 Tailscale(tsnet) Auth Key, 홈 Wi-Fi 자동 전환 모드 및 현재 Wi-Fi 등록
 * - 도커 웹뷰 목록 수동 추가/수정/삭제
 * - 자체 에러 로그 확인 및 클립보드 원클릭 복사
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    externalPluginManager: ExternalPluginManager,
    playerSettingsStore: PlayerSettingsStore,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val dockerServices by viewModel.dockerServices.collectAsStateWithLifecycle()
    val errorLogs by viewModel.errorLogs.collectAsStateWithLifecycle()
    val allowSelfSignedSsl by viewModel.allowSelfSignedSsl.collectAsStateWithLifecycle()
    val connectionMode by viewModel.connectionMode.collectAsStateWithLifecycle()
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val installedPlugins by externalPluginManager.installedPlugins.collectAsStateWithLifecycle()
    val playerSettings by playerSettingsStore.settingsFlow.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showApiKey by remember { mutableStateOf(false) }
    var showTsAuthKey by remember { mutableStateOf(false) }
    var showSmbPassword by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingService by remember { mutableStateOf<DockerServiceBookmark?>(null) }
    var deletingService by remember { mutableStateOf<DockerServiceBookmark?>(null) }
    var showResetTsDialog by remember { mutableStateOf(false) }
    var showErrorLogsDialog by remember { mutableStateOf(false) }

    // 외부 플러그인 관리 UI 상태
    var isPluginBusy by remember { mutableStateOf(false) }
    var showInstallUrlDialog by remember { mutableStateOf(false) }
    var deletingPlugin by remember { mutableStateOf<InstalledPlugin?>(null) }

    // 내장 libmpv 자막 폰트 UI 상태
    var installedFonts by remember { mutableStateOf(MpvFontStorage.listInstalledFonts(context)) }
    var isFontListExpanded by remember { mutableStateOf(false) }
    var subtitleFontNameInput by remember(playerSettings.subtitleFontName) {
        mutableStateOf(playerSettings.subtitleFontName)
    }
    var forceSubtitleFontOnAss by remember(playerSettings.forceSubtitleFontOnAss) {
        mutableStateOf(playerSettings.forceSubtitleFontOnAss)
    }

    // Android 10+ Wi-Fi SSID 감지를 위한 위치 권한 요청 런처
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        viewModel.onLocationPermissionResult(fineGranted)
    }

    // 로컬 .zip 파일 선택기(OpenDocument)
    val pluginZipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isPluginBusy = true
            coroutineScope.launch {
                val result = externalPluginManager.installFromUri(uri)
                isPluginBusy = false
                result.onSuccess { installed ->
                    viewModel.showMessage("'${installed.name}' v${installed.version} 플러그인이 설치되었습니다.")
                }.onFailure { err ->
                    viewModel.showMessage(err.localizedMessage ?: "ZIP 플러그인 설치에 실패했습니다.")
                }
            }
        }
    }

    // 자막 폰트 파일(.ttf / .otf / .ttc) 다중 선택기
    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            var successCount = 0
            var lastError: String? = null
            uris.forEach { uri ->
                MpvFontStorage.importFontUri(context, uri)
                    .onSuccess { successCount += 1 }
                    .onFailure { lastError = it.localizedMessage }
            }
            installedFonts = MpvFontStorage.listInstalledFonts(context)
            when {
                successCount > 0 && lastError == null ->
                    viewModel.showMessage("자막 폰트 ${successCount}개가 추가되었습니다.")
                successCount > 0 ->
                    viewModel.showMessage("폰트 ${successCount}개 추가 완료 (일부 오류: $lastError)")
                else ->
                    viewModel.showMessage(lastError ?: "자막 폰트 추가에 실패했습니다.")
            }
        }
    }

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
                            label = { Text("기본 Unraid 서버 URL (홈 Wi-Fi용)") },
                            placeholder = { Text("예: https://192.168.0.10") },
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

            // 2. Tailscale(tsnet) 내장 및 홈 Wi-Fi 자동 전환 카드
            item(key = "tailscale_auto_switch_card") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.VpnLock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Tailscale 및 자동 전환",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        // 실시간 연결 상태 요약 패널
                        val statusColor = when (connectionStatus.phase) {
                            RoutePhase.DIRECT_HOME_WIFI -> StatusGreen
                            RoutePhase.DIRECT_MANUAL -> StatusBlue
                            RoutePhase.TAILSCALE_CONNECTING -> StatusAmber
                            RoutePhase.TAILSCALE_CONNECTED -> MaterialTheme.colorScheme.primary
                            RoutePhase.TAILSCALE_ERROR -> StatusRed
                        }
                        Surface(
                            color = statusColor.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (connectionStatus.phase == RoutePhase.TAILSCALE_CONNECTING) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = statusColor
                                        )
                                    }
                                    Text(
                                        text = "현재 경로: ${connectionStatus.badgeLabel}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = statusColor
                                    )
                                }
                                Text(
                                    text = "상태: ${connectionStatus.tailscaleDetail}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                val wifiInfoLine = when {
                                    !connectionStatus.isOnWifi -> "현재 네트워크: 모바일 데이터 (LTE/5G) 또는 비 Wi-Fi"
                                    !connectionStatus.currentWifiSsid.isNullOrBlank() ->
                                        "현재 Wi-Fi: ${connectionStatus.currentWifiSsid}"
                                    !connectionStatus.hasLocationPermission ->
                                        "현재 Wi-Fi: 감지 대기 (아래 '현재 Wi-Fi 등록'으로 위치 권한 허용 필요)"
                                    else -> "현재 Wi-Fi: 이름 확인 불가 (기기 위치/GPS 켜짐 여부 확인)"
                                }
                                Text(
                                    text = wifiInfoLine,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!connectionStatus.lastError.isNullOrBlank()) {
                                    Text(
                                        text = "오류: ${connectionStatus.lastError}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = StatusRed
                                    )
                                }
                            }
                        }

                        // 연결 모드 선택 (자동 전환 / 항상 직접 연결 / 항상 Tailscale)
                        Text(
                            text = "연결 모드",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ConnectionMode.entries.forEach { mode ->
                                val selected = connectionMode == mode
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    border = if (selected) {
                                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                                    } else {
                                        null
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.onConnectionModeSelected(mode) }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = selected,
                                            onClick = { viewModel.onConnectionModeSelected(mode) }
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = mode.labelKo,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = mode.descriptionKo,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

                        // 홈 Wi-Fi SSID 입력 + 현재 Wi-Fi 등록 버튼
                        OutlinedTextField(
                            value = uiState.homeWifiSsidInput,
                            onValueChange = viewModel::onHomeWifiSsidChanged,
                            label = { Text("홈 Wi-Fi 이름 (SSID)") },
                            placeholder = { Text("예: MyHome_5G") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        FilledTonalButton(
                            onClick = {
                                if (viewModel.hasLocationPermission()) {
                                    viewModel.registerCurrentWifiSsid()
                                } else {
                                    locationPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MyLocation,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("현재 Wi-Fi 등록 (위치 권한 확인)")
                        }

                        // Tailscale Auth Key 입력 (EncryptedSharedPreferences 암호화 보관)
                        OutlinedTextField(
                            value = uiState.tailscaleAuthKeyInput,
                            onValueChange = viewModel::onTailscaleAuthKeyChanged,
                            label = { Text("Tailscale Auth Key (암호화 보관)") },
                            placeholder = { Text("tskey-auth-...") },
                            singleLine = true,
                            visualTransformation = if (showTsAuthKey) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { showTsAuthKey = !showTsAuthKey }) {
                                    Icon(
                                        imageVector = if (showTsAuthKey) {
                                            Icons.Filled.VisibilityOff
                                        } else {
                                            Icons.Filled.Visibility
                                        },
                                        contentDescription = "Auth Key 표시 전환"
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        // 외부(Tailscale)용 Unraid URL (선택 입력)
                        OutlinedTextField(
                            value = uiState.tailscaleServerUrlInput,
                            onValueChange = viewModel::onTailscaleServerUrlChanged,
                            label = { Text("Tailscale용 Unraid URL (선택)") },
                            placeholder = { Text("비워두면 기본 Unraid URL 그대로 사용") },
                            supportingText = {
                                Text("서브넷 라우터를 사용 중이면 비워두고, Tailscale IP/MagicDNS로 접속할 때만 입력하세요.")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            onClick = viewModel::saveTailscaleSettings,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Save,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Tailscale 설정 저장")
                        }

                        // 수동 재연결 / 상태 초기화 버튼
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = viewModel::reconnectTailscale,
                                enabled = connectionStatus.phase != RoutePhase.TAILSCALE_CONNECTING,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("수동 재연결")
                            }

                            OutlinedButton(
                                onClick = { showResetTsDialog = true },
                                enabled = connectionStatus.phase != RoutePhase.TAILSCALE_CONNECTING,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.RestartAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = StatusRed
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("상태 초기화", color = StatusRed)
                            }
                        }
                    }
                }
            }

            // 2-B. SMB 공유 드라이브 계정 설정 카드
            item(key = "smb_credentials_card") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "SMB 파일 공유 계정 (공유 드라이브)",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 12.dp)
                            ) {
                                Text(
                                    text = "게스트(익명) 접속 사용",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "공개(Public) 공유만 이용할 때 켜세요.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = uiState.smbUseGuest,
                                onCheckedChange = viewModel::onSmbUseGuestChanged
                            )
                        }

                        if (!uiState.smbUseGuest) {
                            OutlinedTextField(
                                value = uiState.smbUsernameInput,
                                onValueChange = viewModel::onSmbUsernameChanged,
                                label = { Text("SMB 사용자 이름") },
                                placeholder = { Text("Unraid 사용자 아이디") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = uiState.smbPasswordInput,
                                onValueChange = viewModel::onSmbPasswordChanged,
                                label = { Text("SMB 비밀번호 (암호화 보관)") },
                                singleLine = true,
                                visualTransformation = if (showSmbPassword) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(onClick = { showSmbPassword = !showSmbPassword }) {
                                        Icon(
                                            imageVector = if (showSmbPassword) {
                                                Icons.Filled.VisibilityOff
                                            } else {
                                                Icons.Filled.Visibility
                                            },
                                            contentDescription = "SMB 비밀번호 표시 전환"
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        OutlinedTextField(
                            value = uiState.smbCustomHostInput,
                            onValueChange = viewModel::onSmbCustomHostChanged,
                            label = { Text("사용자 지정 SMB 호스트 (선택)") },
                            placeholder = { Text("비워두면 Unraid 서버 주소에서 자동 추출") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        val smbTest = uiState.smbConnectionTest
                        if (smbTest.isTesting || !smbTest.message.isNullOrBlank()) {
                            val tint = when (smbTest.isSuccess) {
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
                                    if (smbTest.isTesting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = tint
                                        )
                                    } else {
                                        Icon(
                                            imageVector = if (smbTest.isSuccess == true) {
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
                                        text = smbTest.message.orEmpty(),
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
                                onClick = viewModel::testSmbConnection,
                                enabled = !uiState.smbConnectionTest.isTesting,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.WifiFind,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("SMB 테스트")
                            }

                            Button(
                                onClick = viewModel::saveSmbSettings,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Save,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("SMB 저장")
                            }
                        }
                    }
                }
            }

            // 3. 도커 웹뷰 목록 관리 카드
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

            // 4. 외부 플러그인 관리 카드 (.zip 파일 / URL 동적 설치·업데이트·삭제)
            item(key = "external_plugins_card") {
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
                                    imageVector = Icons.Filled.Extension,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "외부 플러그인 관리",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "${installedPlugins.size}개 설치됨",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Text(
                            text = "앱을 다시 빌드하지 않고도 .zip 패키지나 원격 URL로 서비스 전용 UI 플러그인을 설치·업데이트할 수 있습니다.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    pluginZipPickerLauncher.launch(
                                        arrayOf("application/zip", "application/octet-stream", "*/*")
                                    )
                                },
                                enabled = !isPluginBusy,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.FolderZip,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("ZIP 파일로 설치")
                            }

                            OutlinedButton(
                                onClick = { showInstallUrlDialog = true },
                                enabled = !isPluginBusy,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CloudDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("URL로 설치")
                            }
                        }

                        if (isPluginBusy) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "플러그인 패키지를 처리하는 중...",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        if (installedPlugins.isEmpty()) {
                            Text(
                                text = "설치된 외부 플러그인이 없습니다. 위 버튼으로 manifest.json이 포함된 .zip 패키지를 설치해 주세요.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            installedPlugins.forEachIndexed { index, plugin ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                    )
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = plugin.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Surface(
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = "v${plugin.version}",
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            val updateUrl = plugin.manifest.updateUrl?.takeIf { it.isNotBlank() }
                                            if (updateUrl != null) {
                                                IconButton(
                                                    onClick = {
                                                        isPluginBusy = true
                                                        coroutineScope.launch {
                                                            val result = externalPluginManager.installFromUrl(updateUrl)
                                                            isPluginBusy = false
                                                            result.onSuccess { updated ->
                                                                viewModel.showMessage(
                                                                    "'${updated.name}' v${updated.version} 업데이트가 완료되었습니다."
                                                                )
                                                            }.onFailure { err ->
                                                                viewModel.showMessage(
                                                                    err.localizedMessage ?: "플러그인 업데이트에 실패했습니다."
                                                                )
                                                            }
                                                        }
                                                    },
                                                    enabled = !isPluginBusy
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.SystemUpdateAlt,
                                                        contentDescription = "업데이트",
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                            IconButton(
                                                onClick = { deletingPlugin = plugin },
                                                enabled = !isPluginBusy
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Delete,
                                                    contentDescription = "플러그인 삭제",
                                                    tint = StatusRed
                                                )
                                            }
                                        }
                                    }

                                    Text(
                                        text = "ID: ${plugin.id} · ${plugin.matchSummaryText()}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (plugin.description.isNotBlank()) {
                                        Text(
                                            text = plugin.description,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 5. 내장 비디오 플레이어(libmpv) 전역 설정 카드
            item(key = "native_mpv_settings_card") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.PlayCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "내장 비디오 플레이어 (libmpv)",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "하드웨어 디코딩 우선 사용 (hwdec)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "재생 화면 우측 상단에서 SW/HW 실시간 전환도 가능합니다.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = playerSettings.preferHardwareDecoding,
                                onCheckedChange = { enabled ->
                                    playerSettingsStore.setPreferHardwareDecoding(enabled)
                                }
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "자막 폰트 파일 (.ttf / .otf / .ttc)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "ASS/SSA 자막과 일반 자막(SRT)에 libmpv가 사용할 폰트를 추가하세요.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            FilledTonalButton(
                                onClick = {
                                    fontPickerLauncher.launch(
                                        arrayOf(
                                            "font/ttf",
                                            "font/otf",
                                            "application/x-font-ttf",
                                            "application/x-font-otf",
                                            "application/octet-stream",
                                            "*/*"
                                        )
                                    )
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.FontDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("폰트 추가")
                            }
                        }

                        if (installedFonts.isEmpty()) {
                            Text(
                                text = "등록된 커스텀 자막 폰트가 없습니다.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            // 다량의 폰트를 추가해도 화면이 길어지지 않도록 접기/펼치기 지원
                            Surface(
                                onClick = { isFontListExpanded = !isFontListExpanded },
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "등록된 자막 폰트 ${installedFonts.size}개 (${if (isFontListExpanded) "접기" else "펼치기"})",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Icon(
                                        imageVector = if (isFontListExpanded) {
                                            Icons.Filled.ExpandLess
                                        } else {
                                            Icons.Filled.ExpandMore
                                        },
                                        contentDescription = if (isFontListExpanded) "폰트 목록 접기" else "폰트 목록 펼치기",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            if (isFontListExpanded) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    installedFonts.forEach { fontFile ->
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = fontFile.name,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    modifier = Modifier.weight(1f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                IconButton(
                                                    onClick = {
                                                        MpvFontStorage.deleteFont(fontFile)
                                                        installedFonts = MpvFontStorage.listInstalledFonts(context)
                                                        viewModel.showMessage("폰트 '${fontFile.name}'을(를) 삭제했습니다.")
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Delete,
                                                        contentDescription = "폰트 삭제",
                                                        tint = StatusRed,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = subtitleFontNameInput,
                            onValueChange = { subtitleFontNameInput = it },
                            label = { Text("기본 자막 폰트 이름 (sub-font, 선택)") },
                            placeholder = { Text("예: NanumGothic, Pretendard, Noto Sans CJK KR") },
                            supportingText = {
                                Text("비워두면 ASS 자막 자체 폰트 스타일을 그대로 따릅니다.")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "ASS 자막에도 입력한 폰트 강제 적용",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "켜면 ASS 내부 스타일의 폰트명을 위 폰트로 덮어씁니다.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = forceSubtitleFontOnAss,
                                onCheckedChange = { forceSubtitleFontOnAss = it }
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                playerSettingsStore.saveSubtitleFontSettings(
                                    fontName = subtitleFontNameInput,
                                    forceOnAss = forceSubtitleFontOnAss
                                )
                                viewModel.showMessage("내장 플레이어 자막 폰트 설정이 저장되었습니다.")
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Save,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("자막 폰트 설정 저장")
                        }
                    }
                }
            }

            // 5. 자체 에러 로그 및 디버깅 카드
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
                            text = "API 오류, 웹뷰 오류, 위치 권한 누락 및 내장 Tailscale(tsnet) 시작 실패 로그가 자동 기록됩니다.",
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

            // 5. 앱 버전 정보
            item(key = "app_version_card") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Unraid Hub · v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "내장 Tailscale(tsnet arm64) · 외부 플러그인 엔진 · libmpv 플레이어 지원",
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

    // Tailscale 상태 초기화 확인 다이얼로그
    if (showResetTsDialog) {
        AlertDialog(
            onDismissRequest = { showResetTsDialog = false },
            title = { Text("Tailscale 상태 초기화") },
            text = {
                Text(
                    "앱 내부에 저장된 Tailscale 노드 키와 상태를 모두 삭제합니다. 이후 재연결하려면 유효한 Auth Key가 필요합니다. 계속할까요?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetTsDialog = false
                        viewModel.resetTailscaleState()
                    }
                ) {
                    Text("초기화", color = StatusRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetTsDialog = false }) {
                    Text("취소")
                }
            }
        )
    }

    // 외부 플러그인 URL 설치 다이얼로그
    if (showInstallUrlDialog) {
        InstallPluginUrlDialog(
            onDismiss = { showInstallUrlDialog = false },
            onConfirm = { rawUrl ->
                showInstallUrlDialog = false
                isPluginBusy = true
                coroutineScope.launch {
                    val result = externalPluginManager.installFromUrl(rawUrl)
                    isPluginBusy = false
                    result.onSuccess { installed ->
                        viewModel.showMessage("'${installed.name}' v${installed.version} 플러그인이 설치되었습니다.")
                    }.onFailure { err ->
                        viewModel.showMessage(err.localizedMessage ?: "URL 플러그인 설치에 실패했습니다.")
                    }
                }
            }
        )
    }

    // 외부 플러그인 삭제 확인 다이얼로그
    deletingPlugin?.let { target ->
        AlertDialog(
            onDismissRequest = { deletingPlugin = null },
            title = { Text("외부 플러그인 삭제") },
            text = {
                Text(
                    "'${target.name}' (v${target.version}) 플러그인과 해당 플러그인이 저장한 로그인/설정 데이터를 함께 삭제할까요?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deletingPlugin = null
                        isPluginBusy = true
                        coroutineScope.launch {
                            val result = externalPluginManager.uninstallPlugin(target.id)
                            isPluginBusy = false
                            result.onSuccess {
                                viewModel.showMessage("'${target.name}' 플러그인이 삭제되었습니다.")
                            }.onFailure { err ->
                                viewModel.showMessage(err.localizedMessage ?: "플러그인 삭제에 실패했습니다.")
                            }
                        }
                    }
                ) {
                    Text("삭제", color = StatusRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingPlugin = null }) {
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
private fun InstallPluginUrlDialog(
    onDismiss: () -> Unit,
    onConfirm: (url: String) -> Unit
) {
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("URL로 외부 플러그인 설치") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "GitHub Releases 등의 .zip 파일 직링크(http:// 또는 https://)를 입력하세요. 동일 ID 플러그인이 있으면 자동으로 업데이트됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("플러그인 ZIP URL") },
                    placeholder = { Text("https://example.com/emby-plugin.zip") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(url.trim()) },
                enabled = url.trim().startsWith("http://", ignoreCase = true) ||
                    url.trim().startsWith("https://", ignoreCase = true)
            ) {
                Text("설치")
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
