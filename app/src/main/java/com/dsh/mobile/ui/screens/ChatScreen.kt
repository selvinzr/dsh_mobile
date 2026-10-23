package com.dsh.mobile.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.mobile.data.model.ChatItem
import com.dsh.mobile.data.model.PendingQuestion
import com.dsh.mobile.ui.AppViewModel
import com.dsh.mobile.ui.Dialog
import com.dsh.mobile.ui.UiState
import com.dsh.mobile.ui.components.AssistantBlock
import com.dsh.mobile.ui.components.LiveBlock
import com.dsh.mobile.ui.components.UserBubble
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 聊天主界面。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(state: UiState, vm: AppViewModel, onOpenDrawer: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var atBottom by remember { mutableStateOf(true) }
    // 输入区实测高度：用于底部安全距离、悬浮胶囊定位、回到底部判定
    var composerHeightPx by remember { mutableStateOf(0) }

    val live = state.chat.liveText.isNotEmpty() || state.chat.liveReasoning.isNotEmpty()
    val itemCount = state.chat.items.size + if (live) 1 else 0

    val lastVisible by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
    }
    // 「吸附底部」只在**用户主动滚动**时改变。
    // 不能用"末项是否可见"来判定：新消息一到就会把它算成 false，
    // 于是自动滚动被自己拦住 —— 这正是"经常不自动滚到底"的原因。
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canForward) ->
                if (scrolling) atBottom = !canForward
            }
    }
    // 滚到顶部附近时自动补更早的历史
    LaunchedEffect(lastVisible) {
        if (listState.firstVisibleItemIndex <= 1 && state.hasMoreHistory && !state.loadingMore) {
            vm.loadMoreHistory()
        }
    }
    // 关键：流式输出时 itemCount 不变、只有 liveText 在涨，所以必须把它的长度也作为触发条件，
    // 否则增量文字会一直往视口下方长出去而不跟随。
    // 落位方式用「定位末项 + 瞬移到底」：单纯 animateScrollToItem(last) 会把长消息对齐到顶部，
    // 结果只能看到消息开头而不是最新内容。
    val liveLen = state.chat.liveText.length + state.chat.liveReasoning.length
    LaunchedEffect(itemCount, liveLen) {
        if (itemCount > 0 && atBottom) {
            runCatching {
                listState.scrollToItem(itemCount - 1)
                listState.scrollBy(200_000f)
            }
        }
    }

    // 沉浸式：内容铺满整屏，顶部只浮几个半透明圆钮（不要会话标题）
    // 注意：必须自己铺底色，否则会露出窗口背景（浅色主题下是白的）
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (state.chat.items.isEmpty() && !live) {
            Text(
                "开始对话",
                Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 列表视口：底部让出输入区的高度，内容就不会穿到输入区下方被覆盖
        val composerDp = with(androidx.compose.ui.platform.LocalDensity.current) {
            composerHeightPx.toDp()
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = composerDp),
            // 顶部给浮动控件留出空间（让消息从下方滑过）
            contentPadding = PaddingValues(top = 96.dp, bottom = 12.dp),
        ) {
            if (state.hasMoreHistory || state.loadingMore) {
                item(key = "load-more") { LoadMoreRow(state.loadingMore) { vm.loadMoreHistory() } }
            }
            itemsIndexed(state.chat.items, key = { _, it -> it.key }) { index, item ->
                when (item) {
                    is ChatItem.User -> UserBubble(item, onOpenFile = { name, mime -> vm.openAttachment(context, name, mime, null) })
                    is ChatItem.Assistant -> AssistantBlock(
                        item = item,
                        showHeader = state.chat.items.getOrNull(index - 1) !is ChatItem.Assistant,
                    )
                }
            }
            if (live) {
                item(key = "live") { LiveBlock(state.chat.liveReasoning, state.chat.liveText) }
            }
        }

        // 顶部过渡遮罩：只覆盖状态栏 + 浮动控件，用平滑曲线羽化（避免出现可见的转折带）
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(92.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0.00f to MaterialTheme.colorScheme.surface,
                        0.52f to MaterialTheme.colorScheme.surface,
                        0.66f to MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                        0.78f to MaterialTheme.colorScheme.surface.copy(alpha = 0.60f),
                        0.89f to MaterialTheme.colorScheme.surface.copy(alpha = 0.30f),
                        1.00f to MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                    )
                )
        )

        // 顶部浮动控件：菜单 / 当前模型 / ⋯
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FloatingCircleButton(Icons.Filled.Menu, "会话列表", onOpenDrawer)
            Spacer(Modifier.weight(1f))
            // 当前会话模型（点击切换）——把模型从输入区移到头部，底部输入栏才能回归单行
            state.currentModel?.let { m ->
                FloatingPillButton(
                    icon = Icons.Filled.ModelTraining,
                    label = m.label,
                    desc = "切换模型",
                    onClick = vm::openModelPicker,
                )
                Spacer(Modifier.width(8.dp))
            }
            Spacer(Modifier.width(8.dp))
            Box {
                FloatingCircleButton(Icons.Filled.MoreVert, "更多") { menuOpen = true }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = {
                            menuOpen = false
                            state.openSessionId?.let {
                                vm.showDialog(Dialog.RenameSession(it, state.openSessionTitle))
                            }
                        },
                    )
                    // 置顶项要反映当前状态（已置顶时显示「取消置顶」），
                    // 否则点了看不出变化
                    val pinnedNow = state.openSessionId != null &&
                        state.openSessionId in state.workspaceState.pinnedSessionIds
                    DropdownMenuItem(
                        text = { Text(if (pinnedNow) "取消置顶" else "置顶") },
                        leadingIcon = { Icon(Icons.Filled.PushPin, null) },
                        onClick = {
                            menuOpen = false
                            state.openSessionId?.let(vm::togglePin)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("派生新会话") },
                        leadingIcon = { Icon(Icons.Filled.CallSplit, null) },
                        onClick = {
                            menuOpen = false
                            state.openSessionId?.let(vm::forkSession)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("归档") },
                        leadingIcon = { Icon(Icons.Filled.Archive, null) },
                        onClick = {
                            menuOpen = false
                            state.openSessionId?.let { vm.setArchived(it, archived = true) }
                        },
                    )
                }
            }
        }

        // 底部：提问卡片 + 输入区（纯浮动，不加任何过渡遮罩）
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { composerHeightPx = it.height }
        ) {
            state.pendingQuestion?.let { q ->
                QuestionCard(
                    q = q,
                    onAnswer = { vm.answerQuestion(it) },
                    onUndo = { vm.undoLastAnswer() },
                )
            }
            Composer(state, vm)
        }

        // 排队胶囊：真正的悬浮层 —— 叠在输入区上方、压在内容之上，不占任何布局空间
        if (state.queued.isNotEmpty()) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = composerDp + 6.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.queued.forEach { q ->
                    QueuedChip(
                        queued = q,
                        onEdit = { vm.showDialog(Dialog.EditQueued(q.itemId, q.text)) },
                        onSteer = { vm.steerQueued(q.itemId) },
                        onRemove = { vm.removeQueued(q.itemId) },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = !atBottom && itemCount > 0,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = composerDp + 64.dp),
        ) {
            SmallFloatingActionButton(
                onClick = {
                    scope.launch {
                        runCatching {
                            listState.scrollToItem(itemCount - 1)
                            listState.scrollBy(200_000f)
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(Icons.Filled.ArrowDownward, "回到底部")
            }
        }
    }

    DialogHost(state, vm)
}

/** 把附件的 base64 解成缩略图（失败返回 null，回退成图标）。 */
private fun decodeThumb(base64: String?): android.graphics.Bitmap? = runCatching {
    val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}.getOrNull()

/** 顶部浮动胶囊（显示当前模型，点击切换）。 */
@Composable
private fun FloatingPillButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    desc: String,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.78f),
        shape = RoundedCornerShape(21.dp),
        onClick = onClick,
        modifier = Modifier.height(42.dp),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                null,
                Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp),
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.Filled.ExpandMore,
                desc,
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 顶部浮动圆钮：半透明、无边框，内容从下方滑过。 */
@Composable
private fun FloatingCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.78f),
        shape = CircleShape,
        onClick = onClick,
        modifier = Modifier.size(42.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                icon,
                desc,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}


