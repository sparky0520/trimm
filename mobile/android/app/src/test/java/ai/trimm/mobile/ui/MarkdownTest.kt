package ai.trimm.mobile.ui

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.junit.Assert.*
import org.junit.Test

class MarkdownTest {

    private val extensions = listOf(
        StrikethroughExtension.create(),
        TablesExtension.create(),
        AutolinkExtension.create()
    )

    private val parser = Parser.builder()
        .extensions(extensions)
        .build()

    @Test
    fun testHeadingsParsing() {
        val markdown = """
            # Heading 1
            ## Heading 2
            ### Heading 3
        """.trimIndent()
        val doc = parser.parse(markdown)
        var count = 0
        var child = doc.firstChild
        while (child != null) {
            if (child is Heading) {
                count++
                assertEquals(count, child.level)
            }
            child = child.next
        }
        assertEquals(3, count)
    }

    @Test
    fun testInlineFormatting() {
        val markdown = "Here is **bold**, *italic*, and `code` with ~~strike~~."
        val doc = parser.parse(markdown)
        val p = doc.firstChild as Paragraph

        var hasBold = false
        var hasItalic = false
        var hasCode = false
        var hasStrike = false

        var node = p.firstChild
        while (node != null) {
            when (node) {
                is StrongEmphasis -> hasBold = true
                is Emphasis -> hasItalic = true
                is Code -> hasCode = true
                is Strikethrough -> hasStrike = true
            }
            node = node.next
        }

        assertTrue(hasBold)
        assertTrue(hasItalic)
        assertTrue(hasCode)
        assertTrue(hasStrike)
    }

    @Test
    fun testCodeBlockWithLanguage() {
        val markdown = """
            ```kotlin
            val message = "Hello AI"
            println(message)
            ```
        """.trimIndent()
        val doc = parser.parse(markdown)
        val codeBlock = doc.firstChild as FencedCodeBlock
        assertEquals("kotlin", codeBlock.info)
        assertTrue(codeBlock.literal.contains("Hello AI"))
    }

    @Test
    fun testBulletList() {
        val markdown = """
            - Trimmed silence at 00:04
            - Added punchy transition
            - Normalized audio volume
        """.trimIndent()
        val doc = parser.parse(markdown)
        assertTrue(doc.firstChild is BulletList)
        val list = doc.firstChild as BulletList
        var itemCount = 0
        var item = list.firstChild
        while (item != null) {
            if (item is ListItem) itemCount++
            item = item.next
        }
        assertEquals(3, itemCount)
    }

    @Test
    fun testOrderedList() {
        val markdown = """
            1. First step
            2. Second step
            3. Third step
        """.trimIndent()
        val doc = parser.parse(markdown)
        assertTrue(doc.firstChild is OrderedList)
        val list = doc.firstChild as OrderedList
        var itemCount = 0
        var item = list.firstChild
        while (item != null) {
            if (item is ListItem) itemCount++
            item = item.next
        }
        assertEquals(3, itemCount)
    }

    @Test
    fun testGfmTable() {
        val markdown = """
            | Feature | Status |
            | :--- | :--- |
            | Video Trim | Done |
            | Captions | Enabled |
        """.trimIndent()
        val doc = parser.parse(markdown)
        assertTrue("Expected TableBlock", doc.firstChild is TableBlock)
    }

    @Test
    fun testAutolinkAndLinks() {
        val markdown = "Check out [Trimm](https://trimm.ai) or visit https://example.com"
        val doc = parser.parse(markdown)
        val p = doc.firstChild as Paragraph
        var linkCount = 0
        var node = p.firstChild
        while (node != null) {
            if (node is Link) {
                linkCount++
            }
            node = node.next
        }
        // Both the explicit markdown link and autolinked URL should be Link nodes
        assertEquals(2, linkCount)
    }

    @Test
    fun testBlockQuote() {
        val markdown = "> This is an important advice from the AI assistant."
        val doc = parser.parse(markdown)
        assertTrue(doc.firstChild is BlockQuote)
    }

    @Test
    fun testMalformedMarkdownNeverCrashes() {
        // Edge cases like unclosed markdown tags during streaming
        val malformed = """
            # Unclosed header
            **unclosed bold text
            `unclosed code
            ```
            unclosed code block
            [unclosed link](
            | half | table
        """.trimIndent()
        val doc = parser.parse(malformed)
        assertNotNull(doc)
    }
}
