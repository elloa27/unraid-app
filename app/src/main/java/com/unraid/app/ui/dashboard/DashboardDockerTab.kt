package com.unraid.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unraid.app.data.model.DockerAction
import com.unraid.app.data.model.DockerContainerItem
import com.unraid.app.ui.theme.StatusAmber
import com.unraid.app.ui.theme.StatusBlue
import com.unraid.app.ui.theme.StatusGreen
import com.unraid.app.ui.theme.StatusRed

internal enum class DockerFilterMode(val labelKo: String) {
    ALL("전체"),
    RUNNING("실행 중"),
    STOPPED("중지됨")
}

/**
 * 서브탭 3: 도커 (검색 + 상태 필터 칩 + 자동 시작 배지 + 업데이트/시작/중지/재시작/웹 UI 열기)
 */
internal fun LazyListScope.dashboardDockerTabContent(
    state: DashboardUiState,
    searchQuery: String,
    filterMode: DockerFilterMode,
    onSearchQueryChange: (String) -> Unit,
    onFilterModeChange: (DockerFilterMode) -> Unit,
    onDockerAction: (DockerContainerItem, DockerAction) -> Unit,
    onRequestUpdateContainer: (DockerContainerItem) -> Unit,
    onOpenDockerWebUi: (DockerContainerItem) -> Unit,
    onOpenUnraidWebUi: (String) -> Unit
) {
    val allContainers = state.data?.containers.orEmpty()
    val runningCount = allContainers.count { it.isRunning }
    val stoppedCount = allContainers.size - runningCount

    val filtered = allContainers.filter { container ->
        val matchesState = when (filterMode) {
            DockerFilterMode.ALL -> true
            DockerFilterMode.RUNNING -> container.isRunning
            DockerFilterMode.STOPPED -> !container.isRunning
        }
        val query = searchQuery.trim()
        val matchesQuery = query.isBlank() ||
            container.name.contains(query, ignoreCase = true) ||
            container.image.contains(query, ignoreCase = true)
        matchesState && matchesQuery
    }

    // 검색 입력창 및 상태 필터 칩
    item(key = "docker_search_filter") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("컨테이너 이름 또는 이미지 검색...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "검색어 지우기"
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DockerFilterMode.entries.forEach { mode ->
                    val count = when (mode) {
                        DockerFilterMode.ALL -> allContainers.size
                        DockerFilterMode.RUNNING -> runningCount
                        DockerFilterMode.STOPPED -> stoppedCount
                    }
                    FilterChip(
                        selected = filterMode == mode,
                        onClick = { onFilterModeChange(mode) },
                        label = { Text("${mode.labelKo} ($count)") }
                    )
                }
            }
        }
    }

    item(key = "docker_section_header") {
        SectionHeader(
            title = "도커 컨테이너",
            subtitle = "표시 ${filtered.size} / 전체 ${allContainers.size}"
        )
    }

    if (allContainers.isEmpty()) {
        item(key = "docker_empty") {
            EmptySectionCard("조회된 도커 컨테이너가 없습니다.")
        }
    } else if (filtered.isEmpty()) {
        item(key = "docker_filtered_empty") {
            EmptySectionCard("검색 또는 필터 조건에 일치하는 컨테이너가 없습니다.")
        }
    } else {
        items(
            items = filtered,
            key = { "docker_item_${it.id}" }
        ) { container ->
            DockerContainerCard(
                container = container,
                isActionRunning = state.actionInProgressContainerId == container.id,
                isAnyActionRunning = state.actionInProgressContainerId != null,
                onAction = { action -> onDockerAction(container, action) },
                onUpdateClick = { onRequestUpdateContainer(container) },
                onOpenWebUi = { onOpenDockerWebUi(container) }
            )
        }
    }

    item(key = "docker_webui_shortcut") {
        WebUiShortcutButton(
            label = "Unraid 도커 웹 UI 열기 (/Docker)",
            onClick = { onOpenUnraidWebUi("/Docker") }
        )
    }
}

@Composable
private fun DockerContainerCard(
    container: DockerContainerItem,
    isActionRunning: Boolean,
    isAnyActionRunning: Boolean,
    onAction: (DockerAction) -> Unit,
    onUpdateClick: () -> Unit,
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = container.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (container.autoStart == true) {
                            Surface(
                                color = StatusBlue.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "자동 시작",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = StatusBlue
                                )
                            }
                        }
                    }
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

            // 하단 액션 행: 웹 UI 열기 + 컨테이너 업데이트
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (container.isRunning && container.hasWebUi) {
                    FilledTonalButton(
                        onClick = onOpenWebUi,
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "웹 UI 열기",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                OutlinedButton(
                    onClick = onUpdateClick,
                    enabled = !isAnyActionRunning,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.SystemUpdateAlt,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "업데이트",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
internal fun DockerUpdateConfirmDialog(
    container: DockerContainerItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("컨테이너 업데이트") },
        text = {
            Text("'${container.name}' 컨테이너를 최신 이미지로 업데이트하시겠습니까? 업데이트 중 컨테이너가 잠시 재시작됩니다.")
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("업데이트 실행")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}
