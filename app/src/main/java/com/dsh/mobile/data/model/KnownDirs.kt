package com.dsh.mobile.data.model

/**
 * 「已知目录树」选择器的数据源。
 *
 * DSH 的 directoryPicker/list 需要 browse capability，本部署只提供 native（弹系统框），
 * 远程无法枚举任意目录。改为从已知信息离线构树：
 *  - session/list 全部会话的 cwd
 *  - workspace/follow 现有工作区的 path
 * 这覆盖了"历史上用过的所有目录"，配合手动输入兜底。
 */
data class DirNode(
    val name: String,
    val path: String,
    val children: List<DirNode>,
    val isWorkspace: Boolean = false,
)

object KnownDirs {

    private class MutableNode(
        val name: String,
        val path: String,
        val children: LinkedHashMap<String, MutableNode> = LinkedHashMap(),
        var isWorkspace: Boolean = false,
    )

    /** 从一组绝对路径构建树。path 形如 D:\develop\edu（分隔符 \ 或 / 均可）。 */
    fun build(paths: List<String>): List<DirNode> {
        val roots = LinkedHashMap<String, MutableNode>()
        for (raw in paths.filter { it.isNotBlank() }.distinct()) {
            val norm = raw.replace('/', '\\').trimEnd('\\')
            val parts = norm.split('\\').filter { it.isNotBlank() }
            if (parts.isEmpty()) continue
            var current = roots
            var parent: MutableNode? = null
            var acc = ""
            for ((i, part) in parts.withIndex()) {
                // acc 必须由父节点的 path 推导，不能查未创建的子节点
                acc = if (i == 0) {
                    if (part.endsWith(":")) "$part\\" else part
                } else {
                    parent!!.path.trimEnd('\\') + "\\" + part
                }
                val node = current.getOrPut(part) { MutableNode(part, acc) }
                if (i == parts.lastIndex) node.isWorkspace = true
                parent = node
                current = node.children
            }
        }

        fun sortNodes(nodes: Collection<MutableNode>): List<MutableNode> =
            nodes.sortedWith(
                compareByDescending<MutableNode> { it.children.isNotEmpty() }
                    .thenBy { it.name.lowercase() }
            )

        fun toNode(m: MutableNode): DirNode = DirNode(
            name = m.name,
            path = m.path,
            children = sortNodes(m.children.values).map(::toNode),
            isWorkspace = m.isWorkspace,
        )

        return sortNodes(roots.values).map(::toNode)
    }
}
