package dev.humanagent.agent

import dev.humanagent.llm.Message as LlmMessage
import dev.humanagent.llm.ToolCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTrimTest {

    private fun assistantWithToolCall(id: String, name: String = "tap") = LlmMessage(
        role = "assistant",
        content = "",
        toolCalls = listOf(ToolCall(id, name, """{"index":1}""")),
    )

    @Test
    fun smallHistoriesAreUntouched() {
        val conversation = mutableListOf(
            LlmMessage.system("sys"),
            LlmMessage.user("hello"),
            LlmMessage.assistant("hi"),
        )
        val result = ConversationTrim.trim(conversation)
        assertEquals("", result)
        assertEquals(3, conversation.size)
    }

    @Test
    fun toolReplyIsNeverSeparatedFromItsAssistantCall() {
        val conversation = mutableListOf(LlmMessage.system("sys"))
        // Old pair that must be dropped together.
        conversation.add(assistantWithToolCall("call-1"))
        conversation.add(LlmMessage.tool("call-1", "tap", "Tapped."))
        // Newer messages.
        conversation.add(LlmMessage.user("Step 9. Current screen:\nScreen: Chat"))
        repeat(24) { conversation.add(LlmMessage.assistant("filler $it")) }

        ConversationTrim.trim(conversation)

        // No tool message may appear without its assistant message before it.
        var sawAssistant = false
        for (message in conversation) {
            if (message.role == "assistant") sawAssistant = true
            if (message.role == "tool") assertTrue("orphaned tool reply", sawAssistant)
            if (message.role != "tool") sawAssistant = false
        }
    }

    @Test
    fun historyStartsWithSystemThenOptionallyJournal() {
        val conversation = mutableListOf(LlmMessage.system("the system"))
        conversation.add(assistantWithToolCall("c1"))
        conversation.add(LlmMessage.tool("c1", "tap", "Tapped 1."))
        repeat(30) { conversation.add(LlmMessage.user("Step $it. Current screen:\nScreen: X")) }

        ConversationTrim.trim(conversation)

        assertEquals("the system", conversation.first().content)
        assertTrue(conversation[1].role == "user")
        assertTrue(conversation[1].content.contains("Earlier in this task"))
        assertTrue(conversation[1].content.contains("tap"))
    }

    @Test
    fun journalCondensesOldSteps() {
        val dropped = listOf(
            LlmMessage.user("Step 1. Current screen:\nScreen: Settings — Wi-Fi (10 nodes, 8 addressable)"),
            assistantWithToolCall("c1", "open_app"),
            LlmMessage.tool("c1", "open_app", "Opened WhatsApp."),
        )
        val journal = ConversationTrim.journal(dropped)
        assertTrue(journal.contains("Earlier in this task"))
        assertTrue(journal.contains("open_app"))
        assertTrue(journal.contains("Opened WhatsApp."))
        assertTrue(journal.contains("screen:"))
        // Nothing stays empty.
        assertTrue(journal.length < 1000)
    }

    @Test
    fun journalSkipsEmptyMessages() {
        val journal = ConversationTrim.journal(listOf(LlmMessage.assistant("")))
        assertEquals("", journal)
    }

    @Test
    fun trimDoesNotThrowWhenToolPairsSitInTheKeptTail() {
        val conversation = mutableListOf(LlmMessage.system("sys"))
        // 14 assistant+tool pairs plus one screen message: 29 messages, so trimming drops the
        // oldest pairs while tool pairs remain in the kept tail — the shape that used to throw
        // ConcurrentModificationException from stale subList views.
        repeat(14) { n ->
            conversation.add(assistantWithToolCall("c$n"))
            conversation.add(LlmMessage.tool("c$n", "tap", "done $n"))
        }
        conversation.add(LlmMessage.user("Step 15. Current screen:\nScreen: Chat"))

        val journal = ConversationTrim.trim(conversation)

        assertEquals("sys", conversation.first().content)
        assertTrue(journal.contains("tap"))
        // Every surviving tool reply still sits directly behind its own assistant call.
        for (i in 1 until conversation.size - 1) {
            val current = conversation[i]
            if (current.role == "tool") {
                val previous = conversation[i - 1]
                assertEquals("tool reply orphaned from its assistant call", "assistant", previous.role)
                assertTrue(previous.toolCalls.any { it.id == current.toolCallId })
            }
        }
        // The newest screen message is still the tail.
        assertTrue(conversation.last().content.startsWith("Step 15."))
    }

    @Test
    fun finishSummariesAreNotDuplicatedIntoJournal() {
        val dropped = listOf(
            assistantWithToolCall("c9", "finish"),
            LlmMessage.tool("c9", "finish", "Sent the message."),
        )
        val journal = ConversationTrim.journal(dropped)
        assertTrue(journal.contains("finish"))
        assertTrue(journal.contains("Sent the message."))
    }
}