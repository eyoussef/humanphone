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

@Serializable
data class LaunchEntry(val name: String, val packageName: String)

/**
 * What experience taught about opening apps: once "WhatsApp" resolves to com.whatsapp, the next
 * task opens it straight from this memory instead of scanning every launcher on the phone.
 *
 * Keys are the exact names the model asked with (plus the app's own label), lowercased. Bounded
 * and oldest-first, so the apps actually used stay and the one-offs drop off.
 */
class AppLaunchCache(private val file: File) {

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, String>()

    constructor(context: Context) : this(File(context.filesDir, "app_launches.json"))

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            entries.clear()
            if (file.exists()) {
                val raw = runCatching { file.readText() }.getOrNull()
                val parsed = raw?.let { runCatching { json.decodeFromString<List<LaunchEntry>>(it) }.getOrNull() }
                parsed?.forEach { entries[it.name.lowercase()] = it.packageName }
            }
        }
    }

    /** The package this name opened before, or null when experience has nothing to say. */
    fun lookup(name: String): String? = entries[name.trim().lowercase()]

    /** Remembers where [name] lives. Writing the same name again moves it to the front. */
    suspend fun remember(name: String, packageName: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = name.trim().lowercase().take(80)
            val value = packageName.trim().take(120)
            if (key.isNotEmpty() && value.isNotEmpty()) {
                entries.remove(key)
                entries[key] = value
                while (entries.size > MAX_ENTRIES) {
                    entries.remove(entries.keys.first())
                }
                persistLocked()
            }
        }
    }

    fun snapshot(): Map<String, String> = entries.toMap()

    private fun persistLocked() {
        runCatching {
            val payload = entries.map { LaunchEntry(it.key, it.value) }
            file.writeText(json.encodeToString(payload))
        }
    }

    companion object {
        const val MAX_ENTRIES = 64
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}