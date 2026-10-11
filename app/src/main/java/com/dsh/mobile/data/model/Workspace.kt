package com.dsh.mobile.data.model

import kotlinx.serialization.json.*

/** workspace/follow baseline 里的一个工作区行。 */
data class WorkspaceInfo(
    val workspaceId: String,
    val path: String,
    val title: String,
    val sessionIds: List<String>,
)

/** 工作区 + 归档 + 置顶的完整状态（会话列表的组织方式）。 */
data class WorkspaceState(
    val workspaces: List<WorkspaceInfo> = emptyList(),
    val archivedSessionIds: List<String> = emptyList(),
    val pinnedSessionIds: List<String> = emptyList(),
) {
    val archived: Set<String> get() = archivedSessionIds.toSet()
    val pinnedOrder: Map<String, Int> get() = pinnedSessionIds.withIndex().associate { (i, id) -> id to i }
}

/** workspace/follow 的下行事件。 */
sealed interface WorkspaceEvent {
    data class Baseline(val state: WorkspaceState) : WorkspaceEvent
    data class Upsert(val workspace: WorkspaceInfo) : WorkspaceEvent
    data class Remove(val workspaceId: String) : WorkspaceEvent
    data class Archived(val ids: List<String>) : WorkspaceEvent
    data class Pinned(val ids: List<String>) : WorkspaceEvent
    data class Failure(val message: String) : WorkspaceEvent
}

private fun JsonObject.stringList(vararg keys: String): List<String>? {
    for (k in keys) {
        val arr = this[k] as? JsonArray ?: continue
        return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }
    return null
}

fun parseWorkspace(o: JsonObject): WorkspaceInfo = WorkspaceInfo(
    workspaceId = o.str("workspaceId").orEmpty(),
    path = o.str("path").orEmpty(),
    title = o.str("title")?.takeIf { it.isNotBlank() }
        ?: o.str("path")?.substringAfterLast('\\')?.substringAfterLast('/').orEmpty(),
    sessionIds = o.stringList("sessionIds").orEmpty(),
)

/**
 * 宽容解析 workspace/follow 的帧。
 * 增量帧的字段名各版本可能不同，因此按“找字符串数组”的方式兜底。
 */
fun parseWorkspaceFrame(frame: JsonObject): WorkspaceEvent? {
    val type = frame.str("type") ?: return null
    val value = frame["value"] as? JsonObject
    return when (type) {
        "baseline" -> {
            val items = (value?.get("items") as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.let(::parseWorkspace) }
                .filter { it.workspaceId.isNotEmpty() }
            WorkspaceEvent.Baseline(
                WorkspaceState(
                    workspaces = items,
                    archivedSessionIds = value?.stringList("archivedSessionIds").orEmpty(),
                    pinnedSessionIds = value?.stringList("pinnedSessionIds").orEmpty(),
                )
            )
        }

        "upsert" -> {
            val w = (frame["workspace"] as? JsonObject) ?: value ?: return null
            WorkspaceEvent.Upsert(parseWorkspace(w))
        }

        "remove" -> {
            val id = frame.str("workspaceId")
                ?: value?.str("workspaceId")
                ?: (frame["value"] as? JsonPrimitive)?.contentOrNull
                ?: return null
            WorkspaceEvent.Remove(id)
        }

        "archived" -> {
            val ids = frame.stringList("archivedSessionIds")
                ?: value?.stringList("archivedSessionIds")
                ?: frame.stringList("sessionIds")
                ?: return null
            WorkspaceEvent.Archived(ids)
        }

        "pinned" -> {
            val ids = frame.stringList("pinnedSessionIds")
                ?: value?.stringList("pinnedSessionIds")
                ?: frame.stringList("sessionIds")
                ?: return null
            WorkspaceEvent.Pinned(ids)
        }

        "order" -> null // 顺序由 items 列表本身决定，暂不处理
        else -> null
    }
}
