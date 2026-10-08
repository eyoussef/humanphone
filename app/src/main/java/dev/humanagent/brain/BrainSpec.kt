package dev.humanagent.brain

/**
 * Pinned facts about the on-device memory model and how text is turned into chunks.
 *
 * Model: EmbeddingGemma 2 (text-only, 270M, int4) via MediaPipe Universal Embedder / LiteRT-LM.
 * Apache-2.0, downloaded once from the litert-community repo and verified against a pinned
 * SHA-256 before use. Everything here is pure text/constant work, so it stays unit-testable.
 */
object BrainSpec {

    /** The generic CPU/GPU bundle; SoC-optimised variants exist but this one runs everywhere. */
    const val MODEL_URL =
        "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/main/embeddinggemma-2-text-270m.litertlm"

    /** LFS object id of the bundle = its SHA-256. */
    const val MODEL_SHA256 = "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb"

    const val MODEL_BYTES = 164_626_432L

    const val MODEL_LABEL = "EmbeddingGemma 2 (text, 270M)"

    /** The engine returns 768 dimensions. */
    const val FULL_DIMS = 768

    /** MRL truncation to 256 dims: near-lossless quality per the model card, 3x smaller index. */
    const val DIMS = 256

    /** The index is bounded; the oldest chunks drop off the end. */
    const val MAX_CHUNKS = 4_000

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
}
