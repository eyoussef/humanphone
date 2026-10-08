package dev.humanagent.brain

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One knowledge source the user added to the brain: a link whose page was fetched, or pasted
 * document text. The extracted text is kept here so re-indexing never needs the network.
 */
@Serializable
data class SourceRecord(
    val id: String,
    val title: String,
    val origin: String,
    val ts: Long,
    val text: String,
    /** "text" for links and documents, "image" for uploaded pictures (embedded visually). */
    val kind: String = "text",
    /** For images: the JPEG kept under the brain directory. */
    val file: String = "",
)

/** The user's knowledge sources, bounded so the brain stays small. */
class SourceStore(private val file: File) {

    private val mutex = Mutex()
    private val records = mutableListOf<SourceRecord>()

    constructor(context: android.content.Context) : this(File(context.filesDir, "brain_sources.json"))

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            records.clear()
            if (file.exists()) {
                val raw = runCatching { file.readText() }.getOrNull()
                val parsed = raw?.let { runCatching { json.decodeFromString<List<SourceRecord>>(it) }.getOrNull() }
                records.addAll(parsed.orEmpty())
            }
        }
    }

    fun list(): List<SourceRecord> = synchronized(records) { records.toList() }

    /** Adds or replaces a source (same [SourceRecord.id]); returns the stored record. */
    suspend fun add(record: SourceRecord): SourceRecord = withContext(Dispatchers.IO) {
        mutex.withLock {
            records.removeAll { it.id == record.id }
            val bounded = record.copy(text = record.text.take(MAX_TEXT_CHARS))
            records.add(bounded)
            while (records.size > MAX_SOURCES) records.removeAt(0)
            persistLocked()
            bounded
        }
    }

    suspend fun remove(id: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val removed = records.removeAll { it.id == id }
            if (removed) persistLocked()
            removed
        }
    }

    private fun persistLocked() {
        runCatching { file.writeText(json.encodeToString(records.toList())) }
    }

    companion object {
        const val MAX_SOURCES = 50
        const val MAX_TEXT_CHARS = 512_000
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}