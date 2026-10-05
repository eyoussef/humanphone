package dev.humanagent.agent

import dev.humanagent.llm.Message

/**
 * The step loop's transcript guard. The head is pinned — the system prompt and the task itself —
 * because together they are the contract: the model must never lose who it is or what it was
 * asked. The tail is the working memory; everything in between drops oldest first.
 *
 * The bug this exists for: the task message used to sit at index 1, first out of every trim, so
 * after a dozen steps a task that still owed a result to a conversation had no memory of owing
 * one — the agent did the work and finished without ever sending it.
 *
 * Trimming skips whole tail groups only in the sense that it never lets a `tool` reply lead: a
 * tool answer must always follow the assistant turn it answers, or providers reject the request.
 */
object ConversationTrimmer {

    /** [system prompt, task] — pinned, never evicted. */
    const val PINNED_HEAD = 2

    const val KEEP_TAIL = 24

    fun trim(conversation: MutableList<Message>) {
        if (conversation.size <= PINNED_HEAD + KEEP_TAIL) return
        repeat(conversation.size - PINNED_HEAD - KEEP_TAIL) { conversation.removeAt(PINNED_HEAD) }
        while (conversation.getOrNull(PINNED_HEAD)?.role == "tool") conversation.removeAt(PINNED_HEAD)
    }
}