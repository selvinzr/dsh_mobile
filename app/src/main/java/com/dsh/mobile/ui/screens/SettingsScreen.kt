package com.dsh.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.mobile.ui.AppViewModel
import com.dsh.mobile.ui.Dialog
import com.dsh.mobile.ui.UiState
import com.dsh.mobile.ui.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: UiState, vm: AppViewModel) {
    BackHandler { vm.closeSettings() }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = vm::closeSettings) {
                        Icon(Icons.Filled.ArrowBack, "返回")
                    }
                },
                title = { Text("设置") },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            // ---- 服务器 ----
            item { GroupLabel("服务器", Icons.Filled.Dns) }
            items(state.servers, key = { it.base }) { server ->
                val active = server.base == state.base
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.switchServer(server.base) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            server.base,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (active) "当前 · PIN ${server.pin.take(3)}***" else "PIN ${server.pin.take(3)}***",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (active) {
                        Icon(
                            Icons.Filled.Check,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.logout() }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Logout, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("退出登录（清除当前登录态）")
                }
            }

            // ---- 外观 ----
            item { GroupLabel("外观", Icons.Filled.Palette) }
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text("主题模式", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { i, mode ->
                            SegmentedButton(
                                selected = state.themeMode == mode,
                                onClick = { vm.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                            ) {
                                Text(
                                    when (mode) {
                                        ThemeMode.System -> "跟随系统"
                                        ThemeMode.Light -> "浅色"
                                        ThemeMode.Dark -> "深色"
                                    }
                                )
                            }
                        }
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text("强调色", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        com.dsh.mobile.ui.theme.Accent.entries.forEach { a ->
                            val sel = state.accent == a
                            Surface(
                                color = when (a) {
                                    com.dsh.mobile.ui.theme.Accent.Purple -> androidx.compose.ui.graphics.Color(0xFF7C4DFF)
                                    com.dsh.mobile.ui.theme.Accent.Green -> androidx.compose.ui.graphics.Color(0xFF0E7C5A)
                                    com.dsh.mobile.ui.theme.Accent.Blue -> androidx.compose.ui.graphics.Color(0xFF1D6FD3)
                                    com.dsh.mobile.ui.theme.Accent.Black -> androidx.compose.ui.graphics.Color(0xFF1A1A1A)
                                },
                                shape = androidx.compose.foundation.shape.CircleShape,
                                onClick = { vm.setAccent(a) },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    if (sel) {
                                        Icon(
                                            Icons.Filled.Check,
                                            null,
                                            Modifier.size(18.dp),
                                            tint = androidx.compose.ui.graphics.Color.White,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.DarkMode, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("动态取色", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Android 12+ 跟随壁纸配色",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.dynamicColor, onCheckedChange = vm::setDynamicColor)
                }
            }

            // ---- 会话 ----
            item { GroupLabel("会话", Icons.Filled.Tune) }
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text("打开会话时加载的历史条数：${state.maxMessages}", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = state.maxMessages.toFloat(),
                        onValueChange = { vm.setMaxMessages(it.toInt().coerceIn(10, 200)) },
                        valueRange = 10f..200f,
                        steps = 18,
                    )
                    Text(
                        "条数越多首屏越慢；更早的历史用翻页加载",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.showDialog(Dialog.NewWorkspace()) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("添加工作区（填目录路径）")
                }
            }

            // ---- 关于 ----
            item { GroupLabel("关于", Icons.Filled.Info) }
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text("DSH Mobile", fontWeight = FontWeight.SemiBold)
                    Text(
                        "原生 Android 客户端 · 直连 dsh-pocket 协议\n" +
                            "RPC 一元 + WebSocket 多路复用 · 无 WebView",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            "当前连接：${state.connection.ifBlank { "未连接" }}\n${state.base}",
                            Modifier.padding(10.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    DialogHost(state, vm)
}

@Composable
private fun GroupLabel(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
