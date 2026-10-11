package com.dsh.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dsh.mobile.ui.AppViewModel
import com.dsh.mobile.ui.UiState

/**
 * 连接引导页（首次安装 / 未连接时进入）。
 *
 * 主路径是**扫码**（dsh-pocket 的二维码内容就是服务器地址），
 * 手动输入地址作为次要路径，避免首次上手就面对一个 URL 表单。
 */
@Composable
fun LoginScreen(state: UiState, vm: AppViewModel) {
    var scanError by remember { mutableStateOf<String?>(null) }
    var manualMode by remember { mutableStateOf(false) }

    val hasAddress = state.base.isNotBlank()
    // 扫到地址后自动进入"补 PIN"步骤
    val needPin = hasAddress

    val scanner = androidx.activity.compose.rememberLauncherForActivityResult(
        com.journeyapps.barcodescanner.ScanContract()
    ) { result ->
        val text = result.contents
        if (!text.isNullOrBlank()) scanError = vm.applyPairing(text)
    }

    fun launchScan() {
        scanError = null
        scanner.launch(
            com.journeyapps.barcodescanner.ScanOptions()
                .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                .setPrompt("对准电脑上的二维码")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }

    val cameraPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) launchScan() else scanError = "需要相机权限才能扫码" }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .imePadding()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(20.dp),
            ) {
                Icon(
                    Icons.Filled.PhoneAndroid,
                    null,
                    Modifier.padding(14.dp).size(30.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("连接 DSH", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                if (needPin) "地址已就绪，输入电脑上的 PIN 即可" else "手机与电脑连同一个 Wi-Fi，扫码即可",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        // 引导态：以扫码为主
                        !needPin && !manualMode -> {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.QrCodeScanner,
                                    null,
                                    Modifier.size(56.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                "在电脑上打开 dsh-pocket，用手机扫描它显示的二维码",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = { cameraPermission.launch(android.Manifest.permission.CAMERA) },
                                enabled = !state.busy,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                            ) {
                                Icon(Icons.Filled.QrCodeScanner, null, Modifier.size(19.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("扫码连接", style = MaterialTheme.typography.titleSmall)
                            }
                            TextButton(
                                onClick = { manualMode = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("手动输入地址") }
                        }

                        // 补 PIN / 手动输入态
                        else -> {
                            OutlinedTextField(
                                value = state.base,
                                onValueChange = vm::updateBase,
                                label = { Text("服务器地址") },
                                leadingIcon = { Icon(Icons.Filled.Dns, null, Modifier.size(18.dp)) },
                                singleLine = true,
                                enabled = !state.busy,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = state.pin,
                                onValueChange = vm::updatePin,
                                label = { Text("PIN") },
                                leadingIcon = { Icon(Icons.Filled.Lock, null, Modifier.size(18.dp)) },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                enabled = !state.busy,
                                shape = RoundedCornerShape(12.dp),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Ascii,
                                    imeAction = ImeAction.Go,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = vm::login,
                                enabled = !state.busy,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                            ) {
                                if (state.busy) {
                                    CircularProgressIndicator(
                                        Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text("连接")
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                TextButton(onClick = { cameraPermission.launch(android.Manifest.permission.CAMERA) }) {
                                    Text("重新扫码")
                                }
                                if (!hasAddress) {
                                    TextButton(onClick = { manualMode = false }) { Text("返回") }
                                }
                            }
                        }
                    }

                    (scanError ?: state.loginError)?.let {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                it,
                                Modifier.padding(10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            if (state.servers.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text(
                    "最近使用",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                state.servers.forEach { s ->
                    OutlinedCard(
                        onClick = { vm.switchServer(s.base) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Dns, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(s.base, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(
                                "连接",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "PIN 可在电脑上执行 dsh pocket 时看到，\n也可读取 ~/.dsh/dsh-pocket/token-lan",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
