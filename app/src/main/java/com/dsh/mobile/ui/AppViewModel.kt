package com.dsh.mobile.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.mobile.core.DshException
import com.dsh.mobile.data.model.ChatState
import com.dsh.mobile.data.model.DirNode
import com.dsh.mobile.data.model.KnownDirs
import com.dsh.mobile.data.model.ModelGroup
import com.dsh.mobile.data.model.ModelSelectionInfo
import com.dsh.mobile.data.model.PendingAttachment
import com.dsh.mobile.data.model.PendingQuestion
import com.dsh.mobile.data.model.QueueAction
import com.dsh.mobile.data.model.QueuedMessage
import com.dsh.mobile.data.model.SessionSummary
import com.dsh.mobile.data.model.WorkspaceEvent
import com.dsh.mobile.data.model.WorkspaceInfo
import com.dsh.mobile.data.model.WorkspaceState
import com.dsh.mobile.data.model.parseUserQuestion
import com.dsh.mobile.data.model.reduceEvent
import com.dsh.mobile.data.model.reduceRecords
import com.dsh.mobile.data.repo.DshRepository
import com.dsh.mobile.data.repo.FollowEvent
import com.dsh.mobile.data.repo.StreamDelta
import com.dsh.mobile.ui.theme.Accent
import com.dsh.mobile.ui.theme.ThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 主界面是聊天，会话列表在侧边抽屉里（对齐 Codex 移动端）。 */
enum class Screen { Login, Main, Settings }

@Serializable
data class ServerConfig(val base: String, val pin: String, val label: String = "")

/** 会话列表渲染行（分区分组）。 */
sealed interface SessionRow {
    data class Header(
        val title: String,
        val count: Int,
        val kind: HeaderKind,
        val workspaceId: String? = null,
        /** 工作区路径：用于在该工作区里新建会话 */
        val path: String? = null,
        val expanded: Boolean = true,
    ) : SessionRow
    data class Item(val summary: SessionSummary, val pinned: Boolean, val archived: Boolean) : SessionRow
}

enum class HeaderKind { Pinned, Workspace, Other, Archived }

data class UiState(
    val screen: Screen = Screen.Login,
    val servers: List<ServerConfig> = emptyList(),
    val base: String = "",
    val pin: String = "",
    val busy: Boolean = false,
    val loginError: String? = null,
    val banner: String? = null,
    /** banner 递增 id：让相同文案也能再次触发 Snackbar */
    val bannerId: Int = 0,
    val connection: String = "",
    val sessions: List<SessionSummary> = emptyList(),
    val workspaceState: WorkspaceState = WorkspaceState(),
    val loading: Boolean = false,
    val query: String = "",
    val showArchived: Boolean = false,
    val openSessionId: String? = null,
    val openSessionTitle: String = "",
    val chat: ChatState = ChatState(),
    val draft: String = "",
    val themeMode: ThemeMode = ThemeMode.System,
    val accent: Accent = Accent.Purple,
    val dynamicColor: Boolean = false,
    val maxMessages: Int = 30,
    /** 抽屉里展开的工作区（workspaceId 集合）。 */
    val expandedWorkspaces: Set<String> = emptySet(),
    /** 目录浏览器数据（已知目录树）。 */
    val knownDirRoots: List<DirNode> = emptyList(),
    val sending: Boolean = false,
    val hasMoreHistory: Boolean = false,
    val loadingMore: Boolean = false,
    val oldestSeq: Long = -1L,
    val models: List<ModelGroup> = emptyList(),
    val currentModel: ModelSelectionInfo? = null,
    val loadingModels: Boolean = false,
    val pendingQuestion: PendingQuestion? = null,
    /** 排队中的消息（排队 + 引导）。 */
    val queued: List<QueuedMessage> = emptyList(),
    /** 已挂上、等待与文字一起发送的附件（不再选完即发）。 */
    val attachments: List<PendingAttachment> = emptyList(),
    /** 文档上传中 */
    val uploading: Boolean = false,
    val dialog: Dialog? = null,
    val drawerOpen: Boolean = false,
    /** 启动时自动恢复的会话（connectAndLoad 成功后打开）。 */
    val lastSessionId: String? = null,
) {
    val activeServerLabel: String
        get() = base.ifBlank { "未连接" }
}

