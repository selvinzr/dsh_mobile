package com.dsh.mobile.data.model

/**
 * 把聊天条目按「过程段」分组。
 *
 * DSH 每完成一步就发一条独立的 assistant 消息，一步通常只含一次工具调用。
 * 于是一个回合会散成十几条 `Assistant(text="", toolCalls=[1 个])`，
 * 在界面上就是十几行「调用工具 1 次」——折叠本身没用，必须**在列表层合并**。
 *
 * 规则：
 *  - 连续的「纯过程 assistant 消息」（无正文，只有工具/思维链）合并成一个 [Run]；
 *  - 任何带正文的 assistant 消息、或用户消息，都会打断合并。
 */
sealed interface ChatBlock {
    /** 单条消息，原样渲染 */
    data class Single(val item: ChatItem) : ChatBlock

    /** 连续的过程步骤，合并成一行摘要 */
    data class Run(
        val key: String,
        val items: List<ChatItem.Assistant>,
    ) : ChatBlock {
        val toolCalls: List<ToolCallInfo> get() = items.flatMap { it.toolCalls }
        val reasonings: List<String> get() = items.map { it.reasoning }.filter { it.isNotBlank() }
        val running: Boolean get() = toolCalls.any { it.result == null }
        val failed: Int get() = toolCalls.count { it.isError }
        val toolNames: List<String> get() = toolCalls.map { it.name }.distinct()
    }
}

/** 该条消息是否"只有过程内容"（可被并入 Run）。 */
private fun ChatItem.Assistant.isProcessOnly(): Boolean =
    text.isBlank() && (toolCalls.isNotEmpty() || reasoning.isNotBlank())

/** 把条目列表折叠成渲染块列表。 */
fun List<ChatItem>.toBlocks(): List<ChatBlock> {
    val out = mutableListOf<ChatBlock>()
    var run = mutableListOf<ChatItem.Assistant>()

    fun flush() {
        if (run.isEmpty()) return
        // 只有一条时不必包成 Run，直接单条渲染（保留原有观感）
        if (run.size == 1) {
            out += ChatBlock.Single(run.first())
        } else {
            out += ChatBlock.Run(key = "run-" + run.first().key, items = run.toList())
        }
        run = mutableListOf()
    }

    for (item in this) {
        if (item is ChatItem.Assistant && item.isProcessOnly()) {
            run += item
        } else {
            flush()
            out += ChatBlock.Single(item)
        }
    }
    flush()
    return out
}
