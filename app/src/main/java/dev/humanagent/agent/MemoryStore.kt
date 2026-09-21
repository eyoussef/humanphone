package dev.humanagent.agent

import android.content.Context
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
 * Long-term memory of the assistant: small notes it chose to keep between runs
 * ("mum's number is …", "the user's gym app is FitFlow").
 */
class MemoryStore(private val file: File) {

    private val mutex = Mutex()
    private val notes = LinkedHashMap<String, String>()

    constructor(context: Context) : this(File(context.filesDir, "memory.json"))

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            notes.clear()
            if (file.exists()) {
                val raw = runCatching { file.readText() }.getOrNull()
                val parsed = raw?.let { runCatching { json.decodeFromString<List<Note>>(it) }.getOrNull() }
                parsed?.forEach { notes[it.key] = it.value }
            }
        }
    }

    suspend fun put(key: String, value: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            notes[key.trim().take(80)] = value.trim().take(600)
            persistLocked()
        }
    }

    fun snapshot(): Map<String, String> = notes.toMap()

    private fun persistLocked() {
        runCatching {
            val payload = notes.map { Note(it.key, it.value) }
            file.writeText(json.encodeToString(payload))
        }
    }

    @Serializable
    private data class Note(val key: String, val value: String)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}
