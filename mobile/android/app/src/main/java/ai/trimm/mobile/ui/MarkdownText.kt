package ai.trimm.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.*
import org.commonmark.parser.Parser

private val markdownExtensions = listOf(
    StrikethroughExtension.create(),
    TablesExtension.create(),
    AutolinkExtension.create()
)

private val markdownParser: Parser = Parser.builder()
    .extensions(markdownExtensions)
    .build()

/**
 * Renders CommonMark / GFM markdown with native Jetpack Compose components,
 * tailored to Trimm's dark aesthetic and theme tokens.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = Cream,
    fontSize: TextUnit = 15.sp,
    lineHeight: TextUnit = 22.sp,
    blockSpacing: Dp = 8.dp
) {
    if (markdown.isBlank()) return

    val document = remember(markdown) {
        markdownParser.parse(markdown)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(blockSpacing)
    ) {
        var child = document.firstChild
        while (child != null) {
            if (child is Block) {
                MarkdownBlock(
                    node = child,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    color = color
                )
            }
            child = child.next
        }
    }
}

@Composable
fun MarkdownBlock(
    node: Node,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    color: Color,
    modifier: Modifier = Modifier
) {
    when (node) {
        is Heading -> {
            MarkdownHeading(
                heading = node,
                baseFontSize = fontSize,
                color = color,
                modifier = modifier
            )
        }
        is Paragraph -> {
            MarkdownParagraph(
                paragraph = node,
                fontSize = fontSize,
                lineHeight = lineHeight,
                color = color,
                modifier = modifier
            )
        }
        is BulletList -> {
            MarkdownBulletList(
                bulletList = node,
                fontSize = fontSize,
                lineHeight = lineHeight,
                color = color,
                modifier = modifier
            )
        }
        is OrderedList -> {
            MarkdownOrderedList(
                orderedList = node,
                fontSize = fontSize,
                lineHeight = lineHeight,
                color = color,
                modifier = modifier
            )
        }
        is BlockQuote -> {
            MarkdownBlockQuote(
                blockQuote = node,
                fontSize = fontSize,
                lineHeight = lineHeight,
                color = color,
                modifier = modifier
            )
        }
        is FencedCodeBlock -> {
            MarkdownCodeBlock(
                code = node.literal,
                info = node.info,
                modifier = modifier
            )
        }
        is IndentedCodeBlock -> {
            MarkdownCodeBlock(
                code = node.literal,
                info = null,
                modifier = modifier
            )
        }
        is ThematicBreak -> {
            MarkdownThematicBreak(modifier = modifier)
        }
        is TableBlock -> {
            MarkdownTable(
                tableBlock = node,
                fontSize = fontSize,
                modifier = modifier
            )
        }
        else -> {
            var child = node.firstChild
            if (child != null) {
                Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    while (child != null) {
                        if (child is Block) {
                            MarkdownBlock(
                                node = child,
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                color = color
                            )
                        }
                        child = child.next
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownHeading(
    heading: Heading,
    baseFontSize: TextUnit,
    color: Color,
    modifier: Modifier = Modifier
) {
    val (headingFontSize, headingFontWeight, headingColor) = when (heading.level) {
        1 -> Triple(20.sp, FontWeight.Bold, Cream)
        2 -> Triple(18.sp, FontWeight.Bold, Cream)
        3 -> Triple(16.sp, FontWeight.SemiBold, CreamRaised)
        4 -> Triple(15.sp, FontWeight.SemiBold, CreamRaised)
        else -> Triple(14.sp, FontWeight.Medium, CreamRaised)
    }
    val text = remember(heading, headingColor, headingFontSize) {
        buildAnnotatedString {
            appendInlineChildren(heading, color = headingColor, baseFontSize = headingFontSize)
        }
    }
    Text(
        text = text,
        fontSize = headingFontSize,
        fontWeight = headingFontWeight,
        color = headingColor,
        lineHeight = (headingFontSize.value * 1.3f).sp,
        modifier = modifier.padding(
            top = if (heading.level <= 2) 4.dp else 2.dp,
            bottom = 2.dp
        )
    )
}

@Composable
private fun MarkdownParagraph(
    paragraph: Paragraph,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    color: Color,
    modifier: Modifier = Modifier
) {
    val text = remember(paragraph, color, fontSize) {
        buildAnnotatedString {
            appendInlineChildren(paragraph, color = color, baseFontSize = fontSize)
        }
    }
    Text(
        text = text,
        fontSize = fontSize,
        lineHeight = lineHeight,
        color = color,
        modifier = modifier
    )
}

@Composable
private fun MarkdownBulletList(
    bulletList: BulletList,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    color: Color,
    indent: Int = 0,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        var item = bulletList.firstChild
        while (item != null) {
            if (item is ListItem) {
                MarkdownListItem(
                    listItem = item,
                    prefix = {
                        Box(
                            modifier = Modifier
                                .padding(top = (fontSize.value * 0.45f).dp, end = 8.dp)
                                .size(5.dp)
                                .background(Lavender, CircleShape)
                        )
                    },
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    color = color,
                    indent = indent
                )
            }
            item = item.next
        }
    }
}

@Composable
private fun MarkdownOrderedList(
    orderedList: OrderedList,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    color: Color,
    indent: Int = 0,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        @Suppress("DEPRECATION")
        val startNum = runCatching { orderedList.markerStartNumber ?: 1 }.getOrElse { orderedList.startNumber }
        var index = startNum
        var item = orderedList.firstChild
        while (item != null) {
            if (item is ListItem) {
                val num = index
                MarkdownListItem(
                    listItem = item,
                    prefix = {
                        Text(
                            text = "$num.",
                            fontSize = fontSize,
                            fontWeight = FontWeight.SemiBold,
                            color = Lavender,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    },
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    color = color,
                    indent = indent
                )
                index++
            }
            item = item.next
        }
    }
}

@Composable
private fun MarkdownListItem(
    listItem: ListItem,
    prefix: @Composable () -> Unit,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    color: Color,
    indent: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = (indent * 12).dp)
    ) {
        var child = listItem.firstChild
        var firstBlock = true
        while (child != null) {
            when (child) {
                is Paragraph -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        if (firstBlock) {
                            prefix()
                        } else {
                            Spacer(Modifier.width(16.dp))
                        }
                        val text = remember(child, color, fontSize) {
                            buildAnnotatedString {
                                appendInlineChildren(child, color = color, baseFontSize = fontSize)
                            }
                        }
                        Text(
                            text = text,
                            fontSize = fontSize,
                            lineHeight = lineHeight,
                            color = color,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    firstBlock = false
                }
                is BulletList -> {
                    MarkdownBulletList(
                        bulletList = child,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        color = color,
                        indent = indent + 1
                    )
                }
                is OrderedList -> {
                    MarkdownOrderedList(
                        orderedList = child,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        color = color,
                        indent = indent + 1
                    )
                }
                is Block -> {
                    MarkdownBlock(
                        node = child,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        color = color
                    )
                }
            }
            child = child.next
        }
    }
}

@Composable
private fun MarkdownBlockQuote(
    blockQuote: BlockQuote,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val strokeWidth = 3.dp.toPx()
                drawLine(
                    color = Lavender,
                    start = Offset(strokeWidth / 2f, 0f),
                    end = Offset(strokeWidth / 2f, size.height),
                    strokeWidth = strokeWidth
                )
            }
            .padding(start = 12.dp, top = 2.dp, bottom = 2.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            var child = blockQuote.firstChild
            while (child != null) {
                if (child is Block) {
                    MarkdownBlock(
                        node = child,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        color = CreamRaised.copy(alpha = 0.9f)
                    )
                }
                child = child.next
            }
        }
    }
}

@Composable
private fun MarkdownCodeBlock(
    code: String,
    info: String? = null,
    modifier: Modifier = Modifier
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    val trimmedCode = remember(code) { code.trimEnd('\r', '\n') }
    val language = remember(info) {
        info?.trim()?.split(" ")?.firstOrNull()?.takeIf { it.isNotEmpty() }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Ink)
            .border(1.dp, Border.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Raised.copy(alpha = 0.7f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = language?.lowercase() ?: "code",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Muted,
                letterSpacing = 0.5.sp
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        clipboardManager.setText(AnnotatedString(trimmedCode))
                        copied = true
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = "Copy code",
                    tint = if (copied) Color(0xFF4ADE80) else Muted,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = if (copied) "Copied" else "Copy",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (copied) Color(0xFF4ADE80) else Muted
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(10.dp)
        ) {
            Text(
                text = trimmedCode,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = CreamRaised
            )
        }
    }
}

@Composable
private fun MarkdownThematicBreak(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        thickness = 1.dp,
        color = Border.copy(alpha = 0.6f)
    )
}

@Composable
private fun MarkdownTable(
    tableBlock: TableBlock,
    fontSize: TextUnit,
    modifier: Modifier = Modifier
) {
    val headerRows = remember(tableBlock) {
        mutableListOf<TableRow>().apply {
            var child = tableBlock.firstChild
            while (child != null) {
                if (child is TableHead) {
                    var row = child.firstChild
                    while (row != null) {
                        if (row is TableRow) add(row)
                        row = row.next
                    }
                }
                child = child.next
            }
        }
    }

    val bodyRows = remember(tableBlock) {
        mutableListOf<TableRow>().apply {
            var child = tableBlock.firstChild
            while (child != null) {
                if (child is TableBody) {
                    var row = child.firstChild
                    while (row != null) {
                        if (row is TableRow) add(row)
                        row = row.next
                    }
                }
                child = child.next
            }
        }
    }

    if (headerRows.isEmpty() && bodyRows.isEmpty()) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Border.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
    ) {
        Column {
            headerRows.forEach { row ->
                MarkdownTableRowView(row = row, isHeader = true, fontSize = fontSize)
                HorizontalDivider(thickness = 1.dp, color = Border)
            }
            bodyRows.forEachIndexed { index, row ->
                MarkdownTableRowView(
                    row = row,
                    isHeader = false,
                    fontSize = fontSize,
                    isEven = index % 2 == 0
                )
                if (index < bodyRows.lastIndex) {
                    HorizontalDivider(thickness = 1.dp, color = Border.copy(alpha = 0.35f))
                }
            }
        }
    }
}

@Composable
private fun MarkdownTableRowView(
    row: TableRow,
    isHeader: Boolean,
    fontSize: TextUnit,
    isEven: Boolean = false
) {
    val cells = remember(row) {
        mutableListOf<TableCell>().apply {
            var cellNode = row.firstChild
            while (cellNode != null) {
                if (cellNode is TableCell) add(cellNode)
                cellNode = cellNode.next
            }
        }
    }

    Row(
        modifier = Modifier
            .background(
                when {
                    isHeader -> Raised
                    isEven -> Workspace
                    else -> Workspace.copy(alpha = 0.6f)
                }
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        cells.forEachIndexed { i, cell ->
            if (i > 0) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(16.dp)
                        .background(Border.copy(alpha = 0.4f))
                )
            }
            val textAlign = when (cell.alignment) {
                TableCell.Alignment.CENTER -> TextAlign.Center
                TableCell.Alignment.RIGHT -> TextAlign.End
                else -> TextAlign.Start
            }
            val text = remember(cell, isHeader, fontSize) {
                buildAnnotatedString {
                    appendInlineChildren(
                        cell,
                        color = if (isHeader) Cream else CreamRaised,
                        baseFontSize = if (isHeader) (fontSize.value * 0.95f).sp else (fontSize.value * 0.9f).sp
                    )
                }
            }
            Text(
                text = text,
                fontSize = if (isHeader) (fontSize.value * 0.95f).sp else (fontSize.value * 0.9f).sp,
                fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                textAlign = textAlign,
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .widthIn(min = 60.dp)
            )
        }
    }
}

private fun AnnotatedString.Builder.appendInlineChildren(
    parent: Node,
    color: Color,
    baseFontSize: TextUnit
) {
    var child = parent.firstChild
    while (child != null) {
        appendInlineNode(child, color, baseFontSize)
        child = child.next
    }
}

private fun AnnotatedString.Builder.appendInlineNode(
    node: Node,
    color: Color,
    baseFontSize: TextUnit
) {
    when (node) {
        is org.commonmark.node.Text -> {
            append(node.literal)
        }
        is StrongEmphasis -> {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Cream)) {
                appendInlineChildren(node, color, baseFontSize)
            }
        }
        is Emphasis -> {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInlineChildren(node, color, baseFontSize)
            }
        }
        is Strikethrough -> {
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = Muted)) {
                appendInlineChildren(node, color, baseFontSize)
            }
        }
        is Code -> {
            withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = (baseFontSize.value * 0.9f).sp,
                    background = Raised,
                    color = Color(0xFFF4F4F5),
                    fontWeight = FontWeight.Medium
                )
            ) {
                append(" ")
                append(node.literal)
                append(" ")
            }
        }
        is Link -> {
            val destination = node.destination ?: ""
            pushLink(
                LinkAnnotation.Url(
                    url = destination,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = Color(0xFF93C5FD),
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.Medium
                        )
                    )
                )
            )
            if (node.firstChild != null) {
                appendInlineChildren(node, Color(0xFF93C5FD), baseFontSize)
            } else {
                append(destination)
            }
            pop()
        }
        is HardLineBreak -> {
            append("\n")
        }
        is SoftLineBreak -> {
            append("\n")
        }
        else -> {
            appendInlineChildren(node, color, baseFontSize)
        }
    }
}
