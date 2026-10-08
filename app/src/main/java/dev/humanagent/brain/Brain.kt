package dev.humanagent.brain

import android.content.Context
import dev.humanagent.agent.MemoryStore
import dev.humanagent.chat.ConversationStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class BrainPhase { ABSENT, DOWNLOADING, INDEXING, READY, FAILED }

/** What the Settings card shows; [error] is only set in [BrainPhase.FAILED]. */
data class BrainState(
    val phase: BrainPhase = BrainPhase.ABSENT,
    val error: String = "",
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val indexedChunks: Int = 0,
    val totalChunks: Int = 0,
    val chunks: Int = 0,
)

/**
 * The phone's memory brain: an EmbeddingGemma 2 model the user downloads once, and an index of
 * chat exchanges, task episodes and facts that is searched in milliseconds instead of re-reading
 * whole conversations into every prompt.
 *
 * The brain is strictly additive: without it the app behaves exactly as before. The model and
 * the index never leave the phone; recalled memories join the prompts the user already sends to
 * their own model.
 */
class Brain(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val brainDir = File(context.filesDir, "brain")
    private val modelFile = File(brainDir, "embeddinggemma-2-text-270m.litertlm")
    private val indexFile = File(brainDir, "index.db")
    private val downloader = BrainDownloader()
    private val index = VectorIndex(indexFile)
    private var embedder: BrainEmbedder? = null
    private var setupJob: Job? = null

    private val _state = MutableStateFlow(BrainState())
    val state: StateFlow<BrainState> = _state.asStateFlow()

    init {
        // A model that is present was checksum-verified before it was renamed into place.
        if (modelFile.exists()) {
            _state.value = BrainState(phase = BrainPhase.READY, chunks = runCatching { index.count() }.getOrDefault(0))
        }
    }

    fun ready(): Boolean = _state.value.phase == BrainPhase.READY && modelFile.exists()

    /** Starts download + backfill in the background. Returns at once; watch [state]. */
    fun setup() {
        if (setupJob?.isActive == true) return
        setupJob = scope.launch {
            try {
                if (!modelFile.exists()) {
                    _state.update { it.copy(phase = BrainPhase.DOWNLOADING, error = "", downloadedBytes = 0, totalBytes = BrainSpec.MODEL_BYTES) }
                    downloader.fetch(modelFile) { done, total ->
                        _state.update { it.copy(downloadedBytes = done, totalBytes = total) }
                    }
                }
                backfill()
                _state.update { it.copy(phase = BrainPhase.READY, chunks = index.count()) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.update { BrainState() }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = BrainPhase.FAILED, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun cancelSetup() {
        setupJob?.cancel()
        setupJob = null
    }

    /** Rebuilds the index from the stores on disk. */
    fun reindex() {
        if (setupJob?.isActive == true) return
        if (!ready()) return
        setupJob = scope.launch {
            try {
                backfill()
                _state.update { it.copy(phase = BrainPhase.READY, chunks = index.count()) }
            } catch (e: Exception) {
                _state.update { it.copy(phase = BrainPhase.FAILED, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** Removes the model and the whole index. */
    suspend fun remove() = withContext(Dispatchers.IO) {
        cancelSetup()
        mutex.withLock {
            embedder?.close()
            embedder = null
            index.close()
            brainDir.deleteRecursively()
            _state.value = BrainState()
        }
    }

    /** Top memories for [query], formatted for a prompt, or null when the brain is not ready. */
    suspend fun recall(query: String): String? {
        if (!ready() || query.isBlank()) return null
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val embedder = embedder()
                    val vector = BrainMath.truncateRenormalize(embedder.embed(BrainSpec.queryText(query)))
                    val hits = index.topK(vector, BrainSpec.RECALL_HITS, System.currentTimeMillis())
                    if (hits.isEmpty()) null else render(hits)
                }.getOrNull()
            }
        }
    }

    /** Indexes one finished chat exchange in the background. */
    fun rememberExchange(conversationId: String, title: String, userText: String, replyText: String, ts: Long) {
        if (!ready()) return
        val chunk = BrainChunks.exchange(conversationId, title, userText, replyText, ts)
        scope.launch { runCatching { store(chunk) } }
    }

    /** Indexes one task episode in the background. */
    fun rememberEpisode(task: String, outcome: String, app: String, ts: Long = System.currentTimeMillis()) {
        if (!ready()) return
        val chunk = BrainChunks.episode(task, outcome, app, ts)
        scope.launch { runCatching { store(chunk) } }
    }

    /** Replaces one fact chunk (call after remember/forget so the index mirrors MemoryStore). */
    fun rememberFact(key: String, value: String) {
        if (!ready()) return
        val chunk = BrainChunks.fact(key, value)
        scope.launch { runCatching { store(chunk) } }
    }

    /** Removes one fact chunk (mirrors a MemoryStore forget). */
    fun forgetFact(key: String) {
        scope.launch { runCatching { mutex.withLock { index.delete(BrainChunks.fact(key, "").ref) } } }
    }

    private suspend fun store(chunk: BrainChunk) {
        mutex.withLock {
            val embedder = embedder()
            val vector = BrainMath.truncateRenormalize(embedder.embed(BrainSpec.documentText(chunk.title, chunk.text)))
            index.upsert(chunk, vector)
            _state.update { it.copy(chunks = index.count()) }
        }
    }

    private suspend fun backfill() = withContext(Dispatchers.IO) {
        val chunks = mutableListOf<BrainChunk>()

        val conversations = ConversationStore(File(context.filesDir, "conversations.json")).load()
        for (conversation in conversations) {
            val turns = conversation.turns
            var i = 0
            while (i + 1 < turns.size) {
                val user = turns[i]
                val reply = turns[i + 1]
                if (user.role == "user" && reply.role == "assistant") {
                    chunks.add(
                        BrainChunks.exchange(conversation.id, conversation.title, user.text, reply.text, reply.timestampMs),
                    )
                    i += 2
                } else {
                    i += 1
                }
            }
        }

        val memory = MemoryStore(File(context.filesDir, "memory.json"))
        memory.load()
        for (episode in memory.recentEpisodes(Int.MAX_VALUE)) {
            chunks.add(BrainChunks.episode(episode.task, episode.outcome, episode.app, episode.whenMs))
        }
        for ((key, value) in memory.snapshot()) {
            chunks.add(BrainChunks.fact(key, value))
        }

        mutex.withLock {
            index.clear()
            _state.update { it.copy(phase = BrainPhase.INDEXING, indexedChunks = 0, totalChunks = chunks.size) }
            val embedder = embedder()
            var done = 0
            for (chunk in chunks) {
                val vector = BrainMath.truncateRenormalize(embedder.embed(BrainSpec.documentText(chunk.title, chunk.text)))
                index.upsert(chunk, vector)
                done++
                _state.update { it.copy(indexedChunks = done) }
            }
        }
    }

    private fun embedder(): BrainEmbedder =
        embedder ?: BrainEmbedder(context, modelFile).also { embedder = it }

    private fun render(hits: List<BrainChunk>): String = buildString {
        append("Relevant memories (from the phone's own index):\n")
        for (hit in hits) {
            append("- ")
            if (hit.ts > 0) append("(").append(ageOf(hit.ts)).append(") ")
            append(hit.title).append(": ").append(hit.text.take(240))
            append('\n')
        }
    }

    private fun ageOf(ts: Long): String {
        val days = (System.currentTimeMillis() - ts).coerceAtLeast(0L) / 86_400_000
        return when {
            days < 1 -> "today"
            days < 2 -> "yesterday"
            else -> "${days}d ago"
        }
    }
}
