package dev.humanagent.brain

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Vector work for the memory index: MRL truncation, cosine scoring and top-k ranking with a
 * small recency boost. All pure and cheap — the whole index is scanned (a few thousand
 * vectors), so brute force beats any index structure here.
 */
object BrainMath {

    /** Young memories win ties: at most [RECENCY_BOOST] added, fading to zero over 180 days. */
    const val RECENCY_BOOST = 0.05f

    const val RECENCY_HALFLIFE_DAYS = 180f

    /**
     * Keeps the leading [dims] dimensions (Matryoshka) and re-normalizes. The model card is
     * explicit: a sliced unit vector is not unit length, and skipping this silently degrades
     * ranking.
     */
    fun truncateRenormalize(vector: FloatArray, dims: Int = BrainSpec.DIMS): FloatArray {
        val out = FloatArray(minOf(dims, vector.size))
        System.arraycopy(vector, 0, out, 0, out.size)
        var sum = 0f
        for (value in out) sum += value * value
        val norm = sqrt(sum)
        if (norm > 0f) {
            for (i in out.indices) out[i] = out[i] / norm
        }
        return out
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) sum += a[i] * b[i]
        return sum
    }

    /** Cosine of two unit vectors (i.e. their dot product) plus a small recency boost. */
    fun score(query: FloatArray, candidate: FloatArray, ts: Long, nowMs: Long): Float {
        val recency = if (ts <= 0L) {
            0f // timeless chunks (facts) get no boost
        } else {
            val ageDays = ((nowMs - ts).coerceAtLeast(0L) / 86_400_000.0).toFloat()
            RECENCY_BOOST * max(0f, 1f - ageDays / RECENCY_HALFLIFE_DAYS)
        }
        return dot(query, candidate) + recency
    }

    /**
     * Indices of the best [k] candidates, best first. [vectors] and [timestamps] are parallel
     * lists; scores are computed in one pass and the small top-k is sorted at the end.
     */
    fun topK(
        query: FloatArray,
        vectors: List<FloatArray>,
        timestamps: List<Long>,
        nowMs: Long,
        k: Int,
    ): List<Int> {
        if (vectors.isEmpty() || k <= 0) return emptyList()
        val scores = FloatArray(vectors.size)
        for (i in vectors.indices) scores[i] = score(query, vectors[i], timestamps[i], nowMs)
        val order = vectors.indices.sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it })
        return order.take(k)
    }
}
