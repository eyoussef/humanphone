package dev.humanagent.brain

import org.junit.Assert.assertEquals
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
}