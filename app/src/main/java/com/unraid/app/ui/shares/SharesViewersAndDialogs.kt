package com.unraid.app.ui.shares

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.unraid.app.data.local.SmbCredentials
import com.unraid.app.shares.SmbFileEntry
import com.unraid.app.ui.theme.StatusGreen
import com.unraid.app.ui.theme.StatusRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SMB 계정 입력 / 수정 / 연결 테스트 다이얼로그
 */
@Composable
internal fun SmbAccountDialog(
    initialCredentials: SmbCredentials,
    autoHost: String,
    testState: SmbTestResultState,
    onTestConnection: (username: String, password: String, useGuest: Boolean, customHost: String) -> Unit,
    onSave: (username: String, password: String, useGuest: Boolean, customHost: String) -> Unit,
    onDismiss: () -> Unit
) {
    var username by remember(initialCredentials) { mutableStateOf(initialCredentials.username) }
    var password by remember(initialCredentials) { mutableStateOf(initialCredentials.password) }
    var useGuest by remember(initialCredentials) { mutableStateOf(initialCredentials.useGuest) }
    var customHost by remember(initialCredentials) { mutableStateOf(initialCredentials.customHost) }
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unraid SMB 계정 설정") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "게스트(익명) 접속 사용",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "공개(Public) 공유 폴더만 이용할 때 켜세요.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = useGuest, onCheckedChange = { useGuest = it })
                }

                if (!useGuest) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("SMB 사용자 이름") },
                        placeholder = { Text("Unraid 사용자 아이디") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("SMB 비밀번호 (암호화 보관)") },
                        singleLine = true,
                        visualTransformation = if (showPassword) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = "비밀번호 표시 전환"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = customHost,
                    onValueChange = { customHost = it },
                    label = { Text("사용자 지정 SMB 호스트 (선택)") },
                    placeholder = {
                        Text(if (autoHost.isNotBlank()) "비워두면 자동 사용: $autoHost" else "예: 192.168.0.10")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

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
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (testState.isTesting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = tint
                                )
                            } else {
                                Icon(
                                    imageVector = if (testState.isSuccess == true) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                    contentDescription = null,
                                    tint = tint,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                text = testState.message.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = tint
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = { onTestConnection(username, password, useGuest, customHost) },
                    enabled = !testState.isTesting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.WifiFind, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("SMB 연결 테스트")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(username, password, useGuest, customHost) }
            ) {
                Icon(Icons.Filled.Save, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기")
            }
        }
    )
}

/**
 * 파일/폴더 우측 메뉴 및 길게 누르기 액션 바텀시트
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntryActionBottomSheet(
    entry: SmbFileEntry,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDetails: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (entry.isDirectory) "폴더" else "${formatFileBytes(entry.sizeBytes)} · ${formatModifiedDate(entry.lastModifiedMillis)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            SheetActionItem(
                icon = if (entry.isVideo) Icons.Filled.PlayArrow else Icons.AutoMirrored.Filled.OpenInNew,
                label = if (entry.isVideo) "내장 플레이어로 즉시 재생" else "열기",
                tint = MaterialTheme.colorScheme.primary,
                onClick = onOpen
            )
            if (!entry.isDirectory) {
                SheetActionItem(
                    icon = Icons.Filled.Download,
                    label = "다운로드 (Downloads/UnraidHub)",
                    onClick = onDownload
                )
            }
            SheetActionItem(
                icon = Icons.Filled.Edit,
                label = "이름 변경",
                onClick = onRename
            )
            SheetActionItem(
                icon = Icons.Filled.Info,
                label = "상세 정보",
                onClick = onDetails
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            SheetActionItem(
                icon = Icons.Filled.Delete,
                label = "삭제",
                tint = StatusRed,
                onClick = onDelete
            )
        }
    }
}

@Composable
private fun SheetActionItem(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = tint
            )
        }
    }
}

@Composable
internal fun AddManualShareDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var shareName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("공유 폴더 직접 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "숨김 공유 또는 Unraid SMB 공유 폴더 이름을 입력하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = shareName,
                    onValueChange = { shareName = it },
                    label = { Text("공유 이름") },
                    placeholder = { Text("예: media, downloads, backup") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(shareName.trim()) },
                enabled = shareName.isNotBlank()
            ) {
                Text("추가")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

@Composable
internal fun CreateFolderDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var folderName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("새 폴더 만들기") },
        text = {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text("폴더 이름") },
                placeholder = { Text("새 폴더 이름 입력") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(folderName.trim()) },
                enabled = folderName.isNotBlank()
            ) {
                Text("생성")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

@Composable
internal fun RenameEntryDialog(
    entry: SmbFileEntry,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember(entry) { mutableStateOf(entry.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이름 변경") },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("새 이름") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(newName.trim()) },
                enabled = newName.isNotBlank() && newName.trim() != entry.name
            ) {
                Text("변경")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

@Composable
internal fun DeleteEntryConfirmDialog(
    entry: SmbFileEntry,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("항목 삭제 확인") },
        text = {
            Text(
                text = if (entry.isDirectory) {
                    "'${entry.name}' 폴더와 내부의 모든 파일/하위 폴더를 영구 삭제하시겠습니까? 삭제 후 복구할 수 없습니다."
                } else {
                    "'${entry.name}' 파일을 서버에서 영구 삭제하시겠습니까? 삭제 후 복구할 수 없습니다."
                }
            )
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
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

@Composable
internal fun EntryDetailsDialog(
    shareName: String,
    entry: SmbFileEntry,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("상세 정보") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailInfoRow("이름", entry.name)
                DetailInfoRow("유형", if (entry.isDirectory) "폴더" else entry.extension.uppercase(Locale.US).ifBlank { "파일" })
                DetailInfoRow("전체 경로", "/$shareName/${entry.relativePath}")
                if (!entry.isDirectory) {
                    DetailInfoRow("크기", formatFileBytes(entry.sizeBytes))
                }
                DetailInfoRow("수정일", formatModifiedDate(entry.lastModifiedMillis))
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("확인") }
        }
    )
}

@Composable
private fun DetailInfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 인앱 전체 화면 이미지 뷰어 (핀치 줌 + 드래그 이동 + 다운로드)
 */
