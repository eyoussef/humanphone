package dev.humanagent

import dev.humanagent.util.Markdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    private fun rendered(raw: String): String = Markdown.parse(raw).joinToString("") { span ->
        val markers = buildString {
            if (span.bold) append('b')
            if (span.italic) append('i')
            if (span.code) append('c')
        }
        if (markers.isEmpty()) span.text else "<$markers>${span.text}</$markers>"
    }

    @Test
    fun boldAndItalicMarkersBecomeSpans() {
        assertEquals("<b>hello</b> world", rendered("**hello** world"))
        assertEquals("<i>soft</i> words", rendered("*soft* words"))
        assertEquals("<b>bold</b> <i>it</i>", rendered("**bold** *it*"))
    }

    @Test
    fun emphasisCanNest() {
        // Adjacent runs keep their own flags; Material renders them as one visually merged run.
        assertEquals("<b>very </b><bi>important</bi><b> thing</b>", rendered("**very *important* thing**"))
    }

    @Test
    fun inlineCodeIsKeptAsText() {
        assertEquals("<c>val x = 1</c>", rendered("`val x = 1`"))
        assertTrue(rendered("use `**not bold**` here").contains("<c>**not bold**</c>"))
    }

    @Test
    fun headingsLoseTheirHashesAndStayBold() {
        assertEquals("<b>Plan</b>", rendered("### Plan"))
        assertEquals("<b>Title</b>", rendered("# Title"))
    }

    @Test
    fun bulletsAndNumberingBecomeReadableText() {
        assertEquals("• first\n• second", rendered("- first\n- second"))
        assertEquals("1. one\n2. two", rendered("1. one\n2. two"))
    }

    @Test
    fun codeFencesAreRemovedButTheirContentKept() {
        val raw = "before\n```kotlin\nval a = 1\n```\nafter"
        assertTrue(Markdown.parse(raw).none { it.text.contains("```") })
        assertEquals("before\nval a = 1\nafter", Markdown.strip(raw))
    }

    @Test
    fun snakeCaseAndArithmeticSurvive() {
        assertEquals("my_file_name", rendered("my_file_name"))
        assertEquals("2 * 3 * 4", rendered("2 * 3 * 4"))
        assertEquals("5 ** 6", rendered("5 ** 6"))
    }

    @Test
    fun aMarkerWithoutItsPartnerIsPrintedLiterally() {
        assertEquals("only **one marker here", rendered("only **one marker here"))
        assertEquals("a ** b c", rendered("a ** b c"))
    }

    @Test
    fun escapesSurvive() {
        assertEquals("2 * 3", rendered("2 \\* 3"))
        assertEquals("**not bold**", rendered("\\*\\*not bold\\*\\*"))
    }

    @Test
    fun stripRemovesEveryMarkerForSpeech() {
        assertEquals("Hello world, done.", Markdown.strip("**Hello** *world*, `done`."))
        assertEquals("Step 1\n• and step 2", Markdown.strip("### Step 1\n- and step 2"))
    }

    @Test
    fun singleLineFlattensForNotifications() {
        assertEquals("Title one two", Markdown.singleLine("# Title\n- one\n- two"))
    }

    @Test
    fun plainTextPassesThroughUnchanged() {
        val plain = "Just a normal sentence, nothing else."
        assertEquals(plain, rendered(plain))
        assertEquals(plain, Markdown.strip(plain))
    }

    @Test
    fun emptyInputIsSafe() {
        assertTrue(Markdown.parse("").isEmpty())
        assertEquals("", Markdown.strip(""))
        assertEquals("", Markdown.singleLine(""))
    }
}
