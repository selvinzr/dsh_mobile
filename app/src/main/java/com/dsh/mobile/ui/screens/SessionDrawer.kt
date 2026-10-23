package com.dsh.mobile.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.mobile.data.model.SessionSummary
import com.dsh.mobile.ui.AppViewModel
import com.dsh.mobile.ui.Dialog
import com.dsh.mobile.ui.HeaderKind
import com.dsh.mobile.ui.SessionRow
import com.dsh.mobile.ui.UiState
import com.dsh.mobile.ui.workspaceOf

/**
 * 侧边抽屉里的会话列表 —— 主界面是聊天，会话切换都在这里完成（对齐 Codex 移动端）。
 */
@Composable
fun SessionDrawer(
    state: UiState,
    vm: AppViewModel,
    onClose: () -> Unit,
) {
    val rows = remember(state.sessions, state.workspaceState, state.query, state.showArchived, state.expandedWorkspaces, state.openSessionId) {
        vm.buildRows()
    }
    val listState = rememberLazyListState()
    var searchOpen by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<SessionSummary?>(null) }

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.widthIn(max = 340.dp),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // 头部：品牌 + 连接状态
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "DSH",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (state.connection.startsWith("已连接"))
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                                )
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            state.base.removePrefix("http://").removePrefix("https://"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = { searchOpen = !searchOpen }) {
                    Icon(Icons.Filled.Search, "搜索", Modifier.size(21.dp))
                }
                IconButton(onClick = { vm.loadSessions() }) {
                    Icon(Icons.Filled.Refresh, "刷新", Modifier.size(20.dp))
                }
            }

            // 搜索（点图标才展开）
            if (searchOpen) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::updateQuery,
                placeholder = { Text("搜索会话") },
                leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { vm.updateQuery("") }) {
                            Icon(Icons.Filled.Close, null, Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (state.showArchived) "全部会话" else "会话",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (state.workspaceState.archivedSessionIds.isNotEmpty()) {
                    Text(
                        if (state.showArchived) "隐藏归档" else "归档 ${state.workspaceState.archivedSessionIds.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { vm.toggleArchived() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            if (rows.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.query.isBlank()) "还没有会话" else "没有匹配的会话",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    items(rows, key = { row ->
                        when (row) {
                            is SessionRow.Header -> "h-${row.kind}-${row.workspaceId ?: row.title}"
                            is SessionRow.Item -> row.summary.sessionId
                            is SessionRow.SubagentGroup -> "sg-${row.parentId}"
                        }
                    }) { row ->
                        when (row) {
                            is SessionRow.Header -> SectionHeader(row, vm, onClose)
                            is SessionRow.Item -> DrawerSessionRow(
                                summary = row.summary,
                                pinned = row.pinned,
                                archived = row.archived,
                                selected = row.summary.sessionId == state.openSessionId,
                                workspace = state.workspaceOf(row.summary.sessionId)?.title,
                                onClick = {
                                    vm.openSession(row.summary)
                                    onClose()
                                },
                                onLongClick = { actionsFor = row.summary },
                                onMore = { actionsFor = row.summary },
                            )

                            is SessionRow.SubagentGroup -> SubagentGroupRow(
                                group = row,
                                openSessionId = state.openSessionId,
                                onToggle = { vm.toggleSubagentGroup(row.parentId) },
                                onOpen = { child ->
                                    vm.openSession(child)
                                    onClose()
                                },
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 主操作：紫色胶囊「新会话」
                Button(
                    onClick = {
                        // 带上当前会话的工作区路径，避免建出"无法确定工作区"的会话
                        vm.createSession(
                            cwd = state.sessions
                                .firstOrNull { it.sessionId == state.openSessionId }?.cwd,
                        )
                        onClose()
                    },
                    shape = RoundedCornerShape(21.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.height(42.dp),
                ) {
                    Icon(Icons.Filled.Add, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("新会话", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.weight(1f))
                // 圆形入口：设置
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = CircleShape,
                    onClick = { vm.openSettings() },
                    modifier = Modifier.size(42.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Filled.Settings,
                            "设置",
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                // 圆形入口：添加工作区
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = CircleShape,
                    onClick = { vm.showDialog(Dialog.NewWorkspace()) },
                    modifier = Modifier.size(42.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Filled.CreateNewFolder,
                            "添加工作区",
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    actionsFor?.let { s ->
        SessionActionsSheet(
            summary = s,
            pinned = s.sessionId in state.workspaceState.pinnedSessionIds,
            archived = s.sessionId in state.workspaceState.archivedSessionIds,
            onDismiss = { actionsFor = null },
            onRename = {
                actionsFor = null
                vm.showDialog(Dialog.RenameSession(s.sessionId, s.title))
            },
            onPin = {
                actionsFor = null
                vm.togglePin(s.sessionId)
            },
            onArchive = {
                actionsFor = null
                vm.setArchived(s.sessionId, archived = s.sessionId !in state.workspaceState.archivedSessionIds)
            },
            onFork = {
                actionsFor = null
                vm.forkSession(s.sessionId)
            },
            onCopyId = {
                actionsFor = null
                vm.copyToClipboard(s.sessionId)
            },
        )
    }
}

@Composable
private fun SectionHeader(
    row: SessionRow.Header,
    vm: AppViewModel? = null,
    onClose: () -> Unit = {},
) {
    val clickable = row.kind == HeaderKind.Workspace && vm != null
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (clickable) Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { vm!!.toggleWorkspaceExpanded(row.workspaceId!!) }
                else Modifier
            )
            .padding(start = 16.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = when (row.kind) {
            HeaderKind.Pinned -> Icons.Filled.PushPin
            HeaderKind.Workspace -> Icons.Filled.FolderOpen
            HeaderKind.Archived -> Icons.Filled.Archive
            HeaderKind.Other -> null
        }
        if (icon != null) {
            Icon(icon, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            row.title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            row.count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        // 折叠箭头（仅工作区分组）
        if (row.kind == HeaderKind.Workspace) {
            Spacer(Modifier.width(6.dp))
            Icon(
                if (row.expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                null,
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            // 在该工作区里新建会话（不传 workspaceId 时服务端无法确定会话归属）
            if (vm != null && row.workspaceId != null) {
                IconButton(
                    onClick = {
                        vm.createSession(workspaceId = row.workspaceId)
                        onClose()
                    },
                    modifier = Modifier.size(26.dp),
                ) {
                    Icon(
                        Icons.Filled.Add,
                        "在此工作区新建会话",
                        Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 一条会话下的子 agent 任务列表（默认收起）。
 * 子 agent 会话不再散落在「其他」里，而是作为父会话的"任务"呈现。
 */
@Composable
private fun SubagentGroupRow(
    group: SessionRow.SubagentGroup,
    openSessionId: String?,
    onToggle: () -> Unit,
    onOpen: (com.dsh.mobile.data.model.SessionSummary) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .clickable { onToggle() }
                .padding(start = 34.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (group.expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                null,
                Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.CallSplit,
                null,
                Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "子任务 ${group.children.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        if (group.expanded) {
            group.children.forEach { child ->
                val selected = child.sessionId == openSessionId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 42.dp, end = 12.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.surfaceContainer
                            else Color.Transparent
                        )
                        .clickable { onOpen(child) }
                        .padding(vertical = 7.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.ExpandMore,
                        null,
                        Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            child.subagentLabel?.takeIf { it.isNotBlank() }
                                ?: child.title.ifBlank { "(子任务)" },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            relativeTime(child.updatedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (child.running) {
                        CircularProgressIndicator(
                            Modifier.size(13.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerSessionRow(
    summary: SessionSummary,
    pinned: Boolean,
    archived: Boolean,
    selected: Boolean,
    workspace: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 10.dp, end = 0.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 选中指示条（当前会话）
        Box(
            Modifier
                .padding(end = 8.dp)
                .width(3.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primary
                    else Color.Transparent
                )
        )
        // 圆形图标（运行中套一圈 loading）
        Box(
            Modifier.padding(end = 10.dp).size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.ChatBubbleOutline,
                null,
                Modifier.size(19.dp),
                tint = if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (summary.running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        null,
                        Modifier.size(10.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    summary.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected || summary.running) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (archived) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                buildString {
                    workspace?.let { append(it).append(" · ") }
                    append(relativeTime(summary.updatedAt))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onMore, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.MoreHoriz,
                "更多",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionActionsSheet(
    summary: SessionSummary,
    pinned: Boolean,
    archived: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onFork: () -> Unit,
    onCopyId: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(bottom = 28.dp)) {
            Text(
                summary.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            summary.cwd?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            SheetAction(Icons.Filled.Edit, "重命名") { onRename() }
            SheetAction(
                if (pinned) Icons.Outlined.PushPin else Icons.Filled.PushPin,
                if (pinned) "取消置顶" else "置顶",
            ) { onPin() }
            SheetAction(
                if (archived) Icons.Filled.Unarchive else Icons.Filled.Archive,
                if (archived) "取消归档" else "归档",
            ) { onArchive() }
            SheetAction(Icons.Filled.CallSplit, "派生新会话") { onFork() }
            SheetAction(Icons.Filled.ContentCopy, "复制会话 ID") { onCopyId() }
        }
    }
}

@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

internal fun relativeTime(ms: Long): String {
    if (ms <= 0) return "—"
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        diff < 172_800_000 -> "昨天 " + java.text.SimpleDateFormat(
            "HH:mm", java.util.Locale.getDefault()
        ).format(java.util.Date(ms))

        else -> java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(ms))
    }
}
