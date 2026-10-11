package com.dsh.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.mobile.data.model.DirNode
import com.dsh.mobile.ui.AppViewModel

/**
 * 可视化目录选择器（「已知目录树」）。
 *
 * DSH 的 directoryPicker/list 需要 browse capability，本部署只提供 native（弹系统框），
 * 远程无法枚举任意目录。因此数据源是已知目录集合（session/list 的 cwd + 现有工作区），
 * 逐层下钻；也支持手动输入任意路径兜底。
 */
@Composable
fun DirectoryPickerDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val roots = vm.state.value.knownDirRoots
    var stack by remember { mutableStateOf(listOf<DirNode>()) }
    val current: List<DirNode> = stack.lastOrNull()?.children ?: roots
    val currentTitle = stack.lastOrNull()?.name ?: "位置"

    var manualMode by remember { mutableStateOf(false) }
    var manualPath by remember { mutableStateOf("") }
    var creatingName by remember { mutableStateOf<String?>(null) }
    var selectedPath by remember { mutableStateOf<String?>(null) }

    // 当前层级的父路径（用于返回上一级）
    val parentPath = stack.dropLast(1).lastOrNull()?.path

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (stack.isNotEmpty()) {
                    IconButton(onClick = { stack = stack.dropLast(1) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.ArrowBack, "上一级", Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    when {
                        manualMode -> "输入路径"
                        creatingName != null -> "新建文件夹"
                        else -> currentTitle
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column {
                // 面包屑（当前层级）
                if (!manualMode && creatingName == null) {
                    Text(
                        stack.joinToString(" \\ ") { it.name }.ifBlank { "已知位置" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                when {
                    // 手动输入模式
                    manualMode -> {
                        OutlinedTextField(
                            value = manualPath,
                            onValueChange = { manualPath = it },
                            label = { Text("目录路径") },
                            placeholder = { Text("D:\\develop\\my-project") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "路径需存在于运行 DSH 的电脑上",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // 新建子文件夹
                    creatingName != null -> {
                        OutlinedTextField(
                            value = creatingName!!,
                            onValueChange = { creatingName = it },
                            label = { Text("文件夹名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "将在 ${parentPath ?: "所选位置"} 下创建",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // 目录浏览
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            if (stack.isEmpty()) {
                                item(key = "hint") {
                                    Text(
                                        "从已知位置挑选；选中后可创建工作区",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.padding(bottom = 6.dp),
                                    )
                                }
                            }
                            items(current, key = { it.path }) { node ->
                                val hasChildren = node.children.isNotEmpty()
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (selectedPath == node.path)
                                                MaterialTheme.colorScheme.secondaryContainer
                                            else Color.Transparent
                                        )
                                        .clickable {
                                            selectedPath = node.path
                                            if (hasChildren) stack = stack + node
                                        }
                                        .padding(horizontal = 10.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        if (node.isWorkspace) Icons.Filled.Workspaces else Icons.Filled.Folder,
                                        null,
                                        Modifier.size(18.dp),
                                        tint = if (node.isWorkspace) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            node.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (node.isWorkspace) {
                                            Text(
                                                "已是工作区",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline,
                                            )
                                        }
                                    }
                                    if (hasChildren) {
                                        Text(
                                            "${node.children.size}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                manualMode -> TextButton(
                    onClick = {
                        vm.createWorkspace(manualPath.trim())
                        onDismiss()
                    },
                    enabled = manualPath.isNotBlank(),
                ) { Text("创建工作区") }

                creatingName != null -> TextButton(
                    onClick = {
                        // 用已知父路径 + 新名（DSH 没有 createDirectory 的 browse 能力，
                        // 因此只能对「已知父目录 + 已存在子目录」场景提示手动创建）
                        vm.showBannerOnce("本部署不支持远程创建文件夹，请手动输入完整路径")
                        creatingName = null
                    },
                    enabled = creatingName!!.isNotBlank(),
                ) { Text("确定") }

                else -> TextButton(
                    onClick = {
                        val p = selectedPath
                        if (p != null) {
                            vm.createWorkspace(p)
                            onDismiss()
                        } else {
                            manualMode = true
                        }
                    },
                ) {
                    Text(if (selectedPath != null) "添加为工作区" else "手动输入路径")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