/** 需要弹窗的交互（重命名 / 新建工作区 / 确认归档等）。 */
sealed interface Dialog {
    data class RenameSession(val sessionId: String, val current: String) : Dialog
    data class RenameWorkspace(val workspaceId: String, val current: String) : Dialog
    data class NewWorkspace(val path: String = "") : Dialog
    data class ArchiveRunning(val sessionId: String, val title: String) : Dialog
    data class ConfirmDeleteWorkspace(val workspaceId: String, val title: String) : Dialog
    data class SessionActions(val sessionId: String) : Dialog
    data object ModelPicker : Dialog
    data class EditQueued(val itemId: String, val current: String) : Dialog
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = DshRepository(app)
    private val prefs = app.getSharedPreferences("dsh_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(loadInitial())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var followJob: Job? = null
    private var workspaceJob: Job? = null
    private var eventsJob: Job? = null
    private var pingJob: Job? = null

    private fun loadInitial(): UiState {
        var servers = runCatching {
            json.decodeFromString<List<ServerConfig>>(prefs.getString("servers", "[]")!!)
        }.getOrDefault(emptyList())

        // 从旧版本（单服务器键）迁移
        val legacyBase = prefs.getString("base", null)
        val legacyPin = prefs.getString("pin", null)
        if (servers.isEmpty() && !legacyBase.isNullOrBlank() && !legacyPin.isNullOrBlank()) {
            servers = listOf(ServerConfig(legacyBase, legacyPin))
            prefs.edit()
                .putString("servers", json.encodeToString(servers))
                .putString("active_base", legacyBase)
                .apply()
        }

        val themeMode = runCatching {
            ThemeMode.valueOf(prefs.getString("theme", ThemeMode.System.name)!!)
        }.getOrDefault(ThemeMode.System)
        val accent = runCatching {
            Accent.valueOf(prefs.getString("accent", Accent.Purple.name)!!)
        }.getOrDefault(Accent.Purple)
        val active = prefs.getString("active_base", null)
        val server = servers.firstOrNull { it.base == active } ?: servers.firstOrNull()
        val loggedIn = prefs.getBoolean("logged_in", false)
        return UiState(
            screen = if (loggedIn && server != null) Screen.Main else Screen.Login,
            servers = servers,
            base = server?.base.orEmpty(),
            pin = server?.pin.orEmpty(),
            themeMode = themeMode,
            accent = accent,
            dynamicColor = prefs.getBoolean("dynamic_color", false),
            maxMessages = prefs.getInt("max_messages", 30),
            lastSessionId = prefs.getString("last_session", null),
        )
    }

    init {
        viewModelScope.launch {
            repo.streamStatus.collect { s -> _state.update { it.copy(connection = s) } }
        }
        if (_state.value.screen == Screen.Main) {
            viewModelScope.launch {
                // connectAndLoad 在登录失败时会抛（让 login() 能感知），
                // 这两个静默调用点必须自己接住，否则进程直接崩。
                try {
                    connectAndLoad(silent = true)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // silent 路径已在 connectAndLoad 内部更新过 loginError/screen
                }
            }
        }
    }

    // ---------- 登录 / 服务器 ----------

    fun updateBase(v: String) = _state.update { it.copy(base = v.trim(), loginError = null) }
    fun updatePin(v: String) = _state.update { it.copy(pin = v.trim(), loginError = null) }
    fun updateDraft(v: String) = _state.update { it.copy(draft = v) }
    fun updateQuery(v: String) = _state.update { it.copy(query = v) }
    fun toggleArchived() = _state.update { it.copy(showArchived = !it.showArchived) }
    fun dismissBanner() = _state.update { it.copy(bannerId = it.bannerId + 1, banner = null) }

    fun showBannerOnce(text: String) = _state.update { it.copy(bannerId = it.bannerId + 1, banner = text) }
    fun dismissDialog() = _state.update { it.copy(dialog = null) }
    fun showDialog(d: Dialog) = _state.update { it.copy(dialog = d) }
    fun updateDialogPath(v: String) = _state.update { st ->
        val d = st.dialog
        if (d is Dialog.NewWorkspace) st.copy(dialog = d.copy(path = v)) else st
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString("theme", mode.name).apply()
        _state.update { it.copy(themeMode = mode) }
    }

    fun setAccent(a: Accent) {
        prefs.edit().putString("accent", a.name).apply()
        _state.update { it.copy(accent = a) }
    }

    /** 折叠/展开抽屉里的某个工作区分组。 */
    fun toggleWorkspaceExpanded(workspaceId: String) {
        _state.update { st ->
            val cur = st.expandedWorkspaces
            st.copy(
                expandedWorkspaces = if (workspaceId in cur) cur - workspaceId else cur + workspaceId
            )
        }
    }

    /** 汇总已知目录（session/list 的 cwd + 现有工作区 path），供可视化选择器构树。 */
    private fun rebuildKnownDirs() {
        val st = _state.value
        val paths = buildList {
            addAll(st.sessions.mapNotNull { it.cwd })
            addAll(st.workspaceState.workspaces.map { it.path })
        }.distinct()
        _state.update { it.copy(knownDirRoots = KnownDirs.build(paths)) }
    }

    fun setDynamicColor(on: Boolean) {
        prefs.edit().putBoolean("dynamic_color", on).apply()
        _state.update { it.copy(dynamicColor = on) }
    }

    fun setMaxMessages(n: Int) {
        prefs.edit().putInt("max_messages", n).apply()
        _state.update { it.copy(maxMessages = n) }
    }

    /**
     * 处理扫码得到的配对信息：填入地址/PIN 并直接登录。
     * @return 解析失败时的提示，成功返回 null。
     */
    fun applyPairing(raw: String): String? {
        val r = com.dsh.mobile.core.PairingCode.parse(raw)
        if (!r.usable) return r.error ?: "二维码里没有服务器地址"
        _state.update {
            it.copy(
                base = r.base.orEmpty(),
                pin = r.pin ?: it.pin,
                loginError = null,
            )
        }
        if (_state.value.pin.isBlank()) {
            _state.update { it.copy(loginError = "已识别地址，请再输入 PIN") }
            return null
        }
        login()
        return null
    }

    fun login() {
        val s = _state.value
        if (s.pin.isBlank() || s.base.isBlank()) {
            _state.update { it.copy(loginError = "请填写服务器地址与 PIN") }
            return
        }
        _state.update { it.copy(busy = true, loginError = null) }
        viewModelScope.launch {
            try {
                persistServer(s.base, s.pin)
                connectAndLoad(silent = false)
                _state.update { it.copy(screen = Screen.Main) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                _state.update { it.copy(busy = false, loginError = e.readable()) }
            }
        }
    }

    private fun persistServer(base: String, pin: String) {
        val list = _state.value.servers.filterNot { it.base == base } + ServerConfig(base, pin)
        prefs.edit()
            .putString("servers", json.encodeToString(list))
            .putString("active_base", base)
            .putBoolean("logged_in", true)
            .apply()
        _state.update { it.copy(servers = list) }
    }

    private suspend fun connectAndLoad(silent: Boolean) {
        val s = _state.value
        try {
            repo.login(s.base, s.pin)
            repo.connect(s.base)
            _state.update { it.copy(busy = false, loginError = null) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 登录这一步必须让失败向上抛：login() 只有在连接成功后才能进主界面，
            // 否则 PIN 输错也会切到 Main，用户看到的是空主界面。
            throw e
        } catch (e: Exception) {
            _state.update {
                if (silent) it.copy(screen = Screen.Login, loginError = "自动登录失败：${e.readable()}")
                else it.copy(busy = false, loginError = e.readable())
            }
            throw e
        }
        try {
            startWorkspaceFollow()
            startEventFollow()
            loadSessions()
            startPing()
            // 启动时自动回到最后一次对话。
            // 会话列表是异步拉的（700+ 条要 1~2 秒），所以要有界轮询等待，不能一次判断就清记录。
            val last = _state.value.lastSessionId
            if (last != null) {
                var waited = 0
                var target = _state.value.sessions.firstOrNull { it.sessionId == last }
                while (target == null && _state.value.sessions.isEmpty() && waited < 12) {
                    delay(700)
                    waited++
                    target = _state.value.sessions.firstOrNull { it.sessionId == last }
                }
                if (target != null) {
                    openSession(target)
                } else if (_state.value.sessions.isNotEmpty()) {
                    // 列表已加载但确实没有这个会话（可能被归档/删除）→ 清掉记录
                    _state.update { it.copy(lastSessionId = null) }
                    prefs.edit().remove("last_session").apply()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 连接已成功、只是后续加载失败：停在主界面并提示，不回登录页
            _state.update { it.copy(bannerId = it.bannerId + 1, banner = "加载会话列表失败：${e.readable()}") }
        }
    }

    fun logout() {
        prefs.edit().putBoolean("logged_in", false).apply()
        // eventsJob/pingJob 也要一并取消：它们挂在旧 mux 的 flow 上，
        // shutdown 后 streams 映射已清空，不取消会导致 ask-user 事件永久丢失，
        // 且 startEventFollow 因 isActive 直接 return 不会重订。
        followJob?.cancel(); workspaceJob?.cancel(); pingJob?.cancel(); eventsJob?.cancel()
        followJob = null; workspaceJob = null; pingJob = null; eventsJob = null
        repo.shutdown()
        _state.update {
            it.copy(
                screen = Screen.Login, sessions = emptyList(), chat = ChatState(),
                openSessionId = null, pendingQuestion = null,
                attachments = emptyList(), queued = emptyList(),
            )
        }
    }

    fun switchServer(base: String) {
        val server = _state.value.servers.firstOrNull { it.base == base } ?: return
        followJob?.cancel(); workspaceJob?.cancel(); pingJob?.cancel(); eventsJob?.cancel()
        followJob = null; workspaceJob = null; pingJob = null; eventsJob = null
        repo.shutdown()
        prefs.edit().putString("active_base", base).putBoolean("logged_in", true).apply()
        _state.update {
            it.copy(
                base = server.base,
                pin = server.pin,
                sessions = emptyList(),
                workspaceState = WorkspaceState(),
                screen = Screen.Main,
                chat = ChatState(),
                openSessionId = null,
            )
        }
        viewModelScope.launch {
            try {
                connectAndLoad(silent = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // silent 路径已在内部更新 loginError/screen
            }
        }
    }

    /** 定期刷新会话列表（运行状态会变化）。 */
    private fun startPing() {
        if (pingJob?.isActive == true) return
        pingJob = viewModelScope.launch {
            while (true) {
                delay(15_000)
                runCatching { loadSessions() }
            }
        }
    }

    // ---------- 会话列表 ----------

    fun loadSessions() {
        val base = _state.value.base
        _state.update { it.copy(loading = it.sessions.isEmpty()) }
        viewModelScope.launch {
            try {
                val list = repo.listSessions(base)
                android.util.Log.d("DshMobile", "loadSessions ok count=${list.size}")
                _state.update { it.copy(sessions = list, loading = false) }
                rebuildKnownDirs()
            } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                android.util.Log.w("DshMobile", "loadSessions FAIL ${e::class.java.simpleName}: ${e.message}")
                _state.update { it.copy(bannerId = it.bannerId + 1, banner = e.readable()) }
            }
        }
    }

    /** 订阅 $events：目前用于捕获 ask-user 提问（waterfall 帧）。 */
    private fun startEventFollow() {
        if (eventsJob?.isActive == true) return
        val base = _state.value.base
        eventsJob = viewModelScope.launch {
            runCatching {
                repo.followEvents(base).collect { value ->
                    parseUserQuestion(value)?.let { q ->
                        _state.update { st ->
                            // 只关心当前打开的会话收到的提问
                            if (st.openSessionId != null && q.agentId == st.openSessionId) {
                                st.copy(pendingQuestion = q, banner = "Agent 正在向你提问")
                            } else {
                                st
                            }
                        }
                    }
                }
            }.onFailure { e ->
                    android.util.Log.w("DshMobile", "queueAction FAIL: ${e::class.java.simpleName} ${e.message}")
                    if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("DshMobile", "events follow failed: ${e.message}")
            }
        }
    }

    /**
     * 回答 ask-user 提问。
     * DSH 的机制是把答案作为 user/message（source.kind = 'user-question-reply'）steer 给 agent，
     * 客户端侧等价做法是用 mode=steer 发一条消息；没有"放弃提问"的 Remote 方法。
     */
    fun answerQuestion(text: String) {
        val s = _state.value
        val sessionId = s.openSessionId ?: return
        if (text.isBlank()) return
        _state.update { it.copy(pendingQuestion = null, draft = "", sending = true) }
        viewModelScope.launch {
            try {
                repo.prompt(s.base, sessionId, text, steer = true)
                _state.update { it.copy(sending = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                _state.update { it.copy(sending = false, draft = text, banner = e.readable()) }
            }
        }
    }

    private fun startWorkspaceFollow() {
        if (workspaceJob?.isActive == true) return
        val base = _state.value.base
        workspaceJob = viewModelScope.launch {
            try {
                repo.followWorkspaces(base).collect { ev ->
                    _state.update { st ->
                        val ws = st.workspaceState
                        when (ev) {
                            is WorkspaceEvent.Baseline -> st.copy(workspaceState = ev.state)
                            is WorkspaceEvent.Upsert -> {
                                val list = ws.workspaces.filterNot { it.workspaceId == ev.workspace.workspaceId } +
                                    ev.workspace
                                st.copy(workspaceState = ws.copy(workspaces = list))
                            }

                            is WorkspaceEvent.Remove -> st.copy(
                                workspaceState = ws.copy(
                                    workspaces = ws.workspaces.filterNot { it.workspaceId == ev.workspaceId }
                                )
                            )

                            is WorkspaceEvent.Archived -> st.copy(
                                workspaceState = ws.copy(archivedSessionIds = ev.ids)
                            )

                            is WorkspaceEvent.Pinned -> st.copy(
                                workspaceState = ws.copy(pinnedSessionIds = ev.ids)
                            )

                            is WorkspaceEvent.Failure -> st.copy(banner = ev.message)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                _state.update { it.copy(bannerId = it.bannerId + 1, banner = "工作区订阅失败：${e.readable()}") }
            }
        }
    }

    /** 按「置顶 / 工作区 / 其他 / 已归档」分组，并做本地搜索过滤。 */
    fun buildRows(): List<SessionRow> {
        val s = _state.value
        val byId = s.sessions.associateBy { it.sessionId }
        val q = s.query.trim()
        val archived = s.workspaceState.archived

        fun matches(x: SessionSummary): Boolean {
            if (q.isEmpty()) return true
            return x.title.contains(q, true) || (x.cwd?.contains(q, true) == true)
        }

        val rows = mutableListOf<SessionRow>()
        val used = mutableSetOf<String>()

        // 1) 置顶
        val pinned = s.workspaceState.pinnedSessionIds.mapNotNull { byId[it] }.filter { matches(it) }
        if (pinned.isNotEmpty()) {
            rows += SessionRow.Header("置顶", pinned.size, HeaderKind.Pinned)
            pinned.forEach {
                used += it.sessionId
                rows += SessionRow.Item(it, pinned = true, archived = it.sessionId in archived)
            }
        }

        // 2) 各工作区（支持折叠；搜索时全部展开）
        s.workspaceState.workspaces.forEach { ws ->
            val items = ws.sessionIds
                .filter { it !in archived && it !in s.workspaceState.pinnedSessionIds }
                .mapNotNull { byId[it] }
                .filter { matches(it) }
                .sortedByDescending { it.updatedAt }
            if (items.isNotEmpty()) {
                val expanded = q.isNotEmpty() || ws.workspaceId in s.expandedWorkspaces ||
                    items.any { it.sessionId == s.openSessionId }
                rows += SessionRow.Header(
                    title = ws.title.ifBlank { ws.path },
                    count = items.size,
                    kind = HeaderKind.Workspace,
                    workspaceId = ws.workspaceId,
                path = ws.path,
                    expanded = expanded,
                )
                // 无论是否展开都标记 used，避免折叠分组的会话漏进「其他」
                items.forEach { used += it.sessionId }
                if (expanded) items.forEach {
                    rows += SessionRow.Item(it, pinned = false, archived = false)
                }
            }
        }

        // 3) 不属于任何工作区的会话
        val others = s.sessions
            .filter { it.sessionId !in used && it.sessionId !in archived && it.blank.not() }
            .filter { matches(it) }
            .sortedByDescending { it.updatedAt }
        if (others.isNotEmpty()) {
            rows += SessionRow.Header("其他", others.size, HeaderKind.Other)
            others.forEach { rows += SessionRow.Item(it, pinned = false, archived = false) }
        }

        // 4) 已归档（默认折叠）
        if (s.showArchived) {
            val arch = s.workspaceState.archivedSessionIds.mapNotNull { byId[it] }.filter { matches(it) }
            if (arch.isNotEmpty()) {
                rows += SessionRow.Header("已归档", arch.size, HeaderKind.Archived)
                arch.forEach { rows += SessionRow.Item(it, pinned = false, archived = true) }
            }
        }

        return rows
    }

    fun archivedCount(): Int = _state.value.workspaceState.archivedSessionIds.size

    // ---------- 会话管理动作 ----------

    fun renameSession(sessionId: String, title: String) {
        val base = _state.value.base
        viewModelScope.launch {
            try {
                repo.renameSession(base, sessionId, title)
                _state.update { it.copy(bannerId = it.bannerId + 1, banner = "已重命名") }
                loadSessions()
                if (_state.value.openSessionId == sessionId) {
                    _state.update { it.copy(openSessionTitle = title) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                _state.update { it.copy(bannerId = it.bannerId + 1, banner = "重命名失败：${e.readable()}") }
            }
        }
    }

    fun togglePin(sessionId: String) {
        val base = _state.value.base
        val pinned = sessionId in _state.value.workspaceState.pinnedSessionIds
        viewModelScope.launch {
            try {
                val ids = repo.setPinned(base, sessionId, !pinned)
                _state.update { it.copy(workspaceState = it.workspaceState.copy(pinnedSessionIds = ids)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                _state.update { it.copy(bannerId = it.bannerId + 1, banner = e.readable()) }
            }
        }
    }

    fun setArchived(sessionId: String, archived: Boolean, stopActivity: Boolean = false) {
        val base = _state.value.base
        viewModelScope.launch {
            try {
                val ids = repo.setArchived(base, sessionId, archived, stopActivity)
                _state.update {
                    it.copy(
                        workspaceState = it.workspaceState.copy(archivedSessionIds = ids),
                        banner = if (archived) "已归档" else "已取消归档",
                    )
                }
                if (archived && _state.value.openSessionId == sessionId) closeSession()
            } catch (e: DshException) {
                // 会话仍在跑：服务端拒绝，改为询问是否强制归档
                if (e.code == "workspace/session-active") {
                    val title = _state.value.sessions.firstOrNull { it.sessionId == sessionId }?.title ?: "该会话"
                    _state.update { it.copy(dialog = Dialog.ArchiveRunning(sessionId, title)) }
                } else {
                    _state.update { it.copy(bannerId = it.bannerId + 1, banner = e.readable()) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                _state.update { it.copy(bannerId = it.bannerId + 1, banner = e.readable()) }
            }
        }
    }

    fun forkSession(sessionId: String) {
        val base = _state.value.base
        viewModelScope.launch {
            try {
                val newId = repo.forkSession(base, sessionId)
                _state.update { it.copy(bannerId = it.bannerId + 1, banner = "已派生新会话") }
                loadSessions()
                delay(600)
                _state.value.sessions.firstOrNull { it.sessionId == newId }?.let { openSession(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                _state.update { it.copy(bannerId = it.bannerId + 1, banner = "派生失败：${e.readable()}") }
            }
        }
    }

    fun createSession(workspaceId: String? = null, cwd: String? = null) {
        val base = _state.value.base
        viewModelScope.launch {
            try {
                val id = repo.createSession(base, workspaceId, cwd)
                android.util.Log.d("DshMobile", "createSession ok id=$id ws=$workspaceId")
                // 立刻进入新会话。
                // 不要"先 loadSessions 再在列表里找" —— 那是竞态：列表还没刷新完就找不到，
                // 表现就是"新建后不自动进入"。这里直接用返回的 sessionId 构造一个，
                // 真实标题/时间由随后的 loadSessions 补齐。
                val known = _state.value.sessions.firstOrNull { it.sessionId == id }
                val target = known ?: com.dsh.mobile.data.model.SessionSummary(
                    sessionId = id,
                    title = "",
                    running = false,
                    cwd = workspaceId
                        ?.let { ws -> _state.value.workspaceState.workspaces.firstOrNull { it.workspaceId == ws }?.path }
                        ?: cwd,
                    updatedAt = System.currentTimeMillis(),
                    blank = true,
                )
                openSession(target)
                loadSessions()
            } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                _state.update { it.copy(bannerId = it.bannerId + 1, banner = "新建失败：${e.readable()}") }
            }
        }
    }

    fun createWorkspace(path: String) {
        val base = _state.value.base
        viewModelScope.launch {
            try {
                repo.createWorkspace(base, path)
                _state.update { it.copy(dialog = null, banner = "已添加工作区") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                _state.update { it.copy(bannerId = it.bannerId + 1, banner = "添加失败：${e.readable()}") }
            }
        }
    }

    fun renameWorkspace(workspaceId: String, title: String) {
        val base = _state.value.base
        viewModelScope.launch {
            runCatching { repo.renameWorkspace(base, workspaceId, title) }
                .onFailure { e ->
                    android.util.Log.w("DshMobile", "queueAction FAIL: ${e::class.java.simpleName} ${e.message}")
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _state.update { it.copy(bannerId = it.bannerId + 1, banner = e.readable()) }
                }
            _state.update { it.copy(dialog = null) }
        }
    }

    fun deleteWorkspace(workspaceId: String) {
        val base = _state.value.base
        viewModelScope.launch {
            try {
                repo.deleteWorkspace(base, workspaceId)
                _state.update { it.copy(dialog = null, banner = "工作区已移除") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                _state.update { it.copy(bannerId = it.bannerId + 1, banner = e.readable()) }
            }
        }
    }

    // ---------- 会话打开 / 聊天 ----------

    fun openSession(s: SessionSummary) {
        lastQueueRefresh = 0L
        prefs.edit().putString("last_session", s.sessionId).apply()
        // 先取消并清理上一个会话的订阅，避免服务端残留旧流
        followJob?.cancel()
        _state.value.openSessionId?.takeIf { it != s.sessionId }?.let { repo.closeFollow(it) }
        _state.update {
            it.copy(
                screen = Screen.Main,
                drawerOpen = false,
                openSessionId = s.sessionId,
                openSessionTitle = s.title,
                currentModel = s.modelName?.let { m ->
                    ModelSelectionInfo(s.modelProvider ?: "", m)
                },
                chat = ChatState(),
                draft = "",
                dialog = null,
                hasMoreHistory = false,
                oldestSeq = -1L,
                queued = emptyList(),
                // 上一会话的附件/提问不能带进新会话：附件会发到错误的会话
                attachments = emptyList(),
                uploading = false,
                pendingQuestion = null,
            )
        }
        val base = _state.value.base
        val maxMessages = _state.value.maxMessages
        followJob = viewModelScope.launch {
            try {
                repo.followSession(base, s.sessionId, maxMessages = maxMessages).collect { ev ->
                    when (ev) {
                        is FollowEvent.Snapshot -> {
                            val next = ChatState().reduceRecords(ev.records)
                            val oldest = ev.records
                                .mapNotNull { it["event"]?.jsonObject?.get("seq")?.jsonPrimitive?.longOrNull }
                                .minOrNull() ?: -1L
                            // 会话切换/首次打开时 chat 本来就是空的，整体覆盖没问题。
                            // 但**同会话重订阅**（如队列刷新）时，快照只有最近 maxMessages 条，
                            // 直接覆盖会把用户已通过 session/page 翻出的更早历史清掉。
                            // 判别方法：当前 openSessionId 仍是本会话且已有内容 → 合并去重保留。
                            _state.update { st ->
                                val sameSession = st.openSessionId == s.sessionId && st.chat.items.isNotEmpty()
                                val merged = if (sameSession) {
                                    val existing = st.chat.items.map { it.key }.toSet()
                                    st.chat.copy(items = st.chat.items + next.items.filterNot { it.key in existing })
                                } else next
                                st.copy(
                                    chat = merged,
                                    hasMoreHistory = ev.hasMore || (sameSession && st.hasMoreHistory),
                                    oldestSeq = if (sameSession && st.oldestSeq != -1L) {
                                        if (oldest != -1L) minOf(st.oldestSeq, oldest) else st.oldestSeq
                                    } else oldest,
                                    queued = ev.queued,
                                )
                            }
                            // 记录快照里的排队项，便于直接对服务端复现 updateQueue
                            android.util.Log.d(
                                "DshMobile",
                                "inbox(" + ev.queued.size + ")=" + ev.queued.joinToString(" | ") {
                                    it.itemId + " steer=" + it.steer + " text=" + it.text.take(16)
                                },
                            )
                        }

                        is FollowEvent.Committed -> {
                            val evType = (ev.event["type"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                            _state.update { st -> st.copy(chat = st.chat.reduceEvent(ev.event)) }

                            // 注意：这里**不能**凭 turn/start、step/start 本地摘除排队项。
                            // 我一度这么改过（"回合开始 = 排队项已投递"），但那是错的：
                            // 排队项卡住时它会把"其实没被处理"的消息从 UI 上抹掉，
                            // 让界面谎报投递成功，比留下一个陈旧的胶囊更糟。
                            // 队列的真相只有一个来源：服务端 inbox（靠快照权威刷新）。
                            if (evType == "agent/inbox/spliced") refreshQueue()
                        }

                        is FollowEvent.Delta -> _state.update { st ->
                            val c = st.chat
                            val next = when (val d = ev.delta) {
                                is StreamDelta.Text -> c.copy(liveText = c.liveText + d.text, running = true)
                                is StreamDelta.Reasoning -> c.copy(liveReasoning = c.liveReasoning + d.text, running = true)
                                is StreamDelta.BlockStart -> if (d.blockType == "text") c.copy(liveText = "") else c
                                StreamDelta.Ended -> c.copy(liveText = "", liveReasoning = "")
                            }
                            st.copy(chat = next)
                        }

                        is FollowEvent.Failure -> _state.update { st -> st.copy(banner = ev.message) }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                _state.update { it.copy(bannerId = it.bannerId + 1, banner = e.readable()) }
            }
        }
    }

    /** 向前加载更早的历史（session/page），结果前置拼接到当前列表。 */
    fun loadMoreHistory() {
        val s = _state.value
        val sessionId = s.openSessionId ?: return
        if (s.loadingMore || !s.hasMoreHistory) return
        val through = s.oldestSeq
        if (through <= 0) {
            _state.update { it.copy(hasMoreHistory = false) }
            return
        }
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = repo.pageMessages(
                    base = s.base,
                    sessionId = sessionId,
                    throughSeq = through,
                    // beforeSeq=0 会被服务端判为空页（protocol §7.9），所以下界是 1：
                    // 否则 oldestSeq≤600 时最早一段历史永远取不到，且 hasMore 被误置 false
                    beforeSeq = (through - 600).coerceAtLeast(1),
                    maxMessages = s.maxMessages,
                )
                // 只保留比当前最旧一条更早的记录，并按 key 去重后前置
                val older = ChatState().reduceRecords(page.records)
                val existing = _state.value.chat.items.map { it.key }.toSet()
                val prepend = older.items.filterNot { it.key in existing }
                _state.update { st ->
                    st.copy(
                        chat = st.chat.copy(items = prepend + st.chat.items),
                        loadingMore = false,
                        hasMoreHistory = page.hasMore && page.records.isNotEmpty(),
                        oldestSeq = if (page.records.isEmpty()) st.oldestSeq else page.oldestSeq,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                _state.update { it.copy(loadingMore = false, banner = "加载历史失败：${e.readable()}") }
            }
        }
    }

    // ---------- 模型切换 ----------

    fun openModelPicker() {
        _state.update { it.copy(dialog = Dialog.ModelPicker) }
        if (_state.value.models.isEmpty()) loadModels()
    }

    fun loadModels() {
        if (_state.value.loadingModels) return
        _state.update { it.copy(loadingModels = true) }
        val base = _state.value.base
        viewModelScope.launch {
            try {
                val (def, groups) = repo.modelCatalog(base)
                _state.update { st ->
                    st.copy(
                        models = groups,
                        loadingModels = false,
                        currentModel = st.currentModel ?: def,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                _state.update { it.copy(loadingModels = false, banner = "读取模型目录失败：${e.readable()}") }
            }
        }
    }

    fun selectModel(provider: String, model: String, reasoningEffort: String? = null) {
        val s = _state.value
        val sessionId = s.openSessionId
        _state.update { it.copy(dialog = null) }
        viewModelScope.launch {
            try {
                if (sessionId == null) {
                    // 没有打开会话时只记住选择（新建会话后生效）
                    _state.update { it.copy(currentModel = ModelSelectionInfo(provider, model, reasoningEffort)) }
                    return@launch
                }
                val selected = repo.selectModel(s.base, sessionId, provider, model, reasoningEffort)
                _state.update { it.copy(currentModel = selected, banner = "已切换到 ${selected.label}") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                _state.update { it.copy(bannerId = it.bannerId + 1, banner = "切换模型失败：${e.readable()}") }
            }
        }
    }

    /** 关闭当前会话，回到「未选择会话」的主界面。 */
    fun closeSession() {
        followJob?.cancel()
        _state.value.openSessionId?.let { repo.closeFollow(it) }
        _state.update { it.copy(screen = Screen.Main, openSessionId = null, chat = ChatState()) }
        loadSessions()
    }

    fun openDrawer() = _state.update { it.copy(drawerOpen = true) }
    fun closeDrawer() = _state.update { it.copy(drawerOpen = false) }

    fun openSettings() = _state.update { it.copy(screen = Screen.Settings, drawerOpen = false) }
    fun closeSettings() = _state.update { it.copy(screen = Screen.Main) }

    fun send(steer: Boolean = false) {
        val s = _state.value
        val sessionId = s.openSessionId ?: return
        val text = s.draft.trim()
        val atts = s.attachments
        if ((text.isEmpty() && atts.isEmpty()) || s.sending) return
        _state.update { it.copy(draft = "", sending = true) }
        viewModelScope.launch {
            try {
                if (atts.isEmpty()) {
                    repo.prompt(s.base, sessionId, text, steer = steer)
                } else {
                    // 文字 + 附件一起发：图片内联 base64，文档引用 receiptId
                    val parts = buildList {
                        if (text.isNotBlank()) {
                            add(kotlinx.serialization.json.buildJsonObject {
                                put("type", "text"); put("text", text)
                            })
                        }
                        atts.forEach { a ->
                            add(
                                kotlinx.serialization.json.buildJsonObject {
                                    if (a.isImage) {
                                        put("type", "image")
                                        put("mediaType", a.mediaType)
                                        put("data", a.base64 ?: "")
                                        put("name", a.name)
                                    } else {
                                        put("type", "file")
                                        put("receiptId", a.receiptId ?: "")
                                    }
                                },
                            )
                        }
                    }
                    repo.promptParts(s.base, sessionId, parts, steer = steer)
                }
                // 发送成功才清空附件
                _state.update { it.copy(attachments = emptyList()) }
                // 明确告诉用户这条消息的去向：正在跑任务时不会立刻被看到
                val running = _state.value.chat.running
                _state.update {
                    it.copy(
                        sending = false,
                        banner = when {
                            steer -> "已立刻发送，插入当前任务"
                            running -> "已加入队列，当前任务完成后自动发送"
                            else -> null
                        },
                    )
                }
                refreshQueue()
            } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                _state.update { it.copy(sending = false, draft = text, banner = e.readable()) }
            }
        }
    }

    private var lastQueueRefresh = 0L

    /**
     * 刷新排队列表。
     *
     * 实测 `session/projections` 的 inbox 不可靠（队列有项时也返回空），所以这里改为
     * **让服务端重发 follow 快照**：快照里的 projections.inbox 是权威的，
     * Snapshot 分支会用 ev.queued 整体覆盖本地列表（这样被消费/被桌面端改动的项才会消失）。
     *
     * 事件可能密集（每个 step 边界都有），做 3s 节流。
     */
    fun refreshQueue() {
        val s = _state.value
        val sid = s.openSessionId ?: return
        val now = System.currentTimeMillis()
        if (now - lastQueueRefresh < 3000) return
        lastQueueRefresh = now
        runCatching { repo.resubscribeFollow(sid) }
    }

    /** 编辑排队消息。 */
    fun editQueued(itemId: String, text: String) {
        val s = _state.value
        val sid = s.openSessionId ?: return
        viewModelScope.launch {
            runCatching { repo.updateQueue(s.base, sid, itemId, QueueAction.Edit(text)) }
                .onSuccess {
                    android.util.Log.d("DshMobile", "queueAction OK")
                    _state.update { st ->
                        st.copy(queued = st.queued.map { if (it.itemId == itemId) it.copy(text = text) else it })
                    }
                }
                .onFailure { e ->
                    android.util.Log.w("DshMobile", "queueAction FAIL: ${e::class.java.simpleName} ${e.message}")
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _state.update { it.copy(bannerId = it.bannerId + 1, banner = "编辑失败：${e.readable()}") }
                }
        }
    }

    /** 改为引导（立刻在当前回合生效）。 */
    fun steerQueued(itemId: String) {
        val s = _state.value
        val sid = s.openSessionId ?: return
        viewModelScope.launch {
            runCatching { repo.updateQueue(s.base, sid, itemId, QueueAction.Steer) }
                .onSuccess {
                    android.util.Log.d("DshMobile", "queueAction OK")
                    _state.update { st ->
                        st.copy(
                            queued = st.queued.map { if (it.itemId == itemId) it.copy(steer = true) else it },
                            banner = "已改为引导",
                        )
                    }
                }
                .onFailure { e ->
                    android.util.Log.w("DshMobile", "queueAction FAIL: ${e::class.java.simpleName} ${e.message}")
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (e is DshException && e.code == "session/queue-item-not-found") refreshQueue()
                    else _state.update { it.copy(bannerId = it.bannerId + 1, banner = "操作失败：${e.readable()}") }
                }
        }
    }

    /** 取消排队消息。 */
    fun removeQueued(itemId: String) {
        val s = _state.value
        val sid = s.openSessionId ?: return
        viewModelScope.launch {
            runCatching { repo.updateQueue(s.base, sid, itemId, QueueAction.Remove) }
                .onSuccess {
                    android.util.Log.d("DshMobile", "queueAction OK")
                    _state.update { st ->
                        st.copy(queued = st.queued.filterNot { it.itemId == itemId }, banner = "已取消发送")
                    }
                }
                .onFailure { e ->
                    android.util.Log.w("DshMobile", "queueAction FAIL: ${e::class.java.simpleName} ${e.message}")
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // 排队项在下一个 step 边界就可能被消费掉，此时"找不到"是正常结果：
                    // 目标已经不在队列里了，静默刷新即可，不要报错吓人。
                    if (e is DshException && e.code == "session/queue-item-not-found") {
                        refreshQueue()
                    } else {
                        _state.update { it.copy(bannerId = it.bannerId + 1, banner = "取消失败：${e.readable()}") }
                    }
                }
        }
    }

    fun cancelTurn() {
        val s = _state.value
        val sessionId = s.openSessionId ?: return
        viewModelScope.launch {
            try {
                repo.cancel(s.base, sessionId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                _state.update { it.copy(bannerId = it.bannerId + 1, banner = e.readable()) }
            }
        }
    }

    fun copyToClipboard(text: String) {
        val ctx = getApplication<Application>()
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh", text))
        _state.update { it.copy(bannerId = it.bannerId + 1, banner = "已复制") }
    }

    /**
     * 发送图片（可带当前草稿作为说明文字）。
     *
     * wire 形状：`content: [{type:'text',text}?, {type:'image',mediaType,data(base64),name}]`
     * 图片先降采样再压缩，避免 base64 撑爆请求（DSH 侧单图上限 20MB）。
     */
    /** 选图片 → **挂到输入区**，与文字一起发送（不再选完即发）。 */
    fun attachImage(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val encoded = withContext(kotlinx.coroutines.Dispatchers.IO) { encodeImage(ctx, uri) }
                    ?: throw IllegalStateException("无法读取图片")
                _state.update {
                    it.copy(
                        attachments = it.attachments + PendingAttachment(
                            id = java.util.UUID.randomUUID().toString(),
                            name = encoded.name,
                            mediaType = encoded.mime,
                            bytes = encoded.base64.length * 3L / 4,
                            kind = "image",
                            base64 = encoded.base64,
                        ),

                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(bannerId = it.bannerId + 1, banner = "读取图片失败：${e.readable()}") }
            }
        }
    }

    /** 选文档 → 上传拿 receiptId → 挂到输入区。 */
    fun attachFile(uri: android.net.Uri) {
        val s = _state.value
        val sessionId = s.openSessionId ?: return
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val resolver = ctx.contentResolver
                val name = queryDisplayName(resolver, uri) ?: "attachment"
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw IllegalStateException("无法读取文件")
                _state.update { it.copy(uploading = true) }
                val receipt = repo.uploadFile(s.base, sessionId, name, bytes)
                // 发送前把字节缓存到本地：发送后服务端不提供再下载，
                // 气泡里点"打开"靠这份缓存（FileProvider + ACTION_VIEW）
                val cachePath = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val dir = java.io.File(ctx.cacheDir, "attachments").apply { mkdirs() }
                    val f = java.io.File(dir, "${System.currentTimeMillis()}_$name")
                    f.writeBytes(bytes)
                    f.absolutePath
                }
                _state.update {
                    it.copy(
                        uploading = false,
                        attachments = it.attachments + PendingAttachment(
                            id = java.util.UUID.randomUUID().toString(),
                            name = name,
                            mediaType = mime,
                            bytes = bytes.size.toLong(),
                            kind = "file",
                            receiptId = receipt,
                            localPath = cachePath,
                        ),

                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(uploading = false, banner = "添加文件失败：${e.readable()}") }
            }
        }
    }

    fun removeAttachment(id: String) {
        _state.update { st -> st.copy(attachments = st.attachments.filterNot { it.id == id }) }
    }

    /**
     * 用系统 App 打开附件（文档）。
     * 只有本机发送时缓存过字节的文件可打开；历史消息里的文档只有 receiptId，
     * 服务端不提供回取（/api/file 是工作区文件服务，不是附件服务），点了会给提示。
     */
    fun openAttachment(context: android.content.Context, name: String, mediaType: String, localPath: String?) {
        try {
            val file = localPath?.let { java.io.File(it) }?.takeIf { it.exists() }
            if (file == null) {
                viewModelScope.launch {
                    _state.update {
                        it.copy(bannerId = it.bannerId + 1, banner = "历史附件的字节已不可取回，无法打开")
                    }
                }
                return
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "com.dsh.mobile.files", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mediaType)
                addFlags(
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        or android.content.Intent.FLAG_ACTIVITY_NEW_TASK,
                )
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            viewModelScope.launch {
                _state.update {
                    it.copy(bannerId = it.bannerId + 1, banner = "无法打开：${e.message ?: "没有可用的 App"}")
                }
            }
        }
    }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: android.net.Uri): String? =
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cur ->
                val idx = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cur.moveToFirst()) cur.getString(idx) else null
            }
        }.getOrNull()

    private class EncodedImage(val base64: String, val mime: String, val name: String, val kb: Int)

    private fun encodeImage(ctx: Context, uri: android.net.Uri): EncodedImage? {
        val resolver = ctx.contentResolver
        val sourceMime = resolver.getType(uri) ?: "image/jpeg"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.size <= 1_500_000 && sourceMime in SUPPORTED_IMAGE_TYPES) {
            return EncodedImage(
                base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
                mime = sourceMime,
                name = "image",
                kb = bytes.size / 1024,
            )
        }
        // 大图：按最长边 1600 降采样后转 JPEG
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        val jpeg = out.toByteArray()
        return EncodedImage(
            base64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP),
            mime = "image/jpeg",
            name = "image",
            kb = jpeg.size / 1024,
        )
    }

    private companion object {
        val SUPPORTED_IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    }

    override fun onCleared() {
        repo.shutdown()
        super.onCleared()
    }
}

private fun Throwable.readable(): String = when (this) {
    is DshException -> message
    else -> message ?: this::class.java.simpleName
}

/** 供 UI 使用的便捷取值。 */
fun UiState.workspaceOf(sessionId: String): WorkspaceInfo? =
    workspaceState.workspaces.firstOrNull { sessionId in it.sessionIds }
