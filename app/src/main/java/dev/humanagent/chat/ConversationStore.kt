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

/** A file the user attached to a turn, already copied into app storage. */
@Serializable
data class Attachment(
    val name: String,
    val mimeType: String,
    val kind: String, // "image" | "text" | "file"
    val path: String, // absolute path inside app storage
    val sizeBytes: Long = 0L,
)

@Serializable
data class ChatTurn(
    val role: String,
    val text: String,
    val timestampMs: Long,
    val attachments: List<Attachment> = emptyList(),
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
