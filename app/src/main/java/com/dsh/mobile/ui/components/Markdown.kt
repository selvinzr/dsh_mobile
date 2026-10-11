package com.dsh.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.dsh.mobile.ui.theme.MonoTextStyle

/**
 * 轻量 Markdown 渲染器（无第三方依赖）。
 *
 * 支持：围栏代码块、标题、有序/无序列表、引用、分割线，
 * 行内：**粗体**、*斜体*、`代码`、~~删除线~~、[链接](url)。
 * 不追求完整 CommonMark —— 只覆盖模型输出的常见形态。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    baseColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(markdown) { parseBlocks(markdown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block -> BlockView(block, baseColor) }
    }
}

@Composable
private fun BlockView(block: MdBlock, baseColor: Color) {
    when (block) {
        is MdBlock.Code -> CodeBlock(block.code, block.lang)
        is MdBlock.Heading -> Text(
            text = inline(block.text, baseColor),
            style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            fontWeight = FontWeight.SemiBold,
        )

        is MdBlock.Bullet -> Row {
            Text("•  ", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Text(inline(block.text, baseColor), style = MaterialTheme.typography.bodyMedium)
        }

        is MdBlock.Ordered -> Row {
            Text(
                "${block.index}.  ",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(inline(block.text, baseColor), style = MaterialTheme.typography.bodyMedium)
        }

        is MdBlock.Quote -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(6.dp),
        ) {
            Row {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
                Text(
                    inline(block.text, MaterialTheme.colorScheme.onSurfaceVariant),
                    Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        MdBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        is MdBlock.Paragraph -> Text(inline(block.text, baseColor), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CodeBlock(code: String, lang: String?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            if (!lang.isNullOrBlank() && lang != "text") {
                Text(
                    lang,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                code,
                style = MonoTextStyle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                softWrap = false,
            )
        }
    }
}

// ---------- 解析 ----------

private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Ordered(val index: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val code: String, val lang: String?) : MdBlock
    data object Rule : MdBlock
}

private fun parseBlocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.replace("\r\n", "\n").split('\n')
    val para = StringBuilder()

    fun flush() {
        if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
        para.clear()
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        when {
            trimmed.startsWith("```") -> {
                flush()
                val lang = trimmed.removePrefix("```").trim().ifBlank { null }
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    body.appendLine(lines[i])
                    i++
                }
                out += MdBlock.Code(body.toString().trimEnd('\n'), lang)
            }

            trimmed.isEmpty() -> flush()

            trimmed == "---" || trimmed == "***" -> {
                flush(); out += MdBlock.Rule
            }

            trimmed.startsWith("#") -> {
                flush()
                val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(3)
                out += MdBlock.Heading(level, trimmed.drop(level).trim())
            }

            trimmed.startsWith("> ") || trimmed == ">" -> {
                flush()
                out += MdBlock.Quote(trimmed.removePrefix(">").trim())
            }

            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
                flush()
                out += MdBlock.Bullet(trimmed.drop(2).trim())
            }

            Regex("^\\d+[.)]\\s+.*").matches(trimmed) -> {
                flush()
                val idx = trimmed.takeWhile { it.isDigit() }
                out += MdBlock.Ordered(idx.toIntOrNull() ?: 1, trimmed.drop(idx.length + 1).trim())
            }

            else -> {
                if (para.isNotEmpty()) para.append(' ')
                para.append(trimmed)
            }
        }
        i++
    }
    flush()
    return out
}

private val INLINE = Regex(
    "(`[^`]+`)|(\\*\\*[^*]+\\*\\*)|(\\*[^*]+\\*)|(_[^_]+_)|(~~[^~]+~~)|(\\[[^\\]]+\\]\\([^)]+\\))"
)

private fun inline(text: String, baseColor: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in INLINE.findAll(text)) {
        if (m.range.first > last) append(text.substring(last, m.range.first))
        val token = m.value
        when {
            token.startsWith("`") -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = baseColor.copy(alpha = 0.08f),
                )
            ) { append(token.trim('`')) }

            token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(token.trim('*'))
            }

            token.startsWith("~~") -> withStyle(
                SpanStyle(textDecoration = TextDecoration.LineThrough)
            ) { append(token.trim('~')) }

            token.startsWith("[") -> {
                val label = token.substringAfter('[').substringBefore(']')
                withStyle(
                    SpanStyle(
                        color = baseColor.copy(alpha = 1f),
                        textDecoration = TextDecoration.Underline,
                    )
                ) { append(label) }
            }

            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(token.trim('*', '_')) }
        }
        last = m.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}
