package com.dsh.mobile.data.repo

import android.content.Context
import com.dsh.mobile.core.DshException
import com.dsh.mobile.data.model.HistoryPage
import com.dsh.mobile.data.model.ModelGroup
import com.dsh.mobile.data.model.ModelSelectionInfo
import com.dsh.mobile.data.model.QueueAction
import com.dsh.mobile.data.model.QueuedMessage
import com.dsh.mobile.data.model.parseInbox
import com.dsh.mobile.data.model.SessionSummary
import com.dsh.mobile.data.model.WorkspaceEvent
import com.dsh.mobile.data.model.parseModelCatalog
import com.dsh.mobile.data.model.parseSessionSummary
import com.dsh.mobile.data.model.parseWorkspaceFrame
import com.dsh.mobile.data.net.CookieStore
import com.dsh.mobile.data.net.PocketAuth
import com.dsh.mobile.data.net.RpcClient
import com.dsh.mobile.data.net.StreamMux
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** session/follow 的下行事件。 */
sealed interface FollowEvent {
    data class Snapshot(
        val records: List<JsonObject>,
        val cursor: Long,
        val hasMore: Boolean,
        val cwd: String?,
        val queued: List<QueuedMessage> = emptyList(),
    ) : FollowEvent

    /** 一条已提交的会话事件（与 snapshot.records[] 同构）。 */
    data class Committed(val event: JsonObject, val seq: Long) : FollowEvent

    /** token 级增量。 */
    data class Delta(val delta: StreamDelta) : FollowEvent

    data class Failure(val message: String) : FollowEvent
}

/** assistantStream 增量（源码帧：block-start / *-delta / end）。 */
sealed interface StreamDelta {
    /** 新的内容块开始（text / reasoning / tool-call）。 */
    data class BlockStart(val blockType: String) : StreamDelta

    data class Text(val text: String) : StreamDelta

    data class Reasoning(val text: String) : StreamDelta

    /** 一段生成结束（已提交为 assistant/message）。 */
    data object Ended : StreamDelta
}

/**
 * 协议门面。所有端点与参数形状均来自运行时实测（见 docs/protocol.md 与 _recon/）。
 */
class DshRepository(context: Context) {

    private val store = CookieStore(context)
    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        // ⚠️ 不要设置 pingInterval：dsh-pocket 代理不回 pong，
        // OkHttp 会因此判定连接失活并主动断开，造成每 20s 一次的假重连。
        .build()

    private val auth = PocketAuth(http, store)
    private val rpc = RpcClient(http, store)
    private val mux = StreamMux(http, store)

    /** DSH 重启会让 dsh_pocket_token 失效，401 时自动用记住的 PIN 重登一次。 */
    private var rememberedPin: String? = null

    init {
        rpc.reauth = { base -> relogin(base) }
        mux.onUnauthorized = { base?.let { relogin(it) } ?: false }
    }

    private var base: String? = null

    /**
     * 并发 401 时多个在途 RPC 会各自触发 relogin —— 短时间内多次 PIN 握手会撞上
     * 代理的 handshakeLimit（protocol §7.6 的 503），且 503 返回的"半套 Cookie"
     * （只有 dsh_pocket_token）会被当成功保存，之后全部 401。
     * 所以这里用 Mutex 单飞 + 成功后 10s 内不重复握手。
     */
    private val reloginMutex = kotlinx.coroutines.sync.Mutex()
    private var lastReloginAt = 0L

