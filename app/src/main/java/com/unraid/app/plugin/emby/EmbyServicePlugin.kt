package com.unraid.app.plugin.emby

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unraid.app.UnraidApp
import com.unraid.app.network.NetworkProxyManager
import com.unraid.app.plugin.LauncherService
import com.unraid.app.plugin.ServicePlugin
import com.unraid.app.ui.theme.StatusGreen
import com.unraid.app.ui.theme.StatusRed
import kotlinx.coroutines.launch
import java.net.URI

/**
 * 첫 번째 공식 서비스 전용 네이티브 UI 플러그인: Emby + libmpv Direct Play 플레이어.
 *
 * - 도커 이미지명/컨테이너명/서비스 이름에 "emby"가 포함되거나 웹 UI 포트가 8096/8920이면 자동 매칭됩니다.
 * - 설정 화면의 '플러그인별 설정' 영역에 Emby 로그인, 서버 설정, ASS/SRT 자막 폰트 관리 UI를 자동으로 제공합니다.
 */
class EmbyServicePlugin : ServicePlugin {

    override val pluginId: String = "emby"

    override val displayName: String = "Emby 전용 UI"

    override val badgeLabel: String = "Native"

    override val description: String =
        "libmpv Direct Play(트랜스코딩 없음)와 내장 Tailscale 프록시·이어보기 동기화를 지원하는 Emby 네이티브 플레이어입니다."

    override val hasSettings: Boolean = true

    override fun matches(service: LauncherService): Boolean {
        if (service.imageName?.contains("emby", ignoreCase = true) == true) return true
        if (service.containerName?.contains("emby", ignoreCase = true) == true) return true
        if (service.name.contains("emby", ignoreCase = true)) return true

        val port = runCatching { URI(service.url).port }.getOrNull()
        return port == 8096 || port == 8920
    }

    @Composable
    override fun Content(
        service: LauncherService,
        onOpenWebViewFallback: () -> Unit,
        onExit: () -> Unit
    ) {
        val app = LocalContext.current.applicationContext as UnraidApp
        EmbyNativeRootScreen(
            service = service,
            apiClient = app.embyApiClient,
            credentialStore = app.embyCredentialStore,
            onOpenWebViewFallback = onOpenWebViewFallback,
            onExit = onExit
        )
    }

    @Composable
    override fun SettingsContent() {
        val context = LocalContext.current
        val app = context.applicationContext as UnraidApp
        val credentialStore = app.embyCredentialStore
        val apiClient = app.embyApiClient
        val config by credentialStore.configFlow.collectAsStateWithLifecycle()
        val proxyConfig by NetworkProxyManager.proxyConfig.collectAsStateWithLifecycle()

        var serverUrl by remember(config.serverUrl) { mutableStateOf(config.serverUrl) }
        var tailscaleUrl by remember(config.tailscaleServerUrl) { mutableStateOf(config.tailscaleServerUrl) }
        var username by remember(config.username) { mutableStateOf(config.username) }
        var password by remember { mutableStateOf("") }
        var showPassword by remember { mutableStateOf(false) }
        var isLoggingIn by remember { mutableStateOf(false) }
        var statusMessage by remember { mutableStateOf<String?>(null) }
        var statusIsError by remember { mutableStateOf(false) }

        // ASS / SRT 자막 폰트 설정 상태
        var subtitleFontName by remember(config.subtitleFontName) { mutableStateOf(config.subtitleFontName) }
        var forceFontOnAss by remember(config.forceSubtitleFontOnAss) { mutableStateOf(config.forceSubtitleFontOnAss) }
        var installedFonts by remember { mutableStateOf(EmbyFontStorage.listInstalledFonts(context)) }
        var fontStatusMessage by remember { mutableStateOf<String?>(null) }
        var fontStatusIsError by remember { mutableStateOf(false) }

        val fontPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            var successCount = 0
            var lastError: String? = null
            uris.forEach { uri ->
                val result = EmbyFontStorage.importFontUri(context, uri)
                result.onSuccess { successCount += 1 }
                    .onFailure { err -> lastError = err.localizedMessage }
            }
            installedFonts = EmbyFontStorage.listInstalledFonts(context)
            if (successCount > 0) {
                fontStatusMessage = "폰트 파일 ${successCount}개를 추가했습니다. 다음 재생부터 libass에 자동 적용됩니다."
                fontStatusIsError = false
            } else if (!lastError.isNullOrBlank()) {
                fontStatusMessage = lastError
                fontStatusIsError = true
            }
        }

