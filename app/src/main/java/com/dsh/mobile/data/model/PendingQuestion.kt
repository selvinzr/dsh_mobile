package com.dsh.mobile.data.model

import kotlinx.serialization.json.*

/** ask-user 提问（来自 $events 的 waterfall/user-questions/request）。 */
data class PendingQuestion(
    val agentId: String,
    val eventId: String,
    /** 提问所属的 tool call（userQuestions/answer 的必填参数） */
    val callId: String = "",
    val questions: List<Question>,
    /** 已作答结果：questionId → 选中的 label。多题时逐题累积，全部答完再一起提交。 */
    val answers: Map<String, List<String>> = emptyMap(),
) {
    data class Question(
        val id: String,
        val header: String,
        val question: String,
        /** 补充说明（可空） */
        val detail: String? = null,
        val options: List<Option>,
        /** 是否多选：真源 AskUserQuestionItem.multiSelect */
        val multiSelect: Boolean = false,
    ) {
        data class Option(val label: String, val description: String?)
    }

    /** 第一道**尚未作答**的题；全部答完则为 null */
    val next: Question? get() = questions.firstOrNull { it.id !in answers }

    /** 是否已全部答完 */
    val done: Boolean get() = questions.isNotEmpty() && questions.all { it.id in answers }

    /** 进度文案，如「第 2/3 题」；单题时不显示 */
    val progress: String?
        get() {
            if (questions.size <= 1) return null
            val idx = questions.indexOfFirst { it.id !in answers }
                .let { if (it < 0) questions.size - 1 else it }
            return "第 ${idx + 1}/${questions.size} 题"
        }

    /**
     * 整理提交给 Agent 的文本。
     * 单题直接给 label；多题带题号，否则 Agent 分不清哪个答案对应哪一题。
     */
    fun answerText(): String =
        if (questions.size <= 1) {
            answers[questions.firstOrNull()?.id].orEmpty().joinToString("、")
        } else {
            questions.mapIndexed { i, q ->
                "${i + 1}. ${q.header.ifBlank { q.question }}：${answers[q.id].orEmpty().joinToString("、")}"
            }.joinToString("\n")
        }
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
            detail = (qo["detail"] as? JsonPrimitive)?.contentOrNull,
            multiSelect = (qo["multiSelect"] as? JsonPrimitive)?.booleanOrNull ?: false,
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
    // callId 用于 userQuestions/answer；来源可能是 request.wait.callId 或 request.callId
    val callId = ((req["wait"] as? JsonObject)?.get("callId") as? JsonPrimitive)?.contentOrNull
        ?: (req["callId"] as? JsonPrimitive)?.contentOrNull
        ?: (value["callId"] as? JsonPrimitive)?.contentOrNull
        ?: ""
    return PendingQuestion(
        agentId = (value["agentId"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        eventId = (value["eventId"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        callId = callId,
        questions = qs,
    )
}