    private suspend fun relogin(base: String): Boolean {
        val pin = rememberedPin ?: return false
        return reloginMutex.withLock {
            val now = System.currentTimeMillis()
            if (now - lastReloginAt < 10_000) {
                // 刚登过：让在途请求直接重试，不再重复握手（防 503 限流）
                true
            } else {
                val ok = try {
                    auth.login(base, pin)
                    true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (ok) lastReloginAt = now
                ok
            }
        }
    }

    val streamStatus: Flow<String> get() = mux.status

    suspend fun login(base: String, pin: String): List<String> {
        val names = auth.login(base, pin)
        rememberedPin = pin
        this.base = base
        return names
    }

    fun connect(base: String) {
        this.base = base
        mux.ensureConnected(base)
    }

    /** session/list —— 唯一需要 `_request` 的方法。 */
    suspend fun listSessions(base: String): List<SessionSummary> {
        val value = rpc.call(base, "session/list", buildJsonObject { putJsonObject("_request") {} })
        val items = value.jsonObject["items"] as? JsonArray ?: JsonArray(emptyList())
        return items.mapNotNull { (it as? JsonObject)?.let(::parseSessionSummary) }
            .filter { it.sessionId.isNotEmpty() }
            .sortedByDescending { it.updatedAt }
    }

    /** session/create —— 可指定工作区与工作目录。 */
    suspend fun createSession(
        base: String,
        workspaceId: String? = null,
        cwd: String? = null,
    ): String {
        // 服务端约束：workspaceId 与 cwd 只能二选一
        // （同时传会返回 "session.create accepts workspaceId or cwd, not both"）
        val ws = workspaceId?.takeIf { it.isNotBlank() }
        val dir = if (ws != null) null else cwd?.takeIf { it.isNotBlank() }
        val args = buildJsonObject {
            putJsonObject("request") {
                ws?.let { put("workspaceId", it) }
                dir?.let { put("cwd", it) }
            }
        }
        android.util.Log.d("DshMobile", "session/create ws=$ws cwd=$dir")
        val value = rpc.call(base, "session/create", args)
        return value.jsonObject["sessionId"]?.jsonPrimitive?.contentOrNull
            ?: error("session/create 未返回 sessionId")
    }

    // ---------- 会话管理（workspace/* 与 session/*） ----------

    /** $events —— 全局事件流（args 必须是空对象）。ask-user 提问等流程事件从这里推送。 */
    fun followEvents(base: String): Flow<JsonObject> = flow {
        val frames = mux.open(base, "ev", "\$events", buildJsonObject {})
        frames.collect { emit(it) }
    }

    /**
     * workspace/follow —— 工作区列表 + 归档/置顶集合（会话列表的真正数据源）。 */
    fun followWorkspaces(base: String): Flow<WorkspaceEvent> = flow {
        val frames = mux.open(base, "ws-global", "workspace/follow", buildJsonObject {})
        frames.collect { value ->
            parseWorkspaceFrame(value)?.let { emit(it) }
        }
    }

    /**
     * session/rename。
     * 注意：部分部署里 LLM 自动标题会覆盖手工标题，重命名后可能又被改写。
     */
    suspend fun renameSession(base: String, sessionId: String, title: String) {
        rpc.call(base, "session/rename", buildJsonObject {
            putJsonObject("request") {
                put("sessionId", sessionId)
                put("title", title)
            }
        })
    }

    /** workspace/pinSession | unpinSession → 返回最新置顶集合。 */
    suspend fun setPinned(base: String, sessionId: String, pinned: Boolean): List<String> {
        val method = if (pinned) "workspace/pinSession" else "workspace/unpinSession"
        val value = rpc.call(base, method, buildJsonObject {
            putJsonObject("request") { put("sessionId", sessionId) }
        })
        return (value.jsonObject["pinnedSessionIds"] as? JsonArray).orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull }
    }

