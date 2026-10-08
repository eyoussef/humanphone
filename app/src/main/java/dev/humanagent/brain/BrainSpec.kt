package dev.humanagent.brain

/**
 * Pinned facts about the on-device memory model and how text is turned into chunks.
 *
 * Model: EmbeddingGemma 2 (text-only, 270M, int4) via MediaPipe Universal Embedder / LiteRT-LM.
 * Apache-2.0, downloaded once from the litert-community repo and verified against a pinned
 * SHA-256 before use. Everything here is pure text/constant work, so it stays unit-testable.
 */
object BrainSpec {

    /** The generic CPU/GPU bundle the official Universal Embedder sample uses (text + vision). */
    const val MODEL_URL =
        "https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm/resolve/main/embeddinggemma-2-text-vision-440m.litertlm"

    /** LFS object id of the bundle = its SHA-256. */
    const val MODEL_SHA256 = "92dcbea108899e5d6e30d919b0744f90d9967e80c67a4ab5503ac16d54f62eb0"

    const val MODEL_BYTES = 387_710_976L

    const val MODEL_LABEL = "EmbeddingGemma 2 (text + vision, 440M)"

    /** The engine returns 768 dimensions. */
    const val FULL_DIMS = 768

    /** MRL truncation to 256 dims: near-lossless quality per the model card, 3x smaller index. */
    const val DIMS = 256

    /** The index is bounded; the oldest chunks drop off the end. */
    const val MAX_CHUNKS = 4_000

    /** Uploaded knowledge files are capped so one PDF cannot eat the phone. */
    const val MAX_UPLOAD_BYTES = 20 * 1024 * 1024

    /** How many memories ride along with one prompt. */
    const val RECALL_HITS = 6

    /** Asymmetric retrieval prefixes from the model card ("SearchQuery" / "Document" prompts). */
    fun queryText(query: String): String = "task: search result | query: ${query.trim().take(1_000)}"

    fun documentText(title: String, text: String): String {
        val cleanTitle = title.trim().ifBlank { "none" }.replace('|', '/').take(120)
        val cleanText = text.trim().replace('\n', ' ').take(2_000)
        return "title: $cleanTitle | text: $cleanText"
    }
}

/** One retrievable memory: an exchange, a task episode, or a fact. */
data class BrainChunk(
    /** Stable identity used to replace a chunk when it is re-indexed. */
    val ref: String,
    val kind: String,
    val title: String,
    val text: String,
    val ts: Long,
)

/** Builders for the chunk kinds the app produces. Pure formatting, unit-tested. */
object BrainChunks {

    const val KIND_CHAT = "chat"
    const val KIND_EPISODE = "episode"
    const val KIND_FACT = "fact"
    const val KIND_SOURCE = "source"

    /** One finished chat exchange — the natural unit the user remembers. */
    fun exchange(conversationId: String, title: String, userText: String, replyText: String, ts: Long): BrainChunk =
        BrainChunk(
            ref = "$conversationId:$ts",
            kind = KIND_CHAT,
            title = title.ifBlank { "Chat" },
            text = "user: ${userText.trim().take(900)} → assistant: ${replyText.trim().take(900)}",
            ts = ts,
        )

    /** One task the agent ran. */
    fun episode(task: String, outcome: String, app: String, ts: Long): BrainChunk =
        BrainChunk(
            ref = "episode:$ts",
            kind = KIND_EPISODE,
            title = if (app.isBlank()) "Task" else "Task in $app",
            text = "task: ${task.trim().take(400)} → outcome: ${outcome.trim().take(400)}",
            ts = ts,
        )

    /** One remembered fact about the user. */
    fun fact(key: String, value: String): BrainChunk =
        BrainChunk(
            ref = "fact:$key",
            kind = KIND_FACT,
            title = key.trim().take(80),
            text = value.trim().take(600),
            ts = 0L, // facts do not age out by recency; they live until deleted
        )

    /** One chunk of a user-added knowledge source (link or document text). */
    fun source(id: String, title: String, origin: String, part: Int, text: String, ts: Long): BrainChunk =
        BrainChunk(
            ref = "source:$id:$part",
            kind = KIND_SOURCE,
            title = title.ifBlank { origin.ifBlank { "Source" } },
            text = text,
            ts = ts,
        )

    /** The source id inside a source chunk ref ("source:<id>:<part>"). */
    fun sourceId(ref: String): String = ref.removePrefix("source:").substringBefore(':')
}

/** Turning a fetched page or pasted document into plain text and index-sized chunks. Pure. */
object WebText {

    private val URL = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

    fun isUrl(input: String): Boolean = URL.matches(input.trim())

    /** Rough HTML → text: drop scripts/styles, collapse tags to spaces, decode the common entities. */
    fun strip(html: String): String {
        val body = html
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</(script|style)>"), " ")
            .replace(Regex("(?s)<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        return body.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /** The page's <title>, or empty. */
    fun title(html: String): String =
        Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
            ?.let { strip(it).replace('\n', ' ').trim() }
            .orEmpty()
            .take(120)

    /** Splits text into chunks of about [chunkSize] characters on paragraph boundaries. */
    fun chunk(text: String, chunkSize: Int = 1_200): List<String> {
        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        for (paragraph in text.split('\n')) {
            val line = paragraph.trim()
            if (line.isEmpty()) continue
            if (current.isNotEmpty() && current.length + line.length + 1 > chunkSize) {
                pieces.add(current.toString())
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(line)
            // A single huge paragraph breaks at the cap so chunks stay retrievable.
            while (current.length > chunkSize * 2) {
                pieces.add(current.substring(0, chunkSize))
                current.delete(0, chunkSize)
            }
        }
        if (current.isNotBlank()) pieces.add(current.toString())
        return pieces
    }
}