/** 未选择会话时的主界面。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoSessionScreen(state: UiState, onOpenDrawer: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, "会话列表") }
                },
                title = {
                    Column {
                        Text(
                            "DSH",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            state.connection.ifBlank { "连接中…" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.ChatBubbleOutline,
                null,
                Modifier.size(46.dp),
                tint = MaterialTheme.colorScheme.outlineVariant,
            )
            Spacer(Modifier.height(14.dp))
            Text("选择一个会话", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "从侧栏挑选工作区里的会话，或直接新建一个",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onOpenDrawer, shape = RoundedCornerShape(22.dp)) {
                Icon(Icons.Filled.Menu, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("打开会话列表")
            }
        }
    }
}

// ---------- 输入区：普通态（＋ / 模型 / 麦克风 / 发送）与语音态（✕ / 波形 / ■ / 发送） ----------

@Composable
private fun Composer(state: UiState, vm: AppViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var voiceMode by remember { mutableStateOf(false) }
    var speechState by remember { mutableStateOf<com.dsh.mobile.core.SpeechState?>(null) }
    val amplitudes = remember { mutableStateListOf<Float>() }
    var session by remember { mutableStateOf<com.dsh.mobile.core.SpeechSession?>(null) }
    var lastPartial by remember { mutableStateOf("") }
    // 厂商识别引擎多数不回调 onRmsChanged，波形靠自己采麦克风
    val meter = remember { com.dsh.mobile.core.MicLevelMeter() }
    // 运行中发送：排队（queue）还是插队（steer）

    // 每帧把音量推进波形缓冲
    fun pushAmplitude(level: Float) {
        amplitudes.add(level)
        if (amplitudes.size > 56) amplitudes.removeAt(0)
    }

    fun beginVoice() {
        amplitudes.clear()
        lastPartial = ""
        speechState = com.dsh.mobile.core.SpeechState.Listening
        val s = com.dsh.mobile.core.SpeechSession(context)
        s.onState = { st ->
            speechState = st
            if (st is com.dsh.mobile.core.SpeechState.Partial) lastPartial = st.text
        }
        s.onAmplitude = { lv -> pushAmplitude(lv) }
        session = s
        s.start()
        // 自己采音量（识别引擎独占麦克风时读到 0 也不影响 UI 递进）
        meter.start { lv -> pushAmplitude(lv) }
    }

    fun exitVoice(applyText: Boolean) {
        val s = session
        session = null
        meter.stop()
        s?.destroy()
        if (applyText) {
            val t = lastPartial.ifBlank { null }
            if (t != null) vm.updateDraft(if (state.draft.isBlank()) t else state.draft + t)
        }
        speechState = null
        amplitudes.clear()
        lastPartial = ""
        voiceMode = false
    }

    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceMode = true
            beginVoice()
        } else {
            speechState = com.dsh.mobile.core.SpeechState.Error("需要麦克风权限")
        }
    }

    DisposableEffect(Unit) {
        onDispose { session?.destroy(); session = null; meter.stop() }
    }

    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(vm::attachImage) }

    // 文件：覆盖大模型常见的文档类型（PDF / Office / 纯文本 / 表格 / 代码等）
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(vm::attachFile) }
    val docMimeTypes = arrayOf(
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/msword",
        "application/vnd.ms-excel",
        "text/plain",
        "text/markdown",
        "text/csv",
        "application/json",
        "application/xml",
        "text/html",
        "application/zip",
        "*/*",
    )

    // 系统语音输入兜底：
    // 小米等 ROM 的 ASR 服务会拒绝第三方 App（返回 ERROR_INSUFFICIENT_PERMISSIONS，
    // 即使我们已持有 RECORD_AUDIO）。这种情况下改用系统识别界面 —— 它由系统自己
    // 拉起，权限与权限模型都不经过我们，是最可靠的兜底路径。
    val systemVoice = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val text = res.data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!text.isNullOrBlank()) {
            vm.updateDraft(if (state.draft.isBlank()) text else state.draft + text)
        }
        speechState = null
        exitVoice(applyText = false)
    }

    // 识别结果落定：写入草稿并退出语音态
    LaunchedEffect(speechState) {
        when (val sp = speechState) {
            is com.dsh.mobile.core.SpeechState.Result -> {
                vm.updateDraft(if (state.draft.isBlank()) sp.text else state.draft + sp.text)
                exitVoice(applyText = false)
            }
            is com.dsh.mobile.core.SpeechState.Error -> {
                // 系统识别服务拒绝 → 自动改走系统语音输入界面
                if (sp.message.contains("系统语音服务拒绝")) {
                    runCatching {
                        systemVoice.launch(
                            android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(
                                    android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                )
                                putExtra(
                                    android.speech.RecognizerIntent.EXTRA_LANGUAGE,
                                    java.util.Locale.getDefault().toLanguageTag(),
                                )
                                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "请说话")
                            },
                        )
                    }.onFailure {
                        speechState = com.dsh.mobile.core.SpeechState.Error("设备没有可用的语音输入")
                    }
                    return@LaunchedEffect
                }
                // 语音态下错误留在条内展示；非语音态短暂提示
                if (!voiceMode) {
                    kotlinx.coroutines.delay(2500)
                    if (speechState is com.dsh.mobile.core.SpeechState.Error) {
                        speechState = null
                    }
                }
            }
            else -> Unit
        }
    }

    Column(Modifier.navigationBarsPadding().imePadding()) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            // 排队胶囊不在这里渲染 —— 它是悬浮层，由 ChatScreen 叠在输入区上方（不占布局）
            Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(26.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (voiceMode) {
                        VoiceBar(
                            speechState = speechState,
                            amplitudes = amplitudes,
                            partial = lastPartial,
                            onCancel = { exitVoice(applyText = false) },
                            onStop = { session?.stop() },
                            onSend = { exitVoice(applyText = true) },
                            onRetry = {
                                session?.destroy()
                                session = null
                                beginVoice()
                            },
                        )
                    } else {
                        NormalComposer(
                            state = state,
                            vm = vm,
                            speechState = speechState,
                            onPickImage = {
                                pickImage.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            onPickFile = { pickFile.launch(docMimeTypes) },
                            onMic = {
                                if (!com.dsh.mobile.core.SpeechSession.isAvailable(context)) {
                                    speechState = com.dsh.mobile.core.SpeechState.Error("此设备没有可用的语音识别服务")
                                } else {
                                    micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 普通态：输入 + ＋ / 模型（居中）/ 麦克风 / 发送；回合运行中可切排队·插队。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NormalComposer(
    state: UiState,
    vm: AppViewModel,
    speechState: com.dsh.mobile.core.SpeechState?,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onMic: () -> Unit,
) {
    var showAttachSheet by remember { mutableStateOf(false) }

    // 附件选择浮层：图片 / 文件
    if (showAttachSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    "添加附件",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 20.dp, bottom = 6.dp),
                )
                ListItem(
                    headlineContent = { Text("图片") },
                    supportingContent = { Text("照片、截图，直接内联发送") },
                    leadingContent = { Icon(Icons.Filled.Image, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        onPickImage()
                    },
                )
                ListItem(
                    headlineContent = { Text("文件") },
                    supportingContent = { Text("PDF / Word / Excel / PPT / 文本 / 代码等") },
                    leadingContent = { Icon(Icons.Filled.Description, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        onPickFile()
                    },
                )
            }
        }
    }

    Column {
        // 已挂上的附件（等待与文字一起发送）
        if (state.attachments.isNotEmpty()) {
            LazyRow(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.attachments, key = { it.id }) { a ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Row(
                            Modifier.padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 图片显示缩略图，文档显示类型图标
                            val thumb = if (a.isImage) produceState<android.graphics.Bitmap?>(null, a.id) {
                    withContext(kotlinx.coroutines.Dispatchers.IO) { value = decodeThumb(a.base64) }
                }.value else null
                            if (thumb != null) {
                                Image(
                                    bitmap = thumb.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(RoundedCornerShape(6.dp)),
                                )
                            } else {
                                Icon(
                                    if (a.isImage) Icons.Filled.Image else Icons.Filled.Description,
                                    null,
                                    Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(5.dp))
                            Text(
                                a.name,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 140.dp),
                            )
                            IconButton(
                                onClick = { vm.removeAttachment(a.id) },
                                modifier = Modifier.size(22.dp),
                            ) {
                                Icon(Icons.Filled.Close, "移除附件", Modifier.size(13.dp))
                            }
                        }
                    }
                }
            }
        }
        if (state.uploading) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        // 单行 pill：＋ | 输入（多行时自动撑高） | 麦克风 | 发送/停止
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            // 居中对齐：Bottom 会让按钮圆心低于胶囊中心（用户反馈的偏移）
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { showAttachSheet = true },
                enabled = !state.sending && state.openSessionId != null,
                modifier = Modifier.size(40.dp),
            ) {
            Icon(
                Icons.Filled.Add,
                "发送图片",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(21.dp),
            )
        }

        Box(
            Modifier
                .weight(1f)
                .padding(vertical = 11.dp)
        ) {
            if (state.draft.isEmpty()) {
                val hint = when (speechState) {
                    is com.dsh.mobile.core.SpeechState.Error -> speechState.message
                    else -> state.openSessionTitle.ifBlank { "发送消息…" }
                }
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (speechState is com.dsh.mobile.core.SpeechState.Error)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = state.draft,
                onValueChange = vm::updateDraft,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 6,
            )
        }

        IconButton(onClick = onMic, enabled = !state.sending, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.Mic,
                "语音输入",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(21.dp),
            )
        }
        SendButton(
            running = state.chat.running,
            enabled = (state.draft.isNotBlank() && !state.sending) || (state.chat.running && state.draft.isBlank()),
            busy = state.sending,
            showStop = state.draft.isBlank() && state.chat.running,
            onClick = {
                if (state.draft.isNotBlank()) vm.send()
                else if (state.chat.running) vm.cancelTurn()
            },
        )
    }
        }
}

