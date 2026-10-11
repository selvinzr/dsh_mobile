package com.dsh.mobile.data.model

import kotlinx.serialization.json.*

/**
 * 排队中的用户消息（来自会话 projections 的 inbox）。
 *
 * 结构（实测）：
 * ```
 * inbox: {
 *   "next-turn": [ { content:[{type:"text",text:"…"}], source:{kind:"user",rpcId:"…"},
 *                    role:"user", id:"<itemId>" } ],   // mode=queue 的排队消息
 *   "next-step": [ … ]                                  // mode=steer 的引导消息
 * }
 * ```
 */
data class QueuedMessage(
    val itemId: String,
    val text: String,
    /** true = 引导（next-step，下一 step 生效）；false = 排队（next-turn，回合结束后生效） */
    val steer: Boolean,
)

/** session/updateQueue 的 action（源码：QueueAction）。 */
sealed interface QueueAction {
    /** 编辑消息内容。 */
    data class Edit(val text: String) : QueueAction
    /** 改为引导（从 next-turn 移到 next-step）。 */
    data object Steer : QueueAction
    /** 取消消息发送。 */
    data object Remove : QueueAction

    fun toJson(): JsonObject = when (this) {
        is Edit -> buildJsonObject {
            put("kind", "edit")
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
        }

        Steer -> buildJsonObject { put("kind", "steer") }
        Remove -> buildJsonObject { put("kind", "remove") }
    }
}

/** 从 projections.values（或 projections）里解析 inbox。 */
fun parseInbox(container: JsonObject?): List<QueuedMessage> {
    if (container == null) return emptyList()
    val values = (container["values"] as? JsonObject) ?: container
    val inbox = (values["inbox"] as? JsonObject) ?: return emptyList()

    fun items(key: String, steer: Boolean): List<QueuedMessage> =
        (inbox[key] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val text = (o["content"] as? JsonArray).orEmpty()
                .mapNotNull { c ->
                    val co = c as? JsonObject ?: return@mapNotNull null
                    if ((co["type"] as? JsonPrimitive)?.contentOrNull == "text") {
                        (co["text"] as? JsonPrimitive)?.contentOrNull
                    } else null
                }
                .joinToString("")
            QueuedMessage(itemId = id, text = text, steer = steer)
        }

    return items("next-turn", false) + items("next-step", true)
}
