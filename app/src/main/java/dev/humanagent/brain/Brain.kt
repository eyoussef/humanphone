package dev.humanagent.brain

import android.content.Context
import dev.humanagent.agent.MemoryStore
import dev.humanagent.chat.ConversationStore
import dev.humanagent.doc.Docx
import dev.humanagent.doc.Pdf
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
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
    val sources: List<SourceRecord> = emptyList(),
)

/** Retrieved memories ready for a prompt: the text block, plus pictures worth showing the model. */
data class RecallBlock(val text: String, val images: List<String> = emptyList())

/**
 * The phone's memory brain: an EmbeddingGemma 2 model the user downloads once, and an index of
 * chat exchanges, task episodes, facts and knowledge sources that is searched in milliseconds
 * instead of re-reading whole conversations into every prompt.
 *
 * The brain is strictly additive: without it the app behaves exactly as before. The model and
 * the index never leave the phone; recalled memories join the prompts the user already sends to
 * their own model.
 */
class Brain(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val brainDir = File(context.filesDir, "brain")
    private val modelFile = File(brainDir, "embeddinggemma-2-text-vision-440m.litertlm")
    private val indexFile = File(brainDir, "index.db")
    private val downloader = BrainDownloader()
    private val index = VectorIndex(indexFile)
    private val sourceStore = SourceStore(context)
    private var embedder: BrainEmbedder? = null
    private var setupJob: Job? = null

    private val _state = MutableStateFlow(BrainState())
    val state: StateFlow<BrainState> = _state.asStateFlow()

    init {
        // A model that is present was checksum-verified before it was renamed into place.
        if (modelFile.exists()) {
            _state.value = BrainState(phase = BrainPhase.READY, chunks = runCatching { index.count() }.getOrDefault(0))
        }
        scope.launch { runCatching { sourceStore.load() }; refreshState() }
    }

    private fun refreshState() {
        _state.update { it.copy(chunks = runCatching { index.count() }.getOrDefault(0), sources = sourceStore.list()) }
    }

    fun ready(): Boolean = _state.value.phase == BrainPhase.READY && modelFile.exists()

    /** Starts download + backfill in the background. Returns at once; watch [state]. */
    fun setup() {
        if (setupJob?.isActive == true) return
        setupJob = scope.launch {
            try {
                // Only a checksum-verified bundle may be reused; anything else (an older model,
                // a half-written file) is replaced. Other .litertlm leftovers are dropped.
                brainDir.listFiles()?.filter { it.name.endsWith(".litertlm") && it != modelFile }?.forEach { it.delete() }
                if (!Digests.matches(modelFile, BrainSpec.MODEL_SHA256)) {
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

    /** Top memories for [query], or null when the brain is not ready. */
    suspend fun recall(query: String): RecallBlock? {
        if (!ready() || query.isBlank()) return null
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val embedder = embedder()
                    val vector = BrainMath.truncateRenormalize(embedder.embed(BrainSpec.queryText(query)))
                    val hits = index.topK(vector, BrainSpec.RECALL_HITS, System.currentTimeMillis())
                    if (hits.isEmpty()) return@runCatching null
                    val pictures = hits
                        .mapNotNull { hit -> sourceStore.list().firstOrNull { it.id == BrainChunks.sourceId(hit.ref) && it.kind == "image" } }
                        .take(MAX_RECALLED_IMAGES)
                        .mapNotNull { record -> runCatching { File(brainDir, record.file).readBytes() }.getOrNull() }
                        .map { Base64.getEncoder().encodeToString(it) }
                    RecallBlock(render(hits), pictures)
                }.getOrNull()
            }
        }
    }

    /** Indexes one finished chat exchange in the background. */
    fun rememberExchange(conversationId: String, title: String, userText: String, replyText: String, ts: Long) {
        if (!ready()) return
        val chunk = BrainChunks.exchange(conversationId, title, userText, replyText, ts)
        scope.launch { runCatching { store(chunk, embed(BrainSpec.documentText(chunk.title, chunk.text))) } }
    }

    /** Indexes one task episode in the background. */
    fun rememberEpisode(task: String, outcome: String, app: String, ts: Long = System.currentTimeMillis()) {
        if (!ready()) return
        val chunk = BrainChunks.episode(task, outcome, app, ts)
        scope.launch { runCatching { store(chunk, embed(BrainSpec.documentText(chunk.title, chunk.text))) } }
    }

    /** Replaces one fact chunk (call after remember/forget so the index mirrors MemoryStore). */
    fun rememberFact(key: String, value: String) {
        if (!ready()) return
        val chunk = BrainChunks.fact(key, value)
        scope.launch { runCatching { store(chunk, embed(BrainSpec.documentText(chunk.title, chunk.text))) } }
    }

    /** Removes one fact chunk (mirrors a MemoryStore forget). */
    fun forgetFact(key: String) {
        scope.launch { runCatching { mutex.withLock { index.delete(BrainChunks.fact(key, "").ref) } } }
    }

    /**
     * Adds knowledge from a link (fetched and stripped to text) or pasted document text.
     * Returns an error message, or null on success. Adding the same link again refreshes it.
     */
    suspend fun addSource(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return "Nothing to add."
        if (!ready()) return "Set up the brain first."
        return withContext(Dispatchers.IO) {
            try {
                if (WebText.isUrl(trimmed)) {
                    val fetched = fetchPage(trimmed)
                    addTextSource(WebText.title(fetched).ifBlank { trimmed }, trimmed, WebText.strip(fetched))
                } else {
                    addTextSource(
                        trimmed.lineSequence().first().trim().take(120).ifBlank { "Pasted text" },
                        "pasted text",
                        trimmed,
                    )
                }
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
        }
    }

    /**
     * Adds an uploaded file as knowledge: PDF, DOCX and plain text are read to text; pictures are
     * embedded visually and recalled to the model as pictures. Returns an error message or null.
     */
    suspend fun addSourceFile(uri: android.net.Uri): String? {
        val resolver = context.contentResolver
        val name = runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull().orEmpty()
        return addSourceFile(uri, name.ifBlank { "file" }, resolver.getType(uri).orEmpty())
    }

    private suspend fun addSourceFile(uri: android.net.Uri, name: String, mime: String): String? {
        if (!ready()) return "Set up the brain first."
        return withContext(Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(1 shl 16)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > BrainSpec.MAX_UPLOAD_BYTES) throw IllegalStateException("That file is larger than 20 MB")
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                } ?: return@withContext "Could not read that file."
                val title = name.trim().ifBlank { "File" }.take(120)
                val lower = name.lowercase()
                val isImage = mime.startsWith("image/") || IMAGE_EXTENSIONS.any { lower.endsWith(it) }
                val isPdf = mime == "application/pdf" || lower.endsWith(".pdf")
                val isDocx = mime.contains("wordprocessingml") || lower.endsWith(".docx")
                when {
                    isImage -> addImageSource(title, bytes)
                    isPdf -> {
                        Pdf.ensureInit(context)
                        addTextSource(title, "file: $title", Pdf.extract(bytes))
                    }
                    isDocx -> addTextSource(title, "file: $title", Docx.extract(bytes))
                    else -> addTextSource(title, "file: $title", String(bytes, Charsets.UTF_8))
                }
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
        }
    }

    /** Removes one knowledge source and all its chunks. */
    suspend fun removeSource(id: String) {
        withContext(Dispatchers.IO) {
            sourceStore.remove(id)
            mutex.withLock { index.deleteByRefPrefix("source:$id:") }
            refreshState()
        }
    }

    fun sources(): List<SourceRecord> = sourceStore.list()

    private suspend fun addTextSource(title: String, origin: String, text: String): String? {
        if (text.isBlank()) return "That source had no readable text."
        val existing = sourceStore.list().firstOrNull { it.origin == origin }
        val record = SourceRecord(existing?.id ?: newSourceId(), title, origin, System.currentTimeMillis(), text)
        sourceStore.add(record)
        mutex.withLock { index.deleteByRefPrefix("source:${record.id}:") }
        for ((part, piece) in WebText.chunk(text).withIndex()) {
            store(BrainChunks.source(record.id, record.title, record.origin, part, piece, record.ts), embed(BrainSpec.documentText(record.title, piece)))
        }
        refreshState()
        return null
    }

    private suspend fun addImageSource(title: String, bytes: ByteArray): String? {
        val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return "That file is not a readable image."
        val scaled = scaleDown(decoded)
        val vector = embed(scaled)
        val existing = sourceStore.list().firstOrNull { it.origin == title }
        val id = existing?.id ?: newSourceId()
        val fileName = "src_$id.jpg"
        brainDir.mkdirs()
        File(brainDir, fileName).outputStream().use { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
        if (scaled !== decoded) decoded.recycle()
        scaled.recycle()
        val record = SourceRecord(id, title, title, System.currentTimeMillis(), text = "", kind = "image", file = fileName)
        sourceStore.add(record)
        store(BrainChunks.source(id, title, title, 0, "", record.ts), vector)
        refreshState()
        return null
    }

    private fun newSourceId(): String =
        "s" + System.currentTimeMillis().toString(36) + kotlin.random.Random.nextInt(0x1000, 0x10000).toString(36)

    private fun scaleDown(bitmap: android.graphics.Bitmap, max: Int = 1024): android.graphics.Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= max) return bitmap
        val scale = max.toFloat() / longest
        return android.graphics.Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private suspend fun embed(text: String): FloatArray = mutex.withLock { embedder().embed(text) }

    private suspend fun embed(bitmap: android.graphics.Bitmap): FloatArray =
        mutex.withLock { embedder().embedImage(bitmap) }

    private suspend fun store(chunk: BrainChunk, raw: FloatArray) {
        mutex.withLock {
            index.upsert(chunk, BrainMath.truncateRenormalize(raw))
            _state.update { it.copy(chunks = index.count()) }
        }
    }

    private suspend fun fetchPage(url: String): String {
        val request = okhttp3.Request.Builder().url(url).build()
        dev.humanagent.llm.LlmClient.sharedClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Fetching the link failed with HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("That link returned no content")
            val bytes = body.bytes()
            if (bytes.size > 2 * 1024 * 1024) throw IllegalStateException("That page is larger than 2 MB")
            return String(bytes, Charsets.UTF_8)
        }
    }

    private suspend fun backfill() = withContext(Dispatchers.IO) {
        // Pairs of chunk + picture to embed it with (pictures use the vision side).
        val pending = mutableListOf<Pair<BrainChunk, android.graphics.Bitmap?>>()

        val conversations = ConversationStore(File(context.filesDir, "conversations.json")).load()
        for (conversation in conversations) {
            val turns = conversation.turns
            var i = 0
            while (i + 1 < turns.size) {
                val user = turns[i]
                val reply = turns[i + 1]
                if (user.role == "user" && reply.role == "assistant") {
                    pending.add(
                        BrainChunks.exchange(conversation.id, conversation.title, user.text, reply.text, reply.timestampMs) to null,
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
            pending.add(BrainChunks.episode(episode.task, episode.outcome, episode.app, episode.whenMs) to null)
        }
        for ((key, value) in memory.snapshot()) {
            pending.add(BrainChunks.fact(key, value) to null)
        }
        for (record in sourceStore.list()) {
            if (record.kind == "image") {
                val bitmap = runCatching {
                    android.graphics.BitmapFactory.decodeFile(File(brainDir, record.file).absolutePath)
                }.getOrNull() ?: continue
                pending.add(BrainChunks.source(record.id, record.title, record.origin, 0, "", record.ts) to bitmap)
            } else {
                for ((part, piece) in WebText.chunk(record.text).withIndex()) {
                    pending.add(BrainChunks.source(record.id, record.title, record.origin, part, piece, record.ts) to null)
                }
            }
        }

        mutex.withLock {
            index.clear()
            _state.update { it.copy(phase = BrainPhase.INDEXING, indexedChunks = 0, totalChunks = pending.size) }
            val embedder = embedder()
            var done = 0
            for ((chunk, bitmap) in pending) {
                val raw = if (bitmap != null) {
                    embedder.embedImage(bitmap)
                } else {
                    embedder.embed(BrainSpec.documentText(chunk.title, chunk.text))
                }
                if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
                index.upsert(chunk, BrainMath.truncateRenormalize(raw))
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
            if (hit.kind == BrainChunks.KIND_SOURCE && hit.text.isEmpty()) {
                append("photo: ").append(hit.title).append(" (image you indexed)")
            } else {
                append(hit.title).append(": ").append(hit.text.take(240))
            }
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

    companion object {
        private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp")
        const val MAX_RECALLED_IMAGES = 2
    }
}