@Composable
internal fun FullScreenImagePreviewDialog(
    preview: ImagePreviewState,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    val bitmap = remember(preview.bytes) {
        runCatching {
            BitmapFactory.decodeByteArray(preview.bytes, 0, preview.bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding()
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = preview.entry.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val nextScale = (scale * zoom).coerceIn(1f, 5f)
                                scale = nextScale
                                offset = if (nextScale > 1f) {
                                    offset + pan
                                } else {
                                    Offset.Zero
                                }
                            }
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                )
            } else {
                Text(
                    text = "이미지를 디코딩할 수 없습니다.",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // 상단 오버레이 바
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "닫기", tint = Color.White)
                }
                Text(
                    text = preview.entry.name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDownload) {
                    Icon(Icons.Filled.Download, contentDescription = "다운로드", tint = Color.White)
                }
            }
        }
    }
}

/**
 * 인앱 모노스페이스 텍스트/코드/로그 뷰어
 */
@Composable
internal fun FullScreenTextPreviewDialog(
    preview: TextPreviewState,
    onCopied: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "닫기")
                    }
                    Text(
                        text = preview.entry.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(preview.content))
                            onCopied()
                        }
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "복사")
                    }
                    IconButton(onClick = onDownload) {
                        Icon(Icons.Filled.Download, contentDescription = "다운로드")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(14.dp)
                ) {
                    Text(
                        text = preview.content,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }
    }
}

internal fun formatFileBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val tb = bytes / (1024.0 * 1024.0 * 1024.0 * 1024.0)
    if (tb >= 1.0) return String.format(Locale.getDefault(), "%.2f TB", tb)
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 1.0) return String.format(Locale.getDefault(), "%.2f GB", gb)
    val mb = bytes / (1024.0 * 1024.0)
    if (mb >= 1.0) return String.format(Locale.getDefault(), "%.1f MB", mb)
    val kb = bytes / 1024.0
    if (kb >= 1.0) return String.format(Locale.getDefault(), "%.1f KB", kb)
    return "$bytes B"
}

internal fun formatModifiedDate(millis: Long): String {
    if (millis <= 0L) return "-"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}

internal fun formatShareSpace(rawValue: Long): String {
    val kb = if (rawValue > 10_000_000_000L) rawValue / 1024L else rawValue
    val tb = kb / (1024.0 * 1024.0 * 1024.0)
    if (tb >= 1.0) return String.format(Locale.getDefault(), "%.2f TB", tb)
    val gb = kb / (1024.0 * 1024.0)
    if (gb >= 1.0) return String.format(Locale.getDefault(), "%.1f GB", gb)
    val mb = kb / 1024.0
    return String.format(Locale.getDefault(), "%.0f MB", mb)
}
