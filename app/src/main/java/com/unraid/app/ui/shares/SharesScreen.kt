package com.unraid.app.ui.shares

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unraid.app.data.local.AppErrorLogger
import com.unraid.app.data.model.ConnectionStatus
import com.unraid.app.data.model.RoutePhase
import com.unraid.app.shares.FileTransferProgress
import com.unraid.app.shares.ShareRootEntry
import com.unraid.app.shares.ShareSortMode
import com.unraid.app.shares.ShareViewMode
import com.unraid.app.shares.SmbFileCategory
import com.unraid.app.shares.SmbFileEntry
import com.unraid.app.ui.theme.StatusAmber
import com.unraid.app.ui.theme.StatusBlue
import com.unraid.app.ui.theme.StatusGreen
import com.unraid.app.ui.theme.StatusRed

/**
 * 구글 드라이브 스타일 Unraid SMB 공유 파일 탐색기 화면.
 * - 루트 공유 폴더 목록 + 내부 탐색 브레드크럼 + 검색/정렬/리스트·그리드 전환
 * - 업로드/다운로드 진행률 배너 + 새 폴더/이름변경/삭제 + 이미지·텍스트 인앱 미리보기 + libmpv 즉시 스트리밍
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharesScreen(
    viewModel: SharesViewModel,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showSortMenu by remember { mutableStateOf(false) }
    var showSmbAccountDialog by remember { mutableStateOf(false) }
    var showAddManualShareDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var isFabExpanded by remember { mutableStateOf(false) }

    var actionSheetEntry by remember { mutableStateOf<SmbFileEntry?>(null) }
    var renamingEntry by remember { mutableStateOf<SmbFileEntry?>(null) }
    var deletingEntry by remember { mutableStateOf<SmbFileEntry?>(null) }
    var detailsEntry by remember { mutableStateOf<SmbFileEntry?>(null) }

    // 다중 파일 업로드 선택기
    val uploadPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.uploadFiles(context, uris)
        }
    }

    // 공유 폴더 내부에서 시스템 뒤로가기 -> 상위 폴더로 이동
    BackHandler(enabled = !state.isAtRoot) {
        isFabExpanded = false
        viewModel.navigateUp()
    }

    // 외부 앱 열기(Intent.ACTION_VIEW + FileProvider) 처리
    LaunchedEffect(state.pendingExternalOpen) {
        val req = state.pendingExternalOpen ?: return@LaunchedEffect
        viewModel.consumePendingExternalOpen()
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(req.uri, req.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "${req.fileName} 열기"))
        }.onFailure { err ->
            AppErrorLogger.logError(
                tag = "ShareExternalOpen",
                message = "외부 앱 열기 실패 (${req.fileName}): ${err.localizedMessage ?: err.javaClass.simpleName}",
                throwable = err
            )
            viewModel.showMessage("이 파일을 열 수 있는 앱이 없습니다. 다운로드 후 확인해 주세요.")
        }
    }

    LaunchedEffect(state.feedbackMessage) {
        val msg = state.feedbackMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearFeedbackMessage()
        }
    }

    // 검색 및 정렬이 적용된 폴더 내부 항목
    val visibleEntries = remember(state.entries, state.searchQuery, state.sortMode) {
        val query = state.searchQuery.trim()
        val filtered = if (query.isBlank()) {
            state.entries
        } else {
            state.entries.filter { it.name.contains(query, ignoreCase = true) }
        }
        val comparator = when (state.sortMode) {
            ShareSortMode.NAME_ASC ->
                compareByDescending<SmbFileEntry> { it.isDirectory }
                    .thenBy { it.name.lowercase() }
            ShareSortMode.DATE_DESC ->
                compareByDescending<SmbFileEntry> { it.isDirectory }
                    .thenByDescending { it.lastModifiedMillis }
                    .thenBy { it.name.lowercase() }
            ShareSortMode.SIZE_DESC ->
                compareByDescending<SmbFileEntry> { it.isDirectory }
                    .thenByDescending { it.sizeBytes }
                    .thenBy { it.name.lowercase() }
        }
        filtered.sortedWith(comparator)
    }

    val visibleRootShares = remember(state.rootShares, state.searchQuery) {
        val query = state.searchQuery.trim()
        if (query.isBlank()) {
            state.rootShares
        } else {
            state.rootShares.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.comment.contains(query, ignoreCase = true)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        if (!state.isAtRoot) {
                            IconButton(
                                onClick = {
                                    isFabExpanded = false
                                    viewModel.navigateUp()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "상위 폴더"
                                )
                            }
                        }
                    },
                    title = {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = state.currentShare ?: "공유 드라이브",
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                ShareRouteBadge(
                                    status = state.connectionStatus,
                                    onClick = onNavigateToSettings
                                )
                            }
                            if (state.effectiveHost.isNotBlank()) {
                                Text(
                                    text = "SMB · ${state.effectiveHost}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::toggleSearchVisibility) {
                            Icon(
                                imageVector = if (state.isSearchVisible) Icons.Filled.Close else Icons.Filled.Search,
                                contentDescription = "검색"
                            )
                        }
                        if (!state.isAtRoot) {
                            IconButton(onClick = viewModel::toggleViewMode) {
                                Icon(
                                    imageVector = if (state.viewMode == ShareViewMode.LIST) {
                                        Icons.Filled.GridView
                                    } else {
                                        Icons.AutoMirrored.Filled.ViewList
                                    },
                                    contentDescription = "보기 전환"
                                )
                            }
                            Box {
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Sort,
                                        contentDescription = "정렬"
                                    )
                                }
                                DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false }
                                ) {
                                    ShareSortMode.entries.forEach { mode ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = if (state.sortMode == mode) "✓ ${mode.labelKo}" else mode.labelKo,
                                                    fontWeight = if (state.sortMode == mode) FontWeight.Bold else FontWeight.Normal
                                                )
                                            },
                                            onClick = {
                                                showSortMenu = false
                                                viewModel.setSortMode(mode)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        IconButton(onClick = {
                            viewModel.clearSmbTestState()
                            showSmbAccountDialog = true
                        }) {
                            Icon(
                                imageVector = Icons.Filled.ManageAccounts,
                                contentDescription = "SMB 계정 설정"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )

                // 검색 바
                if (state.isSearchVisible) {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = viewModel::onSearchQueryChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        placeholder = {
                            Text(if (state.isAtRoot) "공유 폴더 이름 검색..." else "현재 폴더 내 파일/폴더 검색...")
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        trailingIcon = {
                            if (state.searchQuery.isNotBlank()) {
                                IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                    Icon(Icons.Filled.Close, contentDescription = "지우기")
                                }
                            }
                        }
                    )
                }

                // 공유 폴더 내부 가로 스크롤 브레드크럼 바
                if (!state.isAtRoot) {
                    ShareBreadcrumbBar(
                        shareName = state.currentShare.orEmpty(),
                        segments = state.breadcrumbSegments,
                        onRootClick = viewModel::navigateToRoot,
                        onBreadcrumbClick = viewModel::navigateToBreadcrumb
                    )
                }

                // 업로드/다운로드 진행률 배너
                state.activeTransfer?.let { transfer ->
                    TransferProgressBanner(
                        transfer = transfer,
                        onCancel = viewModel::cancelActiveTransfer
                    )
                }
            }
        },
        floatingActionButton = {
            if (!state.isAtRoot) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isFabExpanded) {
                        ExtendedFloatingActionButton(
                            onClick = {
                                isFabExpanded = false
                                showCreateFolderDialog = true
                            },
                            icon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                            text = { Text("새 폴더") },
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                        ExtendedFloatingActionButton(
                            onClick = {
                                isFabExpanded = false
                                uploadPickerLauncher.launch(arrayOf("*/*"))
                            },
                            icon = { Icon(Icons.Filled.UploadFile, contentDescription = null) },
                            text = { Text("파일 업로드") },
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    FloatingActionButton(
                        onClick = { isFabExpanded = !isFabExpanded },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ) {
                        Icon(
                            imageVector = if (isFabExpanded) Icons.Filled.Close else Icons.Filled.Add,
                            contentDescription = "추가 작업"
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { viewModel.refreshCurrentLocation(isPullToRefresh = true) },
                modifier = Modifier.fillMaxSize()
            ) {
                if (state.isAtRoot) {
                    RootSharesContent(
                        state = state,
                        shares = visibleRootShares,
                        onOpenShare = { share -> viewModel.openShare(share.name) },
                        onRemoveManualShare = { share -> viewModel.removeManualShare(share.name) },
                        onOpenSmbAccount = {
                            viewModel.clearSmbTestState()
                            showSmbAccountDialog = true
                        },
                        onOpenAddManualShare = { showAddManualShareDialog = true },
                        onNavigateToSettings = onNavigateToSettings
                    )
                } else {
                    DirectoryBrowserContent(
                        state = state,
                        entries = visibleEntries,
                        onEntryClick = { entry -> viewModel.openFile(context, entry) },
                        onEntryMoreClick = { entry -> actionSheetEntry = entry },
                        onOpenSmbAccount = {
                            viewModel.clearSmbTestState()
                            showSmbAccountDialog = true
                        },
                        onRetry = { viewModel.refreshCurrentLocation(isPullToRefresh = true) }
                    )
                }
            }

            if (state.isPreviewLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.4.dp)
                            Text("미리보기를 불러오는 중...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }

    // 다이얼로그 & 바텀시트 모음
    if (showSmbAccountDialog) {
        SmbAccountDialog(
            initialCredentials = state.smbCredentials,
            autoHost = state.effectiveHost,
            testState = state.smbTestState,
            onTestConnection = viewModel::testSmbConnection,
            onSave = { user, pass, guest, host ->
                viewModel.saveSmbCredentials(user, pass, guest, host)
                showSmbAccountDialog = false
                if (!state.isAtRoot) {
                    viewModel.refreshCurrentLocation(isPullToRefresh = true)
                }
            },
            onDismiss = { showSmbAccountDialog = false }
        )
    }

    if (showAddManualShareDialog) {
        AddManualShareDialog(
            onConfirm = { name ->
                showAddManualShareDialog = false
                viewModel.addManualShare(name)
            },
            onDismiss = { showAddManualShareDialog = false }
        )
    }

    if (showCreateFolderDialog) {
        CreateFolderDialog(
            onConfirm = { name ->
                showCreateFolderDialog = false
                viewModel.createFolder(name)
            },
            onDismiss = { showCreateFolderDialog = false }
        )
    }

    actionSheetEntry?.let { entry ->
        EntryActionBottomSheet(
            entry = entry,
            onOpen = {
                actionSheetEntry = null
                viewModel.openFile(context, entry)
            },
            onDownload = {
                actionSheetEntry = null
                viewModel.downloadFile(context, entry)
            },
            onRename = {
                actionSheetEntry = null
                renamingEntry = entry
            },
            onDelete = {
                actionSheetEntry = null
                deletingEntry = entry
            },
            onDetails = {
                actionSheetEntry = null
                detailsEntry = entry
            },
            onDismiss = { actionSheetEntry = null }
        )
    }

    renamingEntry?.let { entry ->
        RenameEntryDialog(
            entry = entry,
            onConfirm = { newName ->
                renamingEntry = null
                viewModel.renameEntry(entry, newName)
            },
            onDismiss = { renamingEntry = null }
        )
    }

    deletingEntry?.let { entry ->
        DeleteEntryConfirmDialog(
            entry = entry,
            onConfirm = {
                deletingEntry = null
                viewModel.deleteEntry(entry)
            },
            onDismiss = { deletingEntry = null }
        )
    }

    detailsEntry?.let { entry ->
        EntryDetailsDialog(
            shareName = state.currentShare.orEmpty(),
            entry = entry,
            onDismiss = { detailsEntry = null }
        )
    }

    state.imagePreview?.let { preview ->
        FullScreenImagePreviewDialog(
            preview = preview,
            onDownload = { viewModel.downloadFile(context, preview.entry) },
            onDismiss = viewModel::dismissImagePreview
        )
    }

    state.textPreview?.let { preview ->
        FullScreenTextPreviewDialog(
            preview = preview,
            onCopied = { viewModel.showMessage("텍스트를 클립보드에 복사했습니다.") },
            onDownload = { viewModel.downloadFile(context, preview.entry) },
            onDismiss = viewModel::dismissTextPreview
        )
    }
}

@Composable
private fun ShareRouteBadge(
    status: ConnectionStatus,
    onClick: () -> Unit
) {
    val color = when (status.phase) {
        RoutePhase.DIRECT_HOME_WIFI -> StatusGreen
        RoutePhase.DIRECT_MANUAL -> StatusBlue
        RoutePhase.TAILSCALE_CONNECTING -> StatusAmber
        RoutePhase.TAILSCALE_CONNECTED -> MaterialTheme.colorScheme.primary
        RoutePhase.TAILSCALE_ERROR -> StatusRed
    }
    val shortLabel = when (status.phase) {
        RoutePhase.DIRECT_HOME_WIFI, RoutePhase.DIRECT_MANUAL -> "홈 Wi-Fi"
        RoutePhase.TAILSCALE_CONNECTED -> "Tailscale"
        RoutePhase.TAILSCALE_CONNECTING -> "연결 중"
        RoutePhase.TAILSCALE_ERROR -> "오류"
    }

    Surface(
        onClick = onClick,
        color = color.copy(alpha = 0.16f),
        shape = RoundedCornerShape(999.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (status.isUsingTailscale) Icons.Filled.VpnLock else Icons.Filled.Wifi,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = shortLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
    }
}

@Composable
private fun ShareBreadcrumbBar(
    shareName: String,
    segments: List<String>,
    onRootClick: () -> Unit,
    onBreadcrumbClick: (Int) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
            .padding(vertical = 6.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item(key = "bc_root") {
            BreadcrumbChip(text = "공유", isCurrent = false, onClick = onRootClick)
        }
        item(key = "bc_sep_0") {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
        item(key = "bc_share") {
            BreadcrumbChip(
                text = shareName,
                isCurrent = segments.isEmpty(),
                onClick = { onBreadcrumbClick(0) }
            )
        }
        itemsIndexed(segments, key = { idx, seg -> "bc_${idx}_$seg" }) { index, seg ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                BreadcrumbChip(
                    text = seg,
                    isCurrent = index == segments.lastIndex,
                    onClick = { onBreadcrumbClick(index + 1) }
                )
            }
        }
    }
}

@Composable
private fun BreadcrumbChip(
    text: String,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TransferProgressBanner(
    transfer: FileTransferProgress,
    onCancel: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val countSuffix = if (transfer.totalFileCount > 1) {
                        " (${transfer.currentFileIndex}/${transfer.totalFileCount})"
                    } else {
                        ""
                    }
                    Text(
                        text = "${transfer.direction.labelKo} 중$countSuffix: ${transfer.fileName}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val progressText = if (transfer.totalBytes > 0L) {
                        val pct = ((transfer.fraction ?: 0f) * 100f).toInt()
                        "${formatFileBytes(transfer.transferredBytes)} / ${formatFileBytes(transfer.totalBytes)} ($pct%)"
                    } else {
                        formatFileBytes(transfer.transferredBytes)
                    }
                    Text(
                        text = progressText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onCancel) {
                    Text("취소", color = StatusRed)
                }
            }
            val frac = transfer.fraction
            if (frac != null) {
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
            }
        }
    }
}

