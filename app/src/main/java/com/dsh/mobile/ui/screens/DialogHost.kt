package com.dsh.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dsh.mobile.ui.AppViewModel
import com.dsh.mobile.ui.Dialog
import com.dsh.mobile.ui.UiState

/** 统一的对话框宿主：重命名、新建工作区、强制归档确认等。 */
@Composable
fun DialogHost(state: UiState, vm: AppViewModel) {
    when (val d = state.dialog) {
        null -> Unit

        is Dialog.RenameSession -> TextInputDialog(
            title = "重命名会话",
            label = "标题",
            initial = d.current,
            onDismiss = vm::dismissDialog,
            onConfirm = { vm.renameSession(d.sessionId, it) },
        )

        is Dialog.RenameWorkspace -> TextInputDialog(
            title = "重命名工作区",
            label = "名称",
            initial = d.current,
            onDismiss = vm::dismissDialog,
            onConfirm = { vm.renameWorkspace(d.workspaceId, it) },
        )

        is Dialog.NewWorkspace -> DirectoryPickerDialog(vm, onDismiss = vm::dismissDialog)

        is Dialog.ArchiveRunning -> AlertDialog(
            onDismissRequest = vm::dismissDialog,
            title = { Text("会话仍在运行") },
            text = {
                Text("「${d.title}」还有正在执行的工作。归档会先停止这些工作，确定继续吗？")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissDialog()
                    vm.setArchived(d.sessionId, archived = true, stopActivity = true)
                }) { Text("停止并归档") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissDialog) { Text("取消") }
            },
        )

        is Dialog.ConfirmDeleteWorkspace -> AlertDialog(
            onDismissRequest = vm::dismissDialog,
            title = { Text("移除工作区") },
            text = { Text("只会把「${d.title}」从工作区列表移除，磁盘目录和会话都不会被删除。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteWorkspace(d.workspaceId) }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissDialog) { Text("取消") }
            },
        )


        is Dialog.EditQueued -> TextInputDialog(
            title = "编辑排队消息",
            label = "内容",
            initial = d.current,
            onDismiss = vm::dismissDialog,
            onConfirm = {
                vm.editQueued(d.itemId, it)
                vm.dismissDialog()
            },
        )

        is Dialog.ModelPicker -> ModelPickerSheet(state, vm)

        else -> {}
    }
}

/** 模型选择：按 provider 分组的底部弹层。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(state: UiState, vm: AppViewModel) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = vm::dismissDialog,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        androidx.compose.foundation.layout.Column(
            Modifier.padding(bottom = 28.dp)
        ) {
            Text(
                "选择模型",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            if (state.currentModel != null) {
                Text(
                    "当前：${state.currentModel.provider} / ${state.currentModel.model}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            if (state.loadingModels) {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                return@ModalBottomSheet
            }
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.heightIn(max = 460.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                state.models.forEach { group ->
                    item(key = "g-${group.id}") {
                        Text(
                            group.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 4.dp),
                        )
                    }
                    items(group.models.size, key = { i -> "${group.id}-${group.models[i].id}" }) { i ->
                        val m = group.models[i]
                        val selected = state.currentModel?.provider == group.id &&
                            state.currentModel?.model == m.id
                        androidx.compose.foundation.layout.Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.selectModel(group.id, m.id) }
                                .padding(horizontal = 24.dp, vertical = 13.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text(
                                m.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    null,
                                    Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    placeholder: String? = null,
    onValueChange: ((String) -> Unit)? = null,
    confirmEnabled: (String) -> Boolean = { it.isNotBlank() },
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        onValueChange?.invoke(it)
                    },
                    label = { Text(label) },
                    placeholder = placeholder?.let { { Text(it) } },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (placeholder != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "提示：路径需存在于 DSH 所在电脑上",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = confirmEnabled(text.trim()),
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
