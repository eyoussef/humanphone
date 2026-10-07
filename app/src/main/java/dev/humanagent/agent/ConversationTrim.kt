package dev.humanagent.agent

import dev.humanagent.llm.Message

/**
 * History trimming that keeps the OpenAI message sequence valid: an assistant message and the
 * tool replies that follow it are one unit and are never split. When older turns are dropped, a
 * compact journal of what was done replaces them, so the model still knows its earlier actions.
 */
object ConversationTrim {

    /**
     * Keeps the system message, an action journal for everything older than [keepTail] messages,
     * and the newest [keepTail] messages, never separating a tool reply from its assistant call.
     */
    fun trim(conversation: MutableList<Message>, keepTail: Int = 24): String {
        val system = conversation.firstOrNull()?.takeIf { it.role == "system" } ?: return ""
        if (conversation.size <= keepTail + 1) return ""

        // Group from the newest end: [assistant + its tool replies] or a single message.
        val groups = mutableListOf<List<Message>>()
        var i = conversation.lastIndex
        while (i > 0) { // index 0 is the system message, never grouped
            val message = conversation[i]
            if (message.role == "tool") {
                var start = i
                while (start > 0 && conversation[start].role == "tool") start--
                if (conversation[start].role == "assistant") {
                    // Copies, not views: trim() clears the conversation below, and touching a stale
                    // subList view after that throws ConcurrentModificationException mid-run.
                    groups.add(conversation.subList(start, i + 1).toList())
                    i = start - 1
                } else {
                    groups.add(listOf(message)) // stray tool reply (defensive)
                    i--
                }
            } else {
                groups.add(listOf(message))
                i--
            }
        }

        var kept = 0
        var firstDropped = groups.size
        for (groupIndex in groups.indices) {
            if (kept >= keepTail) {
                firstDropped = groupIndex
                break
            }
            kept += groups[groupIndex].size
            firstDropped = groupIndex + 1
        }

        if (firstDropped >= groups.size) return ""

        // Groups run newest-first (built from the end); both halves must be re-assembled
        // oldest-first so the model reads its own history forward.
        val dropped = groups.subList(firstDropped, groups.size).reversed().flatten()
        val journal = journal(dropped)
        conversation.clear()
        conversation.add(system)
        if (journal.isNotEmpty()) conversation.add(Message.user(journal))
        conversation.addAll(groups.subList(0, firstDropped).reversed().flatten())
        return journal
    }

    /** One line per assistant action with its tool results, compact enough to keep every run. */
    fun journal(messages: List<Message>): String {
        val lines = mutableListOf<String>()
        var pendingCallNames: List<String> = emptyList()
        for (message in messages) {
            when {
                message.role == "assistant" && message.toolCalls.isNotEmpty() -> {
                    pendingCallNames = message.toolCalls.map { call ->
                        call.name + " " + shortArgs(call.id, call.name, call.arguments)
                    }
                    if (message.content.isNotBlank()) {
                        lines.add("said: " + message.content.take(160))
                    }
                }

                message.role == "assistant" && message.content.isNotBlank() ->
                    lines.add("said: " + message.content.take(160))

                message.role == "user" && message.content.isNotBlank() -> {
                    val text = message.content.replace('\n', ' ')
                    lines.add(
                        if (text.startsWith("Step ")) "screen: " + summaryOf(text) else "you (earlier): " + text.take(160),
                    )
                }

                message.role == "tool" -> {
                    val name = message.name?.takeIf { it.isNotBlank() } ?: "tool"
                    val result = message.content.replace('\n', ' ')
                    lines.add(name + " → " + result.take(240))
                    if (pendingCallNames.isNotEmpty()) pendingCallNames = emptyList()
                }
            }
        }
        if (lines.isEmpty()) return ""
        return "Earlier in this task (condensed):\n" + lines.joinToString("\n") { "- $it" }
    }

    private fun shortArgs(id: String, name: String, arguments: String): String {
        val obj = dev.humanagent.util.JsonArgs.asObject(arguments) ?: return ""
        val interesting = obj.entries
            .filter { (key, _) -> key !in setOf("summary") }
            .joinToString(", ") { (key, value) -> "$key=${value.toString().take(60)}" }
        return if (interesting.isBlank()) "" else "($interesting)"
    }

    /** "Step 12. Current screen: Screen: Settings — ..." condensed to app plus first nodes. */
    private fun summaryOf(stepText: String): String =
        stepText.substringAfter("Current screen:").trim().lineSequence().take(2).joinToString(" ").take(120)
}