package dev.humanagent.agent

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One durable note about the user: "mum's number is …", "the gym app is FitFlow". */
@Serializable
data class Note(val key: String, val value: String)

/**
 * A person the assistant knows: how to reach them, and how to talk to them. This is what turns
 * Auto mode from guessing into judgement — "Sam" stops being a notification title and becomes
 * the user's brother who prefers Arabic.
 */
@Serializable
data class PersonMemory(
    val name: String,
    val relation: String = "",
    val channels: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

/** One task the assistant ran, kept compact, so it has continuity between runs. */
@Serializable
data class Episode(
    val whenMs: Long,
    val task: String,
    val outcome: String,
    val app: String = "",
    val status: String = STATUS_DONE,
) {
    companion object {
        const val STATUS_DONE = "done"
        const val STATUS_OWED = "owed"
        const val STATUS_FAILED = "failed"
    }
}

/** Everything the twin knows, in one file. */
@Serializable
data class MemoryDocument(
    val facts: List<Note> = emptyList(),
    val people: List<PersonMemory> = emptyList(),
    val episodes: List<Episode> = emptyList(),
)

/** Human-friendly age of a timestamp, e.g. "just now", "5m ago", "3h ago", "2d ago". */
internal fun ageLabel(whenMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val seconds = (nowMs - whenMs).coerceAtLeast(0) / 1000
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3600}h ago"
        else -> "${seconds / 86_400}d ago"
    }
}

/**
 * The twin's memory: what the user told it (facts), who the user knows (people), and what it did
 * for the user (episodes). Everything is durable and tolerant — a corrupt or ancient file is
 * read as far as it can be and never crashes the assistant.
 *
 * Bounds are hard limits, so an over-eager model cannot turn this into an unbounded prompt.
 */
class MemoryStore(private val file: File) {

    private val mutex = Mutex()
    private var document = MemoryDocument()

