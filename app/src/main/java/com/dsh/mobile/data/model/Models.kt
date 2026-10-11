package com.dsh.mobile.data.model

import kotlinx.serialization.json.*

/** 会话列表项（session/list 的 value.items[]）。 */
data class SessionSummary(
    val sessionId: String,
    val title: String,
    val running: Boolean,
    val cwd: String?,
    val updatedAt: Long,
    val blank: Boolean,
    val modelProvider: String? = null,
    val modelName: String? = null,
) {
    val modelLabel: String?
        get() = modelName?.let { n -> n.substringAfterLast(':') }
}

/** 模型选择（provider / model / 可选 reasoningEffort）。 */
data class ModelSelectionInfo(
    val provider: String,
    val model: String,
    val reasoningEffort: String? = null,
) {
    val label: String get() = model.substringAfterLast(':')
}

data class ModelInfo(val id: String, val name: String)

data class ModelGroup(
    val id: String,
    val name: String,
    val models: List<ModelInfo>,
)

/** 一次 tool/call 及其配对结果（按 id 配对，可能乱序到达）。 */
data class ToolCallInfo(
    val id: String,
    val name: String,
    val arguments: String,
    val result: String? = null,
    val isError: Boolean = false,
)

/** 用户消息里的附件（渲染 + 系统打开）。 */
data class UserAttachment(
    /** "image" | "file" */
    val kind: String,
    val name: String,
    val mediaType: String,
    /** 图片：内联 base64 */
    val base64: String? = null,
    /** 文档：上传凭证（历史消息里只有它，无法再取回字节） */
    val receiptId: String? = null,
) {
    val isImage: Boolean get() = kind == "image"
}

sealed interface ChatItem {
    val key: String

    data class User(
        override val key: String,
        val text: String,
        val imageCount: Int = 0,
        /** 图片直接渲染；文档显示胶囊 */
        val attachments: List<UserAttachment> = emptyList(),
    ) : ChatItem

    data class Assistant(
        override val key: String,
        val text: String,
        val reasoning: String,
        val toolCalls: List<ToolCallInfo> = emptyList(),
    ) : ChatItem
}

data class ChatState(
    val items: List<ChatItem> = emptyList(),
    val running: Boolean = false,
    /** token 级增量拼出的“正在生成”文本（提交后由 assistant/message 事件接管）。 */
    val liveText: String = "",
    /** token 级增量拼出的思维链。 */
    val liveReasoning: String = "",
    val lastSeq: Long = -1L,
)

// ---------- JSON 取值辅助 ----------

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

fun parseSessionSummary(o: JsonObject): SessionSummary {
    val values = o.obj("projections")?.obj("values")
    val ms = values?.obj("modelSelection")
    val next = ms?.obj("next") ?: ms?.obj("lastUsed")
    return SessionSummary(
        sessionId = o.str("sessionId").orEmpty(),
        title = values?.str("title")?.takeIf { it.isNotBlank() } ?: "(无标题)",
        running = o.bool("running") ?: false,
        cwd = o.str("cwd"),
        updatedAt = o.long("updatedAt") ?: 0L,
        blank = o.bool("blank") ?: false,
        modelProvider = next?.str("provider"),
        modelName = next?.str("model"),
    )
}

/** 解析 session/modelCatalog 的返回。 */
fun parseModelCatalog(value: JsonObject): Pair<ModelSelectionInfo?, List<ModelGroup>> {
    val def = value.obj("default")?.let {
        ModelSelectionInfo(
            provider = it.str("provider").orEmpty(),
            model = it.str("model").orEmpty(),
            reasoningEffort = it.str("reasoningEffort"),
        )
    }
    val groups = (value["groups"] as? JsonArray).orEmpty().mapNotNull { g ->
        val go = g as? JsonObject ?: return@mapNotNull null
        val id = go.str("id") ?: return@mapNotNull null
        ModelGroup(
            id = id,
            name = go.str("name") ?: id,
            models = (go["models"] as? JsonArray).orEmpty().mapNotNull { m ->
                val mo = m as? JsonObject ?: return@mapNotNull null
                val mid = mo.str("id") ?: return@mapNotNull null
                ModelInfo(id = mid, name = mo.str("name") ?: mid)
            },
        )
    }.filter { it.models.isNotEmpty() }
    return def to groups
}

/** session/page 的一页结果。 */
data class HistoryPage(
    val records: List<JsonObject>,
    val hasMore: Boolean,
) {
    val oldestSeq: Long
        get() = records.mapNotNull { it.obj("event")?.long("seq") }.minOrNull() ?: -1L
}

/** 从 content 数组里拼出纯文本。 */
private fun textOf(content: JsonArray?): String =
    content.orEmpty().mapNotNull { part ->
        val p = part as? JsonObject ?: return@mapNotNull null
        if (p.str("type") == "text") p.str("text") else null
    }.joinToString("")

private fun mergeTools(prev: List<ToolCallInfo>, next: List<ToolCallInfo>): List<ToolCallInfo> {
    if (prev.isEmpty()) return next
    val byId = prev.associateBy { it.id }
    return next.map { n ->
        val old = byId[n.id]
        if (old?.result != null && n.result == null) n.copy(result = old.result, isError = old.isError) else n
    }
}

/**
 * 事件归并：records[] 与实时增量同构，历史与实时走同一个 reducer。
 * 未知类型一律忽略并推进 seq（协议风险兜底）。
 */
