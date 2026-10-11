package com.dsh.mobile.data.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "DshMobile"

/**
 * 单条 WebSocket 上的逻辑流多路复用（/api/remote.mux）。
 *
 * - 全 App 只保持一条连接
 * - 每条流用 replay 缓冲区，open 之后才开始收集也不会丢首帧（snapshot）
 * - 断线后指数退避重连，并重新 open 所有活跃流
 */
class StreamMux(
    private val client: OkHttpClient,
    private val store: CookieStore,
) {
    private class StreamDef(val endpoint: String, val args: JsonObject)

    private val streams = ConcurrentHashMap<String, MutableSharedFlow<JsonObject>>()
    private val defs = ConcurrentHashMap<String, StreamDef>()
    private val connected = AtomicBoolean(false)

    /**
     * 代际号：每次 connect/递增。WS 回调与 close() 都要校验，防止"迟到的旧回调"
     * 或"旧 collector 的 finally"把新连接/新流误杀。
     *
     * 典型竞态（曾导致切换会话后聊天区空白）：openSession 先 cancel 旧 collector，
     * 再 open 同名 streamId；旧 collector 的 finally{ close(streamId) } 异步执行，
     * 会把**新**流的 defs/streams 移除并向服务端发 cancel → 新订阅收不到快照。
     */
    private val generation = java.util.concurrent.atomic.AtomicLong(0)

    private class StreamEntry(
        val flow: MutableSharedFlow<JsonObject>,
        /** 创建该流时的代际：close 时校验，只有创建代际 == 当前代际才真正移除 */
        val generation: Long,
    )

    private val streams2 = ConcurrentHashMap<String, StreamEntry>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null

    private var ws: WebSocket? = null
    private var base: String? = null
    private var closed = false

    /** WS 收到 401 时的重登钩子（仓库层用记住的 PIN 重登）。 */
    var onUnauthorized: (suspend () -> Boolean)? = null

    /** 连接状态变化（给 UI 显示）。 */
    val status = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 16)

    fun ensureConnected(base: String) {
        this.base = base
        closed = false
        if (ws != null) return
        connect(base)
    }

    private fun connect(base: String) {
        val wsUrl = base.replaceFirst("http", "ws") + "/api/remote.mux"
        val req = Request.Builder().url(wsUrl)
            .apply { store.header(base)?.let { header("Cookie", it) } }
            .header("Origin", base)
            .build()
        Log.d(TAG, "ws connecting $wsUrl")
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "ws open ${response.code}")
                connected.set(true)
                status.tryEmit("已连接")
                // 重连后重新 open 所有活跃流
                defs.forEach { (streamId, def) -> sendOpen(webSocket, streamId, def) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                dispatch(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "ws closing $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure: ${t.message} resp=${response?.code}")
                connected.set(false)
                ws = null
                if (closed) return
                // DSH 重启后 dsh_pocket_token 失效，WS 会直接 401：先重登再重连
                if (response?.code == 401 && onUnauthorized != null) {
                    status.tryEmit("登录已失效，正在重新登录…")
                    scope.launch {
                        val ok = runCatching { onUnauthorized?.invoke() == true }.getOrDefault(false)
                        if (ok && !closed) {
                            status.tryEmit("已重新登录，正在重连…")
                            base?.let { scheduleReconnect(it) }
                        } else {
                            status.tryEmit("需要重新输入 PIN")
                        }
                    }
                } else {
                    status.tryEmit("连接断开：${t.message}")
                    base?.let { scheduleReconnect(it) }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "ws closed $code $reason")
                connected.set(false)
                status.tryEmit("已断开")
                ws = null
                if (!closed) base?.let { scheduleReconnect(it) }
            }
        })
    }

    /** 指数退避重连（1s → 2s → 4s … 封顶 30s），重连成功后 onOpen 会重放所有活跃流。 */
    private fun scheduleReconnect(base: String) {
        if (closed) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var delayMs = 1_000L
            while (!closed && ws == null) {
                status.tryEmit("正在重连…")
                delay(delayMs)
                if (closed) return@launch
                Log.d(TAG, "ws reconnecting (delay=$delayMs)")
                try { connect(base) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
                // connect() 成功即 ws != null，循环退出
                if (ws != null) {
                    // 给 onOpen 一点时间；若立刻又失败会在 onFailure 里再次进入
                    delay(3_000)
                }
            }
        }
    }

    private fun sendOpen(webSocket: WebSocket, streamId: String, def: StreamDef) {
        val frame = buildJsonObject {
            put("type", "open")
            put("streamId", streamId)
            put("endpoint", def.endpoint)
            putJsonObject("payload") { put("args", def.args) }
        }
        webSocket.send(frame.toString())
    }

    private fun dispatch(text: String) {
        val obj = try {
            DSH_JSON.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            Log.w(TAG, "ws frame parse error: ${e.message}")
            return
        }
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        val streamId = obj["streamId"]?.jsonPrimitive?.contentOrNull
        when (type) {
            "item" -> {
                val value = obj["value"] as? JsonObject ?: return
                if (streamId != null) streams2[streamId]?.flow?.tryEmit(value)
            }
            "error" -> {
                Log.w(TAG, "ws stream error: $text")
                if (streamId != null) {
                    streams2[streamId]?.flow?.tryEmit(buildJsonObject { put("type", "client/error"); put("raw", text) })
                }
            }
            else -> Log.d(TAG, "ws other frame: ${text.take(200)}")
        }
    }

    /**
     * 打开一条逻辑流。返回该流的 value 帧序列（含 replay 缓冲，首帧不会丢）。
     */
    fun open(base: String, streamId: String, endpoint: String, args: JsonObject): Flow<JsonObject> {
        ensureConnected(base)
        defs[streamId] = StreamDef(endpoint, args)
        // 每次订阅都用全新的缓冲区：避免重新进入会话时先收到上一轮的旧 snapshot
        val flow = MutableSharedFlow<JsonObject>(
            replay = 512,
            extraBufferCapacity = 512,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        streams2[streamId] = StreamEntry(flow, generation.get())
        // ⚠️ 只在握手已完成时才立即发；握手未完成时交给 onOpen 统一发送。
        // 否则 OkHttp 会把帧排队，onOpen 又重发一次 → 同一 streamId 两个 open 帧，
        // 服务端会以 1008 "invalid Remote stream request" 直接关闭整条连接。
        val socket = if (connected.get()) ws else null
        val frame = buildJsonObject {
            put("type", "open")
            put("streamId", streamId)
            put("endpoint", endpoint)
            putJsonObject("payload") { put("args", args) }
        }.toString()
        val sent = socket?.send(frame)
        Log.d(TAG, "open stream=$streamId endpoint=$endpoint connected=${connected.get()} sent=$sent")
        return flow
    }

    /**
     * 原地重订阅：对同一条流先 cancel 再 open，**保持同一个 flow 实例**，
     * 这样已有的 collector 能收到服务端重新下发的快照。
     * 用于「需要权威刷新」（例如排队列表被桌面端改过）的场景。
     */
    fun resubscribe(streamId: String) {
        val def = defs[streamId] ?: return
        val socket = ws ?: return
        runCatching {
            socket.send(buildJsonObject { put("type", "cancel"); put("streamId", streamId) }.toString())
            sendOpen(socket, streamId, def)
        }
        Log.d(TAG, "resubscribe stream=$streamId")
    }

    fun close(streamId: String) {
        val entry = streams2[streamId]
        // 旧 collector 的 finally 可能晚于新 open 执行：只有当这条流仍是"创建时的那一代"
        // 且代际未变时才真正关闭；否则视为过期调用直接忽略，避免误删新流。
        if (entry != null && entry.generation != generation.get()) {
            Log.d(TAG, "close ignored (stale generation) stream=$streamId")
            return
        }
        defs.remove(streamId)
        streams2.remove(streamId)
        runCatching {
            ws?.send(buildJsonObject { put("type", "cancel"); put("streamId", streamId) }.toString())
        }
    }

    fun shutdown() {
        generation.incrementAndGet()
        closed = true
        reconnectJob?.cancel()
        reconnectJob = null
        ws?.close(1000, "bye")
        ws = null
        connected.set(false)
        defs.clear()
        streams2.clear()
    }
}
