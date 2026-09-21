package dev.humanagent.chat

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ChatTurn(
    val role: String,
    val text: String,
    val timestampMs: Long,
    /** Absolute paths of images attached to this turn, shown in the bubble and sent to the model. */
    val imagePaths: List<String> = emptyList(),
    /** Absolute path of a recorded voice note attached to this turn. */
    val audioPath: String? = null,
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val updatedAtMs: Long,
    val turns: List<ChatTurn>,
)

/** Plain-file conversation history. Keeps the last [maxConversations] threads. */
class ConversationStore(
    private val file: File,
    private val maxConversations: Int = 40,
) {

    private val mutex = Mutex()

    suspend fun load(): List<Conversation> = withContext(Dispatchers.IO) {
        mutex.withLock { readLocked() }
    }

    suspend fun save(conversations: List<Conversation>): List<Conversation> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pruned = conversations
                .sortedByDescending { it.updatedAtMs }
                .take(maxConversations)
            runCatching { file.writeText(json.encodeToString(pruned)) }
            pruned
        }
    }

    private fun readLocked(): List<Conversation> {
        if (!file.exists()) return emptyList()
        val raw = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return runCatching { json.decodeFromString<List<Conversation>>(raw) }.getOrDefault(emptyList())
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun newId(): String = "c" + System.currentTimeMillis().toString(36)

        /** Title shown in the conversation list, taken from the opening words of the user. */
        fun titleFor(firstUserTurn: String): String {
            val collapsed = firstUserTurn.replace(Regex("\\s+"), " ").trim()
            if (collapsed.isEmpty()) return "New chat"
            return if (collapsed.length <= 42) collapsed else collapsed.take(41) + "…"
        }
    }
}