    /**
     * workspace/archiveSession | unarchiveSession → 返回最新归档集合。
     * 会话仍在运行时服务端会以 workspace/session-active 拒绝；stopActivity=true 可强制归档。
     */
    suspend fun setArchived(
        base: String,
        sessionId: String,
        archived: Boolean,
        stopActivity: Boolean = false,
    ): List<String> {
        val method = if (archived) "workspace/archiveSession" else "workspace/unarchiveSession"
        val value = rpc.call(base, method, buildJsonObject {
            putJsonObject("request") {
                put("sessionId", sessionId)
                if (archived && stopActivity) put("stopActivity", true)
            }
        })
        return (value.jsonObject["archivedSessionIds"] as? JsonArray).orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull }
    }

    /** session/fork —— 从某会话复制出新会话。 */
    suspend fun forkSession(base: String, sessionId: String, atSeq: Long? = null): String {
        val value = rpc.call(base, "session/fork", buildJsonObject {
            putJsonObject("request") {
                put("sessionId", sessionId)
                atSeq?.let { put("atSeq", it) }
            }
        })
        return value.jsonObject["sessionId"]?.jsonPrimitive?.contentOrNull
            ?: value.jsonObject["value"]?.jsonPrimitive?.contentOrNull
            ?: error("session/fork 未返回 sessionId")
    }

    /** workspace/create —— 用目录路径新建工作区。返回体是嵌套的 {workspace:{workspaceId}}。 */
    suspend fun createWorkspace(base: String, path: String): String {
        val value = rpc.call(base, "workspace/create", buildJsonObject {
            putJsonObject("request") { put("path", path) }
        })
        return value.jsonObject["workspace"]?.jsonObject?.get("workspaceId")?.jsonPrimitive?.contentOrNull
            ?: value.jsonObject["workspaceId"]?.jsonPrimitive?.contentOrNull
            ?: error("workspace/create 未返回 workspaceId")
    }

    suspend fun renameWorkspace(base: String, workspaceId: String, title: String) {
        rpc.call(base, "workspace/rename", buildJsonObject {
            putJsonObject("request") {
                put("workspaceId", workspaceId)
                put("title", title)
            }
        })
    }

    suspend fun deleteWorkspace(base: String, workspaceId: String) {
        rpc.call(base, "workspace/delete", buildJsonObject {
            putJsonObject("request") { put("workspaceId", workspaceId) }
        })
    }

    /**
     * session/page —— 向前翻历史。
     *
     * 参数语义（实测）：返回 seq ∈ (beforeSeq, throughSeq) 的记录，
     * 条数上限是 maxMessages 条"消息"（会展开成更多条事件），hasMore 指示更早还有没有。
     */
    suspend fun pageMessages(
        base: String,
        sessionId: String,
        throughSeq: Long,
        beforeSeq: Long,
        maxMessages: Int = 30,
    ): HistoryPage {
        val value = rpc.call(base, "session/page", buildJsonObject {
            putJsonObject("request") {
                putJsonObject("address") {
                    put("kind", "session")
                    put("sessionId", sessionId)
                }
                put("throughSeq", throughSeq)
                put("beforeSeq", beforeSeq.coerceAtLeast(0))
                put("maxMessages", maxMessages)
            }
        })
        val records = (value.jsonObject["records"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
        return HistoryPage(
            records = records,
            hasMore = value.jsonObject["hasMore"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    /**
     * 上传文档附件（原始字节）→ staged receiptId。
     * 之后在 prompt 里用 {type:"file",receiptId} 引用。
     */
    suspend fun uploadFile(base: String, sessionId: String, name: String, bytes: ByteArray): String {
        val v = rpc.postBytes(
            base = base,
            path = "/api/session/uploadFileBinary",
            query = mapOf("sessionId" to sessionId, "name" to name),
            bytes = bytes,
        )
        return v.jsonObject["receiptId"]?.jsonPrimitive?.contentOrNull
            ?: throw DshException(DshException.CODE_PARSE, "上传未返回 receiptId")
    }

    /** session/updateQueue —— 编辑 / 改为引导 / 取消排队消息。 */
    suspend fun updateQueue(base: String, sessionId: String, itemId: String, action: QueueAction) {
        rpc.call(base, "session/updateQueue", buildJsonObject {
            putJsonObject("request") {
                put("sessionId", sessionId)
                put("itemId", itemId)
                put("action", action.toJson())
            }
        })
    }

    /** session/projections —— 读取会话投影（用于刷新排队列表）。 */
    suspend fun fetchInbox(base: String, sessionId: String): List<QueuedMessage> {
        val value = rpc.call(base, "session/projections", buildJsonObject {
            putJsonObject("request") { put("sessionId", sessionId) }
        })
        // 返回结构可能是 {values:{inbox}} 或直接 {inbox}
        return parseInbox(value.jsonObject)
    }

    /** session/modelCatalog —— 可选模型目录（args 为空对象）。 */
    suspend fun modelCatalog(base: String): Pair<ModelSelectionInfo?, List<ModelGroup>> {
        val value = rpc.call(base, "session/modelCatalog", buildJsonObject {})
        return parseModelCatalog(value.jsonObject)
    }

    /** session/selectModel —— 切换当前会话使用的模型。 */
    suspend fun selectModel(
        base: String,
        sessionId: String,
        provider: String,
        model: String,
        reasoningEffort: String? = null,
    ): ModelSelectionInfo {
        val value = rpc.call(base, "session/selectModel", buildJsonObject {
            putJsonObject("request") {
                put("sessionId", sessionId)
                put("provider", provider)
                put("model", model)
                reasoningEffort?.let { put("reasoningEffort", it) }
            }
        })
        val sel = value.jsonObject["selected"]?.jsonObject
        return ModelSelectionInfo(
            provider = sel?.get("provider")?.jsonPrimitive?.contentOrNull ?: provider,
            model = sel?.get("model")?.jsonPrimitive?.contentOrNull ?: model,
            reasoningEffort = sel?.get("reasoningEffort")?.jsonPrimitive?.contentOrNull,
        )
    }

    /**
     * session/prompt —— 发送消息。
     * 源码要求 mode 必填（'queue' | 'steer'），省略会被 boundary validation 拒绝。
     */
    suspend fun prompt(base: String, sessionId: String, text: String, steer: Boolean = false): Boolean =
        promptParts(
            base = base,
            sessionId = sessionId,
            parts = listOf(buildJsonObject { put("type", "text"); put("text", text) }),
            steer = steer,
        )

    /**
     * 发送图文混合消息。
     *
     * wire schema（源码确认）：
     * `content: [{type:'text',text} | {type:'image',mediaType,data(base64),name?}]`
     * 图片 mediaType 只接受 image/png|jpeg|webp|gif。
     */
    suspend fun promptParts(
        base: String,
        sessionId: String,
        parts: List<JsonObject>,
        steer: Boolean = false,
    ): Boolean {
        if (parts.isEmpty()) return false
        val args = buildJsonObject {
            putJsonObject("request") {
                put("requestId", java.util.UUID.randomUUID().toString())
                put("sessionId", sessionId)
                put("mode", if (steer) "steer" else "queue")
                putJsonArray("content") { parts.forEach { add(it) } }
                put("clientTimeZone", java.util.TimeZone.getDefault().id)
            }
        }
        val value = rpc.call(base, "session/prompt", args)
        return value.jsonObject["accepted"]?.jsonPrimitive?.booleanOrNull ?: false
    }

    /** session/cancel —— 中断当前回合。request 只需 sessionId。 */
    suspend fun cancel(base: String, sessionId: String): Boolean {
        val args = buildJsonObject {
            putJsonObject("request") { put("sessionId", sessionId) }
        }
        val value = rpc.call(base, "session/cancel", args)
        return value.jsonObject["accepted"]?.jsonPrimitive?.booleanOrNull ?: false
    }

    /**
     * session/follow —— 会话订阅（历史 snapshot + 实时增量）。
     * streamId 需稳定，重复订阅同一会话会复用同一条流。
     */
    fun followSession(
        base: String,
        sessionId: String,
        maxMessages: Int = 30,
        assistantStream: Boolean = true,
    ): Flow<FollowEvent> = flow {
        val streamId = "fl-${sessionId.takeLast(12)}"
        val args = buildJsonObject {
            putJsonObject("request") {
                putJsonObject("address") {
                    put("kind", "session")
                    put("sessionId", sessionId)
                }
                put("maxMessages", maxMessages)
                put("assistantStream", assistantStream)
            }
        }
        val frames = mux.open(base, streamId, "session/follow", args)
        // 收集结束（含被取消）时通知服务端关闭这条流，
        // 否则旧订阅会一直挂在服务端，切换会话/重开同一条流时会被拒。
        try {
        frames.collect { value ->
            when (value["type"]?.jsonPrimitive?.contentOrNull) {
                "snapshot" -> {
                    val recs = (value["records"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    android.util.Log.d(
                        "DshMobile",
                        "snapshot records=${recs.size} types=" + recs.joinToString(",") {
                            it["event"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull ?: "?"
                        },
                    )
                    emit(
                        FollowEvent.Snapshot(
                            records = recs,
                            cursor = value["cursor"]?.jsonPrimitive?.longOrNull ?: 0L,
                            hasMore = value["hasMore"]?.jsonPrimitive?.booleanOrNull ?: false,
                            cwd = value["header"]?.jsonObject?.get("cwd")?.jsonPrimitive?.contentOrNull,
                            queued = parseInbox(value["projections"] as? JsonObject),
                        )
                    )
                }

                "event" -> {
                    val ev = value["event"] as? JsonObject
                    if (ev != null) {
                        emit(FollowEvent.Committed(ev, ev["seq"]?.jsonPrimitive?.longOrNull ?: -1L))
                    }
                }

                "assistant-stream" -> {
                    val frame = value["frame"] as? JsonObject ?: return@collect
                    parseStreamFrame(frame)?.let { emit(FollowEvent.Delta(it)) }
                }

                "client/error" -> emit(FollowEvent.Failure(value["raw"]?.jsonPrimitive?.contentOrNull ?: "流错误"))
                else -> Unit
            }
        }
        } finally {
            mux.close(streamId)
        }
    }

    private fun parseStreamFrame(frame: JsonObject): StreamDelta? =
        when (frame["type"]?.jsonPrimitive?.contentOrNull) {
            "start" -> StreamDelta.BlockStart("start")
            "end" -> StreamDelta.Ended
            "chunk" -> {
                val chunk = frame["chunk"] as? JsonObject ?: return null
                val type = chunk["type"]?.jsonPrimitive?.contentOrNull ?: return null
                val text = chunk["text"]?.jsonPrimitive?.contentOrNull
                when {
                    type == "block-start" -> StreamDelta.BlockStart(
                        chunk["blockType"]?.jsonPrimitive?.contentOrNull ?: "text"
                    )
                    type == "reasoning-delta" && text != null -> StreamDelta.Reasoning(text)
                    type.endsWith("-delta") && text != null -> StreamDelta.Text(text)
                    else -> null
                }
            }
            else -> null
        }

    fun closeFollow(sessionId: String) = mux.close("fl-${sessionId.takeLast(12)}")

    /**
     * 读一次服务端权威的 inbox（排队列表）。
     *
     * **不要**用「对主订阅流 cancel + open」的方式刷新 —— 服务端会把同一 `streamId`
     * 的第二次 `open` 判成非法请求，直接以 `1008 invalid Remote stream request`
     * 关掉整条 WebSocket（protocol §7.8），表现就是每次刷新排队都把连接打断一次。
     *
     * 这里改为开一条**独立的一次性流**（唯一 streamId），拿到快照就关掉，
     * 完全不动主订阅流。
     */
    suspend fun fetchInboxViaStream(
        base: String,
        sessionId: String,
        timeoutMs: Long = 4_000,
    ): List<QueuedMessage>? {
        val streamId = "flq-" + java.util.UUID.randomUUID().toString().take(8)
        val flow = mux.open(
            base,
            streamId,
            "session/follow",
            buildJsonObject {
                putJsonObject("request") {
                    putJsonObject("address") {
                        put("kind", "session")
                        put("sessionId", sessionId)
                    }
                    put("maxMessages", 1)
                    put("assistantStream", false)
                }
            },
        )
        return try {
            val snapshot = withTimeoutOrNull(timeoutMs) {
                // firstOrNull 拿到首个匹配帧后会取消收集，不会挂到超时
                flow.firstOrNull { o -> (o["type"] as? JsonPrimitive)?.contentOrNull == "snapshot" }
            }
            snapshot?.let { parseInbox(it["projections"] as? JsonObject) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            mux.close(streamId)
        }
    }

    /**
     * 回答 ask-user 提问（**走正式 RPC**，而不是把答案拼成文本发 prompt）。
     *
     * 契约（源码 `userQuestions/answer`）：
     * ```
     * userQuestions/answer(agent: SessionId, callId: ToolCallId,
     *                      answer: { answers: [{ id, selected: string[], custom?: string }] })
     * ```
     * 一次提交**全部**问题的答案（one item per question of the call）。
     * 服务端把它作为 user 消息 steer 进 agent，并关闭该提问投影。
     */
    suspend fun answerUserQuestion(
        base: String,
        sessionId: String,
        callId: String,
        answers: List<Pair<String, List<String>>>,
    ) {
        val args = buildJsonObject {
            // 注意：wire 字段名是 agentId（不是 agent）—— 服务端 descriptor 明确要求
            put("agentId", sessionId)
            put("callId", callId)
            putJsonObject("answer") {
                putJsonArray("answers") {
                    answers.forEach { (id, selected) ->
                        addJsonObject {
                            put("id", id)
                            putJsonArray("selected") { selected.forEach { add(it) } }
                        }
                    }
                }
            }
        }
        rpc.call(base, "userQuestions/answer", args)
    }

    fun shutdown() = mux.shutdown()
}
