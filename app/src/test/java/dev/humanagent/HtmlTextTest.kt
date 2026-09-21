package dev.humanagent

import dev.humanagent.util.HtmlText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTextTest {

    @Test
    fun dropsScriptsStylesAndComments() {
        val html = """
            <html><head><style>body{color:red}</style><script>alert('x')</script></head>
            <body><!-- hidden --><p>Real content</p></body></html>
        """.trimIndent()
        val text = HtmlText.toText(html)
        assertEquals("Real content", text)
    }

    @Test
    fun keepsTextFromParagraphsAndListItems() {
        val html = "<div><p>First line</p><ul><li>One</li><li>Two</li></ul></div>"
        assertEquals("First line\nOne\nTwo", HtmlText.toText(html))
    }

    @Test
    fun readsTheTitle() {
        val html = "<html><head><title> Rust:  the  language </title><body>x"
        assertEquals("Rust: the language", HtmlText.titleOf(html))
        assertEquals("", HtmlText.titleOf("<html><body>no title</body></html>"))
    }

    @Test
    fun decodesNamedAndNumericEntities() {
        assertEquals("Tom & Jerry <3 \"quoted\"", HtmlText.toText("Tom &amp; Jerry &lt;3 &quot;quoted&quot;"))
        assertEquals("caf" + '\u00e9', HtmlText.toText("caf&#233;"))
        assertEquals("hi", HtmlText.toText("&#x68;i"))
        assertEquals("5 - 3", HtmlText.toText("5 &ndash; 3"))
    }

    @Test
    fun unknownEntitiesAreLeftAlone() {
        assertEquals("100 &foo; bar", HtmlText.toText("100 &foo; bar"))
    }

    @Test
    fun collapsesLayoutWhitespaceButKeepsParagraphBreaks() {
        val html = "<p>one   two\n\n   three</p><p>four</p>"
        assertEquals("one two three\nfour", HtmlText.toText(html))
    }

    @Test
    fun truncatesVeryLongPages() {
        val html = "<p>" + "word ".repeat(500) + "</p>"
        val text = HtmlText.toText(html, maxChars = 100)
        assertTrue(text.length <= 106)
        assertTrue(text.endsWith("[…]"))
    }

    @Test
    fun attributesAndNestedMarkupDoNotLeakThrough() {
        val html = """<a href="https://example.com/x?a=1" title="link">Click <b>here</b></a>"""
        val text = HtmlText.toText(html)
        assertEquals("Click here", text)
        assertFalse(text.contains("href"))
    }

    @Test
    fun emptyInputIsSafe() {
        assertEquals("", HtmlText.toText(""))
        assertEquals("", HtmlText.titleOf(""))
    }
}
