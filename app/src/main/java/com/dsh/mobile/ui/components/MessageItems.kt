package com.dsh.mobile.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import com.dsh.mobile.data.model.ChatItem
import com.dsh.mobile.data.model.UserAttachment
import com.dsh.mobile.ui.theme.MonoTextStyle
import com.dsh.mobile.data.model.ToolCallInfo

@Composable
fun UserBubble(item: ChatItem.User, timestamp: String? = null, onOpenFile: ((name: String, mediaType: String) -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    // 图片：直接渲染缩略图（不再是"图标+N张"）
                    val images = item.attachments.filter { it.isImage }
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    images.forEach { att ->
                        // 记录里通常只有 attachmentId（sha256 引用），base64 只在发送时存在；
                        // 所以先试内联数据，再按哈希去本地缓存（发送时按同一哈希存过）取文件。
                        val bmp = remember(att.base64, att.attachmentId) {
                            decodeThumb(att.base64) ?: decodeThumbFile(ctx, att.attachmentId)
                        }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = att.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .widthIn(max = 240.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                            )
                            Spacer(Modifier.height(5.dp))
                        } else {
                            // 解码失败的可见回退：不留空白
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Image,
                                    null,
                                    Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    att.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Spacer(Modifier.height(5.dp))
                        }
                    }
                    // 文档附件：胶囊展示
                    val docs = item.attachments.filterNot { it.isImage }
                    docs.forEach { att ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Description,
                                null,
                                Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                att.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                    }
                    if (item.attachments.isEmpty() && item.imageCount > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Image,
                                null,
                                Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                if (item.imageCount > 1) "${item.imageCount} 张图片" else "图片",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        if (item.text.isNotBlank()) Spacer(Modifier.height(5.dp))
                    }
                    if (item.text.isNotBlank()) {
                        Text(
                            item.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            timestamp?.let {
                Text(
                    it,
                    Modifier.padding(top = 2.dp, end = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun AssistantBlock(item: ChatItem.Assistant, timestamp: String? = null, showHeader: Boolean = true) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = if (showHeader) 6.dp else 2.dp)
    ) {
        if (showHeader) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "DSH",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                timestamp?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        if (item.reasoning.isNotBlank()) {
            ReasoningChip(item.reasoning)
            Spacer(Modifier.height(6.dp))
        }
        if (item.text.isNotBlank()) {
            MarkdownText(item.text, Modifier.padding(start = 4.dp))
        }
        if (item.toolCalls.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item.toolCalls.forEach { ToolCard(it) }
            }
        }
    }
}

@Composable
fun ReasoningChip(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (expanded) "思维链" else "思维链 · ${text.length} 字",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text.take(70).replace('\n', ' ') + if (text.length > 70) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun toolIcon(name: String) = when {
    name.contains("shell", true) || name.contains("bash", true) || name.contains("pwsh", true) ||
        name.contains("terminal", true) -> Icons.Filled.Terminal
    name.contains("read", true) || name.contains("write", true) || name.contains("edit", true) ||
        name.contains("file", true) -> Icons.Filled.Edit
    name.contains("glob", true) || name.contains("grep", true) || name.contains("search", true) ->
        Icons.Filled.Search

    name.contains("web", true) || name.contains("fetch", true) -> Icons.Filled.Language
    name.contains("job", true) || name.contains("agent", true) || name.contains("task", true) ->
        Icons.Filled.FolderOpen

    else -> Icons.Filled.Build
}

@Composable
fun ToolCard(call: ToolCallInfo) {
    var expanded by remember { mutableStateOf(false) }
    val done = call.result != null
    val accent = when {
        call.isError -> MaterialTheme.colorScheme.error
        !done -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(toolIcon(call.name), null, Modifier.size(16.dp), tint = accent)
                Spacer(Modifier.width(6.dp))
                Text(
                    call.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (done && !call.isError) {
                    Icon(Icons.Filled.CheckCircle, null, Modifier.size(14.dp), tint = accent)
                } else if (call.isError) {
                    Icon(Icons.Filled.ErrorOutline, null, Modifier.size(14.dp), tint = accent)
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    when {
                        !done -> "运行中"
                        call.isError -> "失败"
                        else -> "完成"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val summary = call.arguments.take(90).replace('\n', ' ')
            if (!expanded && summary.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    summary,
                    style = MonoTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                LabeledMono("输入", call.arguments)
                call.result?.let {
                    Spacer(Modifier.height(6.dp))
                    LabeledMono("输出", it, if (call.isError) MaterialTheme.colorScheme.error else null)
                }
            }
        }
    }
}

@Composable
private fun LabeledMono(label: String, body: String, color: Color? = null) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
        ) {
            Text(
                body.take(4000),
                Modifier
                    .padding(8.dp)
                    .horizontalScroll(rememberScrollState()),
                style = MonoTextStyle,
                color = color ?: MaterialTheme.colorScheme.onSurface,
                softWrap = false,
            )
        }
    }
}

/** token 级流式的实时块。 */
@Composable
fun LiveBlock(reasoning: String, text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.SmartToy,
                null,
                Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "DSH",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            TypingDots()
        }
        if (reasoning.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "思考中 · " + reasoning.takeLast(200).replace('\n', ' '),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (text.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            // 流式阶段用纯 Text：MarkdownText 会对整个字符串做全量解析重组，
            // token 级流式时每个 delta 都重跑一次，长回复会掉帧。落库后由
            // AssistantBlock 的 MarkdownText 渲染最终排版。
            Text(
                text + " ▍",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(animation = tween(1200)),
        label = "phase",
    )
    val dots = (0..2).joinToString("") { i ->
        if (phase.toInt() % 3 == i) "●" else "○"
    }
    Text(
        dots,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** base64 → 降采样缩略图（失败返回 null，调用方回退成图标）。 */
internal fun decodeThumb(base64: String?): android.graphics.Bitmap? = runCatching {
    val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
    // 先读尺寸再决定降采样：小图（<512px）不降采样，避免小图标被缩没了
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val raw = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 512) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}.getOrNull()

/**
 * 按 `sha256:<hash>` 附件引用去本地缓存取图。
 *
 * DSH 落库后图片是内容寻址引用，记录里没有字节；发送时我们按同一哈希
 * 把字节写进了 `cacheDir/attachments/`，所以能一对一命中。
 * 别处发来的历史图片取不到 → 返回 null，由调用方回退成图标。
 */
internal fun decodeThumbFile(
    context: android.content.Context,
    attachmentId: String?,
): android.graphics.Bitmap? {
    val hex = attachmentId?.removePrefix("sha256:")?.takeIf { it.isNotBlank() } ?: return null
    val file = java.io.File(java.io.File(context.cacheDir, "attachments"), hex)
    if (!file.exists()) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 512) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}
