package dev.humanagent.agent

import dev.humanagent.llm.Message
import dev.humanagent.llm.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The regression for "the agent forgot to reply with the result": the task — and its delivery
 * instructions — used to sit at transcript index 1, first out of every trim, so past a dozen
 * steps an owed result was forgotten mid-run. The head is now pinned.
 */
class ConversationTrimmerTest {

    private fun user(text: String) = Message.user(text)

    private fun assistantToolCalls() = Message(
        role = "assistant",
        content = "",
        toolCalls = listOf(ToolCall(id = "call_1", name = "tap")),
    )

    private fun toolReply() = Message.tool("call_1", "tap", "Tapped it.")

    private fun conversation(): MutableList<Message> = mutableListOf(
        Message.system("system prompt"),
        user("Auto task: send the result to the conversation with Sam"),
    )

    @Test
    fun theTaskSurvivesTrimmingOfLongRuns() {
        val conversation = conversation()
        repeat(30) { index ->
            conversation += user("Step $index. Current screen:\n[index] row")
            conversation += assistantToolCalls()
            conversation += toolReply()
        }
        ConversationTrimmer.trim(conversation)
        assertEquals(
            ConversationTrimmer.PINNED_HEAD + ConversationTrimmer.KEEP_TAIL,
            conversation.size,
        )
        assertEquals("system prompt", conversation.first().content)
        assertTrue(conversation[1].content.contains("send the result to the conversation with Sam"))
        // The oldest removed is the earliest step dump, not the task.
        assertTrue(conversation.none { it.content.contains("Step 0.") })
    }

    @Test
    fun shortConversationsAreLeftAlone() {
        val conversation = conversation()
        conversation += user("one more")
        ConversationTrimmer.trim(conversation)
        assertEquals(3, conversation.size)
    }

    @Test
    fun aToolReplyNeverLeadsAfterATrimEvenWhenTheCutLandsOnItsPair() {
        // Head + a full assistant/tool pair, then exactly enough filler that the size cut
        // removes the assistant but not its tool reply — the trim must not let a tool reply
        // lead, because providers reject a tool message without its assistant turn.
        val conversation = conversation()
        conversation += user("a question")
        conversation += assistantToolCalls()
        conversation += toolReply()
        repeat(23) { index -> conversation += user("filler $index") }
        assertEquals(28, conversation.size)
        ConversationTrimmer.trim(conversation)
        assertTrue(conversation.size <= ConversationTrimmer.PINNED_HEAD + ConversationTrimmer.KEEP_TAIL)
        assertTrue(conversation[ConversationTrimmer.PINNED_HEAD].role != "tool")
        assertTrue(conversation[1].content.contains("Sam"))
    }
}