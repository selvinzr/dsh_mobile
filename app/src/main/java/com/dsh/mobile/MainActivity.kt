package com.dsh.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dsh.mobile.ui.AppViewModel
import com.dsh.mobile.ui.Screen
import com.dsh.mobile.ui.screens.ChatScreen
import com.dsh.mobile.ui.screens.LoginScreen
import com.dsh.mobile.ui.screens.NoSessionScreen
import com.dsh.mobile.ui.screens.SessionDrawer
import com.dsh.mobile.ui.screens.SettingsScreen
import com.dsh.mobile.ui.theme.DshTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 沉浸式：内容铺到状态栏/导航栏下，配合半透明浮动控件
        enableEdgeToEdge()
        setContent {
            val vm: AppViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()

            DshTheme(mode = state.themeMode, accent = state.accent, dynamicColor = state.dynamicColor) {
                MaterialTheme {
                    val snackbar = remember { SnackbarHostState() }
                    LaunchedEffect(state.bannerId) {
                        state.banner?.let {
                            snackbar.showSnackbar(it)
                            vm.dismissBanner()
                        }
                    }

                    // 必须用 Surface 包一层：Material3 的 LocalContentColor 默认是「黑色」，
                    // 只有 Surface/Scaffold 才会把它设成 onSurface/onBackground。
                    // 否则任何没写 color 的 Text 在深色模式下都会变成黑字压暗底。
                    Surface(
                        color = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            when (state.screen) {
                                Screen.Login -> LoginScreen(state, vm)

                                Screen.Settings -> SettingsScreen(state, vm)

                                Screen.Main -> AppShell(state, vm)
                            }
                            // 提示统一放屏幕中间（默认对齐是 TopStart，会跑到最顶部），
// 并用主题色而不是默认的反色，避免在深色下突兀
                            SnackbarHost(
                                hostState = snackbar,
                                modifier = Modifier.align(Alignment.Center),
                            ) { data ->
                                Snackbar(
                                    snackbarData = data,
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                    shape = RoundedCornerShape(14.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 抽屉 + 主内容（聊天）。会话切换完全在抽屉里完成。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppShell(state: com.dsh.mobile.ui.UiState, vm: AppViewModel) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // ViewModel 里的抽屉开关（例如从空态点「打开会话列表」）
    LaunchedEffect(state.drawerOpen) {
        if (state.drawerOpen) {
            drawerState.open()
            vm.closeDrawer()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionDrawer(state, vm, onClose = { scope.launch { drawerState.close() } })
        },
    ) {
        if (state.openSessionId == null) {
            NoSessionScreen(state, onOpenDrawer = { scope.launch { drawerState.open() } })
        } else {
            ChatScreen(state, vm, onOpenDrawer = { scope.launch { drawerState.open() } })
        }
    }
}