@Composable
private fun RootSharesContent(
    state: SharesUiState,
    shares: List<ShareRootEntry>,
    onOpenShare: (ShareRootEntry) -> Unit,
    onRemoveManualShare: (ShareRootEntry) -> Unit,
    onOpenSmbAccount: () -> Unit,
    onOpenAddManualShare: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // SMB 계정 미설정 안내 배너
        if (!state.isSmbConfigured) {
            item(key = "smb_setup_banner") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "SMB 계정을 먼저 설정해 주세요",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Unraid 공유 폴더의 파일 탐색·업로드·동영상 스트리밍을 위해 SMB 사용자 아이디/비밀번호 또는 게스트 접속을 설정하세요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Button(onClick = onOpenSmbAccount) {
                            Icon(Icons.Filled.ManageAccounts, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("SMB 계정 설정하기")
                        }
                    }
                }
            }
        }

        if (!state.graphQlSharesWarning.isNullOrBlank()) {
            item(key = "graphql_shares_warn") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = StatusAmber.copy(alpha = 0.14f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = state.graphQlSharesWarning.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (!state.isUnraidApiConfigured) {
                            OutlinedButton(onClick = onNavigateToSettings) {
                                Text("Unraid 서버 설정으로 이동")
                            }
                        }
                    }
                }
            }
        }

        if (state.isLoading && shares.isEmpty()) {
            item(key = "root_loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("공유 폴더 목록을 불러오는 중...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else if (shares.isEmpty()) {
            item(key = "root_empty") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                        Text("표시할 공유 폴더가 없습니다", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Unraid GraphQL 설정을 확인하거나 아래 버튼으로 SMB 공유 폴더 이름을 직접 추가하세요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(shares, key = { "root_share_${it.name}" }) { share ->
                ShareRootCard(
                    share = share,
                    onClick = { onOpenShare(share) },
                    onRemoveManual = if (share.isManual) {
                        { onRemoveManualShare(share) }
                    } else {
                        null
                    }
                )
            }
        }

        item(key = "add_manual_share_btn") {
            FilledTonalButton(
                onClick = onOpenAddManualShare,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("공유 직접 추가")
            }
        }
    }
}

@Composable
private fun ShareRootCard(
    share: ShareRootEntry,
    onClick: () -> Unit,
    onRemoveManual: (() -> Unit)?
) {
    val used = share.usedKb ?: 0L
    val free = share.freeKb ?: 0L
    val total = share.sizeKb?.takeIf { it > 0L } ?: (used + free).takeIf { it > 0L }
    val ratio = if (share.usedKb != null && share.freeKb != null && total != null && total > 0L) {
        (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }

    Card(
        onClick = onClick,
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = share.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (share.comment.isNotBlank()) {
                        Text(
                            text = share.comment,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (onRemoveManual != null) {
                    IconButton(onClick = onRemoveManual) {
                        Icon(Icons.Filled.Delete, contentDescription = "수동 공유 제거", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (share.usedKb != null || share.freeKb != null) {
                val spaceLabel = buildList {
                    share.usedKb?.let { add("사용 ${formatShareSpace(it)}") }
                    share.freeKb?.let { add("여유 ${formatShareSpace(it)}") }
                }.joinToString(" · ")
                Text(
                    text = spaceLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (ratio != null) {
                    LinearProgressIndicator(
                        progress = { ratio },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (ratio >= 0.85f) StatusAmber else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectoryBrowserContent(
    state: SharesUiState,
    entries: List<SmbFileEntry>,
    onEntryClick: (SmbFileEntry) -> Unit,
    onEntryMoreClick: (SmbFileEntry) -> Unit,
    onOpenSmbAccount: () -> Unit,
    onRetry: () -> Unit
) {
    when {
        state.isLoading && entries.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("SMB 폴더를 불러오는 중...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        !state.errorMessage.isNullOrBlank() && entries.isEmpty() -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = StatusRed.copy(alpha = 0.14f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.Warning, null, tint = StatusRed)
                            Text("SMB 폴더 접근 실패", style = MaterialTheme.typography.titleMedium, color = StatusRed)
                        }
                        Text(state.errorMessage.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onRetry) { Text("다시 시도") }
                            OutlinedButton(onClick = onOpenSmbAccount) { Text("SMB 계정 설정") }
                        }
                    }
                }
            }
        }

        entries.isEmpty() -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state.searchQuery.isNotBlank()) "검색 결과가 없습니다." else "이 폴더는 비어 있습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        state.viewMode == ShareViewMode.LIST -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries, key = { "entry_${it.relativePath}" }) { entry ->
                    FileEntryListCard(
                        entry = entry,
                        onClick = { onEntryClick(entry) },
                        onLongClick = { onEntryMoreClick(entry) },
                        onMoreClick = { onEntryMoreClick(entry) }
                    )
                }
            }
        }

        else -> {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 88.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                gridItems(entries, key = { "grid_${it.relativePath}" }) { entry ->
                    FileEntryGridCard(
                        entry = entry,
                        onClick = { onEntryClick(entry) },
                        onLongClick = { onEntryMoreClick(entry) },
                        onMoreClick = { onEntryMoreClick(entry) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileEntryListCard(
    entry: SmbFileEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    val (icon, tint) = categoryVisual(entry.category)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = tint.copy(alpha = 0.16f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(21.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sub = if (entry.isDirectory) {
                    "폴더 · ${formatModifiedDate(entry.lastModifiedMillis)}"
                } else {
                    "${formatFileBytes(entry.sizeBytes)} · ${formatModifiedDate(entry.lastModifiedMillis)}"
                }
                Text(
                    text = sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onMoreClick) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "항목 메뉴",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileEntryGridCard(
    entry: SmbFileEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    val (icon, tint) = categoryVisual(entry.category)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = tint.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier
                            .padding(10.dp)
                            .size(24.dp)
                    )
                }
                IconButton(onClick = onMoreClick, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "메뉴",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (entry.isDirectory) "폴더" else formatFileBytes(entry.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun categoryVisual(category: SmbFileCategory): Pair<ImageVector, Color> {
    return when (category) {
        SmbFileCategory.FOLDER -> Icons.Filled.Folder to MaterialTheme.colorScheme.primary
        SmbFileCategory.VIDEO -> Icons.Filled.Movie to StatusGreen
        SmbFileCategory.IMAGE -> Icons.Filled.Image to StatusBlue
        SmbFileCategory.AUDIO -> Icons.Filled.AudioFile to StatusAmber
        SmbFileCategory.PDF -> Icons.Filled.PictureAsPdf to StatusRed
        SmbFileCategory.ARCHIVE -> Icons.Filled.FolderZip to StatusAmber
        SmbFileCategory.TEXT, SmbFileCategory.DOCUMENT -> Icons.Filled.Description to StatusBlue
        SmbFileCategory.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile to MaterialTheme.colorScheme.onSurfaceVariant
    }
}
