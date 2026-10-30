package org.exomind.androbody.nativeapp.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
import org.commonmark.node.Text as MarkdownNodeText
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser

/** CommonMark blocks and inline emphasis; raw HTML stays text and remote images are not loaded. */
@Composable fun MarkdownText(value: String, modifier: Modifier = Modifier) {
    val source = if (value.length <= MAX_RENDERED_CHARS) value else value.take(MAX_RENDERED_CHARS)
    val document = remember(source) { Parser.builder().extensions(listOf(TablesExtension.create())).build().parse(source) }
    val uriHandler = LocalUriHandler.current
    val openLink: (String) -> Unit = remember(uriHandler) {
        { destination ->
            val uri = runCatching { Uri.parse(destination) }.getOrNull()
            if (uri?.scheme == "http" || uri?.scheme == "https") runCatching { uriHandler.openUri(uri.toString()) }
        }
    }
    SelectionContainer {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var block = document.firstChild
            while (block != null) {
                MarkdownBlock(block, openLink)
                block = block.next
            }
            if (value.length > MAX_RENDERED_CHARS)
                Text("回复很长，正文预览显示前 250,000 个字符。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun MarkdownBlock(node: Node, openLink: (String) -> Unit) {
    when (node) {
        is Paragraph -> MarkdownInline(node, MaterialTheme.typography.bodyLarge, openLink)
        is Heading -> {
            val style = when (node.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            MarkdownInline(node, style, openLink)
        }
        is BulletList -> MarkdownList(node, ordered = false, openLink)
        is OrderedList -> MarkdownList(node, ordered = true, openLink)
        is BlockQuote -> Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                var child = node.firstChild
                while (child != null) { MarkdownBlock(child, openLink); child = child.next }
            }
        }
        is FencedCodeBlock -> MarkdownCode(node.literal, node.info)
        is IndentedCodeBlock -> MarkdownCode(node.literal, "")
        is ThematicBreak -> HorizontalDivider()
        is TableBlock -> MarkdownTable(node, openLink)
        is HtmlBlock -> SelectableText(node.literal, code = true)
        else -> {
            if (node.firstChild == null) Text(node.toString(), style = MaterialTheme.typography.bodyLarge)
            else {
                var child = node.firstChild
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    while (child != null) { MarkdownBlock(child, openLink); child = child.next }
                }
            }
        }
    }
}

@Composable private fun MarkdownTable(node: TableBlock, openLink: (String) -> Unit) {
    val rows = buildList {
        var child = node.firstChild
        while (child != null) {
            if (child is TableRow) add(child)
            else {
                var row = child.firstChild
                while (row != null) { if (row is TableRow) add(row); row = row.next }
            }
            child = child.next
        }
    }
    if (rows.isEmpty()) return
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        rows.forEachIndexed { rowIndex, row ->
            Row {
                var cell = row.firstChild
                while (cell != null) {
                    if (cell is TableCell) Surface(
                        color = if (rowIndex == 0) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(0.dp),
                    ) {
                        Box(Modifier.widthIn(min = 120.dp).padding(horizontal = 10.dp, vertical = 8.dp)) {
                            MarkdownInline(cell, if (rowIndex == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium, openLink)
                        }
                    }
                    cell = cell.next
                }
            }
            if (rowIndex < rows.lastIndex) HorizontalDivider()
        }
    }
}

@Composable private fun MarkdownList(node: Node, ordered: Boolean, openLink: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        var item = node.firstChild
        var index = 1
        while (item != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (ordered) index.toString() + "." else "•", modifier = Modifier.widthIn(min = 20.dp),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    var child = item.firstChild
                    while (child != null) { MarkdownBlock(child, openLink); child = child.next }
                }
            }
            item = item.next
            index++
        }
    }
}

@Composable private fun MarkdownCode(code: String, language: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (language.isNotBlank()) Text(language.trim(), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectableText(code.trimEnd(), code = true)
        }
    }
}

@Composable private fun MarkdownInline(node: Node, style: androidx.compose.ui.text.TextStyle, openLink: (String) -> Unit) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeColor = MaterialTheme.colorScheme.surfaceVariant
    val text = remember(node, linkColor, codeColor, openLink) { buildInline(node, linkColor, codeColor, openLink) }
    Text(text = text, style = style)
}

private fun buildInline(node: Node, linkColor: Color, codeColor: Color, openLink: (String) -> Unit): AnnotatedString =
    buildAnnotatedString { appendInlineNode(node, linkColor, codeColor, openLink) }

private fun AnnotatedString.Builder.appendInlineNode(node: Node, linkColor: Color, codeColor: Color, openLink: (String) -> Unit) {
    when (node) {
        is MarkdownNodeText -> append(node.literal)
        is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeColor)) { append(node.literal) }
        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInlineChildren(node, linkColor, codeColor, openLink) }
        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlineChildren(node, linkColor, codeColor, openLink) }
        is Link -> {
            val link = LinkAnnotation.Url(node.destination,
                TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))) {
                (it as? LinkAnnotation.Url)?.url?.let(openLink)
            }
            withLink(link) { appendInlineChildren(node, linkColor, codeColor, openLink) }
        }
        is Image -> appendInlineChildren(node, linkColor, codeColor, openLink)
        is HtmlInline -> append(node.literal)
        is SoftLineBreak -> append('\n')
        is HardLineBreak -> append('\n')
        else -> appendInlineChildren(node, linkColor, codeColor, openLink)
    }
}

private fun AnnotatedString.Builder.appendInlineChildren(node: Node, linkColor: Color, codeColor: Color, openLink: (String) -> Unit) {
    var child = node.firstChild
    while (child != null) {
        appendInlineNode(child, linkColor, codeColor, openLink)
        child = child.next
    }
}

private const val MAX_RENDERED_CHARS = 250_000
