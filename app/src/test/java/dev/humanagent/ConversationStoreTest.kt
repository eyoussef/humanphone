package dev.humanagent

import dev.humanagent.chat.ChatTurn
import dev.humanagent.chat.Conversation
import dev.humanagent.chat.ConversationStore
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStoreTest {

    private fun store(file: File, max: Int = 40) = ConversationStore(file, max)

    @Test
    fun roundTripsConversationsThroughDisk() = runTest {
        val file = File.createTempFile("conversations", ".json").also { it.delete() }
        val original = listOf(
            Conversation(
                id = "c1",
                title = "First",
                updatedAtMs = 2L,
                turns = listOf(ChatTurn("user", "hello", 1L), ChatTurn("assistant", "hi", 2L)),
            )
        )
        store(file).save(original)
        val loaded = store(file).load()
        assertEquals(original, loaded)
        file.delete()
    }

    @Test
    fun keepsOnlyTheNewestConversations() = runTest {
        val file = File.createTempFile("conversations", ".json").also { it.delete() }
        val many = (1..8).map { Conversation("c$it", "t$it", it.toLong(), emptyList()) }
        val saved = store(file, max = 3).save(many)
        assertEquals(3, saved.size)
        assertEquals(listOf("c8", "c7", "c6"), saved.map { it.id })
        assertEquals(listOf("c8", "c7", "c6"), store(file, max = 3).load().map { it.id })
        file.delete()
    }

    @Test
    fun corruptFileReadsAsEmptyInsteadOfCrashing() = runTest {
        val file = File.createTempFile("conversations", ".json")
        file.writeText("{ this is not the shape we expect")
        assertTrue(store(file).load().isEmpty())
        file.delete()
    }

    @Test
    fun missingFileReadsAsEmpty() = runTest {
        val file = File(System.getProperty("java.io.tmpdir"), "humanphone-does-not-exist-${System.nanoTime()}.json")
        assertTrue(store(file).load().isEmpty())
    }

    @Test
    fun titleIsBuiltFromTheOpeningWords() {
        assertEquals("New chat", ConversationStore.titleFor("   "))
        assertEquals("text Sam that I am late", ConversationStore.titleFor("text  Sam\nthat I am late"))
        val long = "a".repeat(200)
        val title = ConversationStore.titleFor(long)
        assertEquals(42, title.length)
        assertTrue(title.endsWith("…"))
    }
}
