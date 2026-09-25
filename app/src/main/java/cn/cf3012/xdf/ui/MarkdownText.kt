package cn.cf3012.xdf.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.commonmark.node.BlockQuote
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as CmText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * MarkdownText — Release 更新说明的轻量 Markdown 渲染。
 *
 * 支持 Release body 实际会用的语法：标题、粗体、斜体、行内代码、代码块、
 * 有序/无序列表、引用、分隔线。commonmark 0.24 的 Node 不再实现 Iterable，
 * 因此统一用 getFirstChild/getNext 显式遍历子节点。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
    color: Color = Color(0xFF3C3C43),
) {
    Text(
        text = renderMarkdown(markdown, fontSize, color),
        modifier = modifier,
    )
}

private val HEADING_SCALE = floatArrayOf(1.5f, 1.32f, 1.18f, 1.08f, 1f, 1f)

fun renderMarkdown(
    src: String,
    base: TextUnit,
    bodyColor: Color = Color(0xFF3C3C43),
): AnnotatedString {
    val sb = AnnotatedString.Builder()
    try {
        val doc = Parser.builder().build().parse(src)
        walkBlocks(doc, sb, base, bodyColor, 0)
    } catch (_: Throwable) {
        sb.append(src) // 解析失败退化为纯文本
    }
    return sb.toAnnotatedString()
}

private inline fun forEachChild(node: Node, block: (Node) -> Unit) {
    var c = node.firstChild
    while (c != null) {
        val next = c.next
        block(c)
        c = next
    }
}

private fun walkBlocks(
    parent: Node,
    sb: AnnotatedString.Builder,
    base: TextUnit,
    bodyColor: Color,
    depth: Int,
) {
    var first = true
    forEachChild(parent) { child ->
        if (!first) sb.append("\n")
        first = false
        when (child) {
            is Heading -> {
                val idx = (child.level - 1).coerceIn(0, 5)
                styled(sb, SpanStyle(fontSize = base * HEADING_SCALE[idx], fontWeight = FontWeight.Bold, color = Color(0xFF1C1C1E))) {
                    walkInline(child, sb, base, bodyColor)
                }
            }
            is Paragraph -> walkInline(child, sb, base, bodyColor)
            is org.commonmark.node.BulletList -> walkList(child, sb, base, bodyColor, depth, ordered = false)
            is OrderedList -> walkList(child, sb, base, bodyColor, depth, ordered = true)
            is FencedCodeBlock, is IndentedCodeBlock -> {
                styled(sb, SpanStyle(fontFamily = FontFamily.Monospace, fontSize = base * 0.92f, color = Color(0xFF2F2F35), background = Color(0xFFF2F2F7))) {
                    sb.append("\n")
                    walkInline(child, sb, base, bodyColor)
                    sb.append("\n")
                }
            }
            is BlockQuote -> styled(sb, SpanStyle(color = Color(0xFF8A8A8E))) {
                walkBlocks(child, sb, base, bodyColor, depth + 1)
            }
            is ThematicBreak -> styled(sb, SpanStyle(color = Color(0xFFB0B0B6))) { sb.append("────────────") }
            else -> walkBlocks(child, sb, base, bodyColor, depth)
        }
    }
}

private fun walkList(
    list: Node,
    sb: AnnotatedString.Builder,
    base: TextUnit,
    bodyColor: Color,
    depth: Int,
    ordered: Boolean,
) {
    var idx = 1
    forEachChild(list) { item ->
        if (item is ListItem) {
            if (idx > 1) sb.append("\n")
            val marker = if (ordered) "$idx. " else "• "
            val indent = "  ".repeat(depth)
            styled(sb, SpanStyle(color = Color(0xFF3482FF), fontWeight = FontWeight.Bold)) {
                sb.append(indent + marker)
            }
            var blockFirst = true
            forEachChild(item) { sub ->
                if (!blockFirst) sb.append("\n")
                blockFirst = false
                when (sub) {
                    is Paragraph -> walkInline(sub, sb, base, bodyColor)
                    else -> walkBlocks(sub, sb, base, bodyColor, depth + 1)
                }
            }
            idx++
        }
    }
}

private fun walkInline(
    node: Node,
    sb: AnnotatedString.Builder,
    base: TextUnit,
    bodyColor: Color,
) {
    forEachChild(node) { child ->
        when (child) {
            is CmText -> sb.append(child.literal)
            is StrongEmphasis -> styled(sb, SpanStyle(fontWeight = FontWeight.Bold)) {
                walkInline(child, sb, base, bodyColor)
            }
            is Emphasis -> styled(sb, SpanStyle(fontStyle = FontStyle.Italic)) {
                walkInline(child, sb, base, bodyColor)
            }
            is Code -> styled(sb, SpanStyle(fontFamily = FontFamily.Monospace, color = Color(0xFF7C5CFF), background = Color(0xFFF0F0F5))) {
                sb.append(child.literal)
            }
            is HardLineBreak -> sb.append("\n")
            is SoftLineBreak -> sb.append(" ")
            else -> walkInline(child, sb, base, bodyColor)
        }
    }
}

private inline fun styled(sb: AnnotatedString.Builder, style: SpanStyle, block: () -> Unit) {
    sb.pushStyle(style)
    try {
        block()
    } finally {
        sb.pop()
    }
}