fun ChatState.reduceEvent(ev: JsonObject): ChatState {
    val type = ev.str("type") ?: return this
    val seq = ev.long("seq") ?: lastSeq
    val data = ev.obj("data") ?: JsonObject(emptyMap())

    return when (type) {
        "user/message" -> {
            // 只渲染真人消息，滤掉 time-context / goal 等系统注入
            val kind = data.obj("source")?.str("kind")
            val content = data.arr("content")
            val text = textOf(content)
            // 提取完整附件：图片带 base64 可直接渲染；文件只有 receiptId（无法再取字节）
            val attachments = content.orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                when (o.str("type")) {
                    "image" -> UserAttachment(
                        kind = "image",
                        name = o.str("name") ?: "图片",
                        mediaType = o.str("mediaType") ?: "image/jpeg",
                        base64 = o.str("data"),
                    )
                    "file" -> UserAttachment(
                        kind = "file",
                        name = o.str("name") ?: "附件",
                        mediaType = o.str("mediaType") ?: "application/octet-stream",
                        receiptId = o.str("receiptId"),
                    )
                    else -> null
                }
            }
            val images = attachments.count { it.isImage }
            if ((kind != null && kind != "user") || (text.isBlank() && attachments.isEmpty())) {
                copy(lastSeq = seq)
            } else {
                val id = data.str("id") ?: seq.toString()
                copy(
                    items = items + ChatItem.User("u-$id", text, images, attachments),
                    lastSeq = seq,
                    liveText = "",
                )
            }
        }

        "assistant/message" -> {
            val msg = data.obj("message") ?: return copy(lastSeq = seq)
            val id = msg.str("id") ?: seq.toString()
            val blocks = msg.arr("content").orEmpty()
            val text = blocks.mapNotNull { b ->
                val o = b as? JsonObject ?: return@mapNotNull null
                if (o.str("type") == "text") o.str("text") else null
            }.joinToString("")
            val reasoning = blocks.mapNotNull { b ->
                val o = b as? JsonObject ?: return@mapNotNull null
                if (o.str("type") == "reasoning") o.str("text") else null
            }.joinToString("")
            val calls = blocks.mapNotNull { b ->
                val o = b as? JsonObject ?: return@mapNotNull null
                if (o.str("type") != "tool-call") null
                else ToolCallInfo(
                    id = o.str("id").orEmpty(),
                    name = o.str("name") ?: "tool",
                    arguments = o.str("arguments").orEmpty(),
                )
            }
            val key = "a-$id"
            val idx = items.indexOfFirst { it.key == key }
            val item = if (idx >= 0) {
                val prev = items[idx] as ChatItem.Assistant
                ChatItem.Assistant(
                    key = key,
                    text = text.ifBlank { prev.text },
                    reasoning = reasoning.ifBlank { prev.reasoning },
                    toolCalls = mergeTools(prev.toolCalls, calls),
                )
            } else {
                ChatItem.Assistant(key, text, reasoning, calls)
            }
            copy(
                items = if (idx >= 0) items.toMutableList().also { it[idx] = item } else items + item,
                lastSeq = seq,
                liveText = "",
                liveReasoning = "",
            )
        }

        "tool/call" -> {
            // 独立表示（assistant/message 的 tool-call 块通常已覆盖，这里只兜底）
            val callId = data.str("callId") ?: return copy(lastSeq = seq)
            val exists = items.any { it is ChatItem.Assistant && it.toolCalls.any { c -> c.id == callId } }
            if (exists) {
                copy(lastSeq = seq)
            } else {
                val item = ChatItem.Assistant(
                    key = "a-call-$callId",
                    text = "",
                    reasoning = "",
                    toolCalls = listOf(
                        ToolCallInfo(
                            id = callId,
                            name = data.str("name") ?: "tool",
                            arguments = data.str("arguments").orEmpty(),
                        )
                    ),
                )
                copy(items = items + item, lastSeq = seq)
            }
        }

        "tool/result" -> {
            // 注意：toolCallId 嵌在 data.message 里（源码结构），不在 data 顶层
            val msg = data.obj("message")
            val callId = data.str("toolCallId")
                ?: msg?.str("toolCallId")
                ?: msg?.obj("source")?.str("callId")
                ?: return copy(lastSeq = seq)
            val result = textOf(msg?.arr("content") ?: data.arr("content"))
            val isError = msg?.bool("isError") ?: data.bool("isError") ?: false
            val idx = items.indexOfLast { item ->
                item is ChatItem.Assistant && item.toolCalls.any { it.id == callId }
            }
            if (idx < 0) return copy(lastSeq = seq)
            val prev = items[idx] as ChatItem.Assistant
            val updated = prev.copy(
                toolCalls = prev.toolCalls.map {
                    if (it.id == callId) it.copy(result = result, isError = isError) else it
                }
            )
            copy(items = items.toMutableList().also { it[idx] = updated }, lastSeq = seq)
        }

        "turn/start" -> copy(running = true, lastSeq = seq)
        "turn/end" -> copy(running = false, liveText = "", liveReasoning = "", lastSeq = seq)
        else -> copy(lastSeq = seq)
    }
}

/** 一组 records（snapshot）整体归并。 */
fun ChatState.reduceRecords(records: List<JsonObject>): ChatState {
    var s = this
    val sorted = records.mapNotNull { it.obj("event") }.sortedBy { it.long("seq") ?: 0L }
    for (ev in sorted) s = s.reduceEvent(ev)
    return s
}