/** 语音态：✕ 取消 / 实时波形（或「正在转录…」）/ ■ 停止 / 发送。 */
@Composable
private fun VoiceBar(
    speechState: com.dsh.mobile.core.SpeechState?,
    amplitudes: List<Float>,
    partial: String,
    onCancel: () -> Unit,
    onStop: () -> Unit,
    onSend: () -> Unit,
    onRetry: () -> Unit,
) {
    val transcribing = speechState is com.dsh.mobile.core.SpeechState.Transcribing
    val recording = speechState is com.dsh.mobile.core.SpeechState.Listening ||
        speechState is com.dsh.mobile.core.SpeechState.Partial ||
        speechState == null

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Filled.Close, "取消", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(6.dp))

        Box(Modifier.weight(1f)) {
            when {
                speechState is com.dsh.mobile.core.SpeechState.Error -> Text(
                    speechState.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                )

                transcribing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "正在转录…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> Column {
                    Waveform(
                        amplitudes = amplitudes,
                        modifier = Modifier.fillMaxWidth().height(30.dp),
                    )
                    if (partial.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            partial,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // 出错时给重试
        if (speechState is com.dsh.mobile.core.SpeechState.Error) {
            Spacer(Modifier.width(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(10.dp),
                onClick = onRetry,
                modifier = Modifier.size(38.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Filled.Refresh,
                        "重试",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
        // 停止转写（录音中显示）
        if (recording && speechState !is com.dsh.mobile.core.SpeechState.Error) {
            Spacer(Modifier.width(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(10.dp),
                onClick = onStop,
                modifier = Modifier.size(38.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Filled.Stop,
                        "停止",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        SendButton(
            running = false,
            enabled = true,
            busy = false,
            showStop = false,
            onClick = onSend,
        )
    }
}

/** 实时音量波形（从右往左滚动）。 */
@Composable
private fun Waveform(amplitudes: List<Float>, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val barWidth = 2.5.dp.toPx()
        val gap = 3.dp.toPx()
        val maxBars = ((size.width + gap) / (barWidth + gap)).toInt().coerceAtLeast(1)
        val bars = amplitudes.takeLast(maxBars)
        val centerY = size.height / 2f
        val minH = 3.dp.toPx()
        val maxH = size.height
        // 空态给一排静态矮条，避免看起来像坏了
        if (bars.isEmpty()) {
            repeat(maxBars) { i ->
                val x = size.width - (i + 1) * (barWidth + gap)
                if (x < 0) return@repeat
                drawRoundRect(
                    color = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.35f),
                    topLeft = androidx.compose.ui.geometry.Offset(x, centerY - minH / 2),
                    size = androidx.compose.ui.geometry.Size(barWidth, minH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2),
                )
            }
            return@Canvas
        }
        bars.reversed().forEachIndexed { i, level ->
            val x = size.width - (i + 1) * (barWidth + gap)
            if (x < 0) return@forEachIndexed
            val h = (minH + level.coerceIn(0f, 1f) * (maxH - minH)).coerceAtMost(maxH)
            val alpha = 0.35f + 0.65f * (1f - i.toFloat() / bars.size)
            drawRoundRect(
                color = androidx.compose.ui.graphics.Color.Gray.copy(alpha = alpha.coerceIn(0.3f, 1f)),
                topLeft = androidx.compose.ui.geometry.Offset(x, centerY - h / 2),
                size = androidx.compose.ui.geometry.Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2),
            )
        }
    }
}

/** 主按钮：有文字=发送（↑）；无文字且运行中=中断（■）。 */
@Composable
private fun SendButton(
    running: Boolean,
    enabled: Boolean,
    busy: Boolean,
    showStop: Boolean,
    onClick: () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledContentColor = MaterialTheme.colorScheme.outline,
        ),
    ) {
        if (busy && !showStop) {
            CircularProgressIndicator(
                Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Icon(
                if (showStop) Icons.Filled.Stop else Icons.Filled.ArrowUpward,
                if (showStop) "中断" else "发送",
                Modifier.size(18.dp),
            )
        }
    }
}

/** 排队消息胶囊：图标 + 文本 + ⋯（编辑 / 改为引导 / 取消发送）。 */
@Composable
private fun QueuedChip(
    queued: com.dsh.mobile.data.model.QueuedMessage,
    onEdit: () -> Unit,
    onSteer: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f),
        shape = RoundedCornerShape(18.dp),
    ) {
            Row(
                Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (queued.steer) Icons.Filled.Bolt else Icons.Filled.Schedule,
                    null,
                    Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    queued.text.ifBlank { "(空消息)" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
                Spacer(Modifier.width(4.dp))
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.MoreHoriz,
                            "排队消息操作",
                            Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        Text(
                            if (queued.steer) "立刻插入当前任务" else "等待当前任务完成后自动发送",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 14.dp, top = 8.dp, end = 14.dp, bottom = 4.dp),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DropdownMenuItem(
                            text = { Text("编辑消息") },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("立刻发送") },
                            leadingIcon = { Icon(Icons.Filled.Bolt, null) },
                            onClick = {
                                menuOpen = false
                                onSteer()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text("取消消息发送", color = MaterialTheme.colorScheme.error)
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Delete,
                                    null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onRemove()
                            },
                        )
                    }
                }
            }
        }
}

// ---------- ask-user 提问卡片 ----------

@Composable
private fun QuestionCard(
    q: PendingQuestion,
    onAnswer: (String) -> Unit,
    onUndo: () -> Unit = {},
) {
    // 显示"当前这一道还没答的题"；全部答完时组件通常已被清空，兜底不渲染
    val cur = q.next ?: return
    val answeredCount = q.answers.size
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.HelpOutline,
                    null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    cur.header.ifBlank { "Agent 提问" },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
                // 多题时显示进度，让用户知道还有几题
                q.progress?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f),
                    )
                }
            }

            // 已答的题折叠成一行摘要，方便回看
            if (answeredCount > 0) {
                Spacer(Modifier.height(4.dp))
                q.questions.filter { it.id in q.answers }.forEach { done ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            null,
                            Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "${done.header.ifBlank { done.question }}：${q.answers[done.id]}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                TextButton(onClick = onUndo, contentPadding = PaddingValues(0.dp)) {
                    Text("撤回上一题", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                cur.question,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            cur.detail?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(3.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                )
            }
            if (cur.options.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                // multiSelect：先勾选再提交；单选：点一下即作答
                val picked = mutableListOf<String>()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    cur.options.forEach { opt ->
                        val checked = opt.label in picked
                        Surface(
                            color = if (checked) MaterialTheme.colorScheme.surfaceContainerHighest
                            else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(10.dp),
                            onClick = {
                                if (cur.multiSelect) {
                                    if (checked) picked.remove(opt.label) else picked.add(opt.label)
                                } else {
                                    onAnswer(opt.label)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (cur.multiSelect) {
                                    Icon(
                                        if (checked) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                        null,
                                        Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(opt.label, style = MaterialTheme.typography.bodyMedium)
                                    opt.description?.takeIf { it.isNotBlank() }?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (cur.multiSelect) {
                        Spacer(Modifier.height(2.dp))
                        Button(
                            onClick = {
                                if (picked.isNotEmpty()) onAnswer(picked.joinToString("、"))
                            },
                            enabled = picked.isNotEmpty(),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("提交本题（可多选）") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadMoreRow(loading: Boolean, onLoad: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "正在加载更早的消息…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            TextButton(onClick = onLoad) {
                Text("加载更早的消息", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}