        val scope = rememberCoroutineScope()

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 현재 로그인 상태 배지
            Surface(
                color = if (config.isAuthenticated) {
                    StatusGreen.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (config.isAuthenticated) {
                                "로그인됨 (${config.username})"
                            } else {
                                "Emby 계정 미로그인"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (config.isAuthenticated) StatusGreen else MaterialTheme.colorScheme.onSurface
                        )
                        if (config.serverUrl.isNotBlank()) {
                            Text(
                                text = config.serverUrl,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (config.isAuthenticated) {
                        OutlinedButton(
                            onClick = {
                                credentialStore.logout()
                                statusMessage = "Emby에서 로그아웃했습니다."
                                statusIsError = false
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Logout,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("로그아웃")
                        }
                    }
                }
            }

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("Emby 서버 URL (기본)") },
                placeholder = { Text("비워두면 매칭된 도커 웹 UI 주소 자동 사용") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = tailscaleUrl,
                onValueChange = { tailscaleUrl = it },
                label = { Text("Tailscale용 Emby URL (선택)") },
                placeholder = { Text("비워두면 기본 Emby URL 그대로 사용") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // 하드웨어 디코딩(hwdec) 기본 설정
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp)
                ) {
                    Text(
                        text = "하드웨어 디코딩 기본 사용 (hwdec=auto-safe)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "끄면 소프트웨어 디코딩으로 재생합니다. 플레이어 안에서도 즉시 전환 가능합니다.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.preferHardwareDecoding,
                    onCheckedChange = { enabled ->
                        credentialStore.setPreferHardwareDecoding(enabled)
                    }
                )
            }

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Emby 아이디") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Emby 비밀번호") },
                singleLine = true,
                visualTransformation = if (showPassword) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) {
                                Icons.Filled.VisibilityOff
                            } else {
                                Icons.Filled.Visibility
                            },
                            contentDescription = "비밀번호 표시 전환"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            if (!statusMessage.isNullOrBlank()) {
                val tint = if (statusIsError) StatusRed else StatusGreen
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (statusIsError) Icons.Filled.Error else Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = statusMessage.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = tint
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        credentialStore.saveServerUrls(serverUrl, tailscaleUrl)
                        statusMessage = "Emby 서버 URL이 저장되었습니다."
                        statusIsError = false
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("URL 저장")
                }

                Button(
                    onClick = {
                        val targetUrl = if (proxyConfig.isActive && tailscaleUrl.isNotBlank()) {
                            tailscaleUrl
                        } else {
                            serverUrl
                        }
                        if (targetUrl.isBlank()) {
                            statusMessage = "Emby 서버 URL을 입력해 주세요."
                            statusIsError = true
                            return@Button
                        }
                        scope.launch {
                            isLoggingIn = true
                            statusMessage = null
                            val res = apiClient.authenticateByName(targetUrl, username, password)
                            res.onSuccess { login ->
                                credentialStore.saveLoginSession(
                                    serverUrl = serverUrl.ifBlank { targetUrl },
                                    tailscaleServerUrl = tailscaleUrl,
                                    accessToken = login.accessToken,
                                    userId = login.userId,
                                    username = login.username
                                )
                                password = ""
                                statusMessage = "Emby 로그인 성공 (${login.username})"
                                statusIsError = false
                            }.onFailure { err ->
                                statusMessage = err.localizedMessage ?: "Emby 로그인 실패"
                                statusIsError = true
                            }
                            isLoggingIn = false
                        }
                    },
                    enabled = !isLoggingIn && username.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    if (isLoggingIn) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Login, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isLoggingIn) "연결 중..." else "로그인 / 테스트")
                }
            }

            // ASS / SRT 자막 폰트 설정 섹션
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.FontDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "자막 폰트 설정 (ASS / SRT)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "ASS 자막에서 사용하는 폰트 파일(.ttf/.otf/.ttc)을 여러 개 추가하면 자막에 지정된 스타일별 폰트가 각각 원본대로 표시됩니다. 원하는 기본 폰트 이름을 직접 입력할 수도 있습니다.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 폰트 파일(.ttf/.otf/.ttc) 추가 버튼 및 설치된 폰트 목록
            FilledTonalButton(
                onClick = { fontPickerLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("폰트 파일 추가 (.ttf / .otf / .ttc · 현재 ${installedFonts.size}개)")
            }

            if (installedFonts.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        installedFonts.forEach { fontFile ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = fontFile.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        EmbyFontStorage.deleteFont(fontFile)
                                        installedFonts = EmbyFontStorage.listInstalledFonts(context)
                                        fontStatusMessage = "'${fontFile.name}' 폰트를 삭제했습니다."
                                        fontStatusIsError = false
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "폰트 삭제",
                                        tint = StatusRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 기본 자막 폰트 이름 직접 입력칸
            OutlinedTextField(
                value = subtitleFontName,
                onValueChange = { subtitleFontName = it },
                label = { Text("적용할 자막 폰트 이름 (sub-font)") },
                placeholder = { Text("예: NanumGothic, Pretendard, KoPubWorldDotum") },
                supportingText = {
                    Text("SRT 기본 폰트 및 ASS 대체 폰트로 사용됩니다. 위에서 추가한 폰트 내부 이름(패밀리명)을 입력하세요.")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // ASS 스타일에 입력한 폰트 강제 덮어쓰기 스위치
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp)
                ) {
                    Text(
                        text = "ASS 자막에도 입력한 폰트 강제 적용",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "끄면 ASS 자막의 스타일별 다중 폰트를 유지하고, 켜면 입력한 폰트로 일괄 변경합니다.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = forceFontOnAss,
                    onCheckedChange = { checked ->
                        forceFontOnAss = checked
                        credentialStore.saveSubtitleFontSettings(subtitleFontName, checked)
                    }
                )
            }

            if (!fontStatusMessage.isNullOrBlank()) {
                val tint = if (fontStatusIsError) StatusRed else StatusGreen
                Text(
                    text = fontStatusMessage.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = tint
                )
            }

            OutlinedButton(
                onClick = {
                    credentialStore.saveSubtitleFontSettings(subtitleFontName, forceFontOnAss)
                    fontStatusMessage = if (subtitleFontName.isBlank()) {
                        "기본 폰트 이름을 비웠습니다 (자동/ASS 원본 폰트 사용)."
                    } else {
                        "자막 폰트 '${subtitleFontName.trim()}' 설정이 저장되었습니다."
                    }
                    fontStatusIsError = false
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("자막 폰트 설정 저장")
            }
        }
    }
}