    constructor(context: Context) : this(File(context.filesDir, "memory.json"))

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock { document = read() }
    }

    /** Stores or replaces a fact. The most recent write wins; the oldest drops off the end. */
    suspend fun put(key: String, value: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val stored = key.trim().take(80)
            if (stored.isNotEmpty()) {
                val notes = LinkedHashMap(document.facts.associate { it.key to it.value })
                notes.remove(stored)
                notes[stored] = value.trim().take(600)
                document = document.copy(
                    facts = notes.map { Note(it.key, it.value) }.takeLast(MAX_FACTS),
                )
                persistLocked()
            }
        }
    }

    fun snapshot(): Map<String, String> = document.facts.associate { it.key to it.value }

    /** Deletes one fact by its exact key; returns true when it existed. */
    suspend fun removeFact(key: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val wanted = key.trim().take(80)
            if (wanted.isEmpty() || document.facts.none { it.key == wanted }) {
                false
            } else {
                document = document.copy(facts = document.facts.filterNot { it.key == wanted })
                persistLocked()
                true
            }
        }
    }

    /** Deletes every fact; returns how many were cleared. People and episodes are kept. */
    suspend fun clearFacts(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val count = document.facts.size
            if (count > 0) {
                document = document.copy(facts = emptyList())
                persistLocked()
            }
            count
        }
    }

    /** Deletes one person by name (case-insensitive); returns true when they existed. */
    suspend fun forgetPerson(name: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val wanted = name.trim().take(80)
            if (wanted.isEmpty() || document.people.none { it.name.equals(wanted, ignoreCase = true) }) {
                false
            } else {
                document = document.copy(people = document.people.filterNot { it.name.equals(wanted, ignoreCase = true) })
                persistLocked()
                true
            }
        }
    }

    /** Adds to what is known about a person, creating them on first mention. */
    suspend fun rememberPerson(name: String, relation: String, channel: String, note: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val wanted = name.trim().take(80)
                if (wanted.isNotEmpty()) {
                    val existing = document.people.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
                    val updated = PersonMemory(
                        name = existing?.name ?: wanted,
                        relation = relation.trim().take(60).ifBlank { existing?.relation.orEmpty() },
                        channels = joined(existing?.channels, channel.trim().take(60), MAX_CHANNELS_PER_PERSON),
                        notes = joined(existing?.notes, note.trim().take(200), MAX_NOTES_PER_PERSON),
                        updatedAtMs = System.currentTimeMillis(),
                    )
                    val others = document.people.filterNot { it.name.equals(wanted, ignoreCase = true) }
                    document = document.copy(people = (others + updated).takeLast(MAX_PEOPLE))
                    persistLocked()
                }
            }
        }

    /** The person whose name contains [query], the one the model most likely means. */
    fun findPerson(query: String): PersonMemory? {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return null
        return document.people.firstOrNull { it.name.lowercase().contains(needle) }
    }

    fun people(): List<PersonMemory> = document.people

    /** One completed (or still owed) task, appended in run order and trimmed from the front. */
    suspend fun addEpisode(task: String, outcome: String, app: String = "", status: String = Episode.STATUS_DONE) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val episode = Episode(
                    whenMs = System.currentTimeMillis(),
                    task = task.trim().take(120),
                    outcome = outcome.trim().take(200),
                    app = app.trim().take(40),
                    status = status,
                )
                if (episode.task.isNotBlank()) {
                    document = document.copy(episodes = (document.episodes + episode).takeLast(MAX_EPISODES))
                    persistLocked()
                }
            }
        }

    /** The newest episodes first, the few that fit into a prompt. */
    fun recentEpisodes(limit: Int = EPISODES_IN_PROMPT): List<Episode> = document.episodes.takeLast(limit).reversed()

    /** The whole twin record as prompt text: facts, then people, then the recent tasks. */
    fun render(nowMs: Long = System.currentTimeMillis()): String = buildString {
        append("What you remember about this user:\n")
        val facts = document.facts
        append(if (facts.isEmpty()) "Nothing yet." else facts.joinToString("\n") { "- ${it.key}: ${it.value}" })
        if (document.people.isNotEmpty()) {
            append("\n\nPeople you know:\n")
            append(document.people.joinToString("\n") { renderPerson(it) })
        }
        val recent = recentEpisodes()
        if (recent.isNotEmpty()) {
            append("\n\nRecent tasks:\n")
            append(recent.joinToString("\n") { renderEpisode(it, nowMs) })
        }
    }

    /** The people the user knows, for the watchdog that decides whether to answer for them. */
    fun renderPeople(): String = if (document.people.isEmpty()) {
        ""
    } else {
        "People the user knows:\n" + document.people.joinToString("\n") { renderPerson(it) }
    }

    private fun renderPerson(person: PersonMemory): String = buildString {
        append("- ").append(person.name)
        if (person.relation.isNotBlank()) append(" (").append(person.relation).append(')')
        val channels = person.channels.joinToString(", ")
        if (channels.isNotBlank()) append(" — ").append(channels)
        person.notes.forEach { append(" · ").append(it) }
    }

    private fun renderEpisode(episode: Episode, nowMs: Long): String = buildString {
        append("- ").append(ageLabel(episode.whenMs, nowMs)).append(": \"")
        append(episode.task).append("\" → ")
        if (episode.status == Episode.STATUS_OWED) append("NOT delivered yet — ")
        append(episode.outcome)
        if (episode.app.isNotBlank()) append(" [").append(episode.app).append(']')
    }

    private fun joined(existing: List<String>?, addition: String, limit: Int): List<String> {
        val merged = ArrayList<String>(existing.orEmpty())
        if (addition.isNotBlank() && !merged.any { it.equals(addition, ignoreCase = true) }) merged.add(addition)
        return merged.takeLast(limit)
    }

    private suspend fun read(): MemoryDocument {
        if (!file.exists()) return MemoryDocument()
        val raw = runCatching { file.readText() }.getOrNull() ?: return MemoryDocument()
        return runCatching { json.decodeFromString<MemoryDocument>(raw) }.getOrElse {
            // Files written before the twin existed are bare note lists: keep them as facts.
            val legacy = runCatching {
                json.decodeFromString(ListSerializer(Note.serializer()), raw)
            }.getOrDefault(emptyList())
            MemoryDocument(facts = legacy.takeLast(MAX_FACTS))
        }
    }

    private fun persistLocked() {
        runCatching { file.writeText(json.encodeToString(document)) }
    }

    companion object {
        const val MAX_FACTS = 60
        const val MAX_PEOPLE = 48
        const val MAX_CHANNELS_PER_PERSON = 4
        const val MAX_NOTES_PER_PERSON = 6
        const val MAX_EPISODES = 40
        const val EPISODES_IN_PROMPT = 8
        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = false
        }
    }
}