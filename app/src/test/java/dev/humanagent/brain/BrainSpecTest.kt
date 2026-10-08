package dev.humanagent.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainSpecTest {

    @Test
    fun queriesUseTheSearchPrefix() {
        assertEquals(
            "task: search result | query: where is mum flying",
            BrainSpec.queryText("  where is mum flying  "),
        )
    }

    @Test
    fun documentsCarryTitleWithFallbackAndSanitizedPipes() {
        assertEquals(
            "title: none | text: just some content",
            BrainSpec.documentText("   ", "just some content"),
        )
        assertEquals(
            "title: a/b | text: line1 line2",
            BrainSpec.documentText("a|b", "line1\nline2"),
        )
    }

    @Test
    fun chatExchangeChunksAreIdentifiedByConversationAndTime() {
        val chunk = BrainChunks.exchange("c42", "Trip planning", "when is the flight", "Tuesday at 9", 1234L)
        assertEquals("c42:1234", chunk.ref)
        assertEquals(BrainChunks.KIND_CHAT, chunk.kind)
        assertTrue(chunk.text.startsWith("user: when is the flight"))
        assertTrue(chunk.text.contains("assistant: Tuesday at 9"))
    }

    @Test
    fun episodeAndFactChunksKeepStableRefs() {
        val episode = BrainChunks.episode("text sam", "sent", "WhatsApp", 99L)
        assertEquals("episode:99", episode.ref)
        assertEquals("Task in WhatsApp", episode.title)

        val fact = BrainChunks.fact("mum's number", "+1 555")
        assertEquals("fact:mum's number", fact.ref)
        // Facts are timeless: no recency boost, and they survive chunk trimming longest.
        assertEquals(0L, fact.ts)
    }

    @Test
    fun linksAreDetectedAndPastedTextIsNot() {
        assertTrue(WebText.isUrl("https://example.com/doc"))
        assertTrue(WebText.isUrl("  http://example.com  "))
        assertFalse(WebText.isUrl("just some document text"))
        assertFalse(WebText.isUrl("ftp://example.com"))
    }

    @Test
    fun htmlIsStrippedToReadableText() {
        val html = """
            <html><head><title>My Doc</title><script>var x = 1;</script>
            <style>.a { color: red }</style></head>
            <body><h1>Hello</h1><p>World &amp; friends&nbsp;— stay.</p></body></html>
        """.trimIndent()
        assertEquals("My Doc", WebText.title(html))
        val text = WebText.strip(html)
        assertFalse(text.contains("var x"))
        assertFalse(text.contains("color: red"))
        assertFalse(text.contains("<"))
        assertTrue(text.contains("Hello"))
        assertTrue(text.contains("World & friends"))
    }

    @Test
    fun longTextChunksOnParagraphsWithACap() {
        val paragraphs = (1..20).map { "Paragraph $it " + "x".repeat(200) }
        val chunks = WebText.chunk(paragraphs.joinToString("\n\n"), chunkSize = 500)
        assertTrue(chunks.size > 3)
        // No paragraph is cut mid-way at the soft cap…
        assertTrue(chunks.all { it.startsWith("Paragraph") })
        // …and one enormous paragraph still gets split at the hard cap.
        val huge = WebText.chunk("y".repeat(2500), chunkSize = 500)
        assertTrue(huge.size >= 2)
        assertTrue(huge.all { it.length <= 1000 })
    }

    @Test
    fun sourceChunksCarryTheirSourceIdAndPart() {
        val chunk = BrainChunks.source("s1", "My Doc", "https://d", 3, "piece", 5L)
        assertEquals("source:s1:3", chunk.ref)
        assertEquals(BrainChunks.KIND_SOURCE, chunk.kind)
        assertEquals("My Doc", chunk.title)
    }
}