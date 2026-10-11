package com.dsh.mobile.data.model

import kotlinx.serialization.json.*

/** ask-user 提问（来自 $events 的 waterfall/user-questions/request）。 */
data class PendingQuestion(
    val agentId: String,
    val eventId: String,
    val questions: List<Question>,
) {
    data class Question(
        val id: String,
        val header: String,
        val question: String,
        val options: List<Option>,
    ) {
        data class Option(val label: String, val description: String?)
    }

    val first: Question? get() = questions.firstOrNull()
}

/**
 * 解析 $events 上的 waterfall 帧，识别 ask-user 提问。
 * 形如：
 * {"type":"waterfall","event":"user-questions/request","eventId":"...",
 *  "agentId":"session-...","request":{"questions":[{"id","header","question","options":[...]}]}}
 */
fun parseUserQuestion(value: JsonObject): PendingQuestion? {
    if ((value["type"] as? JsonPrimitive)?.contentOrNull != "waterfall") return null
    if ((value["event"] as? JsonPrimitive)?.contentOrNull != "user-questions/request") return null

    val req = value["request"] as? JsonObject ?: return null
    val qs = (req["questions"] as? JsonArray).orEmpty().mapNotNull { q ->
        val qo = q as? JsonObject ?: return@mapNotNull null
        PendingQuestion.Question(
            id = (qo["id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            header = (qo["header"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            question = (qo["question"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            options = (qo["options"] as? JsonArray).orEmpty().mapNotNull { o ->
                val oo = o as? JsonObject ?: return@mapNotNull null
                PendingQuestion.Question.Option(
                    label = (oo["label"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    description = (oo["description"] as? JsonPrimitive)?.contentOrNull,
                )
            },
        )
    }
    if (qs.isEmpty()) return null
    return PendingQuestion(
        agentId = (value["agentId"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        eventId = (value["eventId"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        questions = qs,
    )
}
